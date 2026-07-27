package com.aibox.platform.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentKnowledgeServicePdfHeuristicTest {

    @Test
    void doesNotSendATextRichPageToVisionOnlyBecauseItHasAPageBorder() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.addRect(20, 20, 550, 800);
                content.stroke();
            }

            assertThat(DocumentKnowledgeService.requiresPdfVision(
                    "可提取正文".repeat(150),
                    page,
                    "这段剧情讲了什么？"
            )).isFalse();
        }
    }

    @Test
    void stillSendsASparseScannedPageToVision() {
        assertThat(DocumentKnowledgeService.requiresPdfVision(
                "",
                new PDPage(),
                "文档内容是什么？"
        )).isTrue();
    }

    @Test
    void analyzesAChartLikePageWhenTheQuestionExplicitlyAsksAboutAChart()
            throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < 10; index++) {
                    content.moveTo(30, 100 + index * 20);
                    content.lineTo(500, 120 + index * 20);
                    content.stroke();
                }
            }

            assertThat(DocumentKnowledgeService.requiresPdfVision(
                    "图表标题与坐标轴标签".repeat(20),
                    page,
                    "这个折线图的趋势是什么？"
            )).isTrue();
        }
    }
}
