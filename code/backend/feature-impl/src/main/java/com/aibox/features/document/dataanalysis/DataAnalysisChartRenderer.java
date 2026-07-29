package com.aibox.features.document.dataanalysis;

import com.aibox.feature.spi.TabularAnalysisDataset;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.CategorySeries;

import java.awt.Font;
import java.io.IOException;
import java.util.List;

final class DataAnalysisChartRenderer {

    List<RenderedChart> render(List<TabularAnalysisDataset.ChartCandidate> candidates) {
        return candidates.stream().map(this::render).toList();
    }

    private RenderedChart render(TabularAnalysisDataset.ChartCandidate candidate) {
        CategoryChart chart = new CategoryChartBuilder()
                .width(1280)
                .height(720)
                .title(candidate.title())
                .xAxisTitle(candidate.categoryLabel())
                .yAxisTitle(candidate.valueLabel())
                .build();
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 24);
        Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
        chart.getStyler().setChartTitleFont(titleFont);
        chart.getStyler().setAxisTitleFont(labelFont);
        chart.getStyler().setAxisTickLabelsFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        chart.getStyler().setLegendVisible(false);
        chart.getStyler().setPlotGridLinesVisible(true);
        chart.getStyler().setXAxisLabelRotation(candidate.categories().size() > 8 ? 35 : 0);
        chart.getStyler().setAvailableSpaceFill(0.75);
        if ("LINE".equals(candidate.type())) {
            chart.getStyler().setDefaultSeriesRenderStyle(
                    CategorySeries.CategorySeriesRenderStyle.Line
            );
            chart.getStyler().setMarkerSize(6);
        } else {
            chart.getStyler().setDefaultSeriesRenderStyle(
                    CategorySeries.CategorySeriesRenderStyle.Bar
            );
        }
        chart.addSeries(
                candidate.valueLabel().isBlank() ? "值" : candidate.valueLabel(),
                candidate.categories(),
                candidate.values()
        );
        try {
            return new RenderedChart(
                    candidate,
                    safeFileName(candidate.id() + "-" + candidate.title()) + ".png",
                    BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成分析图表：" + candidate.title(), exception);
        }
    }

    private static String safeFileName(String value) {
        String normalized = value == null ? "chart" : value.trim();
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "_");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    record RenderedChart(
            TabularAnalysisDataset.ChartCandidate candidate,
            String fileName,
            byte[] content
    ) {
        RenderedChart {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
