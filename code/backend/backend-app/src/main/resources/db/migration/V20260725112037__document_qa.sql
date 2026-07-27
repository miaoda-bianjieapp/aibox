create table task_asset (
    task_id uuid not null references task(id) on delete cascade,
    asset_id uuid not null references asset(id),
    role varchar(80) not null,
    status varchar(30) not null,
    ordinal integer not null,
    snapshot_name varchar(500) not null,
    snapshot_media_type varchar(200) not null,
    snapshot_size_bytes bigint not null,
    added_at timestamptz not null,
    removed_at timestamptz,
    primary key (task_id, asset_id, role),
    constraint ck_task_asset_status check (status in ('ACTIVE', 'REMOVED')),
    constraint ck_task_asset_ordinal check (ordinal >= 0),
    constraint ck_task_asset_size check (snapshot_size_bytes >= 0)
);

create index idx_task_asset_active
    on task_asset(task_id, role, ordinal)
    where status = 'ACTIVE';

create index idx_task_asset_asset
    on task_asset(asset_id, task_id);

create table document_index (
    id uuid primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    asset_id uuid not null references asset(id),
    vision_deployment_code varchar(120) not null references model_deployment(code),
    parser_version integer not null,
    status varchar(30) not null,
    content_hash varchar(64) not null,
    statistics_json jsonb not null default '{}'::jsonb,
    error_code varchar(100),
    error_message varchar(1000),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (asset_id, vision_deployment_code, parser_version),
    constraint ck_document_index_status check (
        status in ('PROCESSING', 'READY', 'FAILED')
    )
);

create index idx_document_index_owner
    on document_index(tenant_id, user_id, status, updated_at desc);

create table document_chunk (
    id uuid primary key,
    document_index_id uuid not null references document_index(id) on delete cascade,
    asset_id uuid not null references asset(id),
    ordinal integer not null,
    text_content text not null,
    locator_json jsonb not null default '{}'::jsonb,
    search_metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    unique (document_index_id, ordinal)
);

create index idx_document_chunk_index
    on document_chunk(document_index_id, ordinal);

insert into model_deployment (
    id, code, provider_code, display_name, description, capability,
    provider_model, enabled, selectable, config_json, created_at, updated_at
) values
    (
        '41000000-0000-0000-0000-000000000021',
        'codex2api-gpt-5-6-sol-text',
        'codex2api-relay',
        'GPT-5.6 Sol',
        '高质量文档检索重排与回答模型',
        'TEXT_GENERATION',
        'gpt-5.6-sol',
        true,
        true,
        '{"source":"relay","supportsStreamUsage":true}',
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000022',
        'codex2api-gpt-5-6-sol-vision',
        'codex2api-relay',
        'GPT-5.6 Sol 视觉',
        '扫描页、图片文字和复杂图表理解模型',
        'VISION',
        'gpt-5.6-sol',
        true,
        true,
        '{"source":"relay"}',
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000023',
        'codex2api-gpt-5-4-mini-vision',
        'codex2api-relay',
        'GPT-5.4 Mini 视觉',
        '更快、更节省的扫描页和图表理解模型',
        'VISION',
        'gpt-5.4-mini',
        true,
        true,
        '{"source":"relay"}',
        now(),
        now()
    )
on conflict (code) do nothing;

insert into model_route (
    id, model_alias, capability, deployment_code, priority, enabled, created_at
) values
    (
        '42000000-0000-0000-0000-000000000021',
        'document.qa.text',
        'TEXT_GENERATION',
        'codex2api-gpt-5-6-sol-text',
        10,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000022',
        'document.qa.vision',
        'VISION',
        'codex2api-gpt-5-6-sol-vision',
        10,
        true,
        now()
    )
on conflict (model_alias, capability, deployment_code) do nothing;

insert into feature_definition (
    id, workspace_id, code, display_name, description, status,
    current_version, result_type, renderer_key, execution_mode,
    sort_order, created_at, updated_at
)
select
    '20000000-0000-0000-0000-000000000021',
    workspace.id,
    'document.qa',
    '文档问答',
    '基于当前会话中的文档进行带精确来源的多轮问答。',
    'INTERNAL',
    1,
    'document_chat',
    'document_qa_chat',
    'ASYNC',
    10,
    now(),
    now()
