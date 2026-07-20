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
    'MODEL_EMPTY_RESPONSE' => '模型未返回可用内容，请重试或切换其他模型。',
    'IMAGE_DECODE_FAILED' => '无法读取原图，请重新选择 PNG、JPG、JPEG 或 WebP 图片。',
    'IMAGE_DIMENSIONS_UNSUPPORTED' => '原图尺寸与目标比例超出模型支持范围，请调整比例或更换尺寸较小的原图。',
    'IMAGE_EXPANSION_PAYLOAD_TOO_LARGE' => '扩图画布超过模型服务允许的大小，请调整比例或更换尺寸较小的原图。',
    'IMAGE_EXPANSION_SCALE_UNSUPPORTED' => '当前比例和扩展倍数超过所选模型的尺寸限制，请降低倍数或切换模型。',
    'IMAGE_EXPANSION_PROTOCOL_UNSUPPORTED' ||
    'IMAGE_EXPANSION_PROTOCOL_NOT_CONFIGURED' =>
      '所选模型暂未完成扩图配置，请切换其他模型或联系管理员。',
    'IMAGE_PROCESSING_FAILED' => '图片处理失败，请重新选择原图后再试。',
    'IMAGE_ASPECT_RATIO_INVALID' => '目标比例无效，请使用宽:高格式并保持在 1:3 至 3:1 之间。',
    'PROVIDER_INVALID_RESPONSE' => '图片模型返回的结果无法使用，请重试。',
    'PROVIDER_CONNECTION_FAILED' => '暂时无法连接模型服务，请稍后重试。',
    _ when message?.trim().isNotEmpty == true => message!.trim(),
    _ => '任务执行失败，请稍后重试。',
  };
}
