package com.aibox.platform.model;

import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ModelRoutingService {
    private final ModelDeploymentRepository deploymentRepository;
    private final ModelProviderRepository providerRepository;
    private final ModelRouteRepository routeRepository;

    public ModelRoutingService(
            ModelDeploymentRepository deploymentRepository,
            ModelProviderRepository providerRepository,
            ModelRouteRepository routeRepository
    ) {
        this.deploymentRepository = deploymentRepository;
        this.providerRepository = providerRepository;
        this.routeRepository = routeRepository;
    }

    @Transactional(readOnly = true)
    public List<ModelCallTarget> resolveCandidates(
            ModelCapability capability,
            String modelAlias,
            String selectedDeploymentCode
    ) {
        if (selectedDeploymentCode != null && !selectedDeploymentCode.isBlank()) {
            return List.of(toTarget(requireDeployment(selectedDeploymentCode, capability)));
        }
        List<ModelCallTarget> targets = routeRepository
                .findByModelAliasAndCapabilityAndEnabledTrueOrderByPriorityAsc(modelAlias, capability.name())
                .stream()
                .map(route -> requireDeployment(route.getDeploymentCode(), capability))
                .map(this::toTarget)
                .toList();
        if (targets.isEmpty()) {
            throw new ModelProviderException(
                    "MODEL_ROUTE_NOT_FOUND",
                    "No enabled deployment is routed for " + capability + " and alias " + modelAlias,
                    false
            );
        }
        return targets;
    }

    private ModelDeploymentEntity requireDeployment(String code, ModelCapability capability) {
        ModelDeploymentEntity deployment = deploymentRepository.findByCodeAndEnabledTrue(code)
                .orElseThrow(() -> new ModelProviderException(
                        "MODEL_DEPLOYMENT_NOT_FOUND", "Model deployment is unavailable: " + code, false));
        if (!deployment.getCapability().equals(capability.name())) {
            throw new ModelProviderException(
                    "MODEL_CAPABILITY_MISMATCH", "Model deployment does not support " + capability, false);
        }
        providerRepository.findByCodeAndEnabledTrue(deployment.getProviderCode())
                .orElseThrow(() -> new ModelProviderException(
                        "MODEL_PROVIDER_DISABLED", "Model provider is disabled", false));
        return deployment;
    }

    private ModelCallTarget toTarget(ModelDeploymentEntity deployment) {
        return new ModelCallTarget(
                deployment.getCode(), deployment.getProviderCode(), deployment.getProviderModel(),
                ModelCapability.valueOf(deployment.getCapability()), deployment.getConfig()
        );
    }
}
