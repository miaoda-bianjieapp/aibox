import 'package:flutter_test/flutter_test.dart';

import 'package:yuanzuo_ai/app/models/prompt_optimization_undo_store.dart';

void main() {
  test('keeps the first original text across repeated optimizations', () {
    final store = PromptOptimizationUndoStore();

    store.captureOriginal('prompt', '原始提示词');
    store.captureOriginal('prompt', '第一次优化后的提示词');

    expect(store.contains('prompt'), isTrue);
    expect(store.takeOriginal('prompt'), '原始提示词');
    expect(store.contains('prompt'), isFalse);
  });

  test('keeps snapshots independent by field and clears them', () {
    final store = PromptOptimizationUndoStore();

    store.captureOriginal('prompt', '原始提示词');
    store.captureOriginal('instruction', '原始指令');

    expect(store.takeOriginal('instruction'), '原始指令');
    store.clear();
    expect(store.contains('prompt'), isFalse);
  });
}
