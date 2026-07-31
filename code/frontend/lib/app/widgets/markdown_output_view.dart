import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_markdown_latex/flutter_markdown_latex.dart';
import 'package:highlight/highlight_core.dart' as syntax;
import 'package:highlight/languages/bash.dart' as syntax_bash;
import 'package:highlight/languages/css.dart' as syntax_css;
import 'package:highlight/languages/dart.dart' as syntax_dart;
import 'package:highlight/languages/java.dart' as syntax_java;
import 'package:highlight/languages/javascript.dart' as syntax_javascript;
import 'package:highlight/languages/json.dart' as syntax_json;
import 'package:highlight/languages/kotlin.dart' as syntax_kotlin;
import 'package:highlight/languages/markdown.dart' as syntax_markdown;
import 'package:highlight/languages/python.dart' as syntax_python;
import 'package:highlight/languages/sql.dart' as syntax_sql;
import 'package:highlight/languages/typescript.dart' as syntax_typescript;
import 'package:highlight/languages/xml.dart' as syntax_xml;
import 'package:highlight/languages/yaml.dart' as syntax_yaml;
import 'package:markdown/markdown.dart' as md;

import '../network/api_config.dart';
import '../theme/app_theme.dart';
import 'output_text_style.dart';

final _syntaxHighlighter = syntax.Highlight()
  ..registerLanguage('bash', syntax_bash.bash)
  ..registerLanguage('css', syntax_css.css)
  ..registerLanguage('dart', syntax_dart.dart)
  ..registerLanguage('java', syntax_java.java)
  ..registerLanguage('javascript', syntax_javascript.javascript)
  ..registerLanguage('json', syntax_json.json)
  ..registerLanguage('kotlin', syntax_kotlin.kotlin)
  ..registerLanguage('markdown', syntax_markdown.markdown)
  ..registerLanguage('python', syntax_python.python)
  ..registerLanguage('sql', syntax_sql.sql)
  ..registerLanguage('typescript', syntax_typescript.typescript)
  ..registerLanguage('xml', syntax_xml.xml)
  ..registerLanguage('yaml', syntax_yaml.yaml);

enum MarkdownRenderMode {
  streaming,
  finalOutput,
}

class MarkdownOutputView extends StatelessWidget {
  const MarkdownOutputView({
    super.key,
    required this.markdown,
    this.renderMode = MarkdownRenderMode.finalOutput,
    this.trailing,
  });

  final String markdown;
  final MarkdownRenderMode renderMode;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final streaming = renderMode == MarkdownRenderMode.streaming;
    final content = _sanitizeImages(markdown);
    if (content.trim().isEmpty) {
      if (trailing != null) {
        return Align(
          alignment: Alignment.centerLeft,
          child: trailing,
        );
      }
      return Text(
        streaming ? '正在等待模型返回内容...' : '暂无可展示内容',
        style: Theme.of(context).textTheme.bodyMedium,
      );
    }

