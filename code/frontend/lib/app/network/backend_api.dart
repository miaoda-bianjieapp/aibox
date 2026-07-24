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

class BackendApi {
  BackendApi._();

  static final instance = BackendApi._();
  static const runPollingTimeout = Duration(minutes: 20);
  static const _runPollingInterval = Duration(milliseconds: 500);

  final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 8);

  static String runFailureMessage(String? code, String? serverMessage) {
    if (code == 'PROVIDER_HTTP_524') {
      return '模型服务处理超时，请重试；如持续失败，请切换其他模型';
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

  Future<AccountSummary> getAccountSummary() async {
    return AccountSummary.fromJson(
      _asMap(await _request('GET', '/account/summary')),
    );
  }

  Future<AssetView> uploadAsset(PickedLocalFile file) async {
    final boundary =
        'yuanzuo-${DateTime.now().microsecondsSinceEpoch}-${Random.secure().nextInt(1 << 32)}';
    final request = await _client
        .postUrl(Uri.parse('${ApiConfig.baseUrl}/assets'))
        .timeout(const Duration(seconds: 10));
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
    request.add(file.bytes);
    request.add(utf8.encode('\r\n--$boundary--\r\n'));
    final response = await request.close().timeout(const Duration(seconds: 60));
    final decoded = await _decodeResponse(response);
    return AssetView.fromJson(_asMap(decoded));
  }

  Future<void> deleteAsset(String assetId) async {
    await _request('DELETE', '/assets/$assetId');
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
        throw ApiException('图片下载失败 (${response.statusCode})');
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
      throw const ApiException('图片下载超时，请稍后重试');
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
        onStatus(_statusLabel(status));
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

  static String _statusLabel(String status) => switch (status) {
        'QUEUED' => '任务排队中',
        'VALIDATING' => '正在检查参数',
        'RUNNING' => '正在执行',
        'WAITING_CALLBACK' => '等待模型返回',
        _ => '正在处理',
      };
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
