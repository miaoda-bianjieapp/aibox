class PromptOptimizationUndoStore {
  final Map<String, String> _originalValues = {};

  bool contains(String field) => _originalValues.containsKey(field);

  void captureOriginal(String field, String text) {
    _originalValues.putIfAbsent(field, () => text);
  }

  String? takeOriginal(String field) => _originalValues.remove(field);

  void clear() => _originalValues.clear();
}
