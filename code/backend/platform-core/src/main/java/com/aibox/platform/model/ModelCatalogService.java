package com.aibox.platform.model;

import com.aibox.platform.common.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class ModelCatalogService {

    private static final Set<String> PUBLIC_PARAMETER_CONSTRAINT_KEYS = Set.of(
            "mode", "editable", "allowedValues", "min", "max", "step", "maxLength", "label", "unit"
    );

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
        return getFeaturePolicies(featureCode).stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ModelPolicyView> getFeaturePolicies(String featureCode) {
        return policyRepository.findByFeatureCodeOrderByCapabilityAsc(featureCode).stream()
                .map(this::toPolicyView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ResolvedModels resolveForRun(
            String featureCode,
            Map<String, String> requestedModels,
            String legacyRequestedDeploymentCode
    ) {
        List<FeatureModelPolicyEntity> policies = policyRepository
                .findByFeatureCodeOrderByCapabilityAsc(featureCode);
        Map<String, String> requested = normalizeRequestedModels(requestedModels);
        if (requested.isEmpty() && legacyRequestedDeploymentCode != null
                && !legacyRequestedDeploymentCode.isBlank() && !policies.isEmpty()) {
            requested.put(policies.get(0).getCapability(), legacyRequestedDeploymentCode.trim());
        }
        if (policies.isEmpty()) {
            if (!requested.isEmpty()) {
                throw new PlatformException(
                        "FEATURE_MODEL_NOT_CONFIGURED", "The feature does not allow selecting models"
                );
            }
            return new ResolvedModels(Map.of(), null);
        }

        Set<String> capabilities = policies.stream()
                .map(FeatureModelPolicyEntity::getCapability)
                .collect(Collectors.toSet());
        if (!capabilities.containsAll(requested.keySet())) {
            throw new PlatformException(
                    "MODEL_CAPABILITY_NOT_CONFIGURED",
                    "A selected model capability is not configured for the feature"
            );
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        for (FeatureModelPolicyEntity policy : policies) {
            resolved.put(policy.getCapability(), resolvePolicy(policy, requested.get(policy.getCapability())));
        }
        return new ResolvedModels(resolved, resolved.get(policies.get(0).getCapability()));
    }

    @Transactional(readOnly = true)
    public ResolvedSelection resolveForRun(String featureCode, String requestedDeploymentCode) {
        ResolvedModels resolved = resolveForRun(featureCode, Map.of(), requestedDeploymentCode);
        if (resolved.primaryDeploymentCode() == null) return null;
        String capability = resolved.deployments().entrySet().stream()
                .filter(entry -> entry.getValue().equals(resolved.primaryDeploymentCode()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        return new ResolvedSelection(resolved.primaryDeploymentCode(), capability);
    }

    private ModelPolicyView toPolicyView(FeatureModelPolicyEntity policy) {
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

    private ModelOptionView toOptionView(
            FeatureModelOptionEntity option,
            FeatureModelPolicyEntity policy
    ) {
        ModelDeploymentEntity deployment = deploymentRepository
                .findByCodeAndEnabledTrue(option.getDeploymentCode())
                .orElse(null);
        if (deployment == null || !deployment.getCapability().equals(policy.getCapability())) return null;
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
                provider.getDisplayName(),
                publicMaxReferenceImages(deployment),
                publicParameterOptions(deployment),
                publicParameterConstraints(deployment)
        );
    }

    private static Integer publicMaxReferenceImages(ModelDeploymentEntity deployment) {
        Map<String, Object> config = deployment.getConfig();
        if (config == null) return null;
        Object value = config.get("maxReferenceImages");
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(value.toString()));
            } catch (NumberFormatException ignored) {
                // Fall through to the older boolean capability flag.
            }
        }
        Object supported = config.get("supportsReferenceImages");
        if (supported instanceof Boolean flag) return flag ? null : 0;
        if (supported != null && "false".equalsIgnoreCase(supported.toString())) return 0;
        return null;
    }

    private static Map<String, Map<String, Object>> publicParameterConstraints(
            ModelDeploymentEntity deployment
    ) {
        Map<String, Object> config = deployment.getConfig();
        if (config == null || !(config.get("parameterConstraints") instanceof Map<?, ?> configured)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        configured.forEach((rawField, rawConstraint) -> {
            String field = rawField == null ? "" : rawField.toString().trim();
            if (field.isEmpty() || field.length() > 120 || !(rawConstraint instanceof Map<?, ?> constraint)) {
                return;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            constraint.forEach((rawKey, rawValue) -> {
                String key = rawKey == null ? "" : rawKey.toString().trim();
                if (key.isEmpty()
                        || key.length() > 80
                        || !PUBLIC_PARAMETER_CONSTRAINT_KEYS.contains(key)
                        || !isPublicConstraintValue(rawValue)) return;
                safe.put(key, rawValue);
            });
            if (!safe.isEmpty()) result.put(field, Map.copyOf(safe));
        });
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private static boolean isPublicConstraintValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return true;
        }
        if (value instanceof List<?> list) {
            return list.stream().allMatch(ModelCatalogService::isPublicConstraintValue);
        }
        return false;
    }

    private static Map<String, List<String>> publicParameterOptions(
            ModelDeploymentEntity deployment
    ) {
        Map<String, Object> config = deployment.getConfig();
        if (config == null || !(config.get("parameterOptions") instanceof Map<?, ?> configured)) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        configured.forEach((rawField, rawOptions) -> {
            String field = rawField == null ? "" : rawField.toString().trim();
            if (field.isEmpty() || field.length() > 120 || !(rawOptions instanceof List<?> options)) {
                return;
            }
            List<String> values = options.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty() && value.length() <= 120)
                    .distinct()
                    .limit(50)
                    .toList();
            if (!values.isEmpty()) result.put(field, values);
        });
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }
    private String resolvePolicy(FeatureModelPolicyEntity policy, String requestedDeploymentCode) {
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
        return selected;
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

    private static Map<String, String> normalizeRequestedModels(Map<String, String> source) {
        Map<String, String> normalized = new TreeMap<>();
        if (source == null) return normalized;
        source.forEach((capability, deployment) -> {
            if (capability != null && !capability.isBlank() && deployment != null && !deployment.isBlank()) {
                normalized.put(capability.trim().toUpperCase(), deployment.trim());
            }
        });
        return normalized;
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
            String sourceName,
            Integer maxReferenceImages,
            Map<String, List<String>> parameterOptions,
            Map<String, Map<String, Object>> parameterConstraints
    ) {
    }

    public record ResolvedSelection(String deploymentCode, String capability) {
    }

    public record ResolvedModels(Map<String, String> deployments, String primaryDeploymentCode) {
        public ResolvedModels {
            deployments = deployments == null ? Map.of() : Map.copyOf(deployments);
        }
    }
}
