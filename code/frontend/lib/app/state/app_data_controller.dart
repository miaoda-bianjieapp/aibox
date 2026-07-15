import 'package:flutter/foundation.dart';

import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../network/native_file_picker.dart';

class AppDataController extends ChangeNotifier {
  AppDataController({BackendApi? api}) : api = api ?? BackendApi.instance;

  final BackendApi api;

  List<WorkspaceDefinition> workspaces = const [];
  List<TaskView> tasks = const [];
  List<ProjectView> projects = const [];
  List<AssetView> assets = const [];
  AccountSummary? account;
  bool loading = false;
  String? error;

  Future<void> refresh() async {
    if (loading) return;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final values = await Future.wait<dynamic>([
        api.listWorkspaces(),
        api.listTasks(),
        api.listProjects(),
        api.listAssets(),
        api.getAccountSummary(),
      ]);
      workspaces = values[0] as List<WorkspaceDefinition>;
      tasks = values[1] as List<TaskView>;
      projects = values[2] as List<ProjectView>;
      assets = values[3] as List<AssetView>;
      account = values[4] as AccountSummary;
    } catch (exception) {
      error = exception.toString().replaceFirst('ApiException: ', '');
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  WorkspaceDefinition? workspaceForFeature(String featureCode) {
    for (final workspace in workspaces) {
      if (workspace.entries.any((entry) => entry.id == featureCode)) {
        return workspace;
      }
    }
    return null;
  }

  FeatureEntry? featureByCode(String featureCode) {
    final workspace = workspaceForFeature(featureCode);
    if (workspace == null) return null;
    return workspace.entries
        .where((entry) => entry.id == featureCode)
        .firstOrNull;
  }

  Future<ProjectView> createProject(String name, String description) async {
    final project = await api.createProject(name, description);
    await refresh();
    return project;
  }

  Future<AssetView?> pickAndUpload(
      {List<String> mimeTypes = const ['*/*']}) async {
    final file = await NativeFilePicker.pick(mimeTypes: mimeTypes);
    if (file == null) return null;
    final asset = await api.uploadAsset(file);
    await refresh();
    return asset;
  }

  Future<void> deleteAsset(String assetId) async {
    await api.deleteAsset(assetId);
    await refresh();
  }
}
