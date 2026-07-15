package com.aibox.api;

import com.aibox.platform.catalog.FeatureCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final FeatureCatalogService catalogService;

    public CatalogController(FeatureCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/workspaces")
    public List<FeatureCatalogService.WorkspaceView> listWorkspaces() {
        return catalogService.listVisibleWorkspaces();
    }

    @GetMapping("/features/{featureCode}")
    public FeatureCatalogService.FeatureDetailView getFeature(@PathVariable String featureCode) {
        return catalogService.getVisibleFeature(featureCode);
    }
}

