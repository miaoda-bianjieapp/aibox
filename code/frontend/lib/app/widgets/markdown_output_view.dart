import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_markdown_latex/flutter_markdown_latex.dart';
import 'package:markdown/markdown.dart' as md;

import '../network/api_config.dart';
import '../theme/app_theme.dart';

class MarkdownOutputView extends StatelessWidget {
  const MarkdownOutputView({
    super.key,
    required this.markdown,
    this.streaming = false,
  });

  final String markdown;
  final bool streaming;

  @override
  Widget build(BuildContext context) {
    final content = _sanitizeImages(markdown);
    if (content.trim().isEmpty) {
      return Text(
        streaming ? '正在等待模型返回内容...' : '暂无可展示内容',
        style: Theme.of(context).textTheme.bodyMedium,
      );
    }

    final segments = _parseOutputSegments(content);
    final children = <Widget>[];
    var codeIndex = 0;
    for (final segment in segments) {
      if (segment is _MarkdownSegment) {
        if (segment.markdown.trim().isNotEmpty) {
          children.add(_buildMarkdownBody(context, segment.markdown));
        }
        continue;
      }

      if (segment is _FencedCodeBlock) {
        children.add(
          _CopyableCodeBlock(
            key: ValueKey('markdown-code-block-$codeIndex'),
            buttonKey: ValueKey('copy-code-block-$codeIndex'),
            language: segment.language,
            code: segment.code,
            copyEnabled: segment.closed,
          ),
        );
        codeIndex++;
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: children,
    );
  }

  Widget _buildMarkdownBody(BuildContext context, String data) {
    final theme = Theme.of(context);
    final bodyStyle = theme.textTheme.bodyLarge?.copyWith(
      color: AppColors.ink,
      height: 1.55,
    );

    return MarkdownBody(
      data: data,
      selectable: true,
      extensionSet: md.ExtensionSet(
        [
          LatexBlockSyntax(),
          ...md.ExtensionSet.gitHubFlavored.blockSyntaxes,
        ],
        [
          LatexInlineSyntax(),
          ...md.ExtensionSet.gitHubFlavored.inlineSyntaxes,
        ],
      ),
      builders: {
        'latex': LatexElementBuilder(textStyle: bodyStyle),
      },
      imageBuilder: (uri, title, alt) {
        final source = uri.toString();
        if (!source.startsWith('${ApiConfig.baseUrl}/assets/')) {
          return Text(
            '${alt ?? '图片'}（外部图片已隐藏）',
            style: theme.textTheme.bodyMedium,
          );
        }
        return Image.network(
          source,
          fit: BoxFit.contain,
          errorBuilder: (context, error, stackTrace) => const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Text('图片加载失败'),
          ),
        );
      },
      styleSheet: MarkdownStyleSheet.fromTheme(theme).copyWith(
        p: bodyStyle,
        h1: theme.textTheme.headlineMedium,
        h2: theme.textTheme.titleLarge,
        h3: theme.textTheme.titleMedium,
        code: const TextStyle(
          color: AppColors.ink,
          backgroundColor: AppColors.wash,
          fontFamily: 'monospace',
          fontSize: 13,
        ),
        codeblockPadding: EdgeInsets.zero,
        codeblockDecoration: const BoxDecoration(),
        blockquoteDecoration: const BoxDecoration(
          color: AppColors.wash,
          border: Border(
            left: BorderSide(color: AppColors.accent, width: 3),
          ),
        ),
        tableBorder: TableBorder.all(color: AppColors.line),
      ),
    );
  }

  static String _sanitizeImages(String value) {
    return value.replaceAllMapped(
      RegExp(r'!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)'),
      (match) {
        final alt = match.group(1) ?? '图片';
        final source = match.group(2) ?? '';
        if (source.startsWith('asset://')) {
          final assetId = source.substring('asset://'.length);
          if (RegExp(
            r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
          ).hasMatch(assetId)) {
            return '![$alt](${ApiConfig.baseUrl}/assets/$assetId/content)';
          }
        }
        if (source.startsWith('${ApiConfig.baseUrl}/assets/')) {
          return match.group(0) ?? '';
        }
        return '[$alt（外部图片已隐藏）]';
      },
    );
  }
}

class _CopyableCodeBlock extends StatefulWidget {
  const _CopyableCodeBlock({
    super.key,
    required this.buttonKey,
    required this.language,
    required this.code,
    required this.copyEnabled,
  });

  final Key buttonKey;
  final String language;
  final String code;
  final bool copyEnabled;

  @override
  State<_CopyableCodeBlock> createState() => _CopyableCodeBlockState();
}

class _CopyableCodeBlockState extends State<_CopyableCodeBlock> {
  Timer? _copiedTimer;
  bool _copied = false;

