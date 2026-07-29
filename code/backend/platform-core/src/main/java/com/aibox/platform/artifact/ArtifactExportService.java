package com.aibox.platform.artifact;

import com.aibox.feature.spi.DocumentComparisonExportRequest;
import com.aibox.feature.spi.DocumentComparisonExporter;
import com.aibox.feature.spi.DocumentComparisonExports;
import com.aibox.feature.spi.DocumentComparisonResponse;
import com.aibox.feature.spi.GeneratedDocumentExport;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ArtifactExportService {

    private final ArtifactRepository artifactRepository;
    private final AssetService assetService;
    private final JdbcTemplate jdbcTemplate;
    private final ActorContextProvider actorContextProvider;
    private final DocumentComparisonExporter documentComparisonExporter;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final Clock clock;

    public ArtifactExportService(
            ArtifactRepository artifactRepository,
            AssetService assetService,
            JdbcTemplate jdbcTemplate,
            ActorContextProvider actorContextProvider,
            DocumentComparisonExporter documentComparisonExporter,
            ObjectMapper objectMapper,
            EntityManager entityManager,
            Clock clock
    ) {
        this.artifactRepository = artifactRepository;
        this.assetService = assetService;
        this.jdbcTemplate = jdbcTemplate;
        this.actorContextProvider = actorContextProvider;
        this.documentComparisonExporter = documentComparisonExporter;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public AssetService.AssetView export(UUID artifactId, String exportType) {
        String normalizedType = normalizeType(exportType);
        String role = DocumentComparisonExports.contentField(normalizedType);
        ActorContext actor = actorContextProvider.current();
        ArtifactEntity artifact = artifactRepository.findOwnedForUpdate(
                        artifactId,
                        actor.tenantId(),
                        actor.userId()
                )
                .orElseThrow(() -> new NotFoundException("artifact", artifactId));
        if (!"document_comparison".equals(artifact.getKind())) {
            throw new PlatformException(
                    "ARTIFACT_EXPORT_UNSUPPORTED",
                    "This artifact does not support document export"
            );
        }
        AssetService.AssetView existing = existingAsset(artifactId, role);
        if (existing != null) return existing;

        DocumentComparisonContext context = comparisonContext(artifact);
        GeneratedDocumentExport generated;
        try {
            generated = documentComparisonExporter.export(
                    new DocumentComparisonExportRequest(
                            normalizedType,
                            context.baselineAssetId(),
                            context.baselineFileName(),
                            context.mode(),
                            context.comparison()
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new PlatformException(
                    "ARTIFACT_EXPORT_UNAVAILABLE",
                    "The requested export is unavailable for this comparison"
            );
        } catch (RuntimeException exception) {
            throw new PlatformException(
                    "ARTIFACT_EXPORT_FAILED",
                    "The requested export could not be generated"
            );
        }
        AssetService.AssetView asset = assetService.storeGenerated(
                artifact.getTenantId(),
                artifact.getUserId(),
                generated.fileName(),
                generated.mediaType(),
                generated.content()
        );
        entityManager.flush();
        jdbcTemplate.update(
                """
                insert into artifact_asset (
                    artifact_id,
                    asset_id,
                    role,
                    created_at
                ) values (?, ?, ?, ?)
                """,
                artifactId,
                asset.id(),
                role,
                Timestamp.from(clock.instant())
        );
        return asset;
    }

    private AssetService.AssetView existingAsset(UUID artifactId, String role) {
        List<UUID> assetIds = jdbcTemplate.query(
                """
                select asset_id
                from artifact_asset
                where artifact_id = ?
                  and role = ?
                order by created_at desc
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("asset_id", UUID.class),
                artifactId,
                role
        );
        if (assetIds.isEmpty()) return null;
        return assetService.describeIncludingDeleted(assetIds).stream()
                .filter(AssetService.AssetView::available)
                .findFirst()
                .orElse(null);
    }

    private DocumentComparisonContext comparisonContext(ArtifactEntity artifact) {
        Map<String, Object> content = artifact.getContent();
        Map<String, Object> baseline = documents(content).stream()
                .filter(document -> "baseline".equals(
                        String.valueOf(document.get("role"))
                ))
                .findFirst()
                .orElse(Map.of());
        UUID baselineAssetId = uuid(baseline.get("assetId"));
        String baselineFileName = text(baseline.get("fileName"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("detectedMode", content.get("detectedMode"));
        response.put("summary", comparisonSummary(content));
        response.put("comparability", content.get("comparability"));
        response.put("reportMarkdown", content.get("reportMarkdown"));
        response.put("pairwiseComparisons", content.get("pairwiseComparisons"));
        response.put(
                "crossDocumentConclusion",
                content.get("crossDocumentConclusion")
        );
        response.put("risks", content.get("risks"));
        response.put("citations", content.get("citations"));
        response.put("warnings", content.get("warnings"));
        response.put("metadata", artifact.getMetadata());
        try {
            return new DocumentComparisonContext(
                    baselineAssetId,
                    baselineFileName,
                    textOr(content.get("mode"), "auto"),
                    objectMapper.convertValue(
                            response,
                            DocumentComparisonResponse.class
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new PlatformException(
                    "ARTIFACT_EXPORT_INVALID",
                    "The comparison result cannot be exported"
            );
        }
    }

    private List<Map<String, Object>> documents(Map<String, Object> content) {
        Object value = content.get("documents");
        if (value == null) return List.of();
        return objectMapper.convertValue(
                value,
                new TypeReference<List<Map<String, Object>>>() {
                }
        );
    }

    private static String comparisonSummary(Map<String, Object> content) {
        String summary = text(content.get("summary"));
        if (!summary.isBlank()) return summary;
        Object conclusion = content.get("crossDocumentConclusion");
        if (conclusion instanceof Map<?, ?> values) {
            return text(values.get("summary"));
        }
        return "";
    }

    private static String normalizeType(String exportType) {
        try {
            return DocumentComparisonExports.normalizeType(exportType);
        } catch (IllegalArgumentException exception) {
            throw new PlatformException(
                    "ARTIFACT_EXPORT_TYPE_INVALID",
                    "The requested export type is invalid"
            );
        }
    }

    private static UUID uuid(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String textOr(Object value, String fallback) {
        String result = text(value);
        return result.isBlank() ? fallback : result;
    }

    private record DocumentComparisonContext(
            UUID baselineAssetId,
            String baselineFileName,
            String mode,
            DocumentComparisonResponse comparison
    ) {
    }
}
