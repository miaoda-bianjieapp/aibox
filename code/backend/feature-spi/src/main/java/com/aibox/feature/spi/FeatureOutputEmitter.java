package com.aibox.feature.spi;

public interface FeatureOutputEmitter {

    void start(String channel, String format);

    void appendText(String channel, String delta);

    void replaceText(String channel, String content);

    void complete(String channel);

    void completeAll();

    boolean isCancelled();

    static FeatureOutputEmitter noop() {
        return new FeatureOutputEmitter() {
            @Override
            public void start(String channel, String format) {
            }

            @Override
            public void appendText(String channel, String delta) {
            }

            @Override
            public void replaceText(String channel, String content) {
            }

            @Override
            public void complete(String channel) {
            }

            @Override
            public void completeAll() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }
}
