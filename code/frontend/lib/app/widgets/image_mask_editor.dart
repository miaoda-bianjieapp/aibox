import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../theme/app_theme.dart';

Future<Uint8List?> showImageMaskEditor(
  BuildContext context, {
  required AssetView sourceAsset,
  required BackendApi api,
}) {
  return Navigator.of(context).push<Uint8List>(MaterialPageRoute(
    fullscreenDialog: true,
    builder: (context) =>
        ImageMaskEditorPage(sourceAsset: sourceAsset, api: api),
  ));
}

class ImageMaskEditorPage extends StatefulWidget {
  const ImageMaskEditorPage({
    super.key,
    required this.sourceAsset,
    required this.api,
  });

  final AssetView sourceAsset;
  final BackendApi api;

  @override
  State<ImageMaskEditorPage> createState() => _ImageMaskEditorPageState();
}

enum _EditorTool { view, brush, eraser }

class _ImageMaskEditorPageState extends State<ImageMaskEditorPage> {
  final TransformationController _transformationController =
      TransformationController();
  final List<_MaskStroke> _strokes = [];
  final List<_MaskStroke> _redo = [];
  ui.Image? _image;
  _MaskStroke? _activeStroke;
  _EditorTool _tool = _EditorTool.brush;
  double _brushSize = 64;
  String? _error;
  bool _loading = true;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _loadImage();
  }

  @override
  void dispose() {
    _transformationController.dispose();
    _image?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_saving,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('涂抹编辑区域'),
          actions: [
            IconButton(
              onPressed: _loading || _saving ? null : _resetView,
              tooltip: '重置缩放',
              icon: const Icon(Icons.center_focus_strong_outlined),
            ),
            IconButton(
              onPressed: _strokes.isEmpty || _saving ? null : _undo,
              tooltip: '撤销',
              icon: const Icon(Icons.undo_rounded),
            ),
            IconButton(
              onPressed: _redo.isEmpty || _saving ? null : _redoStroke,
              tooltip: '重做',
              icon: const Icon(Icons.redo_rounded),
            ),
            IconButton(
              onPressed: _strokes.isEmpty || _saving ? null : _clear,
              tooltip: '清空选区',
              icon: const Icon(Icons.delete_outline_rounded),
            ),
          ],
        ),
        body: SafeArea(
          child: Column(children: [
            Expanded(child: _buildCanvas()),
            _buildControls(),
          ]),
        ),
      ),
    );
  }

  Widget _buildCanvas() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_image == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.broken_image_outlined, size: 38),
            const SizedBox(height: 10),
            Text(
              _error ?? '原图无法读取',
              textAlign: TextAlign.center,
            ),
          ]),
        ),
      );
    }
    final image = _image!;
    return LayoutBuilder(builder: (context, constraints) {
      final available = Size(
        constraints.maxWidth,
        constraints.maxHeight,
      );
      final canvasSize = applyBoxFit(
        BoxFit.cover,
        Size(image.width.toDouble(), image.height.toDouble()),
        available,
      ).destination;
      return ColoredBox(
        color: Colors.white,
        child: InteractiveViewer(
          transformationController: _transformationController,
          alignment: Alignment.center,
          constrained: false,
          minScale: 1,
          maxScale: 6,
          panEnabled: _tool == _EditorTool.view,
          scaleEnabled: _tool == _EditorTool.view,
          child: SizedBox(
            width: canvasSize.width,
            height: canvasSize.height,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onPanStart: _tool == _EditorTool.view
                  ? null
                  : (details) => _startStroke(details, canvasSize),
              onPanUpdate: _tool == _EditorTool.view
                  ? null
                  : (details) => _updateStroke(details, canvasSize),
              onPanEnd: _tool == _EditorTool.view ? null : _finishStroke,
              child: Stack(fit: StackFit.expand, children: [
                RawImage(image: image, fit: BoxFit.fill),
                CustomPaint(
                  painter: _MaskOverlayPainter(
                    strokes: _activeStroke == null
                        ? _strokes
                        : [..._strokes, _activeStroke!],
                  ),
                ),
              ]),
            ),
          ),
        ),
      );
    });
  }

  Widget _buildControls() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        SizedBox(
          width: double.infinity,
          child: SegmentedButton<_EditorTool>(
            segments: const [
              ButtonSegment(
                value: _EditorTool.view,
                icon: Icon(Icons.pan_tool_alt_outlined, size: 18),
                label: Text('查看'),
              ),
              ButtonSegment(
                value: _EditorTool.brush,
                icon: Icon(Icons.brush_outlined, size: 18),
                label: Text('画笔'),
              ),
              ButtonSegment(
                value: _EditorTool.eraser,
                icon: Icon(Icons.auto_fix_normal_outlined, size: 18),
                label: Text('橡皮擦'),
              ),
            ],
            selected: {_tool},
            showSelectedIcon: false,
            onSelectionChanged: _saving
                ? null
                : (selection) => setState(() => _tool = selection.first),
            style: ButtonStyle(
              shape: WidgetStatePropertyAll(RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
              )),
            ),
          ),
        ),
        if (_tool != _EditorTool.view) ...[
          const SizedBox(height: 10),
          Row(children: [
            Text(
              _tool == _EditorTool.eraser ? '橡皮擦粗细' : '画笔粗细',
              style: const TextStyle(
                color: AppColors.muted,
                fontSize: 12,
                fontWeight: FontWeight.w600,
              ),
            ),
            const Spacer(),
            Text(
              '${_brushSize.round()} px',
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
          ]),
          const SizedBox(height: 2),
          Row(children: [
            const Icon(Icons.circle, size: 9, color: AppColors.muted),
            Expanded(
              child: Slider(
                min: 8,
                max: _maxBrushSize,
                value: _brushSize.clamp(8, _maxBrushSize).toDouble(),
                onChanged: _saving
                    ? null
                    : (value) => setState(() => _brushSize = value),
              ),
            ),
            const Icon(Icons.circle, size: 22, color: AppColors.muted),
          ]),
        ],
        if (_error != null && _image != null) ...[
          const SizedBox(height: 8),
          Text(
            _error!,
            style: const TextStyle(color: Color(0xFFB33A32), fontSize: 12),
          ),
        ],
        const SizedBox(height: 10),
        SizedBox(
          width: double.infinity,
          height: 48,
          child: FilledButton.icon(
            onPressed: _saving || _loading || _image == null ? null : _saveMask,
            icon: _saving
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Icon(Icons.check_rounded),
            label: Text(_saving ? '正在生成蒙版' : '完成涂抹'),
          ),
        ),
      ]),
    );
  }

  double get _maxBrushSize {
    final image = _image;
    if (image == null) return 256;
    return (image.width < image.height ? image.width : image.height)
        .toDouble()
        .clamp(32, 256)
        .toDouble();
  }

  Future<void> _loadImage() async {
    try {
      final bytes =
          await widget.api.downloadAssetContent(widget.sourceAsset.id);
      final codec = await ui.instantiateImageCodec(bytes);
      final frame = await codec.getNextFrame();
      codec.dispose();
      if (!mounted) {
        frame.image.dispose();
        return;
      }
      setState(() {
        _image = frame.image;
        _brushSize = _maxBrushSize.clamp(8, 64).toDouble();
        _loading = false;
      });
    } catch (exception) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = '$exception';
        });
      }
    }
  }

  void _startStroke(DragStartDetails details, Size canvasSize) {
    final image = _image;
    if (image == null) return;
    setState(() {
      _error = null;
      _activeStroke = _MaskStroke(
        erase: _tool == _EditorTool.eraser,
        widthFraction: _brushSize /
            (image.width < image.height ? image.width : image.height),
        points: [_normalized(details.localPosition, canvasSize)],
      );
    });
  }

  void _updateStroke(DragUpdateDetails details, Size canvasSize) {
    final stroke = _activeStroke;
    if (stroke == null) return;
    setState(() =>
        stroke.points.add(_normalized(details.localPosition, canvasSize)));
  }

  void _finishStroke(DragEndDetails details) {
    final stroke = _activeStroke;
    if (stroke == null) return;
    setState(() {
      _strokes.add(stroke);
      _activeStroke = null;
      _redo.clear();
    });
  }

  Offset _normalized(Offset localPosition, Size canvasSize) {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) {
      return Offset.zero;
    }
    return Offset(
      (localPosition.dx / canvasSize.width).clamp(0, 1).toDouble(),
      (localPosition.dy / canvasSize.height).clamp(0, 1).toDouble(),
    );
  }

  void _undo() {
    setState(() => _redo.add(_strokes.removeLast()));
  }

  void _redoStroke() {
    setState(() => _strokes.add(_redo.removeLast()));
  }

  void _clear() {
    setState(() {
      _strokes.clear();
      _redo.clear();
      _activeStroke = null;
      _error = null;
    });
  }

  void _resetView() {
    _transformationController.value = Matrix4.identity();
  }

  Future<void> _saveMask() async {
    if (!_strokes.any((stroke) => !stroke.erase)) {
      setState(() => _error = '请先使用画笔涂抹需要修改的区域');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final image = _image!;
      final recorder = ui.PictureRecorder();
      final canvas = Canvas(recorder);
      final size = Size(image.width.toDouble(), image.height.toDouble());
      canvas.drawRect(
        Offset.zero & size,
        Paint()
          ..color = Colors.white
          ..blendMode = BlendMode.src,
      );
      _paintMaskStrokes(canvas, size, _strokes);
      final maskImage =
          await recorder.endRecording().toImage(image.width, image.height);
      final rgba =
          await maskImage.toByteData(format: ui.ImageByteFormat.rawRgba);
      final hasTransparentPixel = rgba != null && _containsTransparency(rgba);
      if (!hasTransparentPixel) {
        maskImage.dispose();
        if (mounted) {
          setState(() {
            _saving = false;
            _error = '当前涂抹未形成有效编辑区域';
          });
        }
        return;
      }
      final png = await maskImage.toByteData(format: ui.ImageByteFormat.png);
      maskImage.dispose();
      if (png == null) throw StateError('蒙版 PNG 生成失败');
      if (mounted) Navigator.of(context).pop(png.buffer.asUint8List());
    } catch (exception) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = '$exception';
        });
      }
    }
  }

  static bool _containsTransparency(ByteData rgba) {
    for (var index = 3; index < rgba.lengthInBytes; index += 4) {
      if (rgba.getUint8(index) < 255) return true;
    }
    return false;
  }
}

