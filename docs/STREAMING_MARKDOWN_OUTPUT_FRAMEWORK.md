# 全平台流式 Markdown 输出框架开发说明

## 1. 目标与范围

本框架为所有文本型 AI 功能提供统一的实时输出能力，不新增独立功能入口，也不改变现有
`Catalog -> Task -> Run -> Worker -> ModelGateway -> Artifact` 主链路。

当前文本与写作功能 `writing.draft`、`writing.rewrite_polish`、`writing.translate` 和
`writing.outline_ideas` 均已实现 `StreamingFeatureHandler`。后续文本功能按同一接口接入；
不支持流式的 Provider 仍自动退化为一次完整输出。

首期支持：

- Markdown 标题、列表、引用、链接和表格。
- 行内代码与代码块。
- 行内及块级 LaTeX 公式。
- 平台 Asset 图片。
- SSE 实时增量、断线重连、事件回放和快照恢复。
- 用户停止后，在已有有效文本时保存 `PARTIAL` Artifact。

首期不支持：

- Mermaid 图形渲染；Mermaid 代码会按源码代码块展示，并支持复制源码。
- 模型返回的任意远程图片。
- 前端执行任意 HTML、JavaScript 或代码。

## 2. 总体链路

1. Flutter 创建 Task 和 Run。
2. Flutter 同时启动 Run 状态轮询和 `/runs/{runId}/events` SSE 订阅。
3. Worker 发现 Handler 实现 `StreamingFeatureHandler` 后创建 Run 级 `FeatureOutputEmitter`。
4. Handler 调用 `ModelGateway.generateTextStream`。
5. Provider 增量返回文本，Emitter 将其写入快照表与事件表，并发布实时 SSE。
6. Flutter 按 `eventId` 和频道 `sequence` 去重、排序并增量渲染。
7. 成功后仍由 Handler 返回完整 `ArtifactDraft`，Artifact 是最终成果的唯一事实来源。
8. SSE 中断时，Flutter 使用 `GET /runs/{runId}/output` 恢复当前快照，并继续轮询 Run。

## 3. 后端公共接口

### `StreamingFeatureHandler`

流式 Handler 使用三参数 `execute`：

```java
FeatureExecutionResult execute(
        FeatureExecutionContext context,
        ModelGateway modelGateway,
        FeatureOutputEmitter outputEmitter
);
```

旧 `FeatureHandler` 无需修改。

### `FeatureOutputEmitter`

每个输出使用稳定频道名。主文本固定使用 `main`：

```java
outputEmitter.start("main", "markdown");
outputEmitter.appendText("main", delta);
outputEmitter.replaceText("main", completeText);
outputEmitter.complete("main");
```

协调器会在 Handler 正常返回后调用 `completeAll()`。Emitter 按约 256 字符或 75ms 合并写入，
避免每个 Token 单独写数据库。

### `ModelGateway.generateTextStream`

支持流式的 Provider 应逐段调用 `TextGenerationListener.onDelta`，并最终返回包含完整文本和
Usage 的 `TextGenerationResponse`。Listener 返回 `false` 时 Provider 应尽快停止读取上游响应。

不支持流式的 Provider 自动调用原 `generateText`，并发送一次完整 delta。

## 4. 数据与事件

### `run_output_stream`

保存每个 Run、每个频道的最新快照：

- `channel`
- `format`
- `content_text`
- `status`
- `last_sequence`

状态为 `STREAMING`、`COMPLETED`、`FAILED` 或 `PARTIAL`。

### `run_output_event`

保存可回放事件。事件包括：

- `started`
- `append`
- `replace`
- `completed`
- `failed`
- `partial`

全局自增 `id` 作为 SSE `id`，频道内 `sequence` 用于内容顺序和去重。事件保留 24 小时，
过期数据在新流启动时清理。

## 5. HTTP 协议

### `GET /api/v1/runs/{runId}/events`

