import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import '../models/feature_models.dart';
import 'api_config.dart';
import 'api_exception.dart';
import 'native_file_picker.dart';
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
    return serverMessage ?? '任务执行失败，请稍后重试';
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

  Future<List<TaskView>> listTasks() async {
    return _asMapList(await _request('GET', '/tasks'))
        .map(TaskView.fromJson)
        .toList();
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

    final pollingDeadline = DateTime.now().add(runPollingTimeout);
    while (DateTime.now().isBefore(pollingDeadline)) {
      final detail = _asMap(await _request('GET', '/runs/$runId'));
      final runData = _asMap(detail['run']);
      final status = _requiredString(runData, 'status');
      onStatus(_statusLabel(status));
      if (status == 'SUCCEEDED' || status == 'PARTIAL') {
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
      if (status == 'FAILED' || status == 'CANCELLED' || status == 'EXPIRED') {
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
  }

  Future<dynamic> _request(
    String method,
    String path, {
    Map<String, String> headers = const {},
    Map<String, Object?>? body,
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
        await request.close().timeout(const Duration(seconds: 30)),
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

typedef ValueChanged<T> = void Function(T value);
