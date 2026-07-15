import 'package:flutter/material.dart';

import 'pages/app_shell.dart';
import 'theme/app_theme.dart';

class YuanzuoApp extends StatelessWidget {
  const YuanzuoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '元作 AI',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      home: const AppShell(),
    );
  }
}
