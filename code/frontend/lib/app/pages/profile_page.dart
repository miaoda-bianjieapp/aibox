import 'package:flutter/material.dart';

import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/brand_header.dart';
import 'asset_library_page.dart';
import 'history_page.dart';
import 'projects_page.dart';

class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key, required this.data});

  final AppDataController data;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      bottom: false,
      child: AnimatedBuilder(
        animation: data,
        builder: (context, _) {
          final account = data.account;
          return RefreshIndicator(
            onRefresh: data.refresh,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
              children: [
                BrandHeader(
                  title: '我的',
                  actionIcon: Icons.refresh_rounded,
                  actionTooltip: '刷新',
                  onAction: data.refresh,
                ),
                if (data.loading) ...[
                  const SizedBox(height: 16),
                  const LinearProgressIndicator(minHeight: 2),
                ],
                const SizedBox(height: 26),
                Row(children: [
                  Container(
                    width: 54,
                    height: 54,
                    alignment: Alignment.center,
                    decoration: const BoxDecoration(
                        color: AppColors.ink, shape: BoxShape.circle),
                    child: const Icon(Icons.person_outline_rounded,
                        color: Colors.white),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(account?.displayName ?? '正在读取账户',
                              style: Theme.of(context).textTheme.titleLarge),
                          const SizedBox(height: 4),
                          Text(
                            account?.accountMode == 'DEVELOPMENT'
                                ? '开发模式 · 数据保存在本机后端'
                                : account?.accountMode ?? '',
                            style: const TextStyle(
                                color: AppColors.muted, fontSize: 12),
                          ),
                        ]),
                  ),
                ]),
                const SizedBox(height: 24),
                _StatsBand(
                  projectCount: account?.projectCount ?? 0,
                  taskCount: account?.taskCount ?? 0,
                  artifactCount: account?.artifactCount ?? 0,
                ),
                const SizedBox(height: 28),
                const Text('内容与资产',
                    style: TextStyle(
                        color: AppColors.muted,
                        fontSize: 13,
                        fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                _MenuRow(
                  icon: Icons.folder_outlined,
                  label: '我的项目',
                  value: '${account?.projectCount ?? 0}',
                  onTap: () => _open(context, ProjectsPage(data: data)),
                ),
                _MenuRow(
                  icon: Icons.history_rounded,
                  label: '任务历史',
                  value: '${account?.taskCount ?? 0}',
                  onTap: () => _open(context, HistoryPage(data: data)),
                ),
                _MenuRow(
                  icon: Icons.attach_file_rounded,
                  label: '附件库',
                  value: '${account?.assetCount ?? 0}',
                  onTap: () => _open(context, AssetLibraryPage(data: data)),
                ),
                const SizedBox(height: 24),
                Container(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  decoration: const BoxDecoration(
                      border: Border.symmetric(
                          horizontal: BorderSide(color: AppColors.line))),
                  child: Row(children: [
                    const Icon(Icons.storage_outlined,
                        color: AppColors.muted, size: 20),
                    const SizedBox(width: 12),
                    const Expanded(child: Text('本地附件占用')),
                    Text(_formatBytes(account?.assetBytes ?? 0),
                        style: const TextStyle(color: AppColors.muted)),
                  ]),
                ),
                if (data.error != null) ...[
                  const SizedBox(height: 20),
                  Text(data.error!,
                      style: const TextStyle(
                          color: Color(0xFFB33A32), fontSize: 12)),
                ],
              ],
            ),
          );
        },
      ),
    );
  }

  void _open(BuildContext context, Widget page) {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));
  }
}

class _StatsBand extends StatelessWidget {
  const _StatsBand(
      {required this.projectCount,
      required this.taskCount,
      required this.artifactCount});
  final int projectCount;
  final int taskCount;
  final int artifactCount;
  @override
  Widget build(BuildContext context) {
    final items = [
      (value: projectCount, label: '项目'),
      (value: taskCount, label: '任务'),
      (value: artifactCount, label: '成果')
    ];
    return Container(
      decoration: const BoxDecoration(
          border:
              Border.symmetric(horizontal: BorderSide(color: AppColors.line))),
      child: Row(
          children: List.generate(items.length, (index) {
        final item = items[index];
        return Expanded(
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 16),
            decoration: BoxDecoration(
                border: index == 0
                    ? null
                    : const Border(left: BorderSide(color: AppColors.line))),
            child: Column(children: [
              Text('${item.value}',
                  style: const TextStyle(
                      fontSize: 18, fontWeight: FontWeight.w800)),
              const SizedBox(height: 3),
              Text(item.label,
                  style: const TextStyle(color: AppColors.muted, fontSize: 11)),
            ]),
          ),
        );
      })),
    );
  }
}

class _MenuRow extends StatelessWidget {
  const _MenuRow(
      {required this.icon,
      required this.label,
      required this.value,
      required this.onTap});
  final IconData icon;
  final String label;
  final String value;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 15),
            decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: AppColors.line))),
            child: Row(children: [
              Icon(icon, color: AppColors.muted, size: 20),
              const SizedBox(width: 12),
              Expanded(child: Text(label)),
              Text(value,
                  style: const TextStyle(color: AppColors.muted, fontSize: 12)),
              const SizedBox(width: 5),
              const Icon(Icons.chevron_right_rounded,
                  color: AppColors.muted, size: 20),
            ]),
          ),
        ),
      );
}

String _formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
}
