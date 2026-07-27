insert into model_deployment (
    id,
    code,
    provider_code,
    display_name,
    description,
    capability,
    provider_model,
    enabled,
    selectable,
    config_json,
    created_at,
    updated_at
) values
    (
        'dc988348-73ab-4e90-a9db-53df2b7da525',
        'codex2api-gpt-5-4-mini-vision',
        'codex2api-relay',
        'GPT-5.4 Mini Vision',
        'GPT-5.4 Mini image understanding through the Codex2API relay',
        'VISION',
        'gpt-5.4-mini',
        true,
        true,
        '{"source":"relay","discovery":"v1/models","supportsImageInputs":true}'::jsonb,
        now(),
        now()
    ),
    (
        'fa1b57df-ad1d-46cb-8cb0-7a4232260557',
        'codex2api-gpt-5-6-sol-text',
        'codex2api-relay',
        'GPT-5.6 Sol',
        'GPT-5.6 Sol text generation through the Codex2API relay',
        'TEXT_GENERATION',
        'gpt-5.6-sol',
        true,
        true,
        '{"source":"relay","discovery":"v1/models"}'::jsonb,
        now(),
        now()
    ),
    (
        '9fa7181c-de84-4843-b204-237f653d8d67',
        'codex2api-gpt-5-6-sol-vision',
        'codex2api-relay',
        'GPT-5.6 Sol Vision',
        'GPT-5.6 Sol image understanding through the Codex2API relay',
        'VISION',
        'gpt-5.6-sol',
        true,
        true,
        '{"source":"relay","discovery":"v1/models","supportsImageInputs":true}'::jsonb,
        now(),
        now()
    );

insert into feature_definition (
    id,
    workspace_id,
    code,
    display_name,
    description,
    status,
    current_version,
    result_type,
    renderer_key,
    execution_mode,
    sort_order,
    created_at,
    updated_at
)
select
    '25d807ac-1958-477c-b60e-2186ef946f5e',
    workspace.id,
    'document.summary',
    '文档总结',
    '总结单个 PDF、Word、Excel 或 CSV 文档，输出摘要、章节要点、结论和行动项。',
    'INTERNAL',
    1,
    'rich_text',
    'rich_text_editor',
    'ASYNC',
    10,
    now(),
    now()
from workspace
where workspace.code = 'document';

