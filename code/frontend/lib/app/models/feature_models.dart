enum WorkspaceGroup { create, process, media }

WorkspaceGroup? workspaceGroupFromJson(String value) => switch (value) {
      'create' => WorkspaceGroup.create,
      'process' => WorkspaceGroup.process,
      'media' => WorkspaceGroup.media,
      _ => null,
    };

class FeatureEntry {
  const FeatureEntry({
    required this.id,
    required this.title,
    required this.description,
    required this.version,
    required this.resultType,
    required this.rendererKey,
    required this.executionMode,
  });

  factory FeatureEntry.fromJson(Map<String, dynamic> json) => FeatureEntry(
        id: _string(json, 'code'),
        title: _string(json, 'displayName'),
        description: _string(json, 'description'),
        version: _integer(json, 'version'),
        resultType: _string(json, 'resultType'),
        rendererKey: _string(json, 'rendererKey'),
        executionMode: _string(json, 'executionMode'),
      );

  final String id;
  final String title;
  final String description;
  final int version;
  final String resultType;
  final String rendererKey;
  final String executionMode;
}

class FeatureDetail extends FeatureEntry {
  const FeatureDetail({
    required super.id,
    required super.title,
    required super.description,
    required super.version,
    required super.resultType,
    required super.rendererKey,
    required super.executionMode,
    required this.inputSchema,
    required this.uiSchema,
    required this.outputSchema,
    required this.config,
    required this.modelPolicies,
  });

  factory FeatureDetail.fromJson(Map<String, dynamic> json) => FeatureDetail(
        id: _string(json, 'code'),
        title: _string(json, 'displayName'),
        description: _string(json, 'description'),
        version: _integer(json, 'version'),
        resultType: _string(json, 'resultType'),
        rendererKey: _string(json, 'rendererKey'),
        executionMode: _string(json, 'executionMode'),
        inputSchema: _map(json['inputSchema']),
        uiSchema: _map(json['uiSchema']),
        outputSchema: _map(json['outputSchema']),
        config: _map(json['config']),
        modelPolicies: _modelPolicies(json),
      );

  final Map<String, dynamic> inputSchema;
  final Map<String, dynamic> uiSchema;
  final Map<String, dynamic> outputSchema;
  final Map<String, dynamic> config;
  final List<ModelPolicy> modelPolicies;

  ModelPolicy? get modelPolicy => modelPolicies.firstOrNull;

  Map<String, dynamic> get properties => _map(inputSchema['properties']);
  Set<String> get requiredFields =>
      _stringList(inputSchema['required']).toSet();
  List<String> get fieldOrder {
    final configured = _stringList(uiSchema['order']);
    return configured.isEmpty ? properties.keys.toList() : configured;
  }

  String? widgetFor(String field) =>
      _map(uiSchema['widgets'])[field]?.toString();

  bool isFieldVisible(String field, Map<String, Object?> values) {
    final rule = _map(_map(uiSchema['visibility'])[field]);
    if (rule.isEmpty) return true;
    return _matchesVisibilityRule(rule, values);
  }

  bool _matchesVisibilityRule(
    Map<String, dynamic> rule,
    Map<String, Object?> values,
  ) {
    final all = rule['all'];
    if (all is List) {
      return all
          .map(_map)
          .every((condition) => _matchesVisibilityRule(condition, values));
    }
    final any = rule['any'];
    if (any is List) {
      return any
          .map(_map)
          .any((condition) => _matchesVisibilityRule(condition, values));
    }
    final dependency = rule['field']?.toString();
    if (dependency == null || dependency.isEmpty) return true;
    return values[dependency]?.toString() == rule['equals']?.toString();
  }

  String optionLabel(String field, String value) {
    final labels = _map(_map(uiSchema['enumLabels'])[field]);
    return labels[value]?.toString() ?? value;
  }

  Map<String, dynamic> fieldOptions(String field) =>
      _map(_map(uiSchema['fieldOptions'])[field]);

