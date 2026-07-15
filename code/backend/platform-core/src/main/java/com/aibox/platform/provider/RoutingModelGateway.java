package com.aibox.platform.provider;

import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.MultimodalTextGenerationRequest;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.TextToSpeechRequest;
import com.aibox.feature.spi.TextToSpeechResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.model.ModelRoutingService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public final class RoutingModelGateway implements ModelGateway {

    private final List<ModelProviderClient> providers;
    private final ProviderInvocationRepository invocationRepository;
    private final AssetService assetService;
    private final ModelRoutingService routingService;
    private final Clock clock;

    public RoutingModelGateway(
            List<ModelProviderClient> providers,
            ProviderInvocationRepository invocationRepository,
            AssetService assetService,
            ModelRoutingService routingService,
            Clock clock
    ) {
        this.providers = List.copyOf(providers);
        this.invocationRepository = invocationRepository;
        this.assetService = assetService;
        this.routingService = routingService;
        this.clock = clock;
    }

    @Override
    public TextGenerationResponse generateText(TextGenerationRequest request) {
        ProviderTarget selected = requireProvider(
                ModelCapability.TEXT_GENERATION, request.modelAlias(), request.deploymentCode()
        );
        return invoke(
                request.tenantId(), request.runId(), ModelCapability.TEXT_GENERATION, request.modelAlias(),
                selected, fingerprint(request.modelAlias(), selected.target().deploymentCode(),
                        request.systemPrompt(), request.userPrompt()),
                () -> selected.provider().generateText(selected.target(), request),
                response -> new InvocationOutcome(response.model(), response.providerRequestId(),
                        response.inputTokens(), response.outputTokens())
        );
    }

    @Override
    public TextGenerationResponse generateMultimodalText(MultimodalTextGenerationRequest request) {
        ProviderTarget selected = requireProvider(
                ModelCapability.VISION, request.modelAlias(), request.deploymentCode()
        );
        List<ModelAsset> assets = request.inputAssetIds().stream().map(assetService::readForModel).toList();
        return invoke(
                request.tenantId(), request.runId(), ModelCapability.VISION, request.modelAlias(), selected,
                fingerprint(request.modelAlias(), selected.target().deploymentCode(),
                        request.systemPrompt(), request.userPrompt(),
                        request.inputAssetIds().toString()),
                () -> selected.provider().generateMultimodalText(selected.target(), request, assets),
                response -> new InvocationOutcome(response.model(), response.providerRequestId(),
                        response.inputTokens(), response.outputTokens())
        );
    }

    @Override
    public AudioTranscriptionResponse transcribeAudio(AudioTranscriptionRequest request) {
        ProviderTarget selected = requireProvider(
                ModelCapability.AUDIO_TRANSCRIPTION, request.modelAlias(), request.deploymentCode()
        );
        ModelAsset asset = assetService.readForModel(request.inputAssetId());
        return invoke(
                request.tenantId(), request.runId(), ModelCapability.AUDIO_TRANSCRIPTION, request.modelAlias(),
                selected, fingerprint(request.modelAlias(), selected.target().deploymentCode(),
                        request.inputAssetId().toString(), request.language(), request.prompt()),
                () -> selected.provider().transcribeAudio(selected.target(), request, asset),
                response -> new InvocationOutcome(response.model(), response.providerRequestId(),
                        response.inputUnits(), response.outputUnits())
        );
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        ProviderTarget selected = requireProvider(
                ModelCapability.IMAGE_GENERATION, request.modelAlias(), request.deploymentCode()
        );
        List<ModelAsset> assets = request.inputAssetIds().stream().map(assetService::readForModel).toList();
        return invoke(
                request.tenantId(), request.runId(), ModelCapability.IMAGE_GENERATION, request.modelAlias(),
                selected, fingerprint(request.modelAlias(), selected.target().deploymentCode(),
                        request.prompt(), request.inputAssetIds().toString(), request.size(),
                        Integer.toString(request.count())),
                () -> selected.provider().generateImage(selected.target(), request, assets),
                response -> new InvocationOutcome(response.model(), response.providerRequestId(),
                        response.inputUnits(), response.outputUnits())
        );
    }

    @Override
    public TextToSpeechResponse synthesizeSpeech(TextToSpeechRequest request) {
        ProviderTarget selected = requireProvider(
                ModelCapability.TEXT_TO_SPEECH, request.modelAlias(), request.deploymentCode()
        );
        return invoke(
                request.tenantId(), request.runId(), ModelCapability.TEXT_TO_SPEECH, request.modelAlias(),
                selected, fingerprint(request.modelAlias(), selected.target().deploymentCode(),
                        request.text(), request.voice(), request.format(), String.valueOf(request.speed())),
                () -> selected.provider().synthesizeSpeech(selected.target(), request),
                response -> new InvocationOutcome(response.model(), response.providerRequestId(),
                        response.inputUnits(), response.outputUnits())
        );
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        ProviderTarget selected = requireProvider(
                ModelCapability.VIDEO_GENERATION, request.modelAlias(), request.deploymentCode()
        );
        List<ModelAsset> assets = request.inputAssetIds().stream().map(assetService::readForModel).toList();
        return invoke(
                request.tenantId(), request.runId(), ModelCapability.VIDEO_GENERATION, request.modelAlias(),
                selected, fingerprint(request.modelAlias(), selected.target().deploymentCode(),
                        request.prompt(), request.inputAssetIds().toString(),
                        String.valueOf(request.durationSeconds()), request.aspectRatio(), request.resolution(),
                        Integer.toString(request.count())),
                () -> selected.provider().generateVideo(selected.target(), request, assets),
                response -> new InvocationOutcome(response.model(), response.providerRequestId(),
                        response.inputUnits(), response.outputUnits())
        );
    }

    private ProviderTarget requireProvider(
            ModelCapability capability,
            String modelAlias,
            String selectedDeploymentCode
    ) {
        for (ModelCallTarget target : routingService.resolveCandidates(
                capability, modelAlias, selectedDeploymentCode
        )) {
            ModelProviderClient provider = providers.stream()
                    .filter(candidate -> candidate.supports(target))
                    .findFirst()
                    .orElse(null);
            if (provider != null) {
                return new ProviderTarget(provider, target);
            }
        }
        throw new ModelProviderException(
                "MODEL_ADAPTER_NOT_FOUND",
                "No configured protocol adapter can call " + capability + " with alias " + modelAlias,
                false
        );
    }

    private <T> T invoke(
            UUID tenantId,
            UUID runId,
            ModelCapability capability,
            String modelAlias,
            ProviderTarget selected,
            String requestFingerprint,
            Supplier<T> call,
            Function<T, InvocationOutcome> outcomeMapper
    ) {
        ProviderInvocationEntity invocation = new ProviderInvocationEntity(
                UUID.randomUUID(), tenantId, runId, capability.name(), selected.target().providerCode(),
                selected.target().deploymentCode(), modelAlias, requestFingerprint, clock.instant()
        );
        invocationRepository.save(invocation);
        try {
            T response = call.get();
            InvocationOutcome outcome = outcomeMapper.apply(response);
            invocation.succeed(outcome.model(), outcome.requestId(), outcome.inputUnits(), outcome.outputUnits(),
                    clock.instant());
            invocationRepository.save(invocation);
            return response;
        } catch (ModelProviderException exception) {
            invocation.fail(exception.code(), clock.instant());
            invocationRepository.save(invocation);
            throw exception;
        } catch (RuntimeException exception) {
            invocation.fail("PROVIDER_UNEXPECTED_ERROR", clock.instant());
            invocationRepository.save(invocation);
            throw new ModelProviderException(
                    "PROVIDER_UNEXPECTED_ERROR", "Model provider failed unexpectedly", true, exception
            );
        }
    }

    private static String fingerprint(String... values) {
        String value = String.join("\n", java.util.Arrays.stream(values)
                .map(item -> item == null ? "" : item)
                .toList());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record InvocationOutcome(String model, String requestId, Integer inputUnits, Integer outputUnits) {
    }

    private record ProviderTarget(ModelProviderClient provider, ModelCallTarget target) {
    }
}
