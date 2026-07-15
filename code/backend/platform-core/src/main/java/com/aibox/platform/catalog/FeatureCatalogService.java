package com.aibox.platform.catalog;

import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.model.ModelCatalogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FeatureCatalogService {

    private static final Set<FeatureStatus> VISIBLE_STATUSES = EnumSet.of(
            FeatureStatus.INTERNAL,
            FeatureStatus.BETA,
            FeatureStatus.PUBLISHED
    );

    private final WorkspaceRepository workspaceRepository;
    private final FeatureDefinitionRepository featureRepository;
    private final FeatureVersionRepository versionRepository;
    private final ModelCatalogService modelCatalogService;

    public FeatureCatalogService(
            WorkspaceRepository workspaceRepository,
            FeatureDefinitionRepository featureRepository,
            FeatureVersionRepository versionRepository,
            ModelCatalogService modelCatalogService
    ) {
        this.workspaceRepository = workspaceRepository;
        this.featureRepository = featureRepository;
        this.versionRepository = versionRepository;
        this.modelCatalogService = modelCatalogService;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceView> listVisibleWorkspaces() {
        List<WorkspaceEntity> workspaces = workspaceRepository.findByEnabledTrueOrderBySortOrderAsc();
        Map<UUID, List<FeatureDefinitionEntity>> featuresByWorkspace = featureRepository
                .findByWorkspaceIdInAndStatusInOrderBySortOrderAsc(
                        workspaces.stream().map(WorkspaceEntity::getId).toList(),
                        VISIBLE_STATUSES
                )
                .stream()
                .collect(Collectors.groupingBy(FeatureDefinitionEntity::getWorkspaceId));

        return workspaces.stream()
                .map(workspace -> new WorkspaceView(
                        workspace.getCode(),
                        workspace.getDisplayName(),
                        workspace.getDescription(),
                        workspace.getIconKey(),
                        workspace.getGroups(),
                        workspace.getSearchTerms(),
                        featuresByWorkspace.getOrDefault(workspace.getId(), List.of()).stream()
                                .map(this::toSummary)
                                .toList()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public FeatureDetailView getVisibleFeature(String code) {
        FeatureDefinitionEntity feature = requireVisibleFeature(code);
        FeatureVersionEntity version = versionRepository
                .findByFeatureIdAndVersion(feature.getId(), feature.getCurrentVersion())
                .orElseThrow(() -> new NotFoundException("feature version", code + ":" + feature.getCurrentVersion()));
        List<ModelCatalogService.ModelPolicyView> modelPolicies = modelCatalogService.getFeaturePolicies(code);

        return new FeatureDetailView(
                feature.getCode(),
                feature.getDisplayName(),
                feature.getDescription(),
                version.getVersion(),
                feature.getResultType(),
                feature.getRendererKey(),
                feature.getExecutionMode(),
                version.getInputSchema(),
                version.getUiSchema(),
                version.getOutputSchema(),
                version.getConfig(),
                modelPolicies.stream().findFirst().orElse(null),
                modelPolicies
        );
    }

    @Transactional(readOnly = true)
    public FeatureDefinitionEntity requireVisibleFeature(String code) {
        FeatureDefinitionEntity feature = featureRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("feature", code));
        if (!VISIBLE_STATUSES.contains(feature.getStatus())) {
            throw new NotFoundException("feature", code);
        }
        return feature;
    }

    private FeatureSummaryView toSummary(FeatureDefinitionEntity feature) {
        return new FeatureSummaryView(
                feature.getCode(),
                feature.getDisplayName(),
                feature.getDescription(),
                feature.getCurrentVersion(),
                feature.getResultType(),
                feature.getRendererKey(),
                feature.getExecutionMode()
        );
    }

    public record WorkspaceView(
            String code,
            String displayName,
            String description,
            String iconKey,
            List<String> groups,
            List<String> searchTerms,
            List<FeatureSummaryView> features
    ) {
    }

    public record FeatureSummaryView(
            String code,
            String displayName,
            String description,
            int version,
            String resultType,
            String rendererKey,
            String executionMode
    ) {
    }

    public record FeatureDetailView(
            String code,
            String displayName,
            String description,
            int version,
            String resultType,
            String rendererKey,
            String executionMode,
            Map<String, Object> inputSchema,
            Map<String, Object> uiSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> config,
            ModelCatalogService.ModelPolicyView modelPolicy,
            List<ModelCatalogService.ModelPolicyView> modelPolicies
    ) {
    }
}
