package com.aibox.api;

import com.aibox.platform.execution.RunOutputService;
import com.aibox.platform.task.TaskApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RunControllerSseHeadersTest {

    @Test
    void disablesCachingAndProxyBufferingForSseResponses() {
        RunController controller = new RunController(
                mock(TaskApplicationService.class),
                mock(SseRunEventPublisher.class),
                mock(RunOutputService.class)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.events(UUID.randomUUID(), null, response);

        assertEquals("no-cache", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
    }
}