    final segments = parseMarkdownOutputSegments(content);
    final children = <Widget>[];
    var codeIndex = 0;
    var trailingConsumed = false;
    for (var index = 0; index < segments.length; index++) {
      final segment = segments[index];
      final isLast = index == segments.length - 1;
      if (segment is MarkdownTextSegment) {
        if (segment.markdown.trim().isNotEmpty) {
          children.add(
            _buildMarkdownBody(
              context,
              segment.markdown,
              selectable: !streaming,
            ),
          );
        }
        continue;
      }

      if (segment is FencedCodeBlockSegment) {
        children.add(
          OutputCodeBlock(
            key: ValueKey('markdown-code-block-$codeIndex'),
            buttonKey: ValueKey('copy-code-block-$codeIndex'),
            codeKey: ValueKey('syntax-highlighted-code-$codeIndex'),
            language: segment.language,
            code: segment.code,
            copyEnabled: segment.closed,
            highlightSyntax: !streaming,
            inProgress: streaming && !segment.closed,
            selectable: !streaming,
            trailing: isLast && !segment.closed ? trailing : null,
          ),
        );
        trailingConsumed = isLast && !segment.closed && trailing != null;
        codeIndex++;
      }
    }
    if (trailing != null && !trailingConsumed) {
      children.add(
        Align(
          alignment: Alignment.centerLeft,
          child: trailing,
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: children,
    );
  }

  Widget _buildMarkdownBody(
    BuildContext context,
    String data, {
    required bool selectable,
  }) {
    final theme = Theme.of(context);
    final bodyStyle = outputBodyTextStyle();

    return MarkdownBody(
      data: data,
      selectable: selectable,
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

class StreamingMarkdownView extends StatefulWidget {
  const StreamingMarkdownView({
    super.key,
    required this.markdown,
  });

  final String markdown;

  @override
  State<StreamingMarkdownView> createState() => _StreamingMarkdownViewState();
}

class _StreamingMarkdownViewState extends State<StreamingMarkdownView> {
  final List<_CachedStreamingMarkdownBlock> _stableBlocks = [];
  String _activeBlock = '';

  @override
  void initState() {
    super.initState();
    _synchronize(widget.markdown);
  }

  @override
  void didUpdateWidget(covariant StreamingMarkdownView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.markdown != widget.markdown) {
      _synchronize(widget.markdown);
    }
  }

  void _synchronize(String markdown) {
    final partition = partitionStreamingMarkdown(markdown);
    var commonPrefix = 0;
    while (commonPrefix < _stableBlocks.length &&
        commonPrefix < partition.stableBlocks.length &&
        _stableBlocks[commonPrefix].source ==
            partition.stableBlocks[commonPrefix]) {
      commonPrefix++;
    }
    if (commonPrefix < _stableBlocks.length) {
      _stableBlocks.removeRange(commonPrefix, _stableBlocks.length);
    }
    for (var index = commonPrefix;
        index < partition.stableBlocks.length;
        index++) {
      final source = partition.stableBlocks[index];
      _stableBlocks.add(
        _CachedStreamingMarkdownBlock(
          source: source,
          widget: Padding(
            key: ValueKey('streaming-markdown-stable-block-$index'),
            padding: const EdgeInsets.only(bottom: 8),
            child: MarkdownOutputView(
              markdown: source,
              renderMode: _isClosedCodeFence(source)
                  ? MarkdownRenderMode.finalOutput
                  : MarkdownRenderMode.streaming,
            ),
          ),
        ),
      );
    }
    _activeBlock = partition.activeBlock;
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ..._stableBlocks.map((block) => block.widget),
        if (_activeBlock.isNotEmpty)
          _StreamingMarkdownActiveBlock(
            key: const ValueKey('streaming-markdown-active-block'),
            markdown: _activeBlock,
          ),
      ],
    );
  }
}

class _StreamingMarkdownActiveBlock extends StatelessWidget {
  const _StreamingMarkdownActiveBlock({
    super.key,
    required this.markdown,
  });

  final String markdown;

  @override
  Widget build(BuildContext context) {
    final segments = parseMarkdownOutputSegments(markdown);
    if (segments.length == 1 && segments.single is FencedCodeBlockSegment) {
      final codeBlock = segments.single as FencedCodeBlockSegment;
      if (!codeBlock.closed) {
        return OutputCodeBlock(
          key: const ValueKey('streaming-code-draft'),
          buttonKey: const ValueKey('streaming-code-draft-action'),
          codeKey: const ValueKey('streaming-code-draft-text'),
          language: codeBlock.language,
          code: codeBlock.code,
          copyEnabled: false,
          highlightSyntax: false,
          inProgress: true,
          selectable: false,
        );
      }
    }
    return MarkdownOutputView(
      markdown: markdown,
      renderMode: MarkdownRenderMode.streaming,
    );
  }
}

class StreamingMarkdownPartition {
  const StreamingMarkdownPartition({
    required this.stableBlocks,
    required this.activeBlock,
  });

