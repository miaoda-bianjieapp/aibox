import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import '../models/feature_models.dart';
import '../models/run_output_models.dart';
import 'api_config.dart';
import 'api_exception.dart';
import 'native_file_picker.dart';
import 'sse_event_parser.dart';
import 'task_execution_result.dart';

class AssetDownloadCancelledException implements Exception {
  const AssetDownloadCancelledException();

  @override
  String toString() => 'Asset download cancelled';
}

class BackendApi {
  BackendApi._();

  static final instance = BackendApi._();
  static const runPollingTimeout = Duration(minutes: 20);
  static const _runPollingInterval = Duration(milliseconds: 500);

  final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 8);

  static String runFailureMessage(String? code, String? serverMessage) {
    if (code == 'PROVIDER_VIDEO_SUBMISSION_UNKNOWN') {
      return '视频提交结果未知，系统已停止自动重提以避免重复计费。请确认供应商状态后再手动重新生成。';
    }
    if (code == 'PROVIDER_HTTP_524') {
      return '模型服务处理超时，请重试；如持续失败，请切换其他模型';
    }
    if (code == 'MODEL_REFERENCE_IMAGES_NOT_SUPPORTED') {
      return '当前模型不支持参考图，请移除参考图或切换其他模型';
    }
    if (code == 'MODEL_REFERENCE_IMAGE_LIMIT_EXCEEDED') {
      return '参考图数量超过当前模型支持的上限';
    }
    if (code == 'MODEL_FRAME_INPUT_NOT_SUPPORTED') {
      return '当前视频模型不支持所选首帧/尾帧组合，请移除尾帧或切换模型';
    }
    if (code == 'MODEL_LAST_FRAME_NOT_SUPPORTED') {
      return '当前视频模型仅支持首帧，不支持尾帧';
    }
    return taskFailureMessage(code: code, message: serverMessage);
  }

  Future<List<WorkspaceDefinition>> listWorkspaces() async {
    return _asMapList(await _request('GET', '/catalog/workspaces'))
        .map(WorkspaceDefinition.fromJson)
        .toList();
  }

  Future<FeatureDetail> getFeature(String featureCode) async {
    return FeatureDetail.fromJson(
      _asMap(await _request('GET', '/catalog/features/$featureCode')),
    );
  }

  Future<String> optimizePrompt({
    required String featureCode,
    required String field,
    required String currentText,
    required Map<String, Object?> parameters,
    required Map<String, List<String>> assetIdsByField,
  }) async {
    final result = _asMap(await _request(
      'POST',
      '/catalog/features/$featureCode/prompt-optimization',
      body: {
        'field': field,
        'currentText': currentText,
        'parameters': parameters,
        'assetIdsByField': assetIdsByField,
      },
      responseTimeout: const Duration(seconds: 60),
    ));
    return _requiredString(result, 'optimizedText');
  }

  Future<List<TaskView>> listTasks({
    String? workspaceCode,
    String? keyword,
  }) async {
    return _asMapList(
      await _request('GET', taskListPath(workspaceCode, keyword)),
    ).map(TaskView.fromJson).toList();
  }

  static String taskListPath(String? workspaceCode, String? keyword) {
    final parameters = <String, String>{};
    final normalizedWorkspace = workspaceCode?.trim() ?? '';
    final normalizedKeyword = keyword?.trim() ?? '';
    if (normalizedWorkspace.isNotEmpty) {
      parameters['workspaceCode'] = normalizedWorkspace;
    }
    if (normalizedKeyword.isNotEmpty) {
      parameters['keyword'] = normalizedKeyword;
    }
    if (parameters.isEmpty) return '/tasks';
    return Uri(path: '/tasks', queryParameters: parameters).toString();
  }

  Future<TaskDetail> getTask(String taskId) async {
    return TaskDetail.fromJson(_asMap(await _request('GET', '/tasks/$taskId')));
  }

  Future<TaskView> createTask({
    required String featureCode,
    required String title,
    String? projectId,
  }) async {
    return TaskView.fromJson(_asMap(await _request(
      'POST',
      '/tasks',
      body: {
        'featureCode': featureCode,
        'title': title,
        'projectId': projectId,
      },
    )));
  }

  Future<List<TaskAssetView>> addTaskAssets(
    String taskId,
    Iterable<String> assetIds, {
    String role = 'DOCUMENT_SOURCE',
  }) async {
    return _asMapList(await _request(
      'POST',
      '/tasks/$taskId/assets',
      body: {
        'assetIds': assetIds.toList(),
        'role': role,
      },
    ))
        .map(TaskAssetView.fromJson)
        .toList();
  }

  Future<List<TaskAssetView>> removeTaskAsset(
    String taskId,
    String assetId, {
    String role = 'DOCUMENT_SOURCE',
  }) async {
    final path = Uri(
      path: '/tasks/$taskId/assets/$assetId',
      queryParameters: {'role': role},
    ).toString();
    return _asMapList(await _request('DELETE', path))
        .map(TaskAssetView.fromJson)
        .toList();
  }

  Future<List<ProjectView>> listProjects() async {
    return _asMapList(await _request('GET', '/projects'))
        .map(ProjectView.fromJson)
        .toList();
  }

  Future<ProjectView> createProject(String name, String description) async {
    return ProjectView.fromJson(_asMap(await _request(
      'POST',
      '/projects',
      body: {'name': name, 'description': description},
    )));
  }

  Future<List<AssetView>> listAssets() async {
    return _asMapList(await _request('GET', '/assets'))
        .map(AssetView.fromJson)
        .toList();
  }

  Future<List<CreativeAssetView>> listCreativeAssets({
    String? scope,
    String? projectId,
    String? assetType,
  }) async {
    final query = <String, String>{
      if (scope != null && scope.trim().isNotEmpty) 'scope': scope,
      if (projectId != null && projectId.trim().isNotEmpty)
        'projectId': projectId,
      if (assetType != null && assetType.trim().isNotEmpty)
        'assetType': assetType,
    };
    final path = Uri(
      path: '/assets/creative',
      queryParameters: query.isEmpty ? null : query,
    ).toString();
    return _asMapList(await _request('GET', path))
        .map(CreativeAssetView.fromJson)
        .toList();
  }

  Future<CreativeAssetView> createCreativeAsset({
    required String scope,
    required String assetType,
    required String name,
    required String description,
    String? personality,
    String? projectId,
  }) async {
    return CreativeAssetView.fromJson(_asMap(await _request(
      'POST',
      '/assets/creative',
      body: {
        'scope': scope,
        'assetType': assetType,
        'name': name,
        'description': description,
        'personality': personality ?? '',
        'projectId': projectId,
      },
    )));
  }

  Future<CreativeAssetView> updateCreativeAsset(
    String creativeAssetId, {
    String? scope,
    String? assetType,
    String? name,
    String? description,
    String? personality,
    String? projectId,
    String? currentPrimaryAssetId,
    String? currentThreeViewAssetId,
    String? approvedPrimaryAssetId,
    String? approvedThreeViewAssetId,
    bool clearCurrentThreeViewAsset = false,
  }) async {
    return CreativeAssetView.fromJson(_asMap(await _request(
      'PATCH',
      '/assets/creative/$creativeAssetId',
      body: {
        if (scope != null) 'scope': scope,
        if (assetType != null) 'assetType': assetType,
        if (name != null) 'name': name,
        if (description != null) 'description': description,
        if (personality != null) 'personality': personality,
        if (projectId != null) 'projectId': projectId,
        if (currentPrimaryAssetId != null)
          'currentPrimaryAssetId': currentPrimaryAssetId,
        if (currentThreeViewAssetId != null)
          'currentThreeViewAssetId': currentThreeViewAssetId,
        if (approvedPrimaryAssetId != null)
          'approvedPrimaryAssetId': approvedPrimaryAssetId,
        if (approvedThreeViewAssetId != null)
          'approvedThreeViewAssetId': approvedThreeViewAssetId,
        if (clearCurrentThreeViewAsset) 'clearCurrentThreeViewAsset': true,
      },
    )));
  }

  Future<void> deleteCreativeAsset(String creativeAssetId) async {
    await _request('DELETE', '/assets/creative/$creativeAssetId');
  }

  Future<AssetPage> listAssetLibrary({
    required String libraryType,
    required String category,
    String query = '',
    String? cursor,
    int pageSize = 20,
  }) async {
    final uri = Uri(
      path: '/assets/library',
      queryParameters: {
        'libraryType': libraryType,
        'category': category,
        'query': query,
        'pageSize': '$pageSize',
        if (cursor != null) 'cursor': cursor,
      },
    );
    return AssetPage.fromJson(
      _asMap(await _request('GET', uri.toString())),
    );
  }

  Future<AssetView> getAsset(String assetId) async {
    return AssetView.fromJson(
      _asMap(await _request('GET', '/assets/$assetId')),
    );
  }

  Future<AssetPreviewDescriptor> getAssetPreview(String assetId) async {
    final preview = AssetPreviewDescriptor.fromJson(
      _asMap(await _request('GET', '/assets/$assetId/preview')),
    );
    final contentUrl = preview.contentUrl;
    return AssetPreviewDescriptor(
      kind: preview.kind,
      mediaType: preview.mediaType,
      contentUrl: contentUrl == null
          ? null
          : contentUrl.startsWith('http')
              ? contentUrl
              : '${_serverOrigin()}$contentUrl',
      text: preview.text,
      truncated: preview.truncated,
      fallback: preview.fallback,
      spreadsheet: preview.spreadsheet,
    );
  }

  Future<AssetDeleteImpact> getAssetDeleteImpact(
      Iterable<String> assetIds) async {
    return AssetDeleteImpact.fromJson(_asMap(await _request(
      'POST',
      '/assets/delete-impact',
      body: {'assetIds': assetIds.toList()},
    )));
  }

  Future<void> deleteAssets(Iterable<String> assetIds) async {
    await _request(
      'POST',
      '/assets/batch-delete',
      body: {'assetIds': assetIds.toList()},
    );
  }

  Future<AccountSummary> getAccountSummary() async {
    return AccountSummary.fromJson(
      _asMap(await _request('GET', '/account/summary')),
    );
  }

  Future<AssetView> uploadAsset(
    PickedLocalFile file, {
    String origin = 'USER_UPLOAD',
  }) async {
    final boundary =
        'yuanzuo-${DateTime.now().microsecondsSinceEpoch}-${Random.secure().nextInt(1 << 32)}';
    try {
      final uri = Uri.parse('${ApiConfig.baseUrl}/assets')
          .replace(queryParameters: {'origin': origin});
      final request =
          await _client.postUrl(uri).timeout(const Duration(seconds: 10));
      request.headers.contentType = ContentType(
        'multipart',
        'form-data',
        parameters: {'boundary': boundary},
      );
      request.add(utf8.encode('--$boundary\r\n'));
      request.add(utf8.encode(
        'Content-Disposition: form-data; name="file"; filename="${_quoted(file.name)}"\r\n',
      ));
      request.add(utf8.encode('Content-Type: ${file.mediaType}\r\n\r\n'));
      await request.addStream(file.openRead());
      request.add(utf8.encode('\r\n--$boundary--\r\n'));
      final response =
          await request.close().timeout(const Duration(minutes: 30));
      final decoded = await _decodeResponse(response);
      return AssetView.fromJson(_asMap(decoded));
    } finally {
      await file.cleanup();
    }
  }

  Future<void> deleteAsset(String assetId) async {
    await _request('DELETE', '/assets/$assetId');
  }

  Future<AssetView> exportArtifact(
    String artifactId,
    String exportType,
  ) async {
    return AssetView.fromJson(_asMap(await _request(
      'POST',
      '/artifacts/$artifactId/exports',
      body: {'type': exportType},
      responseTimeout: const Duration(minutes: 2),
    )));
  }

  String assetContentUrl(String assetId) =>
      '${ApiConfig.baseUrl}/assets/$assetId/content';

  Future<Uint8List> downloadAssetContent(String assetId) async {
    try {
      final request = await _client
          .getUrl(Uri.parse(assetContentUrl(assetId)))
          .timeout(const Duration(seconds: 10));
      final response =
          await request.close().timeout(const Duration(seconds: 60));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await response.drain<void>();
        throw ApiException('文件下载失败 (${response.statusCode})');
      }
      final bytes = BytesBuilder(copy: false);
      await for (final chunk in response) {
        bytes.add(chunk);
      }
      return bytes.takeBytes();
    } on ApiException {
      rethrow;
    } on SocketException {
      throw const ApiException('无法连接电脑后端，请确认电脑和手机仍连接同一个 Wi-Fi');
    } on TimeoutException {
      throw const ApiException('文件下载超时，请稍后重试');
    }
  }

  Future<File> downloadAssetToTemporaryFile(
    String assetId, {
    required String fileName,
    bool Function()? isCancelled,
  }) async {
    return _downloadUrlToTemporaryFile(
      assetContentUrl(assetId),
      fileName: fileName,
      directoryPrefix: 'yuanzuo-download-',
      inactivityTimeout: const Duration(minutes: 2),
      fileSystemError: '无法创建下载缓存',
      isCancelled: isCancelled,
    );
  }

  Future<File> downloadUrlToTemporaryFile(
    String contentUrl, {
    required String fileName,
  }) {
    return _downloadUrlToTemporaryFile(
      contentUrl,
      fileName: fileName,
      directoryPrefix: 'yuanzuo-preview-',
      inactivityTimeout: const Duration(seconds: 60),
      fileSystemError: '无法创建文件预览缓存',
    );
  }

  Future<File> _downloadUrlToTemporaryFile(
    String contentUrl, {
    required String fileName,
    required String directoryPrefix,
    required Duration inactivityTimeout,
    required String fileSystemError,
    bool Function()? isCancelled,
  }) async {
    Directory? directory;
    IOSink? sink;
    try {
      if (isCancelled?.call() == true) {
        throw const AssetDownloadCancelledException();
      }
      directory = await Directory.systemTemp.createTemp(directoryPrefix);
      final file = File(
        '${directory.path}${Platform.pathSeparator}'
        '${_temporaryFileName(fileName)}',
      );
      final request = await _client
          .getUrl(Uri.parse(contentUrl))
          .timeout(const Duration(seconds: 10));
      final response =
          await request.close().timeout(const Duration(seconds: 60));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await response.drain<void>();
        throw ApiException('文件下载失败 (${response.statusCode})');
      }
      sink = file.openWrite();
      await for (final chunk in response.timeout(inactivityTimeout)) {
        if (isCancelled?.call() == true) {
          throw const AssetDownloadCancelledException();
        }
        sink.add(chunk);
      }
      await sink.flush();
      await sink.close();
      sink = null;
      if (isCancelled?.call() == true) {
        throw const AssetDownloadCancelledException();
      }
      return file;
    } on AssetDownloadCancelledException {
      await sink?.close();
      await _deleteTemporaryDirectory(directory);
      rethrow;
    } on ApiException {
      await sink?.close();
      await _deleteTemporaryDirectory(directory);
      rethrow;
    } on SocketException {
      await sink?.close();
      await _deleteTemporaryDirectory(directory);
      throw const ApiException('无法连接电脑后端，请确认电脑和手机仍连接同一个 Wi-Fi');
    } on TimeoutException {
      await sink?.close();
      await _deleteTemporaryDirectory(directory);
      throw const ApiException('文件下载超时，请稍后重试');
    } on FileSystemException {
      await sink?.close();
      await _deleteTemporaryDirectory(directory);
      throw ApiException(fileSystemError);
    }
  }

  Future<void> cancelRun(String runId) async {
    await _request('POST', '/runs/$runId/cancel');
  }

  Future<List<RunOutputSnapshot>> getRunOutput(String runId) async {
    return _asMapList(await _request('GET', '/runs/$runId/output'))
        .map(RunOutputSnapshot.fromJson)
        .toList();
  }

  Future<TaskExecutionResult> executeFeature({
    required FeatureDetail feature,
    required String taskTitle,
    required String? projectId,
    String? existingTaskId,
    String? baseArtifactId,
    String? selectedModelCode,
    Map<String, String> selectedModels = const {},
    required Map<String, Object?> parameters,
    required List<String> inputAssetIds,
    required ValueChanged<String> onStatus,
    ValueChanged<String>? onRunCreated,
    ValueChanged<RunOutputSnapshot>? onOutput,
  }) async {
    late final String taskId;
    if (existingTaskId == null) {
      onStatus('正在创建任务');
      final task = _asMap(await _request(
        'POST',
        '/tasks',
        body: {
          'projectId': projectId,
          'featureCode': feature.id,
          'title': taskTitle,
        },
      ));
      taskId = _requiredString(task, 'id');
    } else {
      onStatus('正在创建新版本');
      taskId = existingTaskId;
    }

    onStatus('任务已创建，等待执行');
    final run = _asMap(await _request(
      'POST',
      '/tasks/$taskId/runs',
      headers: {'Idempotency-Key': _newIdempotencyKey()},
      body: {
        'parameters': parameters,
        'inputAssetIds': inputAssetIds,
        'baseArtifactId': baseArtifactId,
        'selectedModelCode': selectedModelCode,
        'selectedModels': selectedModels,
      },
    ));
    final runId = _requiredString(run, 'id');
    onRunCreated?.call(runId);

    final outputWatcher = onOutput == null
        ? null
        : _RunOutputWatcher(runId: runId, onOutput: onOutput);
    unawaited(outputWatcher?.start());
    try {
      final pollingDeadline = DateTime.now().add(runPollingTimeout);
      var nextOutputRefresh = DateTime.now();
      while (DateTime.now().isBefore(pollingDeadline)) {
        final detail = _asMap(await _request('GET', '/runs/$runId'));
        final runData = _asMap(detail['run']);
        final status = _requiredString(runData, 'status');
        onStatus(_statusLabel(status, runData['executionPhase']?.toString()));
        if (onOutput != null && !DateTime.now().isBefore(nextOutputRefresh)) {
          try {
            for (final snapshot in await getRunOutput(runId)) {
              outputWatcher?.applySnapshot(snapshot);
            }
          } catch (_) {
            // SSE remains primary; snapshots are the recovery fallback.
          }
          nextOutputRefresh = DateTime.now().add(const Duration(seconds: 2));
        }
        if (status == 'SUCCEEDED' || status == 'PARTIAL') {
          if (onOutput != null) {
            try {
              for (final snapshot in await getRunOutput(runId)) {
                outputWatcher?.applySnapshot(snapshot);
              }
            } catch (_) {
              // The final Artifact remains the source of truth.
            }
          }
          final artifacts = _asMapList(detail['artifacts']);
          if (artifacts.isEmpty) {
            throw const ApiException('任务已完成，但没有返回可展示的结果');
          }
          return TaskExecutionResult(
            taskId: taskId,
            runId: runId,
            feature: feature,
            artifact: ArtifactView.fromJson(artifacts.first),
            runStatus: status,
          );
        }
        if (status == 'FAILED' ||
            status == 'CANCELLED' ||
            status == 'EXPIRED') {
          if (status == 'CANCELLED') {
            throw const ApiException('任务已取消', code: 'RUN_CANCELLED');
          }
          final errorCode = runData['errorCode']?.toString();
          throw ApiException(
            runFailureMessage(errorCode, runData['errorMessage']?.toString()),
            code: errorCode,
          );
        }
        await Future<void>.delayed(_runPollingInterval);
      }
      throw const ApiException('等待任务结果超时，请稍后在历史任务中查看');
    } finally {
      outputWatcher?.close();
    }
  }

  Future<dynamic> _request(
    String method,
    String path, {
    Map<String, String> headers = const {},
    Map<String, Object?>? body,
    Duration responseTimeout = const Duration(seconds: 30),
  }) async {
    try {
      final request = await _client
          .openUrl(method, Uri.parse('${ApiConfig.baseUrl}$path'))
          .timeout(const Duration(seconds: 10));
      request.headers.contentType = ContentType.json;
      request.headers.set(HttpHeaders.acceptHeader, ContentType.json.mimeType);
      headers.forEach(request.headers.set);
      if (body != null) request.write(jsonEncode(body));
      return _decodeResponse(
        await request.close().timeout(responseTimeout),
      );
    } on ApiException {
      rethrow;
    } on SocketException {
      throw const ApiException('无法连接电脑后端，请确认电脑和手机仍连接同一个 Wi-Fi');
    } on TimeoutException {
      throw const ApiException('连接后端超时，请检查后端运行状态');
    } on FormatException {
      throw const ApiException('后端返回的数据无法解析');
    }
  }

  Future<dynamic> _decodeResponse(HttpClientResponse response) async {
    final responseText = await utf8.decoder.bind(response).join();
    final decoded =
        responseText.isEmpty ? <String, dynamic>{} : jsonDecode(responseText);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final error = decoded is Map
          ? Map<String, dynamic>.from(decoded)
          : <String, dynamic>{};
      throw ApiException(
        error['message']?.toString() ?? '后端请求失败 (${response.statusCode})',
        code: error['code']?.toString(),
        statusCode: response.statusCode,
      );
    }
    return decoded;
  }

  static Map<String, dynamic> _asMap(Object? value) {
    if (value is Map) return Map<String, dynamic>.from(value);
    throw const ApiException('后端返回的数据格式不正确');
  }

  static List<Map<String, dynamic>> _asMapList(Object? value) {
    if (value is! List) throw const ApiException('后端返回的数据格式不正确');
    return value
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList();
  }

  static String _requiredString(Map<String, dynamic> source, String key) {
    final value = source[key];
    if (value == null || value.toString().isEmpty) {
      throw ApiException('后端返回缺少字段：$key');
    }
    return value.toString();
  }

  static String _newIdempotencyKey() {
    final random = Random.secure().nextInt(1 << 32);
    return '${DateTime.now().microsecondsSinceEpoch}-$random';
  }

  static String _quoted(String value) =>
      value.replaceAll('"', '').replaceAll('\r', '').replaceAll('\n', '');

  static String _temporaryFileName(String value) {
    final normalized = value
        .replaceAll(RegExp(r'[\\/:*?"<>|]'), '_')
        .replaceAll('\r', '')
        .replaceAll('\n', '')
        .trim();
    return normalized.isEmpty ? 'preview-file' : normalized;
  }

  static Future<void> _deleteTemporaryDirectory(Directory? directory) async {
    if (directory == null) return;
    try {
      if (await directory.exists()) {
        await directory.delete(recursive: true);
      }
    } on FileSystemException {
      // Preview cleanup is best effort.
    }
  }

  static String _serverOrigin() {
    final uri = Uri.parse(ApiConfig.baseUrl);
    return uri.replace(path: '', query: null, fragment: null).toString();
  }

  static String _statusLabel(String status, String? executionPhase) {
    switch (executionPhase) {
      case 'SUBMITTING':
        return '正在提交视频生成请求';
      case 'GENERATING':
        return '模型正在生成视频';
      case 'DOWNLOADING':
        return '正在下载视频结果';
      case 'PERSISTING':
        return '正在保存视频成果';
      case 'COMPLETED':
        return '视频生成完成';
      case 'CANCELLED':
        return '任务已取消';
      case 'FAILED':
        return '视频生成失败';
    }
    return switch (status) {
      'QUEUED' => '任务排队中',
      'VALIDATING' => '正在检查参数',
      'RUNNING' => '正在执行',
      'WAITING_CALLBACK' => '等待模型返回',
      _ => '正在处理',
    };
  }
}

