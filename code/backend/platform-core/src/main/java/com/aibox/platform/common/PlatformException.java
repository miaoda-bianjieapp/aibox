package com.aibox.platform.common;

public class PlatformException extends RuntimeException {

    private final String code;

    public PlatformException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

