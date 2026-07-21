package com.aibox.platform.provider;

import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.GeneratedAudio;
import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.GeneratedVideo;
import com.aibox.feature.spi.ImageExpansionRequest;
import com.aibox.feature.spi.ImageExpansionResponse;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.ImagePreservationMode;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.TextToSpeechRequest;
import com.aibox.feature.spi.TextToSpeechResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.model.ModelRoutingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void recordsPromptOptimizationWithoutATaskRun() {
        ProviderInvocationRepository repository = mock(ProviderInvocationRepository.class);
        AtomicReference<ProviderInvocationEntity> invocation = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(call -> {
            ProviderInvocationEntity saved = call.getArgument(0);
            invocation.compareAndSet(null, saved);
            return saved;
        });
        ModelRoutingService routingService = mock(ModelRoutingService.class);
        when(routingService.resolveCandidates(
                ModelCapability.TEXT_GENERATION,
                "prompt.optimize.default",
                null
        )).thenReturn(List.of(target("prompt-model", ModelCapability.TEXT_GENERATION)));
        RoutingModelGateway gateway = new RoutingModelGateway(
                List.of(new TestProvider()),
                repository,
                mock(AssetService.class),
                routingService,
                Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC)
        );

        gateway.generatePromptOptimization(new TextGenerationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "prompt.optimize.default",
                null,
                "system",
                "user",
                500,
                0.4,
                Map.of()
        ));

        assertThat(invocation.get().getInvocationScope())
                .isEqualTo(ProviderInvocationScope.PROMPT_ASSIST);
        assertThat(invocation.get().getRunId()).isNull();
        verify(repository, times(2)).save(any(ProviderInvocationEntity.class));
    }

    @Test
    void routesTextToSpeechAndVideoCapabilities() {
        ProviderInvocationRepository repository = mock(ProviderInvocationRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ModelRoutingService routingService = mock(ModelRoutingService.class);
        when(routingService.resolveCandidates(ModelCapability.TEXT_TO_SPEECH, "speech.default", null))
                .thenReturn(List.of(target("test-speech", ModelCapability.TEXT_TO_SPEECH)));
        when(routingService.resolveCandidates(ModelCapability.VIDEO_GENERATION, "video.default", null))
                .thenReturn(List.of(target("test-video", ModelCapability.VIDEO_GENERATION)));
        RoutingModelGateway gateway = new RoutingModelGateway(
                List.of(new TestProvider()), repository, mock(AssetService.class), routingService,
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
        );

        TextToSpeechResponse speech = gateway.synthesizeSpeech(new TextToSpeechRequest(
                UUID.randomUUID(), UUID.randomUUID(), "speech.default", null,
                "hello", "alloy", 1.0, "mp3", Map.of()
        ));
        VideoGenerationResponse video = gateway.generateVideo(new VideoGenerationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "video.default", null,
                "sunrise", List.of(), 5, "16:9", "720p", 1, Map.of()
        ));

        assertThat(speech.audio().content()).isNotEmpty();
        assertThat(video.videos()).hasSize(1);
        verify(repository, times(4)).save(any(ProviderInvocationEntity.class));
    }

    @Test
    void appendsInlineImagesAfterPersistedInputAssets() {
        ProviderInvocationRepository repository = mock(ProviderInvocationRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ModelRoutingService routingService = mock(ModelRoutingService.class);
        when(routingService.resolveCandidates(
                ModelCapability.IMAGE_GENERATION,
                "image.generation.default",
                "test-image"
        )).thenReturn(List.of(target("test-image", ModelCapability.IMAGE_GENERATION)));
        UUID persistedId = UUID.randomUUID();
        ModelAsset persisted = new ModelAsset(
                persistedId,
                "source.png",
                "image/png",
                new byte[]{1}
        );
        ModelAsset inline = new ModelAsset(
                UUID.randomUUID(),
                "white-background.png",
                "image/png",
                new byte[]{2}
        );
        AssetService assetService = mock(AssetService.class);
        when(assetService.readForModel(persistedId)).thenReturn(persisted);
        AtomicReference<List<ModelAsset>> capturedAssets = new AtomicReference<>();
        ModelProviderClient provider = new TestProvider() {
            @Override
            public ImageGenerationResponse generateImage(
                    ModelCallTarget target,
                    ImageGenerationRequest request,
                    List<ModelAsset> assets
            ) {
                capturedAssets.set(assets);
                return new ImageGenerationResponse(
                        List.of(new GeneratedImage(null, "image/png", null, new byte[]{3})),
                        "test",
                        "model",
                        "image-request",
                        1,
                        1
                );
            }
        };
        RoutingModelGateway gateway = new RoutingModelGateway(
                List.of(provider),
                repository,
                assetService,
                routingService,
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC)
        );

        gateway.generateImage(new ImageGenerationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "image.generation.default",
                "test-image",
                "change the background",
                List.of(persistedId),
                List.of(inline),
                null,
                1,
                Map.of()
        ));

        assertThat(capturedAssets.get()).containsExactly(persisted, inline);
        verify(repository, times(2)).save(any(ProviderInvocationEntity.class));
    }

    @Test
    void routesImageExpansionWithTheOwnedSourceAsset() {
        ProviderInvocationRepository repository = mock(ProviderInvocationRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ModelRoutingService routingService = mock(ModelRoutingService.class);
        when(routingService.resolveCandidates(
                ModelCapability.IMAGE_GENERATION, "image.generation.default", "test-image"
        )).thenReturn(List.of(target("test-image", ModelCapability.IMAGE_GENERATION)));
        AssetService assetService = mock(AssetService.class);
        UUID assetId = UUID.randomUUID();
        when(assetService.readForModel(assetId)).thenReturn(
                new ModelAsset(assetId, "source.png", "image/png", new byte[]{1})
        );
        RoutingModelGateway gateway = new RoutingModelGateway(
                List.of(new TestProvider()), repository, assetService, routingService,
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
        );

        ImageExpansionResponse response = gateway.expandImage(new ImageExpansionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "image.generation.default",
                "test-image",
                "expand",
                assetId,
                "16:9",
                1.25,
                ImagePreservationMode.STRICT,
                Map.of()
        ));

        assertThat(response.targetWidth()).isEqualTo(1280);
        assertThat(response.generation().images()).hasSize(1);
        verify(assetService).readForModel(assetId);
        verify(repository, times(2)).save(any(ProviderInvocationEntity.class));
    }

    private static ModelCallTarget target(String deployment, ModelCapability capability) {
        return new ModelCallTarget(deployment, "test", "provider-model", capability, Map.of());
    }

    private static class TestProvider implements ModelProviderClient {

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

        @Override
        public TextToSpeechResponse synthesizeSpeech(ModelCallTarget target, TextToSpeechRequest request) {
            return new TextToSpeechResponse(
                    new GeneratedAudio("speech.mp3", "audio/mpeg", new byte[]{1}),
                    "test", "model", "speech-request", 1, 1
            );
        }

        @Override
        public VideoGenerationResponse generateVideo(
                ModelCallTarget target,
                VideoGenerationRequest request,
                List<com.aibox.feature.spi.ModelAsset> assets
        ) {
            return new VideoGenerationResponse(
                    List.of(new GeneratedVideo(null, "video.mp4", "video/mp4", new byte[]{1})),
                    "test", "model", "video-request", 1, 1
            );
        }

        @Override
        public ImageExpansionResponse expandImage(
                ModelCallTarget target,
                ImageExpansionRequest request,
                ModelAsset asset
        ) {
            return new ImageExpansionResponse(
                    new ImageGenerationResponse(
                            List.of(new GeneratedImage(null, "image/png", null, new byte[]{1})),
                            "test", "model", "image-request", 1, 1
                    ),
                    640,
                    480,
                    1280,
                    720
            );
        }
    }
}