  final List<String> stableBlocks;
  final String activeBlock;
}

StreamingMarkdownPartition partitionStreamingMarkdown(String markdown) {
  final stableBlocks = <String>[];
  final currentLines = <String>[];
  var insideFence = false;
  var fenceCharacter = '';
  var fenceLength = 0;

  void commitCurrent() {
    if (currentLines.isEmpty) return;
    stableBlocks.add(currentLines.join('\n'));
    currentLines.clear();
  }

  for (final line in markdown.split('\n')) {
    if (insideFence) {
      currentLines.add(line);
      if (_isClosingFence(line.trim(), fenceCharacter, fenceLength)) {
        insideFence = false;
        fenceCharacter = '';
        fenceLength = 0;
        commitCurrent();
      }
      continue;
    }

    final opening = RegExp(r'^\s*(`{3,}|~{3,})(.*)$').firstMatch(line);
    if (opening != null) {
      commitCurrent();
      final marker = opening.group(1)!;
      fenceCharacter = marker[0];
      fenceLength = marker.length;
      insideFence = true;
      currentLines.add(line);
      continue;
    }

    if (line.trim().isEmpty) {
      commitCurrent();
      continue;
    }
    currentLines.add(line);
  }

  return StreamingMarkdownPartition(
    stableBlocks: List.unmodifiable(stableBlocks),
    activeBlock: currentLines.join('\n'),
  );
}

class _CachedStreamingMarkdownBlock {
  const _CachedStreamingMarkdownBlock({
    required this.source,
    required this.widget,
  });

  final String source;
  final Widget widget;
}

class OutputCodeBlock extends StatefulWidget {
  const OutputCodeBlock({
    super.key,
    required this.buttonKey,
    required this.codeKey,
    required this.language,
    required this.code,
    required this.copyEnabled,
    required this.highlightSyntax,
    required this.inProgress,
    required this.selectable,
    this.trailing,
  });

  final Key buttonKey;
  final Key codeKey;
  final String language;
  final String code;
  final bool copyEnabled;
  final bool highlightSyntax;
  final bool inProgress;
  final bool selectable;
  final Widget? trailing;

  @override
  State<OutputCodeBlock> createState() => _OutputCodeBlockState();
}

class _OutputCodeBlockState extends State<OutputCodeBlock> {
  Timer? _copiedTimer;
  bool _copied = false;

  @override
  void didUpdateWidget(covariant OutputCodeBlock oldWidget) {
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
                    widget.copyEnabled
                        ? (_copied ? '已复制' : '复制')
                        : (widget.inProgress ? '生成中' : '未完成'),
                    style: const TextStyle(fontSize: 11),
                  ),
                ),
              ],
            ),
          ),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.all(12),
            child: _CodeText(
              textKey: widget.codeKey,
              code: widget.code,
              language: widget.language,
              highlightSyntax: widget.highlightSyntax,
              selectable: widget.selectable,
              trailing: widget.trailing,
            ),
          ),
        ],
      ),
    );
  }
}

class _CodeText extends StatelessWidget {
  const _CodeText({
    required this.textKey,
    required this.code,
    required this.language,
    required this.highlightSyntax,
    required this.selectable,
    required this.trailing,
  });

  final Key textKey;
  final String code;
  final String language;
  final bool highlightSyntax;
  final bool selectable;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final span = TextSpan(
      style: const TextStyle(
        color: AppColors.ink,
        fontFamily: 'monospace',
        fontSize: 13,
        height: 1.5,
        letterSpacing: 0,
      ),
      children: [
        if (code.isEmpty)
          const TextSpan(
            text: ' ',
            style: TextStyle(color: Colors.transparent),
          )
        else if (highlightSyntax)
          ..._highlightCode(code, language)
        else
          TextSpan(text: code),
        if (trailing != null)
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: trailing!,
          ),
      ],
    );
    if (selectable) {
      return SelectableText.rich(
        key: textKey,
        span,
      );
    }
    return Text.rich(
      span,
      key: textKey,
    );
  }
}

abstract class MarkdownOutputSegment {
  const MarkdownOutputSegment();
}

