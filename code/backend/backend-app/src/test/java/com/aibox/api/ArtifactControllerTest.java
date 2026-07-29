package com.aibox.api;

import com.aibox.platform.artifact.ArtifactExportService;
import com.aibox.platform.artifact.ArtifactService;
import com.aibox.platform.asset.AssetService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArtifactControllerTest {

    @Test
    void exportsThroughTheExistingArtifactController() throws Exception {
        UUID artifactId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        ArtifactExportService exportService = mock(ArtifactExportService.class);
        when(exportService.export(artifactId, "excel")).thenReturn(
                new AssetService.AssetView(
                        assetId,
                        "多文档对比报告.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        3,
                        "sha256",
                        Instant.parse("2026-07-29T03:30:00Z")
                )
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new ArtifactController(
                        mock(ArtifactService.class),
                        exportService
                )
        ).build();

        mockMvc.perform(post(
                        "/api/v1/artifacts/{artifactId}/exports",
                        artifactId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"excel"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assetId.toString()))
                .andExpect(jsonPath("$.name").value("多文档对比报告.xlsx"));
    }
}
