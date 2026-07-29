package com.aibox.platform.artifact;

import com.aibox.feature.spi.DocumentComparisonExportRequest;
import com.aibox.feature.spi.DocumentComparisonExporter;
import com.aibox.feature.spi.GeneratedDocumentExport;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactExportServiceTest {

    @Test
    void generatesRequestedExportAndAttachesItToTheArtifact() {
        Fixture fixture = fixture();
        when(fixture.jdbcTemplate().query(
                anyString(),
                any(RowMapper.class),
                eq(fixture.artifactId()),
                eq("excelAssetId")
        )).thenReturn(List.of());
        when(fixture.exporter().export(any())).thenReturn(
                new GeneratedDocumentExport(
                        "excelAssetId",
                        "多文档对比报告.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[]{1, 2, 3}
                )
        );
        when(fixture.assetService().storeGenerated(
                fixture.tenantId(),
                fixture.userId(),
                "多文档对比报告.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3}
        )).thenReturn(fixture.generatedAsset());

        AssetService.AssetView result = fixture.service().export(
                fixture.artifactId(),
                "excel"
        );

        assertThat(result.id()).isEqualTo(fixture.generatedAsset().id());
        ArgumentCaptor<DocumentComparisonExportRequest> request =
                ArgumentCaptor.forClass(DocumentComparisonExportRequest.class);
        verify(fixture.exporter()).export(request.capture());
        assertThat(request.getValue().exportType()).isEqualTo("excel");
        assertThat(request.getValue().comparison().summary())
                .isEqualTo("总体结论");
        InOrder persistenceOrder = inOrder(
                fixture.assetService(),
                fixture.entityManager(),
                fixture.jdbcTemplate()
        );
        persistenceOrder.verify(fixture.assetService()).storeGenerated(
                eq(fixture.tenantId()),
                eq(fixture.userId()),
                anyString(),
                eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                any(byte[].class)
        );
        persistenceOrder.verify(fixture.entityManager()).flush();
        persistenceOrder.verify(fixture.jdbcTemplate()).update(
                anyString(),
                eq(fixture.artifactId()),
                eq(fixture.generatedAsset().id()),
                eq("excelAssetId"),
                any(Timestamp.class)
        );
    }

    @Test
    void reusesAnExistingAvailableExport() {
        Fixture fixture = fixture();
        when(fixture.jdbcTemplate().query(
                anyString(),
                any(RowMapper.class),
                eq(fixture.artifactId()),
                eq("excelAssetId")
        )).thenReturn(List.of(fixture.generatedAsset().id()));
        when(fixture.assetService().describeIncludingDeleted(
                List.of(fixture.generatedAsset().id())
        )).thenReturn(List.of(fixture.generatedAsset()));

        AssetService.AssetView result = fixture.service().export(
                fixture.artifactId(),
                "excel"
        );

        assertThat(result.id()).isEqualTo(fixture.generatedAsset().id());
        verify(fixture.exporter(), never()).export(any());
        verify(fixture.assetService(), never()).storeGenerated(
                any(), any(), anyString(), anyString(), any()
        );
    }

    @Test
    void rejectsUnsupportedArtifactsBeforeReusingAnExportRole() {
        Fixture fixture = fixture("text");
        when(fixture.jdbcTemplate().query(
                anyString(),
                any(RowMapper.class),
                eq(fixture.artifactId()),
                eq("excelAssetId")
        )).thenReturn(List.of(fixture.generatedAsset().id()));
        when(fixture.assetService().describeIncludingDeleted(
                List.of(fixture.generatedAsset().id())
        )).thenReturn(List.of(fixture.generatedAsset()));

        assertThatThrownBy(() -> fixture.service().export(
                fixture.artifactId(),
                "excel"
        )).isInstanceOf(PlatformException.class)
                .extracting("code")
                .isEqualTo("ARTIFACT_EXPORT_UNSUPPORTED");
        verify(fixture.exporter(), never()).export(any());
    }

    private static Fixture fixture() {
        return fixture("document_comparison");
    }

    private static Fixture fixture(String artifactKind) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-29T03:30:00Z");
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        AssetService assetService = mock(AssetService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ActorContextProvider actorContextProvider =
                mock(ActorContextProvider.class);
        DocumentComparisonExporter exporter =
                mock(DocumentComparisonExporter.class);
        EntityManager entityManager = mock(EntityManager.class);
        when(actorContextProvider.current())
                .thenReturn(new ActorContext(tenantId, userId));
        when(artifactRepository.findOwnedForUpdate(
                artifactId,
                tenantId,
                userId
        )).thenReturn(Optional.of(new ArtifactEntity(
                artifactId,
                tenantId,
                userId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                1,
                artifactKind,
                "合同对比",
                "application/vnd.yuanzuo.document-comparison+json",
                comparisonContent(),
                Map.of("promptVersion", 2),
                now
        )));
        AssetService.AssetView generatedAsset = new AssetService.AssetView(
                UUID.randomUUID(),
                "多文档对比报告.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                3,
                "sha256",
                now,
                "MODEL_OUTPUT",
                "DOCUMENT",
                "READY",
                true,
                0,
                null
        );
        ArtifactExportService service = new ArtifactExportService(
                artifactRepository,
                assetService,
                jdbcTemplate,
                actorContextProvider,
                exporter,
                new ObjectMapper(),
                entityManager,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        return new Fixture(
                service,
                artifactRepository,
                assetService,
                jdbcTemplate,
                exporter,
                entityManager,
                tenantId,
                userId,
                artifactId,
                generatedAsset
        );
    }

    private static Map<String, Object> comparisonContent() {
        return Map.ofEntries(
                Map.entry("format", "document_comparison"),
                Map.entry("mode", "contract"),
                Map.entry("detectedMode", "contract"),
                Map.entry("hasBaseline", true),
                Map.entry("summary", "总体结论"),
                Map.entry("comparability", Map.of(
                        "status", "COMPARABLE",
                        "reason", "两份合同主题一致",
                        "sharedTopics", List.of("终止条款"),
                        "citationMarkers", List.of("S1", "S2")
                )),
                Map.entry("reportMarkdown", "# 对比结论\n总体结论"),
                Map.entry("documents", List.of(
                        Map.of(
                                "assetId", UUID.randomUUID().toString(),
                                "fileName", "baseline.pdf",
                                "role", "baseline"
                        ),
                        Map.of(
                                "assetId", UUID.randomUUID().toString(),
                                "fileName", "comparison.pdf",
                                "role", "comparison"
                        )
                )),
                Map.entry("pairwiseComparisons", List.of()),
                Map.entry("crossDocumentConclusion", Map.of(
                        "summary", "总体结论",
                        "findings", List.of()
                )),
                Map.entry("risks", List.of()),
                Map.entry("citations", List.of()),
                Map.entry("warnings", List.of()),
                Map.entry("exportOptions", List.of(Map.of(
                        "type", "excel",
                        "label", "导出 Excel 报告",
                        "fileName", "多文档对比报告.xlsx",
                        "mediaType",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )))
        );
    }

    private record Fixture(
            ArtifactExportService service,
            ArtifactRepository artifactRepository,
            AssetService assetService,
            JdbcTemplate jdbcTemplate,
            DocumentComparisonExporter exporter,
            EntityManager entityManager,
            UUID tenantId,
            UUID userId,
            UUID artifactId,
            AssetService.AssetView generatedAsset
    ) {
    }
}
