package com.aibox.platform.model;

import com.aibox.platform.common.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class ModelCatalogService {

    private final FeatureModelPolicyRepository policyRepository;
    private final FeatureModelOptionRepository optionRepository;
    private final ModelDeploymentRepository deploymentRepository;
    private final ModelProviderRepository providerRepository;

    public ModelCatalogService(
            FeatureModelPolicyRepository policyRepository,
            FeatureModelOptionRepository optionRepository,
            ModelDeploymentRepository deploymentRepository,
            ModelProviderRepository providerRepository
    ) {
        this.policyRepository = policyRepository;
        this.optionRepository = optionRepository;
        this.deploymentRepository = deploymentRepository;
        this.providerRepository = providerRepository;
    }

    @Transactional(readOnly = true)
    public ModelPolicyView getFeaturePolicy(String featureCode) {
        FeatureModelPolicyEntity policy = policyRepository
                .findFirstByFeatureCodeOrderByCapabilityAsc(featureCode)
                .orElse(null);
        if (policy == null) return null;
        List<ModelOptionView> options = optionRepository
                .findByPolicyIdAndEnabledTrueOrderBySortOrderAsc(policy.getId())
                .stream()
                .map(option -> toOptionView(option, policy))
                .filter(Objects::nonNull)
                .toList();
        return new ModelPolicyView(
                policy.getCapability(),
                policy.getDefaultDeploymentCode(),
                policy.isAllowUserSelection(),
                options
        );
    }

    @Transactional(readOnly = true)
    public ResolvedSelection resolveForRun(String featureCode, String requestedDeploymentCode) {
        FeatureModelPolicyEntity policy = policyRepository
                .findFirstByFeatureCodeOrderByCapabilityAsc(featureCode)
                .orElse(null);
        if (policy == null) {
            if (requestedDeploymentCode != null && !requestedDeploymentCode.isBlank()) {
                throw new PlatformException(
                        "FEATURE_MODEL_NOT_CONFIGURED",
                        "The feature does not allow selecting a model"
                );
            }
            return null;
        }

        String selected = requestedDeploymentCode == null || requestedDeploymentCode.isBlank()
                ? policy.getDefaultDeploymentCode()
                : requestedDeploymentCode.trim();
        List<String> allowed = optionRepository
                .findByPolicyIdAndEnabledTrueOrderBySortOrderAsc(policy.getId())
                .stream()
                .map(FeatureModelOptionEntity::getDeploymentCode)
                .toList();
        if (!selected.equals(policy.getDefaultDeploymentCode()) && !policy.isAllowUserSelection()) {
            throw new PlatformException("MODEL_SELECTION_NOT_ALLOWED", "This feature uses its configured model");
        }
        if (!allowed.contains(selected)) {
            throw new PlatformException("MODEL_NOT_ALLOWED_FOR_FEATURE", "The selected model is not allowed");
        }
        requireAvailable(selected, policy.getCapability());
        return new ResolvedSelection(selected, policy.getCapability());
    }

    private ModelOptionView toOptionView(
            FeatureModelOptionEntity option,
            FeatureModelPolicyEntity policy
    ) {
        ModelDeploymentEntity deployment = deploymentRepository
                .findByCodeAndEnabledTrue(option.getDeploymentCode())
                .orElse(null);
        if (deployment == null || !deployment.getCapability().equals(policy.getCapability())) {
            return null;
        }
        ModelProviderEntity provider = providerRepository
                .findByCodeAndEnabledTrue(deployment.getProviderCode())
                .orElse(null);
        if (provider == null) return null;
        return new ModelOptionView(
                option.getDeploymentCode(),
                option.getDisplayName(),
                option.getDescription(),
                option.getDeploymentCode().equals(policy.getDefaultDeploymentCode()),
                provider.getProviderKind().name(),
                provider.getDisplayName()
        );
    }

    private void requireAvailable(String deploymentCode, String capability) {
        ModelDeploymentEntity deployment = deploymentRepository.findByCodeAndEnabledTrue(deploymentCode)
                .orElseThrow(() -> new PlatformException("MODEL_NOT_AVAILABLE", "Selected model is unavailable"));
        if (!deployment.getCapability().equals(capability)) {
            throw new PlatformException("MODEL_CAPABILITY_MISMATCH", "Selected model has the wrong capability");
        }
        providerRepository.findByCodeAndEnabledTrue(deployment.getProviderCode())
                .orElseThrow(() -> new PlatformException("MODEL_PROVIDER_DISABLED", "Model provider is disabled"));
    }

    public record ModelPolicyView(
            String capability,
            String defaultModelCode,
            boolean allowUserSelection,
            List<ModelOptionView> options
    ) {
    }

    public record ModelOptionView(
            String code,
            String displayName,
            String description,
            boolean isDefault,
            String sourceType,
            String sourceName
    ) {
    }

    public record ResolvedSelection(String deploymentCode, String capability) {
    }
}
