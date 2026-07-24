import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/pages/history_page.dart';
import 'package:yuanzuo_ai/app/state/app_data_controller.dart';
import 'package:yuanzuo_ai/app/theme/app_theme.dart';

void main() {
  testWidgets('shows all workspace categories in catalog order',
      (tester) async {
    final data = _data();

    await tester.pumpWidget(_app(
      HistoryPage(
        data: data,
        taskLoader: (_, __) async => const [],
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('全部'), findsOneWidget);
    expect(find.text('文本与写作'), findsOneWidget);
    expect(find.text('PPT与演示'), findsOneWidget);
    expect(find.text('图片设计'), findsOneWidget);
    expect(find.text('音频'), findsOneWidget);
    expect(find.text('视频'), findsOneWidget);
    expect(find.text('文档与数据'), findsOneWidget);
  });

  testWidgets('requests the selected workspace and shows its empty state',
      (tester) async {
    final data = _data();
    final requests = <(String?, String)>[];

    await tester.pumpWidget(_app(
      HistoryPage(
        data: data,
        taskLoader: (workspaceCode, keyword) async {
          requests.add((workspaceCode, keyword));
          return workspaceCode == null ? [_task()] : const [];
        },
      ),
    ));
    await tester.pumpAndSettle();

    expect(requests, [(null, '')]);
    expect(find.text('示例任务'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('history-category-image')),
    );
    await tester.pumpAndSettle();

    expect(requests, [(null, ''), ('image', '')]);
    expect(find.text('暂无该分类的任务'), findsOneWidget);
    expect(find.text('示例任务'), findsNothing);
  });

  testWidgets('accepts workspace categories that arrive after the page opens',
      (tester) async {
    final data = AppDataController();

    await tester.pumpWidget(_app(
      HistoryPage(
        data: data,
        taskLoader: (_, __) async => const [],
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('全部'), findsOneWidget);
    expect(find.text('视频'), findsNothing);

    data.workspaces = _data().workspaces;
    data.notifyListeners();
    await tester.pumpAndSettle();

    expect(find.text('视频'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('shows the first-run prompt snippet in a fixed column',
      (tester) async {
    final data = _data();

    await tester.pumpWidget(_app(
      HistoryPage(
        data: data,
        taskLoader: (_, __) async => [
          _task(promptSnippet: '那是资讯确他用了十年...'),
        ],
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('那是资讯确他用了十年...'), findsOneWidget);
    final promptFinder = find.byKey(
      const ValueKey<String>('history-prompt-snippet-task-1'),
    );
    expect(promptFinder, findsOneWidget);
    expect(tester.getTopLeft(promptFinder).dx, greaterThan(120));
  });

  testWidgets('debounces title search and combines it with the category',
      (tester) async {
    final data = _data();
    final requests = <(String?, String)>[];

    await tester.pumpWidget(_app(
      HistoryPage(
        data: data,
        taskLoader: (workspaceCode, keyword) async {
          requests.add((workspaceCode, keyword));
          return keyword.isEmpty ? [_task()] : const [];
        },
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey<String>('history-category-writing')),
    );
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey<String>('history-search-field')),
      '示例',
    );
    await tester.pump(const Duration(milliseconds: 250));
    expect(requests.last, ('writing', ''));

    await tester.pump(const Duration(milliseconds: 60));
    await tester.pumpAndSettle();

    expect(requests.last, ('writing', '示例'));
    expect(find.text('未找到匹配任务'), findsOneWidget);
  });
}

Widget _app(Widget child) {
  return MaterialApp(
    theme: AppTheme.light,
    home: child,
  );
}

AppDataController _data() {
  final data = AppDataController();
  data.workspaces = const [
    WorkspaceDefinition(
      id: 'writing',
      title: '文本与写作',
      description: '',
      iconKey: 'edit',
      groups: {},
      searchTerms: [],
      entries: [
        FeatureEntry(
          id: 'writing.draft',
          title: '从零起草',
          description: '',
          version: 1,
          resultType: 'rich_text',
          rendererKey: 'rich_text_editor',
          executionMode: 'ASYNC',
        ),
      ],
    ),
    WorkspaceDefinition(
      id: 'presentation',
      title: 'PPT与演示',
      description: '',
      iconKey: 'presentation',
      groups: {},
      searchTerms: [],
      entries: [],
    ),
    WorkspaceDefinition(
      id: 'image',
      title: '图片设计',
      description: '',
      iconKey: 'image',
      groups: {},
      searchTerms: [],
      entries: [],
    ),
    WorkspaceDefinition(
      id: 'audio',
      title: '音频',
      description: '',
      iconKey: 'audio',
      groups: {},
      searchTerms: [],
      entries: [],
    ),
    WorkspaceDefinition(
      id: 'video',
      title: '视频',
      description: '',
      iconKey: 'video',
      groups: {},
      searchTerms: [],
      entries: [],
    ),
    WorkspaceDefinition(
      id: 'document',
      title: '文档与数据',
      description: '',
      iconKey: 'document',
      groups: {},
      searchTerms: [],
      entries: [],
    ),
  ];
  data.tasks = [_task()];
  return data;
}

TaskView _task({String? promptSnippet}) {
  return TaskView(
    id: 'task-1',
    projectId: null,
    featureCode: 'writing.draft',
    title: '示例任务',
    promptSnippet: promptSnippet,
    status: 'ACTIVE',
    currentArtifactId: null,
    createdAt: DateTime(2026, 7, 23),
    updatedAt: DateTime(2026, 7, 23),
  );
}
