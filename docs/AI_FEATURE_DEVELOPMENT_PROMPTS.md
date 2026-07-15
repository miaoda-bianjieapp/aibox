# 元作 AI 功能开发提示词

固定远程仓库：`https://github.com/miaoda-bianjieapp/aibox.git`；主分支：`main`。功能开发使用独立个人分支，提示词中的“当前分支”不得填写 `main`。

本文档包含五类可独立使用的 AI 提示词：

1. 功能需求澄清：只分析需求和项目适配方式，不编码。
2. 功能端到端实现：按照确认后的需求完成前后端，并给出联调、数据库检查和验收方法。
3. 新模型接入：根据模型协议、能力和业务范围完成 Provider、Deployment、路由及功能策略配置。
4. 功能完成后推送并提交 PR：检查改动、推送个人分支并创建最简 PR。
5. 查看 PR 与清理分支：解决冲突或检查失败，合并成功后同步主分支并清理个人分支。

使用前，应让 AI 能够读取完整仓库，并提供当前分支、功能描述、相关截图和已确认需求。截图只能作为需求参考，不能代替接口协议、参数规则和验收标准。

统一密钥约定：仓库只提交 `application-template.yml`。新增团队 Provider 时，把不含真实 Key 的公共连接配置和 `CHANGE_ME` 占位符写入模板；开发者再把真实 Key 明文写入本地、已被 Git 忽略的 `application.yml`。不要使用环境变量、`.env` 或启动参数，也不得把真实 Key 提交到 Git 或输出到日志、截图和文档。

统一联调网络约定：Android 真机和开发电脑默认连接同一个 Wi-Fi，不依赖手机热点。后端监听 `0.0.0.0:8080`，Flutter 的 `api_config.dart` 使用电脑真实 WLAN IPv4；不得使用 `localhost`、Clash、VMware 等虚拟网卡地址。模拟器才使用 `10.0.2.2`。

## 第一部分：功能需求澄清提示词

### 需要提供给 AI 的信息

- 功能所属分类，例如“文本与写作”“图片设计”“音频”。
- 功能暂定名称。
- 对功能的自然语言描述。
- 类似产品截图或交互参考。
- 已知模型、接口或文件格式要求。
- 明确不做的范围。

### 可复制提示词

