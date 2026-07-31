import 'dart:async';
import 'dart:collection';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show ScrollDirection;
import 'package:flutter/scheduler.dart';

import '../models/run_output_models.dart';
import 'markdown_output_view.dart';
import 'output_text_style.dart';

enum StreamingOutputPhase {
  streaming,
  succeeded,
  partial,
  failed,
}

enum StreamingMarkdownStrategy {
  stableBlocks,
  fullDocument,
}

const activeStreamingMarkdownStrategy = StreamingMarkdownStrategy.stableBlocks;

class StreamingScrollFollowController {
  StreamingScrollFollowController({
    required this.scrollController,
    this.bottomThreshold = 40,
  });

  final ScrollController scrollController;
  final double bottomThreshold;

  bool _following = true;
  bool _userScrolling = false;
  bool _programmaticScroll = false;
  bool _scrollScheduled = false;
  bool _disposed = false;

  bool get following => _following;

  bool handleNotification(ScrollNotification notification) {
    if (_disposed) return false;
    if (notification.metrics.axis != Axis.vertical) return false;

    if (notification is UserScrollNotification) {
      if (_programmaticScroll) return false;
      if (notification.direction == ScrollDirection.idle) {
        _userScrolling = false;
        _resumeIfAtBottom();
      } else {
        _userScrolling = true;
        _following = false;
      }
      return false;
    }

    if (notification is ScrollStartNotification &&
        notification.dragDetails != null) {
      _userScrolling = true;
      _following = false;
    }
    if (notification is ScrollUpdateNotification &&
        notification.dragDetails != null) {
      _userScrolling = true;
      _following = false;
    }
    if (notification is ScrollEndNotification) {
      _userScrolling = false;
      _resumeIfAtBottom();
    } else if (!_userScrolling && _isAtBottom()) {
      _following = true;
    }
    return false;
  }

  void contentChanged() {
    if (_disposed || !_following || _userScrolling) {
      return;
    }
    if (_scrollScheduled) return;
    _scrollScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollScheduled = false;
      _jumpToBottom();
    });
    WidgetsBinding.instance.scheduleFrame();
  }

  void forceFollow() {
    if (_disposed) return;
    _following = true;
    _userScrolling = false;
    contentChanged();
  }

  void dispose() {
    _disposed = true;
    _scrollScheduled = false;
  }

  bool _isAtBottom() {
    if (_disposed) return false;
    if (!scrollController.hasClients) return true;
    return scrollController.position.extentAfter <= bottomThreshold;
  }

  void _resumeIfAtBottom() {
    if (!_isAtBottom()) return;
    _following = true;
    contentChanged();
  }

  void _jumpToBottom() {
    if (_disposed ||
        !_following ||
        _userScrolling ||
        !scrollController.hasClients) {
      return;
    }
    final position = scrollController.position;
    final target = position.maxScrollExtent;
    if ((target - position.pixels).abs() <= 0.5) return;
    _programmaticScroll = true;
    try {
      scrollController.jumpTo(target);
    } finally {
      _programmaticScroll = false;
    }
  }
}

class StreamingOutputView extends StatefulWidget {
  const StreamingOutputView({
    super.key,
    required this.snapshot,
    required this.phase,
    this.stopRequested = false,
    this.onPlaybackCompleted,
    this.onSettled,
    this.onContentChanged,
  });

  final RunOutputSnapshot? snapshot;
  final StreamingOutputPhase phase;
  final bool stopRequested;
  final VoidCallback? onPlaybackCompleted;
  final VoidCallback? onSettled;
  final VoidCallback? onContentChanged;

  @override
  State<StreamingOutputView> createState() => _StreamingOutputViewState();
}

class _StreamingOutputViewState extends State<StreamingOutputView> {
  static const _characterInterval = Duration(milliseconds: 4);
  static const _acceleratedCharacterInterval = Duration(milliseconds: 2);
  static const _markdownRenderInterval = Duration(milliseconds: 32);
  static const _completionDelay = Duration(milliseconds: 300);
  static const _acceleratedBacklog = 40;
  static const _frameBatchBacklog = 100;
  static const _activeFrameBatchSize = 4;
  static const _terminalFrameBatchSize = 8;

