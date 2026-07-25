import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_pdfview/flutter_pdfview.dart';
import 'package:just_audio/just_audio.dart';
import 'package:video_player/video_player.dart';

import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../theme/app_theme.dart';

class AssetPreviewPage extends StatefulWidget {
  const AssetPreviewPage({
    super.key,
    required this.api,
    required this.asset,
    this.initialPage,
    this.initialLine,
    this.endLine,
  });

  final BackendApi api;
  final AssetView asset;
  final int? initialPage;
  final int? initialLine;
  final int? endLine;

  @override
  State<AssetPreviewPage> createState() => _AssetPreviewPageState();
}

class _AssetPreviewPageState extends State<AssetPreviewPage> {
  late final Future<AssetPreviewDescriptor> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.api.getAssetPreview(widget.asset.id);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.asset.name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
      body: widget.asset.available
          ? FutureBuilder<AssetPreviewDescriptor>(
              future: _future,
              builder: (context, snapshot) {
                if (snapshot.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snapshot.hasError) {
                  return _PreviewMessage(
                    icon: Icons.error_outline_rounded,
                    title: '无法预览该文件',
                    message: snapshot.error.toString(),
                  );
                }
                return _PreviewBody(
                  descriptor: snapshot.requireData,
                  api: widget.api,
                  asset: widget.asset,
                  initialPage: widget.initialPage,
                  initialLine: widget.initialLine,
                  endLine: widget.endLine,
                );
              },
            )
          : const _PreviewMessage(
              icon: Icons.delete_outline_rounded,
              title: '原文件已删除',
              message: '任务记录和文件信息仍然保留，需要继续修改时请重新上传文件。',
            ),
    );
  }
}

class _PreviewBody extends StatelessWidget {
  const _PreviewBody({
    required this.descriptor,
    required this.api,
    required this.asset,
    required this.initialPage,
    required this.initialLine,
    required this.endLine,
  });

  final AssetPreviewDescriptor descriptor;
  final BackendApi api;
  final AssetView asset;
  final int? initialPage;
  final int? initialLine;
  final int? endLine;

  @override
  Widget build(BuildContext context) {
    final url = descriptor.contentUrl;
    return switch (descriptor.kind) {
      'IMAGE' when url != null => _ImagePreview(url: url),
      'VIDEO' when url != null => _VideoPreview(url: url),
      'AUDIO' when url != null => _AudioPreview(url: url),
      'PDF' when url != null => _PdfPreview(
          api: api,
          asset: asset,
          initialPage: initialPage,
        ),
      'TEXT' => _TextPreview(
          text: descriptor.text ?? '',
          truncated: descriptor.truncated,
          initialLine: initialLine,
          endLine: endLine,
        ),
      _ => const _PreviewMessage(
          icon: Icons.insert_drive_file_outlined,
          title: '预览数据不可用',
          message: '文件内容暂时无法读取。',
        ),
    };
  }
}

class _PdfPreview extends StatefulWidget {
  const _PdfPreview({
    required this.api,
    required this.asset,
    required this.initialPage,
  });

  final BackendApi api;
  final AssetView asset;
  final int? initialPage;

  @override
  State<_PdfPreview> createState() => _PdfPreviewState();
}

class _PdfPreviewState extends State<_PdfPreview> {
  late final Future<File> _fileFuture;
  File? _file;
  String? _viewerError;

  @override
  void initState() {
    super.initState();
    _fileFuture = widget.api
        .downloadAssetToTemporaryFile(
      widget.asset.id,
      fileName: widget.asset.name,
    )
        .then((file) {
      _file = file;
      if (!mounted) unawaited(_deletePreviewFile(file));
      return file;
    });
  }

  @override
  void dispose() {
    final file = _file;
    if (file != null) unawaited(_deletePreviewFile(file));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<File>(
      future: _fileFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return _PreviewMessage(
            icon: Icons.picture_as_pdf_outlined,
            title: 'PDF 加载失败',
            message: snapshot.error.toString(),
          );
        }
        if (_viewerError != null) {
          return _PreviewMessage(
            icon: Icons.picture_as_pdf_outlined,
            title: 'PDF 无法打开',
            message: _viewerError!,
          );
        }
        return PDFView(
          filePath: snapshot.requireData.path,
          defaultPage: (widget.initialPage ?? 1).clamp(1, 1 << 20) - 1,
          enableSwipe: true,
          swipeHorizontal: false,
          autoSpacing: true,
          pageFling: true,
          onError: (error) {
            if (mounted) setState(() => _viewerError = '$error');
          },
          onPageError: (page, error) {
            if (mounted) {
              final pageLabel = page == null ? 'PDF 页面' : '第 ${page + 1} 页';
              setState(() => _viewerError = '$pageLabel 加载失败：$error');
            }
          },
        );
      },
    );
  }
}

