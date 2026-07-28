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
    '5d9f9755-7314-455d-88e5-baa7151be0b9',
    workspace.id,
    'document.translate',
    '文档翻译',
    '将单个 Word 或 PDF 文档翻译为指定语言，并输出尽量保留原始排版的同格式文件。',
    'INTERNAL',
    1,
    'file',
    'file',
    'ASYNC',
    20,
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
    'bd08cf1c-d317-4517-8ea2-dbab6d932031',
    '5d9f9755-7314-455d-88e5-baa7151be0b9',
    1,
    $json$
    {
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["document","targetLanguage"],
      "properties":{
        "document":{
          "type":"string",
          "format":"uuid",
          "title":"待翻译文档",
          "description":"每次处理 1 个 DOCX、DOC 或 PDF 文档，系统自动识别原文语言。"
        },
        "targetLanguage":{
          "type":"string",
          "enum":["zh-CN","zh-TW","en","ja","ko","fr","de","es","ru","ar"],
          "default":"en",
          "title":"目标语言",
          "description":"选择译文使用的语言。"
        }
      },
      "additionalProperties":false
    }
    $json$::jsonb,
    $json$
    {
      "order":["document","targetLanguage"],
      "widgets":{
        "document":"file",
        "targetLanguage":"select"
      },
      "enumLabels":{
        "targetLanguage":{
          "zh-CN":"简体中文",
          "zh-TW":"繁体中文",
          "en":"英语",
          "ja":"日语",
          "ko":"韩语",
          "fr":"法语",
          "de":"德语",
          "es":"西班牙语",
          "ru":"俄语",
          "ar":"阿拉伯语"
        }
      },
      "fieldOptions":{
        "document":{
          "acceptedMimeTypes":[
            "application/pdf",
            "application/x-pdf",
            "application/msword",
            "application/vnd.ms-word",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream"
          ],
          "allowedExtensions":[".docx",".doc",".pdf"],
          "maxItems":1,
          "maxFileSizeBytes":52428800,
          "maxTotalSizeBytes":52428800,
          "showPreview":false,
          "uploadLabel":"选择并上传文档"
        }
      },
      "fieldHelp":{
        "document":{
          "text":"可翻译正文最多 3 万字符；扫描 PDF 最多 20 页。系统按文档结构分批，最多调用模型 5 次。复杂 DOC 结构可能无法写回，届时请转换为 DOCX。PDF 译文最低缩小到 8pt，仍放不下时任务失败且不输出半成品。"
        }
      },
      "modelSelectionGroups":[
        {
          "key":"documentTranslationModel",
          "label":"文档翻译模型",
          "description":"正文翻译和扫描页识别使用同一模型家族。",
          "capabilities":["TEXT_GENERATION","VISION"],
          "options":[
            {
              "value":"gpt-5.6-sol",
              "displayName":"GPT-5.6 Sol",
              "description":"默认模型，适合复杂文档与长段落翻译。",
              "deployments":{
                "TEXT_GENERATION":"codex2api-gpt-5-6-sol-text",
                "VISION":"codex2api-gpt-5-6-sol-vision"
              }
            },
            {
              "value":"gpt-5.4-mini",
              "displayName":"GPT-5.4 Mini",
              "description":"响应更轻量，适合排版和语言较简单的文档。",
              "deployments":{
                "TEXT_GENERATION":"codex2api-gpt-5-4-mini-text",
                "VISION":"codex2api-gpt-5-4-mini-vision"
              }
            }
          ]
        }
      ],
      "feeNotice":"文档翻译会调用所选付费模型，单个任务最多约 5 次调用；扫描 PDF 还会使用同一模型家族的视觉能力。点击“开始翻译”即表示确认本次调用。",
      "submitLabel":"开始翻译",
      "revisionSubmitLabel":"重新翻译"
    }
    $json$::jsonb,
    $json$
    {
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["assetId","name","sourceAssetId"],
      "properties":{
        "assetId":{"type":"string","format":"uuid"},
        "name":{"type":"string","minLength":1},
        "sourceAssetId":{"type":"string","format":"uuid"}
      },
      "additionalProperties":false
    }
    $json$::jsonb,
    $json$
    {
      "modelAliases":{
        "TEXT_GENERATION":"text.document-translation",
        "VISION":"vision.document-translation"
      },
      "maxInputFiles":1,
      "maxInputFileBytes":52428800,
      "maxTranslatableCharacters":30000,
      "maxScannedPdfPages":20,
      "maxTextBatchCharacters":6000,
      "maxVisualBatchPages":4,
      "maxModelInvocations":5,
      "minPdfFontPoints":8,
      "sameFormatOutput":true,
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
        '2df6b647-7d55-4ab4-8e70-8952c1bda959',
        'document.translate',
        'TEXT_GENERATION',
        'codex2api-gpt-5-6-sol-text',
        true,
        now(),
        now()
    ),
    (
        'd2fb25d6-cd0d-41dd-8d3c-cd24fc0549d7',
        'document.translate',
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
        '2df6b647-7d55-4ab4-8e70-8952c1bda959',
        'codex2api-gpt-5-6-sol-text',
        'GPT-5.6 Sol',
        '默认模型，适合复杂文档与长段落翻译。',
        10,
        true
    ),
    (
        '2df6b647-7d55-4ab4-8e70-8952c1bda959',
        'codex2api-gpt-5-4-mini-text',
        'GPT-5.4 Mini',
        '响应更轻量，适合排版和语言较简单的文档。',
        20,
        true
    ),
    (
        'd2fb25d6-cd0d-41dd-8d3c-cd24fc0549d7',
        'codex2api-gpt-5-6-sol-vision',
        'GPT-5.6 Sol',
        '扫描页识别与翻译使用同一 GPT-5.6 Sol 模型。',
        10,
        true
    ),
    (
        'd2fb25d6-cd0d-41dd-8d3c-cd24fc0549d7',
        'codex2api-gpt-5-4-mini-vision',
        'GPT-5.4 Mini',
        '扫描页识别与翻译使用同一 GPT-5.4 Mini 模型。',
        20,
        true
    );

update workspace
set search_terms_json = search_terms_json || '["翻译","文档翻译"]'::jsonb
where code = 'document';
