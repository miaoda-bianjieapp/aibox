package com.aibox.api;

import com.aibox.platform.catalog.FeatureCatalogService;
import com.aibox.platform.prompt.PromptOptimizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final FeatureCatalogService catalogService;
    private final PromptOptimizationService promptOptimizationService;

    public CatalogController(
            FeatureCatalogService catalogService,
            PromptOptimizationService promptOptimizationService
    ) {
        this.catalogService = catalogService;
        this.promptOptimizationService = promptOptimizationService;
    }

    @GetMapping("/workspaces")
    public List<FeatureCatalogService.WorkspaceView> listWorkspaces() {
        return catalogService.listVisibleWorkspaces();
    }

    @GetMapping("/features/{featureCode}")
    public FeatureCatalogService.FeatureDetailView getFeature(@PathVariable String featureCode) {
        return catalogService.getVisibleFeature(featureCode);
    }

    @PostMapping("/features/{featureCode}/prompt-optimization")
    public PromptOptimizationService.Result optimizePrompt(
            @PathVariable String featureCode,
            @RequestBody PromptOptimizationRequest request
    ) {
        return promptOptimizationService.optimize(
                featureCode,
                new PromptOptimizationService.Command(
                        request.field(),
                        request.currentText(),
                        request.parameters(),
                        request.assetIdsByField()
                )
        );
    }

    public record PromptOptimizationRequest(
            String field,
            String currentText,
            Map<String, Object> parameters,
            Map<String, List<UUID>> assetIdsByField
    ) {
    }
}