insert into feature_version (
    id,
    feature_id,
    version,
    input_schema_json,
    ui_schema_json,
    output_schema_json,
    config_json,
    created_at
) values (
    '9395fa75-ad3c-4f52-ac95-dac8598fd05f',
    '25d807ac-1958-477c-b60e-2186ef946f5e',
    1,
    $json$
    {
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["document","summaryDepth"],
      "properties":{
        "document":{
          "type":"string",
          "format":"uuid",
          "title":"待总结文档",
          "description":"每次处理 1 个 PDF、Word、Excel 或 UTF-8 CSV 文档。"
        },
        "summaryDepth":{
          "type":"string",
          "enum":["concise","standard","detailed"],
          "default":"standard",
          "title":"总结深度",
          "description":"控制摘要和章节要点的展开程度。"
        },
        "focus":{
          "type":"string",
          "maxLength":500,
          "title":"关注重点",
          "description":"可选。说明需要重点关注的问题、数据或决策信息。"
        }
      },
      "additionalProperties":false
    }
    $json$::jsonb,
    $json$
    {
      "order":["document","summaryDepth","focus"],
      "widgets":{
        "document":"file",
        "summaryDepth":"segmented",
        "focus":"textarea"
      },
      "enumLabels":{
        "summaryDepth":{
          "concise":"简洁",
          "standard":"标准",
          "detailed":"详细"
        }
      },
      "fieldOptions":{
        "document":{
          "acceptedMimeTypes":[
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "text/plain",
            "application/csv",
            "text/comma-separated-values",
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream"
          ],
          "allowedExtensions":[".pdf",".doc",".docx",".xls",".xlsx",".csv"],
          "maxItems":1,
          "maxFileSizeBytes":52428800,
          "maxTotalSizeBytes":52428800,
          "showPreview":false,
          "uploadLabel":"选择并上传文档"
        },
        "summaryDepth":{
          "showSelectedIcon":false,
          "compact":true,
          "labelMaxLines":1
        }
      },
      "fieldHelp":{
        "document":{
          "text":"正文抽取后最多 15 万字符；超出时请拆分文档。扫描 PDF 会使用所选模型识别页面文字。CSV 首版仅支持 UTF-8 或 UTF-8 BOM。"
        }
      },
      "modelSelectionGroups":[
        {
          "key":"documentModel",
          "label":"文档处理模型",
          "description":"正文总结和扫描页识别使用同一模型家族。",
          "capabilities":["TEXT_GENERATION","VISION"],
          "options":[
            {
              "value":"gpt-5.6-sol",
              "displayName":"GPT-5.6 Sol",
              "description":"默认模型，适合长文档和复杂结构总结。",
              "deployments":{
                "TEXT_GENERATION":"codex2api-gpt-5-6-sol-text",
                "VISION":"codex2api-gpt-5-6-sol-vision"
              }
            },
            {
              "value":"gpt-5.4-mini",
              "displayName":"GPT-5.4 Mini",
              "description":"响应更轻量，适合结构清晰的常规文档。",
              "deployments":{
                "TEXT_GENERATION":"codex2api-gpt-5-4-mini-text",
                "VISION":"codex2api-gpt-5-4-mini-vision"
              }
            }
          ]
        }
      ],
      "feeNotice":"文档总结会调用所选付费模型；扫描 PDF 将改用同一模型家族的视觉能力。点击“开始总结”即表示确认本次调用。",
      "submitLabel":"开始总结",
      "revisionSubmitLabel":"生成新版本"
    }
    $json$::jsonb,
    $json$
    {
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["format","text"],
      "properties":{
        "format":{"const":"markdown"},
        "text":{"type":"string","minLength":1}
      },
      "additionalProperties":false
    }
    $json$::jsonb,
    $json$
    {
      "modelAliases":{
        "TEXT_GENERATION":"text.document-summary",
        "VISION":"vision.document-ocr"
      },
      "maxInputFiles":1,
      "maxInputFileBytes":52428800,
      "maxExtractedCharacters":150000,
      "maxFocusCharacters":500,
      "csvEncoding":"UTF-8",
      "capabilities":["TEXT_GENERATION","VISION"]
    }
    $json$::jsonb,
    now()
);

insert into feature_model_policy (
    id,
    feature_code,
    capability,
    default_deployment_code,
    allow_user_selection,
    created_at,
    updated_at
) values
    (
        'b2221005-0e3b-4a80-a52d-093601bc2cfd',
        'document.summary',
        'TEXT_GENERATION',
        'codex2api-gpt-5-6-sol-text',
        true,
        now(),
        now()
    ),
    (
        '17290a44-6cb6-4a76-a5f6-86ee430fce2b',
        'document.summary',
        'VISION',
        'codex2api-gpt-5-6-sol-vision',
        true,
        now(),
        now()
    );

insert into feature_model_option (
    policy_id,
    deployment_code,
    display_name,
    description,
    sort_order,
    enabled
) values
    (
        'b2221005-0e3b-4a80-a52d-093601bc2cfd',
        'codex2api-gpt-5-6-sol-text',
        'GPT-5.6 Sol',
        '默认模型，适合长文档和复杂结构总结。',
        10,
        true
    ),
    (
        'b2221005-0e3b-4a80-a52d-093601bc2cfd',
        'codex2api-gpt-5-4-mini-text',
        'GPT-5.4 Mini',
        '响应更轻量，适合结构清晰的常规文档。',
        20,
        true
    ),
    (
        '17290a44-6cb6-4a76-a5f6-86ee430fce2b',
        'codex2api-gpt-5-6-sol-vision',
        'GPT-5.6 Sol',
        '扫描页识别与图表理解使用同一 GPT-5.6 Sol 模型。',
        10,
        true
    ),
    (
        '17290a44-6cb6-4a76-a5f6-86ee430fce2b',
        'codex2api-gpt-5-4-mini-vision',
        'GPT-5.4 Mini',
        '扫描页识别与图表理解使用同一 GPT-5.4 Mini 模型。',
        20,
        true
    );
