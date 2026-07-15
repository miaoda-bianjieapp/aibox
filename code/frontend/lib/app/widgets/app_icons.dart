import 'package:flutter/material.dart';

abstract final class AppIcons {
  static IconData resolve(String key) => switch (key) {
        'edit' => Icons.edit_note_rounded,
        'presentation' => Icons.co_present_rounded,
        'image' => Icons.image_outlined,
        'audio' => Icons.graphic_eq_rounded,
        'video' => Icons.videocam_outlined,
        'document' => Icons.description_outlined,
        _ => Icons.auto_awesome_mosaic_outlined,
      };
}
