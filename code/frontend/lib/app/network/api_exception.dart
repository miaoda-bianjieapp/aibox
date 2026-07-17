class ApiException implements Exception {
  const ApiException(this.message, {this.code, this.statusCode});

  final String message;
  final String? code;
  final int? statusCode;

  @override
  String toString() => message;
}

String taskFailureMessage({String? code, String? message}) {
  return switch (code) {
    'PROVIDER_NO_AVAILABLE_ACCOUNT' ||
    'PROVIDER_ACCOUNT_POOL_LIMIT_REACHED' =>
      '模型服务当前没有可用资源，请稍后重试；如持续失败，请联系管理员检查供应商账号池。',
    'PROVIDER_HTTP_503' => '模型服务暂时不可用，请稍后重试；如持续失败，请联系管理员检查供应商状态。',
    'PROVIDER_HTTP_429' => '模型服务请求过于频繁，请稍后重试。',
    'PROVIDER_HTTP_401' || 'PROVIDER_HTTP_403' => '模型服务鉴权失败，请联系管理员检查供应商配置。',
    'MODEL_ADAPTER_NOT_FOUND' ||
    'MODEL_ADAPTER_NOT_CONFIGURED' =>
      '所选模型尚未完成服务配置，请切换其他模型或联系管理员。',
    'PROVIDER_CONNECTION_FAILED' => '暂时无法连接模型服务，请稍后重试。',
    _ when message?.trim().isNotEmpty == true => message!.trim(),
    _ => '任务执行失败，请稍后重试。',
  };
}