```text
你现在是“元作 AI 单功能需求分析师和架构适配负责人”。本轮只负责理解需求、检查现有项目和形成可执行的功能规格，不得编码、不得创建迁移、不得修改任何文件。只有我明确说“需求确认，可以开始开发”后，才能进入编码阶段。

当前信息：

- 仓库根目录：<本地仓库绝对路径>
- 当前分支：<分支名>
- 功能所属分类：<例如 图片设计>
- 功能暂定名称：<例如 AI 图片生成>
- 功能描述：<开发者描述>
- 类似产品截图：<图片绝对路径或附件>
- 已知模型或外部接口：<没有则写待定>
- 本次明确不做：<没有则写待确认>

请先读取项目，不要根据通用经验直接设计。至少检查：

- BACKEND_DEVELOPMENT_PLAN.md
- code/backend/README.md
- code/backend/docs/FEATURE_DEVELOPMENT_GUIDE.md
- code/backend/contracts/openapi.yaml
- code/backend/contracts/features/writing/draft 下的参考契约
- code/backend/feature-spi 中 FeatureHandler、FeatureExecutionContext、ModelGateway、ArtifactDraft、ArtifactDrafts 和 OutputAssetDraft
- code/backend/feature-impl 中 writing.draft 参考实现
- code/backend/platform-core 中 catalog、task、execution、artifact、asset、model、provider 包
- code/backend/backend-app/src/main/resources/db/migration 下全部 Flyway 迁移
- code/frontend/lib/app/models/feature_models.dart
- code/frontend/lib/app/network/backend_api.dart
- code/frontend/lib/app/widgets/task_sheet.dart
- code/frontend/lib/app/pages/features_page.dart、task_history_page.dart 和结果渲染页面

如果仓库结构已经变化，以当前代码为准，并在结论中指出差异。

分析原则：

1. 分类依据是用户的主要任务对象和主要输出，不是底层调用了哪类模型。先检查现有 workspace 是否合适，避免重复分类。
2. 先搜索现有功能，确认是否已经存在相同或高度重叠的能力。能扩展现有功能时，不新增重复入口。
3. 一个功能使用稳定命名空间 featureCode，例如 image.text_to_image。名称、featureCode 和 workspace 需要明确。
4. 不因为参考截图中存在某个控件就直接照搬。先判断它是否服务于核心工作流。
5. 尽量复用动态 Schema 表单、Task/Run、附件、Worker、ModelGateway、Artifact、版本链、历史页和通用渲染器。
6. 不为单个功能新增 Controller、Task 表、Run 表、上传接口、轮询机制或厂商客户端。
7. 只有动态表单或通用渲染器确实无法表达需求时，才提出自定义 Flutter 页面或 renderer，并解释必要性。
8. 前端不能使用假数据。目录、模型选项、任务、附件、状态和成果必须来自后端。
9. 不确定的模型协议、参数或输出格式必须标记为待确认，不得猜测后当成事实。
10. 图片、音频、视频和文件输出必须考虑 Asset/Artifact 落库、历史回看和后续继续修改。

请从开发者描述和截图中提取并补全以下内容：

A. 产品定位

- 用户要解决的核心问题；
- 典型使用者和使用场景；
- 它与同分类现有功能的边界；
- 最短成功路径和非核心功能。

B. 输入参数

逐项明确：字段名、用户显示名、数据类型、是否必填、默认值、枚举值、长度/数值范围、控件类型、帮助文案和后端校验。

附件还要明确：允许的 MIME 类型、扩展名、数量、单文件大小、总大小、是否支持复用历史附件，以及模型不支持该文件时如何提示。

不要漏掉常见但可能不必要的参数，例如语言、尺寸、比例、数量、风格、时长、清晰度、随机种子、负面提示词。是否需要必须结合功能目标判断，不能为了参数多而添加。

C. 输出与成果

- resultType、rendererKey、Artifact kind 和 MIME type；
- 输出是文本、结构化数据、图片、音频、视频、文件还是多个成果；
- 页面如何展示、下载、复制、分享或继续修改；
- 二进制结果如何变成 OutputAssetDraft 并关联 artifact_asset；
- 多结果的顺序、标题和失败一部分时的行为。

D. 模型能力

- 使用 TEXT_GENERATION、VISION、AUDIO_TRANSCRIPTION、IMAGE_GENERATION、TEXT_TO_SPEECH、VIDEO_GENERATION 中的哪些能力，还是确实需要扩展新的标准能力；
- 每种能力对应的 modelAlias；
- 每种能力的默认 Deployment；
- 每种能力是否允许用户选择模型；
- 每个模型选项的业务展示名与差异说明；
- 模型输入输出与平台标准对象如何映射；
- 是否同步返回、异步任务、回调或轮询。

当前能力边界必须按实际实现理解：IMAGE_GENERATION 同时支持“文字 -> 图片”和“文字 + 参考图片 -> 图片”；TEXT_TO_SPEECH 输出音频字节；VIDEO_GENERATION 支持文字和可选参考图片，并可能需要厂商专属异步提交/轮询适配。一个功能可以配置多个能力，Run 通过 selectedModels 分别固化每种能力的 Deployment。

E. 任务生命周期

- Task 标题如何产生；
- Run 创建、幂等、排队、执行、取消、失败和重试；
- 是否支持基于旧 Artifact 继续修改；
- 修改时复用哪些参数、附件和 selectedModels 能力映射；
- 新成果如何形成 parentArtifactId/versionNumber 版本链；
- 哪些失败允许重试，哪些属于参数错误。

F. 前端交互

- 功能入口在功能 Tab 的哪个 workspace；
- 动态表单字段顺序与 widget；
- 是否能完全复用 task_sheet.dart；
- 加载、上传、提交、执行中、成功、空结果和错误状态；
- 结果是否能复用现有 renderer；
- 手机小屏、长文本、长文件名和多附件情况下的布局。

G. 后端落点

- contracts/features 下新增哪些契约；
- Flyway 需要写入哪些目录、版本和模型策略数据；
- feature-impl 下 Handler 的职责；
- 复用哪些 ModelGateway、Asset 和 Artifact API；
- 是否真的需要扩展 SPI。如需要，说明为什么现有能力不足。

H. 安全和约束

- API Key 不进入前端、不写日志；
- 不记录完整敏感输入；
- 文件类型和大小校验；
- 厂商错误转换为稳定错误码；
- 调用可能产生费用时的确认方式。

如果任何关键内容不清楚，请先提出按优先级排序的问题。问题应具体且能改变实现，例如“每次生成一张还是最多四张”，不要问“还有什么要求”。每轮最多询问 5 个关键问题，已能从代码或截图确认的内容不要重复问我。

当信息足够后，输出《功能需求确认稿》，固定包含：

1. 功能摘要与范围；
2. 用户流程；
3. 输入参数表；
4. 输出和 Artifact/Asset 定义；
5. 模型能力与选择策略；
6. 前端页面及状态；
7. 后端复用链路；
8. 数据库变更范围；
9. 错误、重试、取消和版本规则；
10. 验收标准；
11. 明确不做；
12. 待确认问题；
13. 预计修改文件清单，但不要写代码。

最后明确告诉我：需求是否已经足够进入开发；如果不足，缺少哪几项决定性信息。
```

