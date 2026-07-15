package com.aibox.platform.execution;

import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureHandler;
import com.aibox.feature.spi.ModelGateway;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class RunExecutionCoordinator {

    private final RunExecutionStateService stateService;
    private final FeatureRegistry featureRegistry;
    private final ModelGateway modelGateway;

    public RunExecutionCoordinator(
            RunExecutionStateService stateService,
            FeatureRegistry featureRegistry,
            ModelGateway modelGateway
    ) {
        this.stateService = stateService;
        this.featureRegistry = featureRegistry;
        this.modelGateway = modelGateway;
    }

    public boolean execute(UUID runId) {
        Optional<FeatureExecutionContext> contextOptional = stateService.start(runId);
        if (contextOptional.isEmpty()) {
            return false;
        }

        FeatureExecutionContext context = contextOptional.get();
        FeatureHandler handler = featureRegistry.require(context.featureCode());
        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, modelGateway);
        stateService.succeed(runId, result.artifacts());
        return true;
    }
}