  Map<String, dynamic> fieldHelp(
    String field,
    Map<String, Object?> values,
  ) {
    final help = _map(_map(uiSchema['fieldHelp'])[field]);
    if (help.isEmpty) return const {};
    final when = _map(help['when']);
    if (when.isNotEmpty && !_matchesVisibilityRule(when, values)) {
      return const {};
    }
    return help;
  }

  String? get feeNotice => uiSchema['feeNotice']?.toString();

  String get submitLabel => uiSchema['submitLabel']?.toString() ?? '开始执行';

  String get revisionSubmitLabel =>
      uiSchema['revisionSubmitLabel']?.toString() ?? '生成新版本';

  String? exampleFor(String field) {
    final value = _map(uiSchema['examples'])[field]?.toString().trim();
    return value == null || value.isEmpty ? null : value;
  }

  bool get showResetAction => _map(uiSchema['actions'])['showReset'] == true;

  String? get revisionSourceField {
    final value = config['revisionSourceField']?.toString().trim();
    return value == null || value.isEmpty ? null : value;
  }

  String? get revisionSourceAssetField {
    final value = config['revisionSourceAssetField']?.toString().trim();
    return value == null || value.isEmpty ? null : value;
  }

  Set<String> get revisionResetFields =>
      _stringList(config['revisionResetFields']).toSet();
}

class ModelPolicy {
  const ModelPolicy({
    required this.capability,
    required this.defaultModelCode,
    required this.allowUserSelection,
    required this.options,
  });

  factory ModelPolicy.fromJson(Map<String, dynamic> json) => ModelPolicy(
        capability: _string(json, 'capability'),
        defaultModelCode: _string(json, 'defaultModelCode'),
        allowUserSelection: json['allowUserSelection'] == true,
        options: _mapList(json['options']).map(ModelOption.fromJson).toList(),
      );

  final String capability;
  final String defaultModelCode;
  final bool allowUserSelection;
  final List<ModelOption> options;

  bool get shouldShowSelector => allowUserSelection && options.isNotEmpty;
}

class ModelOption {
  const ModelOption({
    required this.code,
    required this.displayName,
    required this.description,
    required this.isDefault,
    required this.sourceType,
    required this.sourceName,
  });

  factory ModelOption.fromJson(Map<String, dynamic> json) => ModelOption(
        code: _string(json, 'code'),
        displayName: _string(json, 'displayName'),
        description: _string(json, 'description'),
        isDefault: json['isDefault'] == true,
        sourceType: _string(json, 'sourceType'),
        sourceName: _string(json, 'sourceName'),
      );

  final String code;
  final String displayName;
  final String description;
  final bool isDefault;
  final String sourceType;
  final String sourceName;

  String get sourceLabel => sourceType == 'RELAY' ? '中转' : '官方';
}

class WorkspaceDefinition {
  const WorkspaceDefinition({
    required this.id,
    required this.title,
    required this.description,
    required this.iconKey,
    required this.groups,
    required this.searchTerms,
    required this.entries,
  });

  factory WorkspaceDefinition.fromJson(Map<String, dynamic> json) {
    return WorkspaceDefinition(
      id: _string(json, 'code'),
      title: _string(json, 'displayName'),
      description: _string(json, 'description'),
      iconKey: _string(json, 'iconKey'),
      groups: _stringList(json['groups'])
          .map(workspaceGroupFromJson)
          .whereType<WorkspaceGroup>()
          .toSet(),
      searchTerms: _stringList(json['searchTerms']),
      entries: _mapList(json['features']).map(FeatureEntry.fromJson).toList(),
    );
  }

  final String id;
  final String title;
  final String description;
  final String iconKey;
  final Set<WorkspaceGroup> groups;
  final List<String> searchTerms;
  final List<FeatureEntry> entries;

  bool matches(String query, WorkspaceGroup? group) {
    final normalized = query.trim().toLowerCase();
    if (group != null && !groups.contains(group)) return false;
    if (normalized.isEmpty) return true;
    return <String>[
      title,
      description,
      ...searchTerms,
      ...entries.map((entry) => entry.title),
    ].join(' ').toLowerCase().contains(normalized);
  }
}

class ProjectView {
  const ProjectView({
    required this.id,
    required this.name,
    required this.description,
    required this.updatedAt,
  });