## 第二部分：功能端到端实现提示词

### 使用前提

只有第一部分已经形成完整需求确认稿，并由开发者确认后使用。将确认稿原文附在提示词末尾，不要只给一个功能名称。

### 可复制提示词

```text
你现在是“元作 AI 单功能端到端开发负责人”。请基于我附带的《功能需求确认稿》完成真实可运行的 Flutter + Java 功能开发

仓库信息：

- 仓库根目录：<绝对路径>
- 当前功能分支：<分支名>
- 功能 featureCode：<确认后的 featureCode>
- 允许使用的 Provider/Deployment：<代码或待配置项>
- 是否允许执行付费模型请求：<否/是；默认否>

先读取当前仓库和需求确认稿，检查是否有其他同事的新改动。工作区可能不是干净的，不得撤销或覆盖不属于本功能的修改。若需求稿与当前代码冲突，先说明冲突和影响；能够按现有架构合理解决时直接继续，只有会改变产品行为的关键冲突才询问我。

必须遵守的架构规则：

1. 复用 Catalog -> Task -> TaskRun -> Job/Worker -> FeatureHandler -> ModelGateway -> Artifact/Asset -> History 完整链路。
2. 不为功能新增独立 Controller、任务状态表、上传接口、历史接口、轮询框架或厂商 HTTP 客户端。
3. FeatureHandler 不得包含厂商 URL、API Key、SDK 初始化、数据库直接写入或本地文件写入。
4. Handler 只能通过 ModelGateway 调用标准能力，通过 ArtifactDraft/ArtifactDrafts/OutputAssetDraft 返回结果。
5. 前端目录、表单、模型选项、任务和结果都来自后端，不使用 Mock 或硬编码假数据。
6. 优先使用 Feature inputSchema/uiSchema 驱动 task_sheet.dart。现有动态表单支持文本、textarea、数字、布尔、枚举、segmented、file、image、audio 和 video。只有需求无法表达时才增加通用控件或功能专用页面。
7. 优先扩展通用 renderer；只有输出交互明显独特时才增加专用 renderer。
8. 继续修改必须创建新 Run 和新 Artifact，保留 parentArtifactId/versionNumber，不覆盖旧成果。
9. 重试和继续修改应沿用原 selectedModels 能力映射；旧 selectedModelCode 只用于兼容。
10. Provider 公共配置写入 application-template.yml 并使用 CHANGE_ME Key 占位符；真实 Key 只写入本地 application.yml，不使用环境变量或 .env；日志、异常、测试数据和最终总结不得包含完整 API Key。
11. 真机联调默认使用电脑和手机所在的同一 Wi-Fi，确认 api_config.dart 指向电脑 WLAN IPv4，并检查 Windows 防火墙和路由器 AP 隔离；不要要求使用手机热点。

Flyway 与多人协作规则：

1. 永远不要修改已经执行或已经合入共享分支的迁移。
2. 新功能使用新的迁移文件。为减少三人并行冲突，优先使用时间版本：VyyyyMMddHHmmss__<feature_code>.sql。
3. 创建迁移前先查看当前最大版本；合并或 rebase 后再次检查版本是否冲突。
4. 如果本机已经执行了后来被重命名的开发期迁移，先说明处理方式；不得直接删除生产或共享数据库的 flyway_schema_history。
5. 功能契约升级要新增 FeatureVersion 并更新 current_version，不回写旧版本定义。

按以下顺序实施：

第一步：契约与目录

- 在 code/backend/contracts/features/<workspace>/<feature>/ 创建或更新 feature.yaml、input-schema.json、ui-schema.json 和 output-schema.json；
- Schema 中定义完整类型、必填项、范围、枚举和 additionalProperties；
- 通过新 Flyway 迁移写入 feature_definition 和 feature_version；
- 开发阶段使用 INTERNAL，确认发布后才改 BETA/PUBLISHED；
- 配置正确 workspace、sort_order、result_type、renderer_key 和 execution_mode。

第二步：模型策略

- 对需要模型的功能按能力配置一条或多条 feature_model_policy；
- 每条 Policy 在 feature_model_option 中只开放该能力允许使用的 Deployment；
- 分别明确每种能力的默认模型和 allow_user_selection；
- Handler 使用稳定 modelAlias，并通过 context.selectedModelCode(ModelCapability.X) 取得各能力的 Deployment；
- 创建 Run 时通过 selectedModels 提交 capability -> deployment code 映射；selectedModelCode 只用于旧客户端兼容；
- model_route 只作为未显式选择 Deployment 时的全局默认路由，不能代替 Feature Policy 白名单；
- 不把 provider code 或 provider model 硬编码在功能代码中。

第三步：后端功能实现

- 在 feature-impl 中按 workspace/feature 独立目录实现 FeatureHandler；
- validate 同时保护必填、长度、数量、枚举、附件类型等服务端边界；
- execute 只编排参数、Prompt/模型请求和标准输出；
- 输入附件使用 context.inputAssetIds；参考图生图时把对应 Asset ID 放入 ImageGenerationRequest.inputAssetIds，不能只把图片名称写进 Prompt；
- 支持修改时读取 context.baseArtifact；
- 图片、语音和视频输出优先使用 ArtifactDrafts.generatedImage/generatedImages/generatedAudio/generatedVideos/generatedMedia 或 OutputAssetDraft，确保二进制内容进入 Asset/Artifact；
- TTS 使用 ModelGateway.synthesizeSpeech，视频生成使用 ModelGateway.generateVideo，不得借用文本或生图接口；
- 视频厂商为异步协议时，在 Provider Adapter 内封装提交、查询和结果下载，FeatureHandler 不处理厂商任务 ID；
- 将厂商响应中的必要追踪信息放入 metadata，但不存密钥和完整敏感内容。

第四步：Flutter

- 确认 FeatureDetail 能直接解析新增契约和 modelPolicies；
- 优先让现有 task_sheet 自动渲染参数、附件和 modelPolicies 多能力模型选择器；
- 如果扩展通用表单控件，不能破坏已有 writing.draft；
- 输出优先交给现有通用结果页；新增 renderer 时处理加载、空、错误、多结果、下载和历史回看；
- 继续修改时带上已有 taskId、baseArtifactId、参数、附件和 selectedModels；
- 不增加只存在于前端的功能目录数据。

第五步：最小联调检查

- 检查 task_run.selected_models_json 是否按 capability 保存本次所有模型选择；selected_model_code 仅作为兼容字段；
- 检查 provider_invocation 是否为每次实际模型调用记录正确的 capability、deployment_code、provider_code 和状态；
- 图片、TTS、视频等二进制输出检查 artifact、artifact_asset、asset 是否形成完整关联，不能只保存厂商临时 URL；
- 继续修改和重试后确认 selectedModels 被沿用，Artifact 版本递增且旧成果未覆盖。

上文中的《功能需求确认稿》是已经确认的需求，必须以它为准

编码完成后尽量减少不必要的测试，不要打apk，我自己来测试功能
```

