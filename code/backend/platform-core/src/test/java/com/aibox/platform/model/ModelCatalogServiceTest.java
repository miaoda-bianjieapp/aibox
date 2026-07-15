package com.aibox.platform.model;

import com.aibox.platform.common.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCatalogServiceTest {

    private final FeatureModelPolicyRepository policyRepository = mock(FeatureModelPolicyRepository.class);
    private final FeatureModelOptionRepository optionRepository = mock(FeatureModelOptionRepository.class);
    private final ModelDeploymentRepository deploymentRepository = mock(ModelDeploymentRepository.class);
    private final ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
    private final ModelCatalogService service = new ModelCatalogService(
            policyRepository, optionRepository, deploymentRepository, providerRepository
    );

    @BeforeEach
    void configurePolicy() {
        UUID policyId = UUID.randomUUID();
        FeatureModelPolicyEntity policy = mock(FeatureModelPolicyEntity.class);
        when(policy.getId()).thenReturn(policyId);
        when(policy.getCapability()).thenReturn("TEXT_GENERATION");
        when(policy.getDefaultDeploymentCode()).thenReturn("model-a");
        when(policy.isAllowUserSelection()).thenReturn(true);
        when(policyRepository.findFirstByFeatureCodeOrderByCapabilityAsc("writing.draft"))
                .thenReturn(Optional.of(policy));

        FeatureModelOptionEntity optionA = option("model-a");
        FeatureModelOptionEntity optionB = option("model-b");
        when(optionRepository.findByPolicyIdAndEnabledTrueOrderBySortOrderAsc(policyId))
                .thenReturn(List.of(optionA, optionB));

        availableDeployment("model-a");
        availableDeployment("model-b");
        ModelProviderEntity provider = mock(ModelProviderEntity.class);
        when(provider.getProviderKind()).thenReturn(ModelProviderKind.OFFICIAL);
        when(provider.getDisplayName()).thenReturn("Official provider");
        when(providerRepository.findByCodeAndEnabledTrue("provider")).thenReturn(Optional.of(provider));
    }

    @Test
    void resolvesDefaultAndAllowedUserSelection() {
        assertThat(service.resolveForRun("writing.draft", null).deploymentCode()).isEqualTo("model-a");
        assertThat(service.resolveForRun("writing.draft", "model-b").deploymentCode()).isEqualTo("model-b");
    }

    @Test
    void rejectsDeploymentOutsideFeaturePolicy() {
        assertThatThrownBy(() -> service.resolveForRun("writing.draft", "model-c"))
                .isInstanceOf(PlatformException.class)
                .extracting(exception -> ((PlatformException) exception).code())
                .isEqualTo("MODEL_NOT_ALLOWED_FOR_FEATURE");
    }

    @Test
    void exposesProviderSourceWithoutConnectionDetails() {
        ModelCatalogService.ModelPolicyView policy = service.getFeaturePolicy("writing.draft");

        assertThat(policy.options()).allSatisfy(option -> {
            assertThat(option.sourceType()).isEqualTo("OFFICIAL");
            assertThat(option.sourceName()).isEqualTo("Official provider");
        });
    }

    private FeatureModelOptionEntity option(String code) {
        FeatureModelOptionEntity option = mock(FeatureModelOptionEntity.class);
        when(option.getDeploymentCode()).thenReturn(code);
        when(option.getDisplayName()).thenReturn(code);
        when(option.getDescription()).thenReturn("description");
        return option;
    }

    private void availableDeployment(String code) {
        ModelDeploymentEntity deployment = mock(ModelDeploymentEntity.class);
        when(deployment.getCapability()).thenReturn("TEXT_GENERATION");
        when(deployment.getProviderCode()).thenReturn("provider");
        when(deploymentRepository.findByCodeAndEnabledTrue(code)).thenReturn(Optional.of(deployment));
    }
}
