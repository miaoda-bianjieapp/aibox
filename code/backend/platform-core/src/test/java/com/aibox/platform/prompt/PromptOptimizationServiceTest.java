package com.aibox.platform.prompt;

import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.catalog.FeatureCatalogService;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptOptimizationServiceTest {

    @Test
    void optimizesOnlyConfiguredNonEmptyFieldsWithWhitelistedContext() {
        FeatureCatalogService catalog = mock(FeatureCatalogService.class);
        when(catalog.getVisibleFeature("image.generate")).thenReturn(feature());
        ActorContextProvider actors = mock(ActorContextProvider.class);
        when(actors.current()).thenReturn(new ActorContext(UUID.randomUUID(), UUID.randomUUID()));
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        PromptOptimizationModelGateway gateway = request -> {
            captured.set(request);
            return new TextGenerationResponse(
                    "一只橘猫站在雨夜霓虹街道中，电影感构图。",
                    "test-provider",
                    "test-model",
                    "request-1",
                    10,
                    20
            );
        };
        PromptOptimizationService service = new PromptOptimizationService(
                catalog,
                mock(AssetService.class),
                actors,
                gateway
        );

        PromptOptimizationService.Result result = service.optimize(
                "image.generate",
                new PromptOptimizationService.Command(
                        "prompt",
                        "一只猫",
                        Map.of(
                                "aspectRatio", "16:9",
                                "unusedField", "must not reach the model"
                        ),
                        Map.of()
                )
        );

        assertThat(result.optimizedText()).contains("橘猫");
        assertThat(captured.get().modelAlias()).isEqualTo("prompt.optimize.default");
        assertThat(captured.get().deploymentCode()).isNull();
        assertThat(captured.get().systemPrompt())
                .contains("500")
                .contains("禁止输出")
                .contains("必须保留原文本中的核心主体");
        assertThat(captured.get().userPrompt())
                .contains("一只猫")
                .contains("图片比例")
                .contains("16:9")
                .doesNotContain("must not reach the model");

        Map<String, Object> parametersWithNull = new LinkedHashMap<>();
        parametersWithNull.put("aspectRatio", null);
        PromptOptimizationService.Result nullContextResult = service.optimize(
                "image.generate",
                new PromptOptimizationService.Command(
                        "prompt",
                        "一只猫",
                        parametersWithNull,
                        Map.of()
                )
        );
        assertThat(nullContextResult.optimizedText()).isNotBlank();
    }

    @Test
    void rejectsEmptyOrUnconfiguredFieldsWithoutCallingTheModel() {
        FeatureCatalogService catalog = mock(FeatureCatalogService.class);
        when(catalog.getVisibleFeature("image.generate")).thenReturn(feature());
        ActorContextProvider actors = mock(ActorContextProvider.class);
        when(actors.current()).thenReturn(new ActorContext(UUID.randomUUID(), UUID.randomUUID()));
        PromptOptimizationModelGateway gateway = request -> {
            throw new AssertionError("model must not be called");
        };
        PromptOptimizationService service = new PromptOptimizationService(
                catalog,
                mock(AssetService.class),
                actors,
                gateway
        );

        assertThatThrownBy(() -> service.optimize(
                "image.generate",
                new PromptOptimizationService.Command("prompt", "  ", Map.of(), Map.of())
        )).isInstanceOfSatisfying(
                PlatformException.class,
                exception -> assertThat(exception.code()).isEqualTo("PROMPT_ASSIST_EMPTY")
        );
        assertThatThrownBy(() -> service.optimize(
                "image.generate",
                new PromptOptimizationService.Command("aspectRatio", "16:9", Map.of(), Map.of())
        )).isInstanceOfSatisfying(
                PlatformException.class,
                exception -> assertThat(exception.code()).isEqualTo("PROMPT_ASSIST_FIELD_NOT_ALLOWED")
        );
        assertThatThrownBy(() -> service.optimize(
                "image.generate",
                new PromptOptimizationService.Command(
                        "prompt",
                        "一只猫",
                        Map.of("unknownField", "value"),
                        Map.of()
                )
        )).isInstanceOfSatisfying(
                PlatformException.class,
                exception -> assertThat(exception.code()).isEqualTo("PROMPT_ASSIST_INVALID_PARAMETERS")
        );

        when(catalog.getVisibleFeature("image.generate")).thenReturn(featureWithHiddenPrompt());
        assertThatThrownBy(() -> service.optimize(
                "image.generate",
                new PromptOptimizationService.Command(
                        "prompt",
                        "一只猫",
                        Map.of("mode", "remove"),
                        Map.of()
                )
        )).isInstanceOfSatisfying(
                PlatformException.class,
                exception -> assertThat(exception.code()).isEqualTo("PROMPT_ASSIST_FIELD_NOT_ALLOWED")
        );
    }

    @Test
    void rejectsModelResponsesThatExceedTheTargetFieldLimit() {
        FeatureCatalogService catalog = mock(FeatureCatalogService.class);
        when(catalog.getVisibleFeature("image.generate")).thenReturn(feature());
        ActorContextProvider actors = mock(ActorContextProvider.class);
        when(actors.current()).thenReturn(new ActorContext(UUID.randomUUID(), UUID.randomUUID()));
        PromptOptimizationService service = new PromptOptimizationService(
                catalog,
                mock(AssetService.class),
                actors,
                request -> new TextGenerationResponse(
                        "x".repeat(501),
                        "test-provider",
                        "test-model",
                        "request-2",
                        null,
                        null
                )
        );

        assertThatThrownBy(() -> service.optimize(
                "image.generate",
                new PromptOptimizationService.Command("prompt", "一只猫", Map.of(), Map.of())
        )).isInstanceOfSatisfying(
                PlatformException.class,
                exception -> assertThat(exception.code()).isEqualTo("PROMPT_ASSIST_INVALID_RESPONSE")
        );
    }

    private static FeatureCatalogService.FeatureDetailView feature() {
        return new FeatureCatalogService.FeatureDetailView(
                "image.generate",
                "AI 生图",
                "根据描述生成图片",
                2,
                "image",
                "image",
                "ASYNC",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "prompt", Map.of(
                                        "type", "string",
                                        "title", "画面描述",
                                        "description", "描述主体、场景、风格、构图和光线。",
                                        "maxLength", 500
                                ),
                                "aspectRatio", Map.of(
                                        "type", "string",
                                        "title", "图片比例"
                                ),
                                "unusedField", Map.of(
                                        "type", "string",
                                        "title", "非上下文字段"
                                ),
                                "mode", Map.of(
                                        "type", "string",
                                        "title", "处理方式",
                                        "enum", List.of("remove", "replace")
                                )
                        )
                ),
                Map.of(
                        "promptAssist", Map.of(
                                "fields", Map.of(
                                        "prompt", Map.of(
                                                "contextFields", List.of("aspectRatio")
                                        )
                                )
                        )
                ),
                Map.of(),
                Map.of(),
                null,
                List.of()
        );
    }

    private static FeatureCatalogService.FeatureDetailView featureWithHiddenPrompt() {
        FeatureCatalogService.FeatureDetailView feature = feature();
        Map<String, Object> uiSchema = new java.util.LinkedHashMap<>(feature.uiSchema());
        uiSchema.put("visibility", Map.of(
                "prompt", Map.of("field", "mode", "equals", "replace")
        ));
        return new FeatureCatalogService.FeatureDetailView(
                feature.code(),
                feature.displayName(),
                feature.description(),
                feature.version(),
                feature.resultType(),
                feature.rendererKey(),
                feature.executionMode(),
                feature.inputSchema(),
                Map.copyOf(uiSchema),
                feature.outputSchema(),
                feature.config(),
                feature.modelPolicy(),
                feature.modelPolicies()
        );
    }
}