class _MaskStroke {
  _MaskStroke({
    required this.erase,
    required this.widthFraction,
    required this.points,
  });

  final bool erase;
  final double widthFraction;
  final List<Offset> points;
}

class _MaskOverlayPainter extends CustomPainter {
  const _MaskOverlayPainter({required this.strokes});

  final List<_MaskStroke> strokes;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.saveLayer(Offset.zero & size, Paint());
    for (final stroke in strokes) {
      final paint = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke.widthFraction *
            (size.width < size.height ? size.width : size.height)
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round
        ..isAntiAlias = true
        ..color = const Color(0x99E34A43)
        ..blendMode = stroke.erase ? BlendMode.clear : BlendMode.srcOver;
      _drawStroke(canvas, size, stroke, paint);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _MaskOverlayPainter oldDelegate) => true;
}

void _paintMaskStrokes(
  Canvas canvas,
  Size size,
  List<_MaskStroke> strokes,
) {
  for (final stroke in strokes) {
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke.widthFraction *
          (size.width < size.height ? size.width : size.height)
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..isAntiAlias = true
      ..color = Colors.white
      ..blendMode = stroke.erase ? BlendMode.src : BlendMode.clear;
    _drawStroke(canvas, size, stroke, paint);
  }
}

void _drawStroke(
  Canvas canvas,
  Size size,
  _MaskStroke stroke,
  Paint paint,
) {
  if (stroke.points.isEmpty) return;
  Offset point(Offset normalized) =>
      Offset(normalized.dx * size.width, normalized.dy * size.height);
  if (stroke.points.length == 1) {
    final center = point(stroke.points.first);
    final circlePaint = Paint()
      ..color = paint.color
      ..blendMode = paint.blendMode
      ..isAntiAlias = true;
    canvas.drawCircle(center, paint.strokeWidth / 2, circlePaint);
    return;
  }
  final path = Path()
    ..moveTo(point(stroke.points.first).dx, point(stroke.points.first).dy);
  for (final normalized in stroke.points.skip(1)) {
    final next = point(normalized);
    path.lineTo(next.dx, next.dy);
  }
  canvas.drawPath(path, paint);
}