## 第三部分：新模型接入提示词

官方渠道和中转站必须使用不同提示词。开发者通常只需要提供 Base URL 和 API Key；已知模型能力、模型名或目标 `featureCode` 时可以补充，不知道就由 AI 查询。

### A. 官方渠道接入提示词

```text
你现在是“元作 AI 官方模型接入负责人”。我提供的是模型厂商官方渠道，请查询官方模型能力并接入现有模型网关。

仓库根目录：<绝对路径>
当前分支：<分支名>
base-url：<必填>
api-key：<必填>
希望使用的能力或模型：<可选，例如文本生成、生图、glm-xxx；不知道就留空>
featureCode：<可选；暂不绑定功能就留空>

执行要求：

1. 先检查 application.yml、Flyway 模型表、ModelGateway、ModelProviderClient、RoutingModelGateway 和现有 provider-adapters，复用当前多 Provider/Deployment 架构。
2. 根据 Base URL 识别官方厂商，优先查询官方文档和官方只读模型列表接口。不得要求我提前整理协议、路径、请求响应或参数。
3. 整理该官方渠道可用模型并按文本、视觉、音频转写、生图、TTS、视频生成等能力分类。确认生图是否支持参考图/编辑，视频是否同步或异步；区分稳定模型、预览模型和日期版本，不把所有模型不加筛选地放进产品。
4. 如果我没有指定模型，为目标能力选择少量稳定、通用模型；只有模型选择会明显影响成本或产品行为且无法合理判断时才询问我。
5. 确认协议、鉴权、路径、请求体、响应体和同步/异步方式。OpenAI-compatible 复用现有适配器，厂商专属协议才新增 ModelProviderClient，专属逻辑不得进入 FeatureHandler。
6. 在 application-template.yml 配置不含真实 Key 的官方 Provider，并使用 CHANGE_ME 占位符；真实 API Key 只写入本地 application.yml，不使用环境变量、.env、${...} 或 System.getenv()。
7. 使用新 Flyway 迁移写入 model_provider、model_deployment 和必要路由，provider_kind 必须为 OFFICIAL。每个 Deployment 只声明一种 capability；同一厂商模型同时支持文本和视觉时可建立两个能力不同的 Deployment。已有同一官方 Provider 时复用，不能重复创建。
8. 提供 featureCode 时按所需能力配置一条或多条 Feature Policy/Option，使 Run 通过 selectedModels 固化选择；未提供时只完成全局接入并说明前端尚不可选。
9. 不向前端、日志、测试、文档或最终回答输出完整 Key。未经明确允许，只调用模型列表等只读接口，不执行可能计费的生成请求。
10. 直接完成配置、代码、迁移和构建测试，不要只给方案。

完成后告诉我：官方接口确认依据、发现的模型分类、实际白名单模型、配置与迁移位置、已绑定功能、测试结果及尚未真实调用验证的内容。Key 必须掩码显示。
```