class _RunOutputWatcher {
  _RunOutputWatcher({
    required this.runId,
    required this.onOutput,
  });

  final String runId;
  final ValueChanged<RunOutputSnapshot> onOutput;
  final RunOutputAccumulator _accumulator = RunOutputAccumulator();
  final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 8);
  bool _closed = false;

  Future<void> start() async {
    while (!_closed) {
      try {
        final request = await _client
            .getUrl(Uri.parse('${ApiConfig.baseUrl}/runs/$runId/events'))
            .timeout(const Duration(seconds: 10));
        request.headers.set(HttpHeaders.acceptHeader, 'text/event-stream');
        if (_accumulator.lastEventId > 0) {
          request.headers.set(
            'Last-Event-ID',
            _accumulator.lastEventId.toString(),
          );
        }
        final response =
            await request.close().timeout(const Duration(seconds: 15));
        if (response.statusCode < 200 || response.statusCode >= 300) {
          await response.drain<void>();
          if (!_closed) {
            await Future<void>.delayed(const Duration(milliseconds: 750));
          }
          continue;
        }
        final lines =
            response.transform(utf8.decoder).transform(const LineSplitter());
        await for (final frame in parseSseLines(lines)) {
          if (_closed) break;
          if (frame.event != 'output' || frame.data.isEmpty) continue;
          final decoded = jsonDecode(frame.data);
          if (decoded is! Map) continue;
          final data = Map<String, dynamic>.from(decoded);
          if (data['eventId'] == null && frame.id != null) {
            data['eventId'] = frame.id;
          }
          final snapshot = _accumulator.applyEvent(
            runId,
            RunOutputEvent.fromJson(data),
          );
          if (snapshot != null) onOutput(snapshot);
        }
      } catch (_) {
        if (_closed) return;
      }
      if (!_closed) {
        await Future<void>.delayed(const Duration(milliseconds: 750));
      }
    }
  }

  void applySnapshot(RunOutputSnapshot snapshot) {
    if (_closed) return;
    final applied = _accumulator.applySnapshot(snapshot);
    if (applied != null) onOutput(applied);
  }

  void close() {
    if (_closed) return;
    _closed = true;
    _client.close(force: true);
  }
}

typedef ValueChanged<T> = void Function(T value);
