create table if not exists creative_asset (
    id uuid primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    project_id uuid references project(id),
    scope varchar(20) not null,
    asset_type varchar(30) not null,
    name varchar(120) not null,
    description varchar(2000) not null default '',
    personality varchar(1000) not null default '',
    current_primary_asset_id uuid references asset(id),
    current_three_view_asset_id uuid references asset(id),
    approved_primary_asset_id uuid references asset(id),
    approved_three_view_asset_id uuid references asset(id),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ck_creative_asset_scope check (scope in ('PROJECT', 'GLOBAL')),
    constraint ck_creative_asset_type check (
        asset_type in ('CHARACTER', 'SCENE', 'PROP', 'UNCLASSIFIED')
    ),
    constraint ck_creative_asset_scope_project check (
        (scope = 'GLOBAL' and project_id is null)
        or (scope = 'PROJECT' and project_id is not null)
    )
);

create index if not exists idx_creative_asset_owner_updated
    on creative_asset(tenant_id, user_id, updated_at desc)
    where deleted_at is null;

create index if not exists idx_creative_asset_project_type
    on creative_asset(project_id, asset_type, updated_at desc)
    where deleted_at is null;

alter table task_run
    add column if not exists execution_phase varchar(40) not null default 'QUEUED';

alter table provider_invocation
    add column if not exists provider_state_json jsonb not null default '{}'::jsonb,
    add column if not exists submitted_at timestamptz,
    add column if not exists last_polled_at timestamptz,
    add column if not exists downloaded_at timestamptz;

insert into feature_definition (
    id, workspace_id, code, display_name, description, status,
    current_version, result_type, renderer_key, execution_mode,
    sort_order, created_at, updated_at
)
select
    '20000000-0000-0000-0000-000000000050',
    workspace.id,
    'video.generate',
    'AI视频生成',
    '从文字、剧本和可复用资产生成短视频。',
    'INTERNAL',
    1,
    'video',
    'video_generate',
    'ASYNC',
    10,
    now(),
    now()
from workspace
where workspace.code = 'video'
on conflict (code) do update
set display_name = excluded.display_name,
    description = excluded.description,
    status = excluded.status,
    current_version = excluded.current_version,
    result_type = excluded.result_type,
    renderer_key = excluded.renderer_key,
    execution_mode = excluded.execution_mode,
    updated_at = now();

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '30000000-0000-0000-0000-000000000050',
    feature.id,
    1,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["mode","operation"],
      "properties":{
        "mode":{"type":"string","enum":["simple","expert"]},
        "operation":{
          "type":"string",
          "enum":[
            "SIMPLE_GENERATE","BREAKDOWN_SCRIPT","SAVE_STORYBOARD",
            "GENERATE_ASSET_PRIMARY","GENERATE_CHARACTER_THREE_VIEW",
            "GENERATE_VIDEO"
          ]
        },
        "prompt":{"type":"string","maxLength":4000},
        "script":{"type":"string","maxLength":20000},
        "storyboard":{"type":"array","maxItems":20},
        "assetType":{"type":"string","enum":["CHARACTER","SCENE","PROP","UNCLASSIFIED"]},
        "assetName":{"type":"string","maxLength":120},
        "assetDescription":{"type":"string","maxLength":2000},
        "personality":{"type":"string","maxLength":1000},
        "durationSeconds":{"type":"integer","enum":[4,8,12],"default":8},
        "aspectRatio":{"type":"string","enum":["16:9","9:16"],"default":"16:9"},
        "resolution":{"type":"string","enum":["720p","1080p"],"default":"720p"},
        "assetCatalog":{"type":"array","maxItems":100},
        "inputAssetIds":{"type":"array","items":{"type":"string","format":"uuid"},"maxItems":20}
      },
      "additionalProperties":true
    }'::jsonb,
    '{
      "pageKey":"video_generate",
      "order":["mode","prompt","script","storyboard","durationSeconds","aspectRatio","resolution"],
      "widgets":{
        "mode":"segmented",
        "prompt":"textarea",
        "script":"textarea",
        "storyboard":"hidden",
        "durationSeconds":"select",
        "aspectRatio":"select",
        "resolution":"select"
      },
      "modelSelectors":{
        "TEXT_GENERATION":{"label":"分镜文本模型","widget":"dropdown"},
        "IMAGE_GENERATION":{"label":"资产图片模型","widget":"dropdown"},
        "VIDEO_GENERATION":{"label":"视频模型","widget":"dropdown"}
      },
      "feeNotice":"剧本拆分、资产生成和最终视频生成可能分别产生模型费用。每次生成前请确认当前步骤。",
      "submitLabel":"生成视频",
      "revisionSubmitLabel":"生成新版本"
    }'::jsonb,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "properties":{
        "format":{"enum":["video_storyboard","image","video"]},
        "script":{"type":"string"},
        "shots":{"type":"array"},
        "assetId":{"type":"string","format":"uuid"},
        "assetIds":{"type":"array","items":{"type":"string","format":"uuid"}}
      },
      "additionalProperties":true
    }'::jsonb,
    '{
      "modelAliases":{
        "TEXT_GENERATION":"text.video-storyboard",
        "IMAGE_GENERATION":"image.video-asset",
        "VIDEO_GENERATION":"video.default"
      },
      "maxScriptCharacters":20000,
      "maxStoryboardShots":20,
      "maxInputImages":20,
      "characterViewLayout":"FRONT_SIDE_BACK_LABELED"
    }'::jsonb,
    now()