  factory ProjectView.fromJson(Map<String, dynamic> json) => ProjectView(
        id: _string(json, 'id'),
        name: _string(json, 'name'),
        description: _string(json, 'description'),
        updatedAt: _date(json['updatedAt']),
      );

  final String id;
  final String name;
  final String description;
  final DateTime updatedAt;
}

class TaskView {
  const TaskView({
    required this.id,
    required this.projectId,
    required this.featureCode,
    required this.title,
    required this.status,
    required this.currentArtifactId,
    required this.createdAt,
    required this.updatedAt,
  });

  factory TaskView.fromJson(Map<String, dynamic> json) => TaskView(
        id: _string(json, 'id'),
        projectId: json['projectId']?.toString(),
        featureCode: _string(json, 'featureCode'),
        title: _string(json, 'title'),
        status: _string(json, 'status'),
        currentArtifactId: json['currentArtifactId']?.toString(),
        createdAt: _date(json['createdAt']),
        updatedAt: _date(json['updatedAt']),
      );

  final String id;
  final String? projectId;
  final String featureCode;
  final String title;
  final String status;
  final String? currentArtifactId;
  final DateTime createdAt;
  final DateTime updatedAt;
}

class RunView {
  const RunView({
    required this.id,
    required this.runNumber,
    required this.status,
    required this.parameters,
    required this.inputAssetIds,
    required this.baseArtifactId,
    required this.selectedModelCode,
    required this.selectedModels,
    required this.errorCode,
    required this.errorMessage,
    required this.createdAt,
  });

  factory RunView.fromJson(Map<String, dynamic> json) => RunView(
        id: _string(json, 'id'),
        runNumber: _integer(json, 'runNumber'),
        status: _string(json, 'status'),
        parameters: _map(json['parameters']),
        inputAssetIds: _stringList(json['inputAssetIds']),
        baseArtifactId: json['baseArtifactId']?.toString(),
        selectedModelCode: json['selectedModelCode']?.toString(),
        selectedModels: _stringMap(json['selectedModels']),
        errorCode: json['errorCode']?.toString(),
        errorMessage: json['errorMessage']?.toString(),
        createdAt: _date(json['createdAt']),
      );

  final String id;
  final int runNumber;
  final String status;
  final Map<String, dynamic> parameters;
  final List<String> inputAssetIds;
  final String? baseArtifactId;
  final String? selectedModelCode;
  final Map<String, String> selectedModels;
  final String? errorCode;
  final String? errorMessage;
  final DateTime createdAt;
}

class ArtifactView {
  const ArtifactView({
    required this.id,
    required this.taskId,
    required this.runId,
    required this.parentArtifactId,
    required this.versionNumber,
    required this.kind,
    required this.title,
    required this.mimeType,
    required this.content,
    required this.metadata,
    required this.createdAt,
  });

  factory ArtifactView.fromJson(Map<String, dynamic> json) => ArtifactView(
        id: _string(json, 'id'),
        taskId: _string(json, 'taskId'),
        runId: _string(json, 'runId'),
        parentArtifactId: json['parentArtifactId']?.toString(),
        versionNumber: _integer(json, 'versionNumber'),
        kind: _string(json, 'kind'),
        title: _string(json, 'title'),
        mimeType: _string(json, 'mimeType'),
        content: _map(json['content']),
        metadata: _map(json['metadata']),
        createdAt: _date(json['createdAt']),
      );

  final String id;
  final String taskId;
  final String runId;
  final String? parentArtifactId;
  final int versionNumber;
  final String kind;
  final String title;
  final String mimeType;
  final Map<String, dynamic> content;
  final Map<String, dynamic> metadata;
  final DateTime createdAt;
}

class TaskDetail {
  const TaskDetail(
      {required this.task, required this.runs, required this.artifacts});

  factory TaskDetail.fromJson(Map<String, dynamic> json) => TaskDetail(
        task: TaskView.fromJson(_map(json['task'])),
        runs: _mapList(json['runs']).map(RunView.fromJson).toList(),
        artifacts:
            _mapList(json['artifacts']).map(ArtifactView.fromJson).toList(),
      );

