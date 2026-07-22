package com.aibox.features.support;

import com.aibox.feature.spi.FeatureOutputEmitter;

public final class RecordingFeatureOutputEmitter implements FeatureOutputEmitter {

    private String channel;
    private String format;
    private final StringBuilder content = new StringBuilder();
    private boolean completed;
    private boolean cancelled;

    @Override
    public void start(String channel, String format) {
        this.channel = channel;
        this.format = format;
        content.setLength(0);
        completed = false;
    }

    @Override
    public void appendText(String channel, String delta) {
        requireChannel(channel);
        content.append(delta);
    }

    @Override
    public void replaceText(String channel, String content) {
        requireChannel(channel);
        this.content.setLength(0);
        this.content.append(content);
    }

    @Override
    public void complete(String channel) {
        requireChannel(channel);
        completed = true;
    }

    @Override
    public void completeAll() {
        completed = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    public String channel() {
        return channel;
    }

    public String format() {
        return format;
    }

    public String content() {
        return content.toString();
    }

    public boolean completed() {
        return completed;
    }

    public void cancel() {
        cancelled = true;
    }

    private void requireChannel(String value) {
        if (!value.equals(channel)) {
            throw new IllegalArgumentException("Unexpected output channel: " + value);
        }
    }
}
