package com.aibox.platform.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class DevelopmentActorContextProvider implements ActorContextProvider {

    public static final UUID DEVELOPMENT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DEVELOPMENT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final ActorContext CONTEXT = new ActorContext(DEVELOPMENT_TENANT_ID, DEVELOPMENT_USER_ID);

    @Override
    public ActorContext current() {
        return CONTEXT;
    }
}

