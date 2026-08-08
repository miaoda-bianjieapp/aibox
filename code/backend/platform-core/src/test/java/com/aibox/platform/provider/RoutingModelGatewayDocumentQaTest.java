package com.aibox.platform.provider;

import com.aibox.feature.spi.DocumentQuestionRequest;
import com.aibox.feature.spi.DocumentQuestionResponse;
import com.aibox.feature.spi.DocumentConversationTurn;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.document.DocumentKnowledgeService;
import com.aibox.platform.execution.RunExecutionPhaseService;
import com.aibox.platform.model.ModelRoutingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingModelGatewayDocumentQaTest {

    @Test
    void reranksBm25CandidatesAndMapsCitationsBackToTheSelectedChunk() {
        ProviderInvocationRepository invocationRepository =
                mock(ProviderInvocationRepository.class);
        when(invocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ModelRoutingService routingService = mock(ModelRoutingService.class);
        when(routingService.resolveCandidates(
                ModelCapability.TEXT_GENERATION,
                "document.qa.text",
                "selected-text"
        )).thenReturn(List.of(new ModelCallTarget(
                "selected-text",
                "relay",
                "gpt-5.6-sol",
                ModelCapability.TEXT_GENERATION,
                Map.of()
        )));

        List<DocumentKnowledgeService.ChunkCandidate> candidates = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            candidates.add(new DocumentKnowledgeService.ChunkCandidate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "source-" + index + ".pdf",
                    "候选段落 " + index,
                    Map.of("type", "PDF_PAGE", "pageNumber", index),
                    10 - index
            ));
        }
        DocumentKnowledgeService knowledgeService =
                mock(DocumentKnowledgeService.class);
        when(knowledgeService.prepareAndSearch(any(), any())).thenReturn(
                new DocumentKnowledgeService.PreparedSearch(
                        candidates,
                        Map.of("retrievalMode", "LUCENE_BM25")
                )
        );
        ModelProviderClient provider = new ModelProviderClient() {
            @Override
            public String adapterCode() {
                return "test";
            }

            @Override
            public boolean supports(ModelCallTarget target) {
                return "relay".equals(target.providerCode());
            }

            @Override
            public TextGenerationResponse generateText(
                    ModelCallTarget target,
                    TextGenerationRequest request
            ) {
                String operation = request.metadata().get("operation").toString();
                assertThat(request.userPrompt())
                        .contains("上一问里的项目叫什么？")
                        .doesNotContain("秘密答案不应作为证据");
                String text = switch (operation) {
                    case "DOCUMENT_RERANK" -> "[\"C9\",\"C2\"]";
                    case "DOCUMENT_ANSWER" -> "第九段是最终依据 [S1]";
                    default -> throw new AssertionError("Unexpected operation: " + operation);
                };
                return new TextGenerationResponse(
                        text,
                        "relay",
                        target.providerModel(),
                        operation,
                        10,
                        5
                );
            }
        };
        RoutingModelGateway gateway = new RoutingModelGateway(
                List.of(provider),
                invocationRepository,
                mock(AssetService.class),
                knowledgeService,
                routingService,
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC),
                mock(RunExecutionPhaseService.class)
        );
        DocumentQuestionRequest request = new DocumentQuestionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "最终依据是什么？",
                List.of(UUID.randomUUID()),
                List.of(new DocumentConversationTurn(
                        "上一问里的项目叫什么？",
                        "秘密答案不应作为证据 [S1]"
                )),
                "document.qa.text",
                "selected-text",
                "document.qa.vision",
                "selected-vision",
                1000,
                Map.of()
        );
        List<String> deltas = new ArrayList<>();

        DocumentQuestionResponse response = gateway.answerDocumentQuestion(
                request,
                delta -> {
                    deltas.add(delta);
                    return true;
                }
        );

        assertThat(response.answerMarkdown()).isEqualTo("第九段是最终依据 [S1]");
        assertThat(deltas).containsExactly("第九段是最终依据 [S1]");
        assertThat(response.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.assetId()).isEqualTo(candidates.get(8).assetId());
            assertThat(citation.fileName()).isEqualTo("source-9.pdf");
            assertThat(citation.locator().get("pageNumber")).isEqualTo(9);
        });
        assertThat(response.metadata().get("retrievalMode")).isEqualTo("LUCENE_BM25");
        verify(invocationRepository, times(4)).save(any(ProviderInvocationEntity.class));
    }
}
