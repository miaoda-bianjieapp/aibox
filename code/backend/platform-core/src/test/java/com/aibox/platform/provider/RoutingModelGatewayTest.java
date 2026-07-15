package com.aibox.platform.provider;

import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.model.ModelRoutingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingModelGatewayTest {

    @Test
    void routesByAliasAndRecordsInvocationLifecycle() {
        ProviderInvocationRepository repository = mock(ProviderInvocationRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ModelProviderClient provider = new TestProvider();
        ModelRoutingService routingService = mock(ModelRoutingService.class);
        when(routingService.resolveCandidates(ModelCapability.TEXT_GENERATION, "text.default", null))
                .thenReturn(List.of(new ModelCallTarget(
                        "test-text", "test", "provider-model", ModelCapability.TEXT_GENERATION, Map.of()
                )));
        RoutingModelGateway gateway = new RoutingModelGateway(
                List.of(provider),
                repository,
                mock(AssetService.class),
                routingService,
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
        );

        TextGenerationResponse response = gateway.generateText(new TextGenerationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "text.default",
                "system",
                "user",
                100,
                0.5,
                Map.of()
        ));

        assertThat(response.text()).isEqualTo("result");
        verify(repository, times(2)).save(any(ProviderInvocationEntity.class));
    }

    private static final class TestProvider implements ModelProviderClient {

        @Override
        public String adapterCode() {
            return "test-adapter";
        }

        @Override
        public boolean supports(ModelCallTarget target) {
            return "test".equals(target.providerCode());
        }

        @Override
        public TextGenerationResponse generateText(ModelCallTarget target, TextGenerationRequest request) {
            return new TextGenerationResponse("result", "test", "model", "request", 1, 2);
        }
    }
}
