import 'package:flutter/material.dart';

import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'task_history_page.dart';

class HistoryPage extends StatelessWidget {
  const HistoryPage({super.key, required this.data});
  final AppDataController data;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('任务历史')),
        body: AnimatedBuilder(
          animation: data,
          builder: (context, _) => RefreshIndicator(
            onRefresh: data.refresh,
            child: data.tasks.isEmpty
                ? ListView(children: const [
                    SizedBox(height: 180),
                    Center(
                        child: Text('还没有任务记录',
                            style: TextStyle(color: AppColors.muted))),
                  ])
                : ListView.separated(
                    padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                    itemCount: data.tasks.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, index) {
                      final task = data.tasks[index];
                      final workspace =
                          data.workspaceForFeature(task.featureCode);
                      return ListTile(
                        contentPadding: const EdgeInsets.symmetric(vertical: 5),
                        title: Text(task.title,
                            maxLines: 1, overflow: TextOverflow.ellipsis),
                        subtitle: Text(workspace?.title ?? task.featureCode),
                        trailing: const Icon(Icons.chevron_right_rounded,
                            color: AppColors.muted),
                        onTap: () =>
                            Navigator.of(context).push(MaterialPageRoute<void>(
                          builder: (context) =>
                              TaskHistoryPage(taskId: task.id, data: data),
                        )),
                      );
                    },
                  ),
          ),
        ),
      );
}
