import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/pages/projects_page.dart';
import 'package:yuanzuo_ai/app/state/app_data_controller.dart';

void main() {
  testWidgets('cancelling project creation closes cleanly', (tester) async {
    final data = AppDataController();

    await tester.pumpWidget(
      MaterialApp(home: ProjectsPage(data: data)),
    );
    await tester.tap(find.byTooltip('新建项目'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, '不会创建的项目');
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();

    expect(find.byType(AlertDialog), findsNothing);
    expect(data.projects, isEmpty);
    expect(tester.takeException(), isNull);
  });
}
