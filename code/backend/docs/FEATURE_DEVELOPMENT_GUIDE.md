# 单功能开发指南

一个功能是一个前后端纵向任务，永久使用命名空间形式的 `featureCode`，例如 `writing.draft`。开发者不得为功能单独创建任务表、Controller、上传逻辑、轮询逻辑或供应商客户端。

## 后端步骤

1. 在 `contracts/features/<workspace>/<feature>/` 创建 Manifest 和三个 Schema。
2. 通过 Flyway 发布 FeatureDefinition/FeatureVersion；开发阶段状态使用 `INTERNAL`。
3. 为调用模型的功能发布 `feature_model_policy` 和 `feature_model_option`，声明能力、默认 Deployment 和是否允许用户选择。
4. 在 `feature-impl` 对应目录实现 `FeatureHandler`。
5. Handler 只调用 `ModelGateway` 的标准能力，不依赖具体厂商；把 `context.selectedModelCode()` 传入标准请求。
6. 将结果转换为一个或多个 `ArtifactDraft`。
   模型生成的图片、音频和文件使用 `OutputAssetDraft`，平台自动保存并回填 `assetId`。
7. 为参数边界、模型策略、标准结果和异常映射编写单元测试。
8. 通过 Catalog -> Task -> Run -> ModelGateway -> Worker -> Artifact 全链路测试。
9. 支持继续修改的功能应读取 `FeatureExecutionContext.baseArtifact`，返回完整的新版本成果；重试和继续修改默认沿用原 Run 的模型。

## Flutter 步骤

1. 从 `GET /catalog/workspaces` 获取功能列表，不在页面硬编码新增入口。
2. 标准字段交给 DynamicParameterForm；复杂输入才注册自定义 `pageKey`。
3. 使用 FeatureDetail 的 `modelPolicy` 渲染公共模型选择器，只提交 `ModelOption.code`，不暴露 Provider 连接信息。
4. 使用公共 TaskRunController 提交、取消、重试和恢复状态。
5. 标准结果交给 `rendererKey` 对应组件；确有差异才新增 renderer。
6. App 重启或 SSE 断开后，使用 `GET /runs/{id}` 对账。

## 禁止事项

- 在 FeatureHandler 中写供应商 URL、密钥或 SDK 调用。
- 直接更新 TaskRun 状态或写 Artifact 表。
- 在 FeatureHandler 中直接写本地文件或 OSS。
- 把供应商原始响应直接返回 Flutter。
- 新运行覆盖旧 Artifact。
- 用异常文本作为前端业务判断依据。

## 完成定义

- 重复提交同一 Idempotency-Key 不产生第二个 Run。
- 非法参数生成标准失败状态和错误码。
- Worker 重启后任务可恢复，达到最大尝试次数后明确失败。
- 成功 Run 至少生成一个 Artifact，并可从任务历史回看。
- 功能禁用后不可新建任务，但历史仍可读取。
- 日志中没有密钥和完整用户输入。