  final _markdownFenceTracker = _MarkdownFenceTracker();
  final ListQueue<String> _pendingCharacters = ListQueue();
  Timer? _characterTimer;
  Timer? _markdownRenderTimer;
  Timer? _completionTimer;
  int? _frameCallbackId;
  String _targetText = '';
  String _renderedText = '';
  String _markdownRenderedText = '';
  String _format = 'text';
  bool _finalMarkdown = false;
  bool _playbackStopped = false;
  bool _terminalPresentationStarted = false;
  bool _playbackCompletedNotified = false;
  bool _settledNotified = false;
  bool _contentNotificationScheduled = false;

  @override
  void initState() {
    super.initState();
    _acceptSnapshot(widget.snapshot);
    if (widget.stopRequested) {
      _stopPlaybackImmediately();
    }
    if (widget.phase != StreamingOutputPhase.streaming) {
      _beginTerminalPhase();
    }
  }

  @override
  void didUpdateWidget(covariant StreamingOutputView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.stopRequested && widget.stopRequested) {
      _stopPlaybackImmediately();
    }
    final oldSnapshot = oldWidget.snapshot;
    final snapshot = widget.snapshot;
    if (!_playbackStopped &&
        snapshot != null &&
        (oldSnapshot == null ||
            oldSnapshot.lastSequence != snapshot.lastSequence ||
            oldSnapshot.content != snapshot.content ||
            oldSnapshot.status != snapshot.status ||
            oldSnapshot.updateType != snapshot.updateType)) {
      _acceptSnapshot(snapshot);
    }
    if (oldWidget.phase != widget.phase) {
      if (widget.phase == StreamingOutputPhase.streaming) {
        _resetSettlement();
      } else {
        _beginTerminalPhase();
      }
    }
  }

  @override
  void dispose() {
    _cancelPlaybackSchedule();
    _markdownRenderTimer?.cancel();
    _completionTimer?.cancel();
    super.dispose();
  }

  void _acceptSnapshot(RunOutputSnapshot? snapshot) {
    if (snapshot == null || _playbackStopped) return;
    _format = snapshot.format;
    final incoming = snapshot.content;
    switch (snapshot.updateType) {
      case RunOutputUpdateType.started:
        _replaceImmediately('');
      case RunOutputUpdateType.replace:
        _replaceImmediately(incoming);
      case RunOutputUpdateType.append:
        _setTarget(incoming);
      case RunOutputUpdateType.snapshot:
        _setTarget(incoming);
      case RunOutputUpdateType.completed:
      case RunOutputUpdateType.failed:
      case RunOutputUpdateType.partial:
        _setTarget(incoming);
    }
  }

  void _setTarget(String value) {
    if (_targetText == value && _renderedText == value) {
      _scheduleMarkdownRender();
      _finishPlaybackIfReady();
      return;
    }
    final previousRenderedText = _renderedText;
    _targetText = value;
    _finalMarkdown = false;
    if (!value.startsWith(_renderedText)) {
      _renderedText = _commonGraphemePrefix(_renderedText, value);
      _markdownRenderedText = _commonGraphemePrefix(
        _markdownRenderedText,
        _renderedText,
      );
      _markdownFenceTracker.reset(_renderedText);
    }
    if (_terminalPresentationStarted && _targetText != _renderedText) {
      _completionTimer?.cancel();
      _completionTimer = null;
      _terminalPresentationStarted = false;
    }
    if (_renderedText != previousRenderedText) {
      _notifyContentChanged();
    }
    _rebuildPendingCharacters();
    _scheduleMarkdownRender();
    _scheduleCharacter();
  }

  void _replaceImmediately(String value) {
    _cancelPlaybackSchedule();
    _markdownRenderTimer?.cancel();
    _markdownRenderTimer = null;
    _targetText = value;
    _renderedText = value;
    _markdownRenderedText = value;
    _pendingCharacters.clear();
    _markdownFenceTracker.reset(value);
    _finalMarkdown = false;
    _notifyContentChanged();
    _finishPlaybackIfReady();
  }

  void _scheduleCharacter() {
    if (_pendingCharacters.isEmpty) {
      _finishPlaybackIfReady();
      return;
    }
    if (_characterTimer != null || _frameCallbackId != null) return;

    if (widget.phase != StreamingOutputPhase.streaming ||
        _pendingCharacters.length > _frameBatchBacklog) {
      _frameCallbackId = SchedulerBinding.instance.scheduleFrameCallback((_) {
        _frameCallbackId = null;
        _renderCharacterBatch(
          widget.phase == StreamingOutputPhase.streaming
              ? _activeFrameBatchSize
              : _terminalFrameBatchSize,
        );
      });
      return;
    }

    final interval = _pendingCharacters.length > _acceleratedBacklog
        ? _acceleratedCharacterInterval
        : _characterInterval;
    _characterTimer = Timer(
      interval,
      () => _renderCharacterBatch(1),
    );
  }

  void _renderCharacterBatch(int maximumCharacters) {
    _characterTimer = null;
    _frameCallbackId = null;
    if (!mounted || _playbackStopped || _pendingCharacters.isEmpty) {
      _finishPlaybackIfReady();
      return;
    }
    final appended = StringBuffer();
    var completedCodeFence = false;
    for (var index = 0;
        index < maximumCharacters && _pendingCharacters.isNotEmpty;
        index++) {
      final nextCharacter = _pendingCharacters.removeFirst();
      appended.write(nextCharacter);
      if (_format == 'markdown' && _markdownFenceTracker.add(nextCharacter)) {
        completedCodeFence = true;
      }
    }
    final appendedText = appended.toString();
    if (_format == 'markdown') {
      _renderedText += appendedText;
      if (completedCodeFence) {
        _flushMarkdownRender();
      } else {
        _scheduleMarkdownRender();
      }
    } else {
      setState(() => _renderedText += appendedText);
      _notifyContentChanged();
    }
    if (_pendingCharacters.isEmpty) {
      if (widget.phase != StreamingOutputPhase.streaming &&
          _format == 'markdown' &&
          _markdownRenderedText != _renderedText) {
        _flushMarkdownRender();
        return;
      }
      _finishPlaybackIfReady();
    } else {
      _scheduleCharacter();
    }
  }

  void _rebuildPendingCharacters() {
    _pendingCharacters
      ..clear()
      ..addAll(
        _targetText.substring(_renderedText.length).characters,
      );
  }

  void _cancelPlaybackSchedule() {
    _characterTimer?.cancel();
    _characterTimer = null;
    final callbackId = _frameCallbackId;
    if (callbackId != null) {
      SchedulerBinding.instance.cancelFrameCallbackWithId(callbackId);
      _frameCallbackId = null;
    }
  }

  void _scheduleMarkdownRender() {
    if (_format != 'markdown' ||
        _markdownRenderedText == _renderedText ||
        _markdownRenderTimer != null) {
      return;
    }
    _markdownRenderTimer = Timer(
      _markdownRenderInterval,
      _flushMarkdownRender,
    );
  }

  void _flushMarkdownRender() {
    _markdownRenderTimer?.cancel();
    _markdownRenderTimer = null;
    if (!mounted || _format != 'markdown') return;
    if (_markdownRenderedText != _renderedText) {
      setState(() => _markdownRenderedText = _renderedText);
      _notifyContentChanged();
    }
    _finishPlaybackIfReady();
  }

  void _beginTerminalPhase() {
    if (_playbackStopped) {
      _targetText = _renderedText;
      _pendingCharacters.clear();
      _finishPlaybackIfReady();
      return;
    }
    _targetText = widget.snapshot?.content ?? _targetText;
    _rebuildPendingCharacters();
    _cancelPlaybackSchedule();
    if (_pendingCharacters.isNotEmpty) {
      _scheduleCharacter();
      return;
    }
    _finishPlaybackIfReady();
  }

  void _finishPlaybackIfReady() {
    if (widget.phase == StreamingOutputPhase.streaming ||
        _pendingCharacters.isNotEmpty ||
        _targetText != _renderedText ||
        _terminalPresentationStarted) {
      return;
    }
    if (_format == 'markdown' && _markdownRenderedText != _renderedText) {
      _flushMarkdownRender();
      return;
    }
    _terminalPresentationStarted = true;
    final showFinalMarkdown = widget.phase == StreamingOutputPhase.succeeded ||
        widget.phase == StreamingOutputPhase.partial;
    setState(() => _finalMarkdown = showFinalMarkdown);
    _notifyContentChanged();
    _notifyPlaybackCompleted();

    if (widget.phase != StreamingOutputPhase.succeeded) {
      _notifySettled();
      return;
    }
    _completionTimer = Timer(_completionDelay, () {
      if (!mounted) return;
      _notifySettled();
    });
  }

  void _stopPlaybackImmediately() {
    if (_playbackStopped) return;
    _playbackStopped = true;
    _cancelPlaybackSchedule();
    _markdownRenderTimer?.cancel();
    _markdownRenderTimer = null;
    final visibleText =
        _format == 'markdown' ? _markdownRenderedText : _renderedText;
    _targetText = visibleText;
    _renderedText = visibleText;
    _markdownRenderedText = visibleText;
    _pendingCharacters.clear();
    _markdownFenceTracker.reset(visibleText);
    _notifyContentChanged();
  }

  void _resetSettlement() {
    _completionTimer?.cancel();
    _completionTimer = null;
    _finalMarkdown = false;
    _terminalPresentationStarted = false;
    _playbackCompletedNotified = false;
    _settledNotified = false;
  }

  void _notifyContentChanged() {
    if (_contentNotificationScheduled) return;
    _contentNotificationScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _contentNotificationScheduled = false;
      if (mounted) widget.onContentChanged?.call();
    });
  }

  void _notifySettled() {
    if (_settledNotified) return;
    _settledNotified = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onSettled?.call();
    });
    WidgetsBinding.instance.scheduleFrame();
  }

  void _notifyPlaybackCompleted() {
    if (_playbackCompletedNotified) return;
    _playbackCompletedNotified = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onPlaybackCompleted?.call();
    });
  }

  @override
  Widget build(BuildContext context) {
    final Widget content;
    if (_format == 'markdown') {
      if (_finalMarkdown) {
        content = MarkdownOutputView(
          key: const ValueKey('streaming-output-rich-text'),
          markdown: _renderedText,
        );
      } else {
        content = switch (activeStreamingMarkdownStrategy) {
          StreamingMarkdownStrategy.stableBlocks => StreamingMarkdownView(
              key: const ValueKey('streaming-output-markdown'),
              markdown: _markdownRenderedText,
            ),
          StreamingMarkdownStrategy.fullDocument => MarkdownOutputView(
              key: const ValueKey('streaming-output-markdown'),
              markdown: _markdownRenderedText,
              renderMode: MarkdownRenderMode.streaming,
            ),
        };
      }
    } else {
      content = _PlainStreamingOutput(text: _renderedText);
    }

    return content;
  }
}