Future<void> _deletePreviewFile(File file) async {
  try {
    final directory = file.parent;
    if (await directory.exists()) {
      await directory.delete(recursive: true);
    }
  } on FileSystemException {
    // Preview cleanup is best effort.
  }
}

class _ImagePreview extends StatelessWidget {
  const _ImagePreview({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    return ColoredBox(
      color: Colors.white,
      child: InteractiveViewer(
        minScale: 0.8,
        maxScale: 6,
        boundaryMargin: const EdgeInsets.all(80),
        child: Center(
          child: Image.network(
            url,
            fit: BoxFit.contain,
            loadingBuilder: (context, child, progress) =>
                progress == null ? child : const CircularProgressIndicator(),
            errorBuilder: (_, __, ___) => const _PreviewMessage(
              icon: Icons.broken_image_outlined,
              title: '图片加载失败',
              message: '请检查后端连接后重试。',
            ),
          ),
        ),
      ),
    );
  }
}

class _VideoPreview extends StatefulWidget {
  const _VideoPreview({required this.url});

  final String url;

  @override
  State<_VideoPreview> createState() => _VideoPreviewState();
}

class _VideoPreviewState extends State<_VideoPreview> {
  late final VideoPlayerController _controller;
  late final Future<void> _initialized;

  @override
  void initState() {
    super.initState();
    _controller = VideoPlayerController.networkUrl(Uri.parse(widget.url));
    _initialized = _controller.initialize();
    _controller.addListener(_refresh);
  }

  @override
  void dispose() {
    _controller
      ..removeListener(_refresh)
      ..dispose();
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<void>(
      future: _initialized,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError || !_controller.value.isInitialized) {
          return const _PreviewMessage(
            icon: Icons.videocam_off_outlined,
            title: '视频加载失败',
            message: '文件可能损坏，或当前设备不支持该编码。',
          );
        }
        final ratio = _controller.value.aspectRatio > 0
            ? _controller.value.aspectRatio
            : 16 / 9;
        return ColoredBox(
          color: Colors.black,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                AspectRatio(
                  aspectRatio: ratio,
                  child: VideoPlayer(_controller),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 12, 18, 18),
                  child: Row(
                    children: [
                      IconButton.filled(
                        onPressed: () => _controller.value.isPlaying
                            ? _controller.pause()
                            : _controller.play(),
                        tooltip: _controller.value.isPlaying ? '暂停' : '播放',
                        icon: Icon(
                          _controller.value.isPlaying
                              ? Icons.pause_rounded
                              : Icons.play_arrow_rounded,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: VideoProgressIndicator(
                          _controller,
                          allowScrubbing: true,
                          colors: const VideoProgressColors(
                            playedColor: AppColors.accent,
                            bufferedColor: Color(0xFF63706C),
                            backgroundColor: Color(0xFF303633),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _AudioPreview extends StatefulWidget {
  const _AudioPreview({required this.url});

  final String url;

  @override
  State<_AudioPreview> createState() => _AudioPreviewState();
}

class _AudioPreviewState extends State<_AudioPreview> {
  final AudioPlayer _player = AudioPlayer();
  late final Future<Duration?> _initialized;

  @override
  void initState() {
    super.initState();
    _initialized = _player.setUrl(widget.url);
  }

  @override
  void dispose() {
    unawaited(_player.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Duration?>(
      future: _initialized,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return const _PreviewMessage(
            icon: Icons.audio_file_outlined,
            title: '音频加载失败',
            message: '文件可能损坏，或当前设备不支持该编码。',
          );
        }
        final duration = snapshot.data ?? Duration.zero;
        return Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 28),
            child: StreamBuilder<Duration>(
              stream: _player.positionStream,
              initialData: Duration.zero,
              builder: (context, positionSnapshot) {
                final position = positionSnapshot.data ?? Duration.zero;
                final maxMillis = duration.inMilliseconds.clamp(1, 1 << 31);
                return Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 76,
                      height: 76,
                      alignment: Alignment.center,
                      decoration: const BoxDecoration(
                        color: AppColors.accentSoft,
                        shape: BoxShape.circle,
                      ),
                      child: StreamBuilder<PlayerState>(
                        stream: _player.playerStateStream,
                        builder: (context, stateSnapshot) {
                          final playing = stateSnapshot.data?.playing == true;
                          return IconButton(
                            onPressed: playing ? _player.pause : _player.play,
                            tooltip: playing ? '暂停' : '播放',
                            iconSize: 38,
                            color: AppColors.accent,
                            icon: Icon(
                              playing
                                  ? Icons.pause_rounded
                                  : Icons.play_arrow_rounded,
                            ),
                          );
                        },
                      ),
                    ),
                    const SizedBox(height: 28),
                    Slider(
                      value: position.inMilliseconds
                          .clamp(0, maxMillis)
                          .toDouble(),
                      max: maxMillis.toDouble(),
                      onChanged: (value) =>
                          _player.seek(Duration(milliseconds: value.round())),
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(_durationLabel(position)),
                        Text(_durationLabel(duration)),
                      ],
                    ),
                  ],
                );
              },
            ),
          ),
        );
      },
    );
  }
}

class _TextPreview extends StatefulWidget {
  const _TextPreview({
    required this.text,
    required this.truncated,
    required this.initialLine,
    required this.endLine,
  });

