import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/network/backend_api.dart';

void main() {
  test('long-running model jobs outlive the previous two-minute limit', () {
    expect(
      BackendApi.runPollingTimeout,
      greaterThanOrEqualTo(const Duration(minutes: 15)),
    );
  });

  test('HTTP 524 is translated into an actionable model timeout message', () {
    expect(
      BackendApi.runFailureMessage(
        'PROVIDER_HTTP_524',
        'Model provider returned HTTP 524',
      ),
      '模型服务处理超时，请重试；如持续失败，请切换其他模型',
    );
  });

  test('task list path omits an empty workspace filter', () {
    expect(BackendApi.taskListPath(null, null), '/tasks');
    expect(BackendApi.taskListPath('  ', '  '), '/tasks');
  });

  test('task list path encodes workspace and search filters', () {
    expect(
      BackendApi.taskListPath(' image design ', ' 扩图 '),
      '/tasks?workspaceCode=image+design&keyword=%E6%89%A9%E5%9B%BE',
    );
  });
}
