import '../models/feature_models.dart';

class TaskExecutionResult {
  const TaskExecutionResult({
    required this.taskId,
    required this.runId,
    required this.feature,
    required this.artifact,
  });

  final String taskId;
  final String runId;
  final FeatureDetail feature;
  final ArtifactView artifact;
}
