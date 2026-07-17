import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/network/api_exception.dart';

void main() {
  test('maps existing provider 503 failures to an actionable message', () {
    expect(
      taskFailureMessage(
        code: 'PROVIDER_HTTP_503',
        message: 'Model provider returned HTTP 503',
      ),
      '模型服务暂时不可用，请稍后重试；如持续失败，请联系管理员检查供应商状态。',
    );
  });

  test('maps an unconfigured model adapter to a configuration message', () {
    expect(
      taskFailureMessage(
        code: 'MODEL_ADAPTER_NOT_FOUND',
        message: 'No configured protocol adapter',
      ),
      '所选模型尚未完成服务配置，请切换其他模型或联系管理员。',
    );
  });

  test('maps empty model output to a retry message', () {
    expect(
      taskFailureMessage(
        code: 'MODEL_EMPTY_RESPONSE',
        message: 'The model returned an empty writing framework',
      ),
      '模型未返回可用内容，请重试或切换其他模型。',
    );
  });

  test('keeps an unknown backend message', () {
    expect(
      taskFailureMessage(code: 'CUSTOM_ERROR', message: '自定义错误'),
      '自定义错误',
    );
  });
}
