package com.aibox.platform.document;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentKnowledgeServiceCandidateBackfillTest {

    @Test
    void backfillsSparseBm25ResultsSoOverviewQuestionsCanBeReranked() {
        List<DocumentKnowledgeService.ChunkCandidate> all = new ArrayList<>();
        for (int index = 1; index <= 18; index++) {
            all.add(new DocumentKnowledgeService.ChunkCandidate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "overview.pptx",
                    "Slide " + index,
                    Map.of("type", "PPT_SLIDE", "slideNumber", index),
                    0
            ));
        }
        List<DocumentKnowledgeService.ChunkCandidate> ranked =
                List.of(all.get(9));

        List<DocumentKnowledgeService.ChunkCandidate> result =
                DocumentKnowledgeService.backfillCandidates(ranked, all);

        assertThat(result).hasSize(12);
        assertThat(result.get(0)).isSameAs(ranked.get(0));
        assertThat(result)
                .extracting(DocumentKnowledgeService.ChunkCandidate::chunkId)
                .doesNotHaveDuplicates();
    }
}
