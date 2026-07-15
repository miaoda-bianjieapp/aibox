# 元作 AI 后端

Java 17 + Spring Boot 3.5.10 + PostgreSQL 16 的模块化 AI 任务平台。

## 当前范围

已实现：

- 工作台与版本化功能目录。
- Project、Task、TaskRun、Artifact、附件、成果版本链和历史回看。
- PostgreSQL Job 租约、重试、Worker 恢复和 Outbox。
- 创建 Run 的幂等控制。
- REST、SSE 状态通知和统一错误响应。
- ModelGateway、FeatureHandler 与文本、视觉、音频转写、图片生成能力 SPI。
- `writing.draft` 参考功能和可选的本地 Fake Provider。
- Flyway 核心表、索引和目录种子数据。

暂缓：

- 用户登录：当前固定为开发租户和开发用户。
- OSS：当前附件保存在本机 `E:/aibox/data/assets`，后续可替换为 OSS 实现。
- 外部 MQ：当前使用 PostgreSQL Job 表，不依赖 RabbitMQ/Kafka。
- 计费：当前不做额度预占和结算。
- 当前已配置智谱 OpenAI-compatible Provider；本地 Fake Provider 仅在显式开启时注册。

## 模块

- `feature-spi`：功能和模型网关的稳定开发契约。
- `platform-core`：目录、任务、成果、幂等、Outbox 和 Worker。
- `feature-impl`：按单功能组织的业务实现。
- `provider-adapters`：各模型厂商协议适配；当前有本地假实现和 OpenAI-compatible 实现。
- `backend-app`：Spring Boot 启动、REST、SSE 和 Flyway。
- `contracts`：OpenAPI 与功能 JSON Schema。

## 数据库

先在 PostgreSQL 16 创建数据库：

```sql
create database yuanzuo_ai encoding 'UTF8';
```

应用启动时 Flyway 自动创建表并写入工作台及 `writing.draft` 契约。

首次运行先复制配置模板：

```powershell
Copy-Item .\backend-app\src\main\resources\application-template.yml .\backend-app\src\main\resources\application.yml
```

然后在本地 `application.yml` 填写 PostgreSQL 密码、资源目录和管理员提供的 Codex2API 开发 Key。真实 `application.yml` 已被 Git 忽略，不得强制提交。

## 构建和运行

```powershell
cd E:\aibox\code\backend
mvn test
mvn -pl backend-app -am package -DskipTests
java -jar .\backend-app\target\backend-app-0.1.0-SNAPSHOT.jar
```

服务默认监听 `http://localhost:8080`，健康检查为：

```text
GET http://localhost:8080/actuator/health
```

模型管理分为三层：

1. 本地 `application.yml` 的 `yuanzuo.model.providers` 保存 Provider 连接、协议和真实 Key；共享的非敏感配置写入 `application-template.yml`。
2. `model_deployment` 保存可调用的具体模型、能力和厂商模型名；同一能力可以有多个 Deployment。
3. `model_route` 提供后端默认别名路由，`feature_model_policy/option` 决定某功能的默认模型和用户可选范围。

`model_provider.provider_kind` 区分官方直连 `OFFICIAL` 和第三方中转 `RELAY`。同一个上游模型通过
官方接口和中转站调用时，应创建两个独立 Deployment，便于路由、审计和用户选择。

功能代码仍只依赖 `text.default`、`vision.default` 等能力别名。创建 Run 时平台校验并固化
`selectedModelCode`，执行时解析为具体 Deployment 和 Provider。供应商 HTTP 状态、Deployment、请求 ID
和 token 用量由网关标准化并记录到 `provider_invocation`。

复合功能使用 `selectedModels` 按能力同时固化多个 Deployment，例如：

```json
{
  "TEXT_GENERATION": "codex2api-gpt-5-6-text",
  "IMAGE_GENERATION": "aliyun-qwen-image-2-0"
}
```

`selectedModelCode` 暂时保留用于旧客户端兼容。FeatureDetail 同时返回 `modelPolicies`，Flutter 会按能力
显示多个模型选择器。

当前 ModelGateway 标准能力包括文本生成、视觉理解、音频转写、文字转语音、文字/参考图生图和
文字/参考图视频生成。TTS 和二进制媒体输出通过 `OutputAssetDraft` 进入统一 Asset/Artifact 存储。

新增同协议 Provider 时只需增加 YAML Provider 连接和数据库 Provider/Deployment 数据；新增同一 Provider
下的模型只增加 Deployment、Route 或 Feature Policy 数据，无需修改 OpenAI-compatible Java 适配器。
中转站如果需要额外 HTTP Header，可在对应 Provider 连接下配置：

```yaml
headers:
  HTTP-Referer: https://your-app.example
  X-Title: Yuanzuo AI
```

功能详情只向 Flutter 返回模型来源类型和来源名称，不返回 Provider 地址、Header 或 API Key。

## 最小 API 流程

1. `GET /api/v1/catalog/workspaces` 获取目录。
2. `POST /api/v1/tasks` 创建 `writing.draft` 任务。
3. `POST /api/v1/tasks/{taskId}/runs` 创建 Run，请求头必须带 `Idempotency-Key`；通过 `selectedModels` 按能力提交模型选择，旧客户端仍可传 `selectedModelCode`。
4. `GET /api/v1/runs/{runId}/events` 订阅 SSE。
5. `GET /api/v1/runs/{runId}` 轮询兜底并获取 Artifact。
6. `GET /api/v1/tasks/{taskId}` 回看全部 Run 和历史成果。
7. `POST /api/v1/assets` 上传功能输入附件，使用 multipart 的 `file` 字段。

继续修改已有成果时，在创建 Run 的请求中传入 `baseArtifactId`。新结果会自动记录
`parentArtifactId` 和递增的 `versionNumber`，旧结果不会被覆盖。

模型生成图片、音频或文件时，Handler 返回带 `OutputAssetDraft` 的 ArtifactDraft。
平台统一写入本地 Asset 存储，并把生成后的 `assetId` 回填到 Artifact 内容。

创建 Task 示例：

```json
{
  "featureCode": "writing.draft",
  "title": "AI 产品周报"
}
```

创建 Run 示例：

```json
{
  "parameters": {
    "topic": "AI 产品周报",
    "audience": "产品团队",
    "tone": "professional",
    "length": "medium"
  },
  "inputAssetIds": []
}
```

详细接口见 [contracts/openapi.yaml](contracts/openapi.yaml)，成员开发规范见 [docs/FEATURE_DEVELOPMENT_GUIDE.md](docs/FEATURE_DEVELOPMENT_GUIDE.md)。

## 新增功能

新增功能不创建新的 Controller、任务表或供应商客户端。功能成员只需要：

1. 添加独立 Manifest、输入/界面/输出 Schema。
2. 发布新的 FeatureVersion Flyway 数据。
3. 实现一个 FeatureHandler。
4. 通过 ModelGateway 调用文本、视觉、音频转写或图片生成标准能力。
5. 返回标准 ArtifactDraft 并补齐测试。
   二进制输出使用 `ArtifactDrafts.generatedImage/generatedImages/generatedMedia`，不自行写文件。
