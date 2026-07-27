package com.aibox.api;

import com.aibox.feature.spi.ModelProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @Test
    void preservesProviderAuthenticationFailureForSynchronousApis() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/provider-failure"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVIDER_HTTP_401"))
                .andExpect(jsonPath("$.message").value("Model provider authentication failed"));
    }

    @Test
    void ignoresClientDisconnectsDuringResponseStreaming() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/client-disconnect"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @RestController
    private static final class FailingController {

        @GetMapping("/provider-failure")
        void fail() {
            throw new ModelProviderException(
                    "PROVIDER_HTTP_401",
                    "Model provider authentication failed",
                    false
            );
        }

        @GetMapping("/client-disconnect")
        void disconnect() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("client disconnected");
        }
    }
}
