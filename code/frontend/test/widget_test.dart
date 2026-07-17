import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/app.dart';

void main() {
  testWidgets('main navigation switches between three pages', (tester) async {
    await tester.pumpWidget(const YuanzuoApp());

    expect(find.text('元作 AI'), findsOneWidget);
    await tester.tap(find.text('功能').last);
    await tester.pumpAndSettle();
    expect(find.text('你要交付什么？'), findsOneWidget);

    await tester.tap(find.text('我的').last);
    await tester.pumpAndSettle();
    expect(find.text('内容与资产'), findsOneWidget);
  });
}
