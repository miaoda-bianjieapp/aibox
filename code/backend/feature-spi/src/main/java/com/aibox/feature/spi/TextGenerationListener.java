package com.aibox.feature.spi;

@FunctionalInterface
public interface TextGenerationListener {

    boolean onDelta(String delta);
}
