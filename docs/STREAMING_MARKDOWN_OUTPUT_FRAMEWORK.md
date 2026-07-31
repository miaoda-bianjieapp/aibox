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
- Java、Python、JavaScript、TypeScript、Dart、Kotlin、Shell、SQL、JSON、
  HTML/XML、CSS、YAML 和 Markdown 代码语法着色。
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
- 服务端每 15 秒发送一次 `:ping` SSE 注释心跳
- 响应包含 `Cache-Control: no-cache` 和 `X-Accel-Buffering: no`

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

Flutter 将流式过渡和最终成果渲染拆成两个公共组件：

- `StreamingOutputView`：用于通用 `TaskExecutionPage` 和 `DocumentQaPage` 的临时输出。
- `MarkdownOutputView`：用于流结束后的完整 Markdown 和最终 Artifact 成果页。

`RunOutputAccumulator` 先按 `eventId` 和 `sequence` 合并全部 delta，页面只接收当前完整字符串。
`StreamingOutputView` 保存最新目标字符串，字符播放缓冲按固定 4ms 间隔每次推进一个 Unicode
字素；同一期间到达的增量始终先拼成完整目标字符串，不解析单独 delta，也不假设分片是完整
单词、JSON 或 Markdown 结构。
`replace` 内容用于数据分析、文档翻译等进度提示，继续即时替换，不进入节流窗口。

流式 Markdown 使用独立的 32ms 展示节流和稳定块策略：以空行和闭合代码围栏划分块，已完成
块缓存并复用原 Widget，每次只重新解析最后一个活动块。字符播放不会逐字触发 Markdown 解析；
代码围栏闭合时绕过节流立即固化代码卡片。标题、加粗、列表、引用和表格仍在流式阶段近实时
展示。代码卡片保留语言标签、等宽字体、横向滚动、复制和语言级着色。
`activeStreamingMarkdownStrategy` 可切换回全文重渲染，便于实际设备对比和回退。

成功或 `PARTIAL` 到达时先等待前端逐字队列播放完毕，再恢复文本选择并完成最终 Markdown
渲染。成功状态由固定底部操作栏切换为“生成完成”，保留约 300ms 后再通知页面跳转或替换
临时消息；正文内部不插入完成图标或状态槽。`PARTIAL` 使用中性完成态；失败时保留已生成文本
并在顶部展示错误。
准备、排队、取消和错误由页面顶部状态区显示，首个真实 `append` 到达后隐藏普通运行状态，
不再显示“正在思考”提示。

正文统一使用 16px、1.6 行高的系统无衬线字体，不插入光标、完成图标或额外状态槽参与高度
计算。完整增量进入目标缓冲区后，积压不超过 40 字素时按 4ms 单字素推进，积压为
41～100 字素时按 2ms 单字素推进，超过 100 字素时每帧最多推进 4 字素；模型进入终态后每帧
最多推进 8 字素。普通文本在每次显示后通知页面，Markdown 则在每次 32ms 展示刷新或代码
围栏闭合后通知：
页面位于底部时持续跟随，用户主动上滑离开底部后停止跟随，重新滚动到底部后恢复。
页面处于跟随状态时，在内容布局完成后的下一帧直接跳到最新底部，不再等待合并计时器或启动
滚动动画。通用执行页的停止生成操作栏固定在视口底部，不参与正文滚动。

渲染支持 Markdown、代码块、表格和 LaTeX。Mermaid 本期不执行图形渲染，只展示语言标识为
`Mermaid` 的源码代码块，并支持复制源码。最终代码块保留语言标签、等宽字体、横向滚动和复制，
并通过 `highlight` 完成语言级语法着色；未闭合代码围栏从开始输出起就使用与终态一致的代码
卡片、语言栏、内边距和等宽字体，围栏闭合后只启用复制和语法着色，不替换卡片几何结构。
未知语言安全退化为普通等宽源码。仅允许平台 Asset
URL；`asset://<uuid>` 会转换为后端 Asset 内容地址，其他远程图片会被替换为安全提示。

`RunOutputAccumulator` 使用以下规则合并内容：

1. `eventId` 已处理则忽略。
2. `sequence` 不大于当前频道序号则忽略。
3. `append` 追加文本，`replace` 替换全文。
4. 新的 `started` 表示 Provider 重试，清空上一次未完成内容。
5. 快照序号更新时，以后端快照恢复本地内容。

Flutter 在 `RunOutputSnapshot` 中保留本地 `updateType`，用于区分 `append`、`replace` 和
恢复快照。该字段不改变 REST/OpenAPI 契约；REST 快照默认标记为 `snapshot`。

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
- 任意位置拆分的单词、Markdown、代码围栏和 Unicode 内容都能通过完整字符串累计正确恢复。
- 积压不超过 40 时按 4ms 单字素播放，41～100 时按 2ms 单字素播放。
- 活跃积压超过 100 时每帧最多推进 4 字素，终态每帧最多推进 8 字素。
- Markdown 展示最多每 32ms 重建一次，字符播放与 Markdown 解析相互解耦。
- 已完成 Markdown 块复用同一 Widget，后续 delta 只重建最后一个活动块。
- 未闭合代码围栏直接使用正式代码卡片结构；围栏闭合后只切换复制和语法着色，无几何跳变。
- 流式正文不显示光标、完成图标或固定状态槽，输出区末尾不保留额外空白。
- 通用执行页的停止生成操作栏在 Markdown 重排和最终样式切换时位置不变。
- 页面贴底时在布局完成后直接跟随；用户离开底部后停止，重新到底部后恢复。
- 页面不显示“正在思考”文案或底部动态点。
- 点击停止后立即冻结当前可见文本、清空未展示缓冲并忽略后续增量，同时异步请求后端取消。
- 前端逐字队列、最终全文和代码高亮完成后，固定底部操作栏才显示“生成完成”，再进入成果页；文档问答
  随后替换持久化消息。
- `replace` 进度提示即时替换，不逐字播放。
- SSE 空闲期间每 15 秒发送注释心跳，代理缓存被明确禁用。
- 最终代码块、表格、公式和平台 Asset 图片可展示。
- Java、Python 等支持列表内语言具有语法着色；未知语言和 Mermaid 安全显示源码。
- 任意远程图片不会发起加载。
- 后端测试、Flutter 分析、Flutter 测试和 Debug APK 构建通过。