### B. 中转站接入提示词

```text
你现在是“元作 AI 中转站接入负责人”。我提供的是第三方模型中转站，请先查询它宣称可用的模型，再以 RELAY Provider 接入现有模型网关。

仓库根目录：<绝对路径>
当前分支：<分支名>
base-url：<必填>
api-key：<必填>
希望使用的能力或模型：<可选；不知道就留空>
featureCode：<可选；暂不绑定功能就留空>

执行要求：

1. 先检查现有 Provider、Deployment、Route、Feature Policy、模型适配器和 Flutter 模型来源标签，不创建第二套调用框架。
2. 优先使用 Key 查询 `/v1/models`、`/models` 或中转站文档提供的只读模型列表，不执行生成请求。根据结果判断是否 OpenAI-compatible，并确认实际 Base URL 拼接方式。
3. 输出中转站宣称可用的完整模型列表，并按通用文本、视觉、生图、TTS、音频转写、视频、Realtime、Codex/编程专用等分类。确认生图是否支持参考图/编辑、视频是否需要异步轮询；模型列表只代表中转站宣称可用，不能表述成已完成真实调用验证。
4. 不把全部模型自动写入产品。根据目标能力白名单配置少量稳定通用模型，跳过无关、重复、预览、Realtime 和编程专用模型；选择存在重大歧义时再问我。
5. 使用中转站要求的精确 model 名称。即使与官方是同一个上游模型，也必须创建独立 Deployment，保证路由、计费和调用记录可区分。
6. OpenAI-compatible 中转站复用现有适配器及 chat-path、audio-path、image-path、image-edit-path、speech-path、video-path；需要 HTTP-Referer、X-Title 等额外 Header 时使用 Provider headers。视频等协议与通用同步响应不一致时实现专属适配器，不在 Handler 中轮询。
7. 在 application-template.yml 新增不含真实 Key 的中转 Provider，并使用 CHANGE_ME 占位符；真实 Key 只写入本地 application.yml，不使用环境变量、.env、${...} 或 System.getenv()。
8. 使用新 Flyway 迁移写入 provider_kind=RELAY 的 model_provider、模型 Deployment 和必要 Route。不得修改已执行迁移或重复创建已有 Provider。
9. 提供 featureCode 时按每种能力增加 Feature Policy/Option，让 Flutter 显示多个能力选择器和“中转”来源，并通过 selectedModels 提交；未提供时只完成全局接入。官方模型默认路由不要被中转站自动替换，也不要实现未确认的自动故障切换。
10. 不向前端、日志、测试、文档或最终回答输出完整 Key。未经明确允许，不执行文本、生图等可能计费的真实请求。
11. 直接完成配置、代码、迁移和构建测试，不要只给方案。

完成后告诉我：只读接口返回的完整模型分类、实际白名单模型、Provider/Deployment/Route、已绑定功能、构建结果，以及哪些能力仍需付费请求验证。Key 必须掩码显示。
```

