package com.aibox.platform.common;

public final class NotFoundException extends PlatformException {

    public NotFoundException(String resource, Object id) {
        super("RESOURCE_NOT_FOUND", resource + " not found: " + id);
    }
}