  final TaskView task;
  final List<RunView> runs;
  final List<ArtifactView> artifacts;
}

class AssetView {
  const AssetView({
    required this.id,
    required this.name,
    required this.mediaType,
    required this.sizeBytes,
    required this.createdAt,
  });

  factory AssetView.fromJson(Map<String, dynamic> json) => AssetView(
        id: _string(json, 'id'),
        name: _string(json, 'name'),
        mediaType: _string(json, 'mediaType'),
        sizeBytes: _integer(json, 'sizeBytes'),
        createdAt: _date(json['createdAt']),
      );

  final String id;
  final String name;
  final String mediaType;
  final int sizeBytes;
  final DateTime createdAt;
}

class AccountSummary {
  const AccountSummary({
    required this.accountMode,
    required this.displayName,
    required this.projectCount,
    required this.taskCount,
    required this.runCount,
    required this.artifactCount,
    required this.assetCount,
    required this.assetBytes,
  });

  factory AccountSummary.fromJson(Map<String, dynamic> json) => AccountSummary(
        accountMode: _string(json, 'accountMode'),
        displayName: _string(json, 'displayName'),
        projectCount: _integer(json, 'projectCount'),
        taskCount: _integer(json, 'taskCount'),
        runCount: _integer(json, 'runCount'),
        artifactCount: _integer(json, 'artifactCount'),
        assetCount: _integer(json, 'assetCount'),
        assetBytes: _integer(json, 'assetBytes'),
      );

  final String accountMode;
  final String displayName;
  final int projectCount;
  final int taskCount;
  final int runCount;
  final int artifactCount;
  final int assetCount;
  final int assetBytes;
}

class TaskLaunchRequest {
  const TaskLaunchRequest({
    required this.workspace,
    required this.entry,
    this.initialParameters = const {},
    this.initialAssetIds = const [],
    this.existingTaskId,
    this.baseArtifactId,
    this.baseVersion,
    this.taskTitle,
    this.projectId,
    this.initialModelCode,
    this.initialModels = const {},
    this.baseArtifactText,
    this.baseArtifactAssetIds = const [],
  });

  final WorkspaceDefinition workspace;
  final FeatureEntry entry;
  final Map<String, Object?> initialParameters;
  final List<String> initialAssetIds;
  final String? existingTaskId;
  final String? baseArtifactId;
  final int? baseVersion;
  final String? taskTitle;
  final String? projectId;
  final String? initialModelCode;
  final Map<String, String> initialModels;
  final String? baseArtifactText;
  final List<String> baseArtifactAssetIds;

  bool get isRevision => existingTaskId != null && baseArtifactId != null;
}

Map<String, dynamic> _map(Object? value) =>
    value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};

Map<String, String> _stringMap(Object? value) => value is Map
    ? Map<String, String>.fromEntries(value.entries
        .where((entry) => entry.key != null && entry.value != null)
        .map((entry) => MapEntry(entry.key.toString(), entry.value.toString())))
    : <String, String>{};

List<ModelPolicy> _modelPolicies(Map<String, dynamic> json) {
  final policies =
      _mapList(json['modelPolicies']).map(ModelPolicy.fromJson).toList();
  if (policies.isEmpty && json['modelPolicy'] is Map) {
    policies.add(ModelPolicy.fromJson(_map(json['modelPolicy'])));
  }
  return policies;
}

List<Map<String, dynamic>> _mapList(Object? value) => value is List
    ? value
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList()
    : <Map<String, dynamic>>[];

List<String> _stringList(Object? value) =>
    value is List ? value.map((item) => item.toString()).toList() : <String>[];

String _string(Map<String, dynamic> json, String key) =>
    json[key]?.toString() ?? '';

int _integer(Map<String, dynamic> json, String key) => json[key] is num
    ? (json[key] as num).toInt()
    : int.tryParse('${json[key]}') ?? 0;

DateTime _date(Object? value) =>
    DateTime.tryParse(value?.toString() ?? '')?.toLocal() ??
    DateTime.fromMillisecondsSinceEpoch(0);