## 第四部分：功能完成后推送并提交 PR 提示词

功能已经开发完成，需要 AI 负责检查改动、提交、推送远程个人分支并创建 PR 时使用。

```text
你现在是“元作 AI 功能分支提交助手”。当前功能已经开发完成，我授权你在当前任务范围内执行 Git 提交、推送远程个人分支，并向 main 创建 Pull Request。

仓库根目录：<绝对路径>
功能名称：<功能名称>
个人分支：<例如 feat/audio-recognition；不确定时先从当前仓库确认>

请按以下要求直接执行：

1. 读取 docs/DEVELOPER_GIT_GUIDE.md，并检查当前分支、git status、git diff 和远程仓库。不得直接向 main 提交或推送；如果功能改动还在 main 的本地工作区，先创建符合规范的个人分支再继续。
2. 只提交本功能相关改动，保留并忽略不属于本功能的用户改动。不得提交构建产物、日志、本地数据库、个人 IP、数据库密码、Token 或完整 API Key。
3. 特别检查 backend-app/src/main/resources/application.yml，真实数据库密码和模型 Key 的本地改动不得进入提交。
4. 提交前获取 origin/main 的最新状态，并把最新 main 合入个人分支。普通代码冲突应在理解双方改动后解决；涉及 Flyway 顺序、重复版本号或无法判断的业务冲突时停止并告诉我，不得自行猜测。
5. 使用清晰的中文提交信息提交改动，不修改或覆盖他人的提交，不使用 git push --force。
6. 将当前个人分支推送到 origin。优先使用已有 GitHub 工具或 gh CLI 创建从个人分支到 main 的 PR；如果缺少登录权限，只要求我完成必要登录，然后继续创建，不要让我手工整理 PR 内容。
7. PR 标题简洁说明功能。PR 内容只保留以下必要信息：

功能：<功能名称>
是否修改核心代码：是/否；如果是，写明公共模块名称
是否修改数据库：是/否；如果是，列出 Flyway SQL 文件名

8. 创建完成后返回 PR 链接、分支名和提交摘要。不要合并 PR，也不要删除本地或远程个人分支。
```