class _PlainStreamingOutput extends StatelessWidget {
  const _PlainStreamingOutput({
    required this.text,
  });

  final String text;

  @override
  Widget build(BuildContext context) {
    final style = outputBodyTextStyle();
    return SelectableText.rich(
      key: const ValueKey('streaming-output-visible-text'),
      TextSpan(
        style: style,
        children: [
          TextSpan(text: text),
        ],
      ),
      textAlign: TextAlign.left,
      strutStyle: outputBodyStrutStyle(),
    );
  }
}

String _commonGraphemePrefix(String first, String second) {
  final firstCharacters = first.characters.iterator;
  final secondCharacters = second.characters.iterator;
  final common = StringBuffer();
  while (firstCharacters.moveNext() && secondCharacters.moveNext()) {
    if (firstCharacters.current != secondCharacters.current) break;
    common.write(firstCharacters.current);
  }
  return common.toString();
}

class _MarkdownFenceTracker {
  final StringBuffer _currentLine = StringBuffer();
  bool _insideFence = false;
  String _fenceCharacter = '';
  int _fenceLength = 0;

  bool add(String character) {
    if (character != '\n') {
      _currentLine.write(character);
      return false;
    }
    final completedFence = _processLine(_currentLine.toString());
    _currentLine.clear();
    return completedFence;
  }

  void reset(String markdown) {
    _currentLine.clear();
    _insideFence = false;
    _fenceCharacter = '';
    _fenceLength = 0;
    for (final character in markdown.characters) {
      add(character);
    }
  }

  bool _processLine(String line) {
    final trimmed = line.trim();
    if (_insideFence) {
      if (_isFenceLine(trimmed, _fenceCharacter, _fenceLength)) {
        _insideFence = false;
        _fenceCharacter = '';
        _fenceLength = 0;
        return true;
      }
      return false;
    }

    final opening = RegExp(r'^\s*(`{3,}|~{3,})(.*)$').firstMatch(line);
    if (opening == null) return false;
    final marker = opening.group(1)!;
    _insideFence = true;
    _fenceCharacter = marker[0];
    _fenceLength = marker.length;
    return false;
  }
}

bool _isFenceLine(String line, String character, int minimumLength) {
  if (line.length < minimumLength) return false;
  for (final codeUnit in line.codeUnits) {
    if (String.fromCharCode(codeUnit) != character) return false;
  }
  return true;
}
