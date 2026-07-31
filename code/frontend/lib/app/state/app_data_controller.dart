import 'package:flutter/foundation.dart';

import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../network/api_exception.dart';
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
      {List<String> mimeTypes = const ['*/*'],
      List<String> allowedExtensions = const [],
      int? maxSizeBytes}) async {
    final file = await NativeFilePicker.pick(mimeTypes: mimeTypes);
    if (file == null) return null;
    try {
      _validatePickedFile(
        file,
        mimeTypes: mimeTypes,
        allowedExtensions: allowedExtensions,
        maxSizeBytes: maxSizeBytes,
      );
      final asset = await api.uploadAsset(file);
      await refresh();
      return asset;
    } finally {
      await file.cleanup();
    }
  }

  Future<List<AssetView>> pickImagesAndUpload({
    int maxFiles = 1,
    List<String> mimeTypes = const ['image/*'],
    List<String> allowedExtensions = const [],
    int? maxSizeBytes,
    int? maxTotalSizeBytes,
  }) async {
    final files = await NativeFilePicker.pickImages(maxFiles: maxFiles);
    if (files.isEmpty) return const [];
    final uploaded = <AssetView>[];
    try {
      for (final file in files) {
        _validatePickedFile(
          file,
          mimeTypes: mimeTypes,
          allowedExtensions: allowedExtensions,
          maxSizeBytes: maxSizeBytes,
        );
      }
      final selectedBytes =
          files.fold<int>(0, (sum, file) => sum + file.sizeBytes);
      if (maxTotalSizeBytes != null && selectedBytes > maxTotalSizeBytes) {
        throw ApiException(
          '所选图片合计不能超过 ${_formatMegabytes(maxTotalSizeBytes)} MB',
        );
      }
      for (final file in files) {
        uploaded.add(await api.uploadAsset(file));
      }
      await refresh();
      return List.unmodifiable(uploaded);
    } catch (_) {
      if (uploaded.isNotEmpty) {
        try {
          await api.deleteAssets(uploaded.map((asset) => asset.id));
        } catch (_) {
          // A refresh below exposes any asset that could not be rolled back.
        }
        await refresh();
      }
      rethrow;
    } finally {
      await Future.wait(files.map((file) => file.cleanup()));
    }
  }

  void _validatePickedFile(
    PickedLocalFile file, {
    required List<String> mimeTypes,
    required List<String> allowedExtensions,
    required int? maxSizeBytes,
  }) {
    if (!_matchesMimeType(file.mediaType, mimeTypes)) {
      throw const ApiException('文件类型不符合当前功能要求');
    }
    final normalizedName = file.name.toLowerCase();
    if (allowedExtensions.isNotEmpty &&
        !allowedExtensions
            .map((value) => value.toLowerCase())
            .any(normalizedName.endsWith)) {
      throw ApiException('仅支持 ${allowedExtensions.join('、')} 格式');
    }
    if (maxSizeBytes != null && file.sizeBytes > maxSizeBytes) {
      throw ApiException(
        '单个文件不能超过 ${_formatMegabytes(maxSizeBytes)} MB',
      );
    }
  }

  Future<void> deleteAsset(String assetId) async {
    await api.deleteAsset(assetId);
    await refresh();
  }

  Future<void> deleteAssets(Iterable<String> assetIds) async {
    await api.deleteAssets(assetIds);
    await refresh();
  }
}

bool _matchesMimeType(String mediaType, List<String> accepted) {
  if (accepted.isEmpty || accepted.contains('*/*')) return true;
  return accepted.any((value) {
    if (value == mediaType) return true;
    if (value.endsWith('/*')) {
      return mediaType.startsWith(value.substring(0, value.length - 1));
    }
    return false;
  });
}

String _formatMegabytes(int bytes) {
  final megabytes = bytes / (1024 * 1024);
  return megabytes == megabytes.roundToDouble()
      ? megabytes.toInt().toString()
      : megabytes.toStringAsFixed(1);
}