class MarkdownTextSegment extends MarkdownOutputSegment {
  const MarkdownTextSegment(this.markdown);

  final String markdown;
}

class FencedCodeBlockSegment extends MarkdownOutputSegment {
  const FencedCodeBlockSegment({
    required this.language,
    required this.code,
    required this.closed,
  });

  final String language;
  final String code;
  final bool closed;
}

List<MarkdownOutputSegment> parseMarkdownOutputSegments(String markdown) {
  final lines = markdown.split('\n');
  final segments = <MarkdownOutputSegment>[];
  final markdownLines = <String>[];
  var index = 0;

  void flushMarkdown() {
    if (markdownLines.isEmpty) return;
    segments.add(MarkdownTextSegment(markdownLines.join('\n')));
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
      FencedCodeBlockSegment(
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

bool _isClosedCodeFence(String markdown) {
  final segments = parseMarkdownOutputSegments(markdown);
  return segments.length == 1 &&
      segments.single is FencedCodeBlockSegment &&
      (segments.single as FencedCodeBlockSegment).closed;
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

List<InlineSpan> _highlightCode(String code, String language) {
  final highlightLanguage = _highlightLanguage(language);
  if (highlightLanguage == null) {
    return [TextSpan(text: code)];
  }
  try {
    final nodes =
        _syntaxHighlighter.parse(code, language: highlightLanguage).nodes;
    if (nodes == null || nodes.isEmpty) {
      return [TextSpan(text: code)];
    }
    return nodes.map(_highlightNode).toList();
  } catch (_) {
    return [TextSpan(text: code)];
  }
}

InlineSpan _highlightNode(syntax.Node node) {
  final children = node.children;
  return TextSpan(
    text: node.value,
    style: _syntaxStyle(node.className),
    children: children?.map(_highlightNode).toList(),
  );
}

TextStyle? _syntaxStyle(String? className) {
  final token = className?.split(RegExp(r'[-\s]')).lastOrNull;
  return switch (token) {
    'keyword' || 'selector-tag' || 'doctag' => const TextStyle(
        color: Color(0xFF8A3B8F),
        fontWeight: FontWeight.w700,
      ),
    'title' ||
    'section' ||
    'name' ||
    'type' ||
    'built_in' =>
      const TextStyle(color: Color(0xFF1769AA)),
    'string' ||
    'quote' ||
    'addition' =>
      const TextStyle(color: Color(0xFF4F772D)),
    'number' || 'literal' => const TextStyle(color: Color(0xFFB45F06)),
    'comment' => const TextStyle(
        color: Color(0xFF748079),
        fontStyle: FontStyle.italic,
      ),
    'meta' ||
    'meta-keyword' ||
    'meta-string' ||
    'template-tag' =>
      const TextStyle(color: Color(0xFFA14B36)),
    'attr' ||
    'attribute' ||
    'variable' ||
    'template-variable' =>
      const TextStyle(color: Color(0xFF00695C)),
    'symbol' ||
    'bullet' ||
    'link' ||
    'deletion' =>
      const TextStyle(color: Color(0xFFA03C3C)),
    'regexp' ||
    'selector-id' ||
    'selector-class' =>
      const TextStyle(color: Color(0xFF006D77)),
    'emphasis' => const TextStyle(fontStyle: FontStyle.italic),
    'strong' => const TextStyle(fontWeight: FontWeight.w700),
    _ => null,
  };
}

String? _highlightLanguage(String value) {
  return switch (value.trim().toLowerCase()) {
    'js' || 'javascript' => 'javascript',
    'ts' || 'typescript' => 'typescript',
    'py' || 'python' => 'python',
    'sh' || 'shell' || 'bash' => 'bash',
    'json' => 'json',
    'sql' => 'sql',
    'html' || 'xml' => 'xml',
    'css' => 'css',
    'java' => 'java',
    'kotlin' => 'kotlin',
    'dart' => 'dart',
    'yaml' || 'yml' => 'yaml',
    'md' || 'markdown' => 'markdown',
    _ => null,
  };
}