from workspace
where workspace.code = 'document';

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '30000000-0000-0000-0000-000000000021',
    feature.id,
    1,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["documents","question"],
      "properties":{
        "documents":{
          "type":"array","title":"问答文档","minItems":1,"maxItems":10,
          "uniqueItems":true,"items":{"type":"string","format":"uuid"}
        },
        "question":{
          "type":"string","title":"输入问题","minLength":1,"maxLength":4000
        },
        "strictGrounding":{"type":"boolean","const":true,"default":true}
      },
      "additionalProperties":false
    }'::jsonb,
    '{
      "pageKey":"document_qa",
      "order":["documents","question","strictGrounding"],
      "widgets":{"documents":"file","question":"textarea","strictGrounding":"hidden"},
      "fieldOptions":{
        "documents":{
          "acceptedMimeTypes":[
            "application/pdf","application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain","text/markdown","text/csv","application/json",
            "application/octet-stream"
          ],
          "allowedExtensions":[
            ".pdf",".doc",".docx",".xls",".xlsx",".ppt",".pptx",
            ".txt",".md",".csv",".json"
          ],
          "maxItems":10,
          "maxFileSizeBytes":52428800,
          "maxTotalSizeBytes":209715200
        }
      },
      "feeNotice":"文档解析、扫描页识别、图表理解、检索重排和回答可能产生模型费用。文档内容将发送给所选中转模型处理。",
      "submitLabel":"开始问答"
    }'::jsonb,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":[
        "format","question","answerMarkdown","citations","contextTurns","warnings"
      ],
      "properties":{
        "format":{"const":"document_chat"},
        "question":{"type":"string","minLength":1},
        "answerMarkdown":{"type":"string","minLength":1},
        "citations":{
          "type":"array",
          "items":{
            "type":"object",
            "required":["marker","assetId","fileName","excerpt","locator"],
            "properties":{
              "marker":{"type":"string","pattern":"^S[1-9][0-9]*$"},
              "assetId":{"type":"string","format":"uuid"},
              "fileName":{"type":"string","minLength":1,"maxLength":500},
              "excerpt":{"type":"string","minLength":1,"maxLength":600},
              "locator":{
                "oneOf":[
                  {
                    "type":"object",
                    "required":["type","pageNumber"],
                    "properties":{
                      "type":{"const":"PDF_PAGE"},
                      "pageNumber":{"type":"integer","minimum":1}
                    },
                    "additionalProperties":false
                  },
                  {
                    "type":"object",
                    "required":["type","paragraphStart","paragraphEnd"],
                    "properties":{
                      "type":{"const":"WORD_PARAGRAPH"},
                      "paragraphStart":{"type":"integer","minimum":1},
                      "paragraphEnd":{"type":"integer","minimum":1},
                      "heading":{"type":"string","maxLength":500},
                      "visual":{"type":"boolean"}
                    },
                    "additionalProperties":false
                  },
                  {
                    "type":"object",
                    "required":["type","sheetName","startRow","endRow"],
                    "properties":{
                      "type":{"const":"EXCEL_ROWS"},
                      "sheetName":{"type":"string","minLength":1,"maxLength":255},
                      "startRow":{"type":"integer","minimum":1},
                      "endRow":{"type":"integer","minimum":1},
                      "chartIndex":{"type":"integer","minimum":1}
                    },
                    "additionalProperties":false
                  },
                  {
                    "type":"object",
                    "required":["type","slideNumber"],
                    "properties":{
                      "type":{"const":"PPT_SLIDE"},
                      "slideNumber":{"type":"integer","minimum":1}
                    },
                    "additionalProperties":false
                  },
                  {
                    "type":"object",
                    "required":["type","startLine","endLine"],
                    "properties":{
                      "type":{"const":"TEXT_LINES"},
                      "startLine":{"type":"integer","minimum":1},
                      "endLine":{"type":"integer","minimum":1}
                    },
                    "additionalProperties":false
                  }
                ]
              }
            },
            "additionalProperties":false
          }
        },
        "contextTurns":{
          "type":"array",
          "maxItems":20,
          "items":{
            "type":"object",
            "required":["question","answer"],
            "properties":{
              "question":{"type":"string","minLength":1,"maxLength":4000},
              "answer":{"type":"string","minLength":1}
            },
            "additionalProperties":false
          }
        },
        "warnings":{"type":"array","items":{"type":"string"}}
      },
      "additionalProperties":false
    }'::jsonb,
    '{
      "pageKey":"document_qa",
      "modelAliases":{
        "TEXT_GENERATION":"document.qa.text",
        "VISION":"document.qa.vision"
      },
      "modelBundles":[
        {
          "code":"gpt-5.6-sol",
          "displayName":"GPT-5.6 Sol",
          "description":"质量优先，适合复杂文档、扫描页和图表问答。",
          "selectedModels":{
            "TEXT_GENERATION":"codex2api-gpt-5-6-sol-text",
            "VISION":"codex2api-gpt-5-6-sol-vision"
          }
        },
        {
          "code":"gpt-5.4-mini",
          "displayName":"GPT-5.4 Mini",
          "description":"速度与成本优先，适合常规文档问答。",
          "selectedModels":{
            "TEXT_GENERATION":"codex2api-gpt-5-4-mini-text",
            "VISION":"codex2api-gpt-5-4-mini-vision"
          }
        }
      ],
      "maxFiles":10,
      "maxFileSizeBytes":52428800,
      "maxTotalSizeBytes":209715200,
      "maxQuestionLength":4000,
      "maxContextTurns":20,
      "retrievalMode":"BM25_GPT_RERANK",
      "strictGrounding":true
    }'::jsonb,
    now()
from feature_definition feature
where feature.code = 'document.qa';

insert into feature_model_policy (
    id, feature_code, capability, default_deployment_code,
    allow_user_selection, created_at, updated_at
) values
    (
        '43000000-0000-0000-0000-000000000021',
        'document.qa',
        'TEXT_GENERATION',
        'codex2api-gpt-5-6-sol-text',
        true,
        now(),
        now()
    ),
    (
        '43000000-0000-0000-0000-000000000022',
        'document.qa',
        'VISION',
        'codex2api-gpt-5-6-sol-vision',
        true,
        now(),
        now()
    );

insert into feature_model_option (
    policy_id, deployment_code, display_name, description, sort_order, enabled
) values
    (
        '43000000-0000-0000-0000-000000000021',
        'codex2api-gpt-5-6-sol-text',
        'GPT-5.6 Sol',
        '质量优先的文档检索重排与回答模型',
        10,
        true
    ),
    (
        '43000000-0000-0000-0000-000000000021',
        'codex2api-gpt-5-4-mini-text',
        'GPT-5.4 Mini',
        '速度与成本优先的文档问答模型',
        20,
        true
    ),
    (
        '43000000-0000-0000-0000-000000000022',
        'codex2api-gpt-5-6-sol-vision',
        'GPT-5.6 Sol',
        '质量优先的扫描页和图表理解模型',
        10,
        true
    ),
    (
        '43000000-0000-0000-0000-000000000022',
        'codex2api-gpt-5-4-mini-vision',
        'GPT-5.4 Mini',
        '速度与成本优先的扫描页和图表理解模型',
        20,
        true
    );