from feature_definition feature
where feature.code = 'video.generate'
on conflict (feature_id, version) do nothing;

insert into model_route (
    id, model_alias, capability, deployment_code, priority, enabled, created_at
)
values
    ('42000000-0000-0000-0000-000000000050',
     'text.video-storyboard', 'TEXT_GENERATION',
     'codex2api-gpt-5-6-text', 10, true, now()),
    ('42000000-0000-0000-0000-000000000051',
     'text.video-storyboard', 'TEXT_GENERATION',
     'codex2api-gpt-5-4-mini-text', 20, true, now()),
    ('42000000-0000-0000-0000-000000000052',
     'text.video-storyboard', 'TEXT_GENERATION',
     'codex2api-grok-4-5-text', 30, true, now()),
    ('42000000-0000-0000-0000-000000000053',
     'image.video-asset', 'IMAGE_GENERATION',
     'codex2api-gpt-image-2-image', 10, true, now()),
    ('42000000-0000-0000-0000-000000000054',
     'image.video-asset', 'IMAGE_GENERATION',
     'aliyun-qwen-image-2-0', 20, true, now())
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = true;

insert into feature_model_policy (
    id, feature_code, capability, default_deployment_code,
    allow_user_selection, created_at, updated_at
)
values
    ('43000000-0000-0000-0000-000000000050',
     'video.generate', 'TEXT_GENERATION',
     'codex2api-gpt-5-6-text', true, now(), now()),
    ('43000000-0000-0000-0000-000000000051',
     'video.generate', 'IMAGE_GENERATION',
     'codex2api-gpt-image-2-image', true, now(), now()),
    ('43000000-0000-0000-0000-000000000052',
     'video.generate', 'VIDEO_GENERATION',
     'codex2api-grok-imagine-video', true, now(), now())
on conflict (feature_code, capability) do update
set default_deployment_code = excluded.default_deployment_code,
    allow_user_selection = excluded.allow_user_selection,
    updated_at = now();

insert into feature_model_option (
    policy_id, deployment_code, display_name, description, sort_order, enabled
)
values
    ('43000000-0000-0000-0000-000000000050',
     'codex2api-gpt-5-6-text', 'GPT-5.6',
     '质量优先，适合长剧本和复杂分镜拆解。', 10, true),
    ('43000000-0000-0000-0000-000000000050',
     'codex2api-gpt-5-4-mini-text', 'GPT-5.4 Mini',
     '速度和成本优先，适合短剧本。', 20, true),
    ('43000000-0000-0000-0000-000000000050',
     'codex2api-grok-4-5-text', 'Grok 4.5',
     '适合创意描述和镜头语言扩写。', 30, true),
    ('43000000-0000-0000-0000-000000000051',
     'codex2api-gpt-image-2-image', 'GPT Image 2',
     '支持参考图，适合角色主参考图和三视图一致性生成。', 10, true),
    ('43000000-0000-0000-0000-000000000051',
     'aliyun-qwen-image-2-0', 'Qwen Image 2.0',
     '适合中文文生图；不用于需要参考图的角色双成果生成。', 20, true),
    ('43000000-0000-0000-0000-000000000052',
     'codex2api-grok-imagine-video', 'Grok Imagine Video',
     '支持多参考图，作为专家模式默认视频模型。', 10, true),
    ('43000000-0000-0000-0000-000000000052',
     'codex2api-sora-2-video', 'Sora 2',
     '兼容单参考图视频生成。', 20, true)
on conflict (policy_id, deployment_code) do update
set display_name = excluded.display_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true;
