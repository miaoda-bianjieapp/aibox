import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../pages/document_qa_page.dart';
import '../pages/task_history_page.dart';
import '../state/app_data_controller.dart';
import 'task_sheet.dart';

Future<void> openFeatureExperience(
  BuildContext context, {
  required AppDataController data,
  required WorkspaceDefinition workspace,
  required FeatureEntry entry,
}) async {
  try {
    final detail = await data.api.getFeature(entry.id);
    if (!context.mounted) return;
    if (detail.pageKey == 'document_qa') {
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (context) => DocumentQaPage(
            data: data,
            workspace: workspace,
            feature: detail,
          ),
        ),
      );
      return;
    }
    await showTaskSheet(
      context,
      data: data,
      request: TaskLaunchRequest(workspace: workspace, entry: entry),
    );
  } catch (exception) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(_message(exception))),
    );
  }
}

Future<void> openTaskExperience(
  BuildContext context, {
  required AppDataController data,
  required TaskView task,
}) async {
  final workspace = data.workspaceForFeature(task.featureCode);
  final entry = data.featureByCode(task.featureCode);
  if (workspace == null || entry == null) {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => TaskHistoryPage(taskId: task.id, data: data),
      ),
    );
    return;
  }
  try {
    final detail = await data.api.getFeature(task.featureCode);
    if (!context.mounted) return;
    if (detail.pageKey == 'document_qa') {
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (context) => DocumentQaPage(
            data: data,
            workspace: workspace,
            feature: detail,
            taskId: task.id,
          ),
        ),
      );
      return;
    }
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => TaskHistoryPage(taskId: task.id, data: data),
      ),
    );
  } catch (exception) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(_message(exception))),
    );
  }
}

String _message(Object exception) =>
    exception.toString().replaceFirst('ApiException: ', '');