  @override
  void didUpdateWidget(covariant _CopyableCodeBlock oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.code != widget.code && _copied) {
      _copiedTimer?.cancel();
      _copied = false;
    }
  }

  @override
  void dispose() {
    _copiedTimer?.cancel();
    super.dispose();
  }

  Future<void> _copy() async {
    _copiedTimer?.cancel();
    setState(() => _copied = true);
    _copiedTimer = Timer(const Duration(milliseconds: 1600), () {
      if (mounted) setState(() => _copied = false);
    });
    try {
      await Clipboard.setData(ClipboardData(text: widget.code));
    } catch (_) {
      if (!mounted) return;
      _copiedTimer?.cancel();
      setState(() => _copied = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final displayLanguage = _languageLabel(widget.language);
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.symmetric(vertical: 8),
      decoration: BoxDecoration(
        color: const Color(0xFFF8FAF9),
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(8),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            height: 40,
            padding: const EdgeInsets.only(left: 12, right: 4),
            decoration: const BoxDecoration(
              color: Color(0xFFF1F4F3),
              border: Border(
                bottom: BorderSide(color: AppColors.line),
              ),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    displayLanguage,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                TextButton.icon(
                  key: widget.buttonKey,
                  onPressed: widget.copyEnabled ? _copy : null,
                  style: TextButton.styleFrom(
                    minimumSize: const Size(72, 36),
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  icon: Icon(
                    widget.copyEnabled
                        ? (_copied
                            ? Icons.check_rounded
                            : Icons.copy_all_outlined)
                        : Icons.more_horiz_rounded,
                    size: 15,
                  ),
                  label: Text(
                    widget.copyEnabled ? (_copied ? '已复制' : '复制') : '生成中',
                    style: const TextStyle(fontSize: 11),
                  ),
                ),
              ],
            ),
          ),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.all(12),
            child: SelectableText(
              widget.code,
              style: const TextStyle(
                color: AppColors.ink,
                fontFamily: 'monospace',
                fontSize: 13,
                height: 1.5,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

abstract class _OutputSegment {
  const _OutputSegment();
}

class _MarkdownSegment extends _OutputSegment {
  const _MarkdownSegment(this.markdown);

  final String markdown;
}

class _FencedCodeBlock extends _OutputSegment {
  const _FencedCodeBlock({
    required this.language,
    required this.code,
    required this.closed,
  });

  final String language;
  final String code;
  final bool closed;
}

List<_OutputSegment> _parseOutputSegments(String markdown) {
  final lines = markdown.split('\n');
  final segments = <_OutputSegment>[];
  final markdownLines = <String>[];
  var index = 0;

  void flushMarkdown() {
    if (markdownLines.isEmpty) return;
    segments.add(_MarkdownSegment(markdownLines.join('\n')));
    markdownLines.clear();
  }

  while (index < lines.length) {
    final opening = RegExp(r'^\s*(`{3,}|~{3,})(.*)$').firstMatch(lines[index]);
    if (opening == null) {
      markdownLines.add(lines[index]);
      index++;
      continue;
    }

    flushMarkdown();
    final marker = opening.group(1)!;
    final markerCharacter = marker[0];
    final info = (opening.group(2) ?? '').trim();
    final language =
        info.isEmpty ? '' : info.split(RegExp(r'\s+')).first.trim();
    final codeLines = <String>[];
    var closed = false;

    index++;
    while (index < lines.length) {
      final line = lines[index].trim();
      if (_isClosingFence(line, markerCharacter, marker.length)) {
        closed = true;
        index++;
        break;
      }
      codeLines.add(lines[index]);
      index++;
    }

    segments.add(
      _FencedCodeBlock(
        language: language,
        code: codeLines.join('\n'),
        closed: closed,
      ),
    );
  }

  flushMarkdown();
  return segments;
}

bool _isClosingFence(String line, String character, int minimumLength) {
  if (line.length < minimumLength) return false;
  for (final codeUnit in line.codeUnits) {
    if (String.fromCharCode(codeUnit) != character) return false;
  }
  return true;
}

String _languageLabel(String value) {
  final normalized = value.trim().toLowerCase();
  return switch (normalized) {
    '' => '代码',
    'js' || 'javascript' => 'JavaScript',
    'ts' || 'typescript' => 'TypeScript',
    'py' || 'python' => 'Python',
    'sh' || 'shell' || 'bash' => 'Shell',
    'json' => 'JSON',
    'sql' => 'SQL',
    'html' => 'HTML',
    'css' => 'CSS',
    'java' => 'Java',
    'kotlin' => 'Kotlin',
    'dart' => 'Dart',
    'yaml' || 'yml' => 'YAML',
    'xml' => 'XML',
    'md' || 'markdown' => 'Markdown',
    'mermaid' => 'Mermaid',
    _ => value.trim(),
  };
}
