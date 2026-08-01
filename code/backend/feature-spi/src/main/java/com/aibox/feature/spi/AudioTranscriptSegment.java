package com.aibox.feature.spi;

public record AudioTranscriptSegment(
        long startMs,
        long endMs,
        String text,
        String speaker,
        Double confidence
) {
    public AudioTranscriptSegment {
        if (startMs < 0) {
            throw new IllegalArgumentException("startMs must not be negative");
        }
        if (endMs < startMs) {
            throw new IllegalArgumentException("endMs must not precede startMs");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("segment text is required");
        }
        text = text.trim();
        speaker = speaker == null || speaker.isBlank() ? null : speaker.trim();
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