- 响应：`text/event-stream`
- 客户端重连时发送 `Last-Event-ID`
- 输出事件名：`output`
- Run 生命周期事件仍使用 `connected`、`status`、`completed` 和 `failed`

输出事件示例：

```json
{
  "eventId": 128,
  "channel": "main",
  "sequence": 7,
  "type": "append",
  "delta": "新增文本",
  "status": "STREAMING"
}
```

### `GET /api/v1/runs/{runId}/output`

返回当前用户有权访问的全部频道快照，供 App 重启、SSE 断线和轮询兜底使用。

## 6. Flutter 公共渲染

`MarkdownOutputView` 同时用于：

- 二级 Task 执行页面中的实时预览。
- 最终富文本 Artifact 成果页。

二级执行页面在用户处于底部时自动跟随新增内容。检测到用户主动滚动后暂停自动跟随，
防止阅读位置被新 Token 抢走；用户重新滚动到底部后自动恢复跟随。纯文本流使用
`SelectableText` 保留原始换行，Markdown 流继续使用公共渲染器。

渲染支持 Markdown、代码块、表格和 LaTeX。Mermaid 本期不执行图形渲染，只展示语言标识为
`Mermaid` 的源码代码块，并支持复制源码。仅允许平台 Asset URL；`asset://<uuid>` 会转换为
后端 Asset 内容地址，其他远程图片会被替换为安全提示。

`RunOutputAccumulator` 使用以下规则合并内容：

1. `eventId` 已处理则忽略。
2. `sequence` 不大于当前频道序号则忽略。
3. `append` 追加文本，`replace` 替换全文。
4. 新的 `started` 表示 Provider 重试，清空上一次未完成内容。
5. 快照序号更新时，以后端快照恢复本地内容。

## 7. 生命周期规则

- 成功：输出流为 `COMPLETED`，Run 为 `SUCCEEDED`，保存完整 Artifact。
- Provider 最终失败：输出流为 `FAILED`，Run 为 `FAILED`。
- Provider 可重试失败：同一 Run 再次启动输出时清空前一次未完成文本，避免拼接重复内容。
- 用户停止且已有文本：输出流为 `PARTIAL`，Run 最终为 `PARTIAL`，保存当前 Artifact。
- 用户停止且没有文本：Run 保持 `CANCELLED`，不创建空 Artifact。

## 8. 安全约束

- API Key、Provider URL 和认证头不进入 Flutter。
- SSE 和快照接口必须执行 Run 所有权检查。
- 不在日志记录完整 Prompt、完整输出或上游原始错误体。
- Markdown 不执行 HTML 或脚本。
- 外部图片默认禁用，防止跟踪、泄露和不受控流量。

## 9. 新功能接入步骤

1. 确认成果是可增量展示的文本或 Markdown。
2. 将 Handler 改为实现 `StreamingFeatureHandler`。
3. 在调用模型前启动 `main` 频道。
4. 使用 `generateTextStream` 将 delta 写入 Emitter。
5. Handler 最终仍返回完整标准 `ArtifactDraft`。
6. 为流式增量、Provider 退化、取消和 Artifact 内容一致性补测试。
7. 不新增功能专属 SSE、轮询接口、Controller 或输出表。

## 10. 验收标准

- 旧功能和旧 Provider 不修改即可运行。
- `writing.draft`、`writing.rewrite_polish`、`writing.translate` 和
  `writing.outline_ideas` 可在二级 Task 执行页面实时显示输出。
- 页面处于底部时跟随新增内容；用户滚动离开后停止跟随，重新到底部后恢复。
- SSE 断开后可携带 `Last-Event-ID` 重连，重复事件不会重复展示。
- 快照恢复后内容与服务器一致。
- Provider 重试不拼接旧的半截输出。
- 最终成果页与实时预览使用同一 Markdown 渲染器。
- 代码块、表格、公式和平台 Asset 图片可展示。
- 任意远程图片不会发起加载。
- 后端测试、Flutter 分析、Flutter 测试和 Debug APK 构建通过。
