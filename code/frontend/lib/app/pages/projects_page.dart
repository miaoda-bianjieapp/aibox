import 'package:flutter/material.dart';

import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'task_history_page.dart';

class ProjectsPage extends StatelessWidget {
  const ProjectsPage({super.key, required this.data});
  final AppDataController data;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: const Text('我的项目'),
          actions: [
            IconButton(
                onPressed: () => _create(context),
                tooltip: '新建项目',
                icon: const Icon(Icons.add_rounded)),
          ],
        ),
        body: AnimatedBuilder(
          animation: data,
          builder: (context, _) => RefreshIndicator(
            onRefresh: data.refresh,
            child: data.projects.isEmpty
                ? ListView(children: [
                    const SizedBox(height: 170),
                    const Center(
                        child: Text('还没有项目',
                            style: TextStyle(color: AppColors.muted))),
                    Center(
                        child: TextButton.icon(
                            onPressed: () => _create(context),
                            icon: const Icon(Icons.add_rounded),
                            label: const Text('新建项目'))),
                  ])
                : ListView.separated(
                    padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                    itemCount: data.projects.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, index) {
                      final project = data.projects[index];
                      final tasks = data.tasks
                          .where((task) => task.projectId == project.id)
                          .toList();
                      return ExpansionTile(
                        tilePadding: EdgeInsets.zero,
                        childrenPadding:
                            const EdgeInsets.only(left: 12, bottom: 8),
                        title: Text(project.name),
                        subtitle: Text('${tasks.length} 个任务'),
                        children: tasks.isEmpty
                            ? const [
                                ListTile(
                                    title: Text('项目中还没有任务',
                                        style:
                                            TextStyle(color: AppColors.muted)))
                              ]
                            : tasks
                                .map((task) => ListTile(
                                      title: Text(task.title),
                                      trailing: const Icon(
                                          Icons.chevron_right_rounded),
                                      onTap: () => Navigator.of(context)
                                          .push(MaterialPageRoute<void>(
                                        builder: (context) => TaskHistoryPage(
                                            taskId: task.id, data: data),
                                      )),
                                    ))
                                .toList(),
                      );
                    },
                  ),
          ),
        ),
      );

  Future<void> _create(BuildContext context) async {
    final draft = await showDialog<_ProjectDraft>(
      context: context,
      builder: (context) => const _CreateProjectDialog(),
    );
    if (draft == null) return;
    await data.createProject(draft.name, draft.description);
  }
}

class _ProjectDraft {
  const _ProjectDraft(this.name, this.description);

  final String name;
  final String description;
}

class _CreateProjectDialog extends StatefulWidget {
  const _CreateProjectDialog();

  @override
  State<_CreateProjectDialog> createState() => _CreateProjectDialogState();
}

class _CreateProjectDialogState extends State<_CreateProjectDialog> {
  final TextEditingController _name = TextEditingController();
  final TextEditingController _description = TextEditingController();
  String? _nameError;

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('新建项目'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(
            controller: _name,
            autofocus: true,
            textInputAction: TextInputAction.next,
            decoration:
                InputDecoration(labelText: '项目名称', errorText: _nameError),
            onChanged: (_) {
              if (_nameError != null) setState(() => _nameError = null);
            },
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _description,
            decoration: const InputDecoration(labelText: '说明（可选）'),
            onSubmitted: (_) => _submit(),
          ),
        ]),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(onPressed: _submit, child: const Text('创建')),
        ],
      );

  void _submit() {
    final name = _name.text.trim();
    if (name.isEmpty) {
      setState(() => _nameError = '请输入项目名称');
      return;
    }
    Navigator.pop(
      context,
      _ProjectDraft(name, _description.text.trim()),
    );
  }
}