## 第五部分：查看 PR、解决失败和清理分支提示词

PR 已提交，需要 AI 查看合并情况、处理冲突，或者在合并成功后清理分支时使用。

```text
你现在是“元作 AI PR 状态与分支清理助手”。请查看指定 PR 的真实状态：失败时处理能够安全解决的问题；确认已经合并成功后，拉取最新 main 并删除远程和本地个人分支。

仓库根目录：<绝对路径>
PR 编号或链接：<PR 编号或链接>
个人分支：<不知道时从 PR 获取>

执行规则：

1. 读取 docs/DEVELOPER_GIT_GUIDE.md，检查当前工作区、远程仓库和 PR 状态。使用 GitHub 工具或 gh CLI 获取 state、mergeable、检查结果、目标分支和个人分支，不根据本地状态猜测 PR 是否已合并。
2. 不要替管理员点击合并，不使用 git push --force，不删除未合并分支，不覆盖本地未提交改动。

根据 PR 状态处理：

A. PR 已合并（MERGED）

- 先确认个人分支没有未推送或未提交的重要改动。
- 切换到 main，执行 fetch 和 pull --ff-only，确保本地 main 为最新版本。
- 删除远程个人分支；如果 GitHub 已自动删除，视为成功，不要报错退出。
- 使用 git branch -d 删除本地个人分支，不使用 -D 强制删除。
- 最后返回最新 main 提交、远程分支和本地分支清理结果。

B. PR 存在合并冲突

- 切换到个人分支，获取最新 origin/main，并把 origin/main 合入个人分支。
- 逐个理解并解决普通代码冲突，保留双方需要的改动。
- 涉及 Flyway SQL 顺序、版本号重复或无法判断的业务取舍时，不自行决定，列出冲突并要求管理员确定顺序。
- 完成必要检查后提交并推送个人分支，原 PR 会自动更新。
- 再次查看 PR 状态并返回结果，不删除分支。

C. PR 检查失败

- 查看失败检查和日志，定位属于本 PR 的真实原因。
- 修复后执行受影响范围内的必要检查，提交并推送到原个人分支。
- 再次查看检查状态并返回结果，不新建 PR、不删除分支。

D. PR 仍在等待管理员，且没有冲突或检查失败

- 只报告当前正在等待合并，不修改代码、不删除分支。

E. PR 已关闭但没有合并（CLOSED）

- 不删除分支、不重新创建 PR，说明关闭状态并询问我是否需要继续处理。
```

## 团队协作补充约定

- 一个 Feature 使用一个独立分支和一个明确负责人，前后端一起交付。
- 需求澄清稿应提交到 Issue、PR 描述或功能目录文档，避免只保留在个人 AI 对话中。
- 合并前更新目标分支，重点检查 Flyway 版本、featureCode、workspace sort_order、deployment code 和 renderer key 冲突。
- 不把本机 `application.yml` 的个人密码和 Key 改动混入功能提交。
- 代码评审优先检查是否绕过公共 Task/Run/Artifact/ModelGateway 链路，以及是否引入前端假数据。
- 模型效果问题与平台链路问题分开记录。先确认请求、Run、Invocation、Artifact 正常，再评价模型输出质量。