  final String text;
  final bool truncated;
  final int? initialLine;
  final int? endLine;

  @override
  State<_TextPreview> createState() => _TextPreviewState();
}

class _TextPreviewState extends State<_TextPreview> {
  final ScrollController _scrollController = ScrollController();
  final GlobalKey _citationKey = GlobalKey();
  bool _citationRevealScheduled = false;

  @override
  void didUpdateWidget(covariant _TextPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.text != widget.text ||
        oldWidget.initialLine != widget.initialLine ||
        oldWidget.endLine != widget.endLine) {
      _citationRevealScheduled = false;
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.initialLine != null && widget.text.isNotEmpty) {
      return _buildFullTextWithCitation();
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 32),
      children: [
        if (widget.truncated) _buildTruncatedNotice(),
        SelectableText(
          widget.text.isEmpty ? '文件没有可显示的文本内容。' : widget.text,
          style: _textStyle,
        ),
      ],
    );
  }

  Widget _buildFullTextWithCitation() {
    final lines = widget.text.split(RegExp(r'\r?\n'));
    final citedStart = widget.initialLine!.clamp(1, lines.length).toInt();
    final citedEnd =
        (widget.endLine ?? citedStart).clamp(citedStart, lines.length).toInt();
    final before = lines.take(citedStart - 1).join('\n');
    final cited = lines.sublist(citedStart - 1, citedEnd).join('\n');
    final after = lines.skip(citedEnd).join('\n');
    _scheduleCitationReveal();

    return SingleChildScrollView(
      controller: _scrollController,
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.truncated) _buildTruncatedNotice(),
          if (before.isNotEmpty)
            SelectableText(
              before,
              style: _textStyle,
            ),
          Container(
            key: _citationKey,
            color: AppColors.accentSoft,
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: 58,
                  child: Text(
                    citedStart == citedEnd
                        ? '$citedStart'
                        : '$citedStart-$citedEnd',
                    textAlign: TextAlign.right,
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 11,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: SelectableText(
                    cited,
                    style: _textStyle,
                  ),
                ),
              ],
            ),
          ),
          if (after.isNotEmpty)
            SelectableText(
              after,
              style: _textStyle,
            ),
        ],
      ),
    );
  }

  Widget _buildTruncatedNotice() {
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF8E8),
        borderRadius: BorderRadius.circular(8),
      ),
      child: const Text('文件较大，当前显示前 200 万个字符。'),
    );
  }

  void _scheduleCitationReveal() {
    if (_citationRevealScheduled) return;
    _citationRevealScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final citationContext = _citationKey.currentContext;
      if (citationContext == null) return;
      Scrollable.ensureVisible(
        citationContext,
        alignment: 0.18,
        duration: const Duration(milliseconds: 320),
        curve: Curves.easeOutCubic,
      );
    });
  }

  static const TextStyle _textStyle = TextStyle(fontSize: 14, height: 1.6);
}

class _PreviewMessage extends StatelessWidget {
  const _PreviewMessage({
    required this.icon,
    required this.title,
    required this.message,
  });

  final IconData icon;
  final String title;
  final String message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(36),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 38, color: AppColors.muted),
            const SizedBox(height: 14),
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 7),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.muted),
            ),
          ],
        ),
      ),
    );
  }
}

String _durationLabel(Duration duration) {
  final minutes = duration.inMinutes;
  final seconds = duration.inSeconds.remainder(60);
  return '$minutes:${seconds.toString().padLeft(2, '0')}';
}
