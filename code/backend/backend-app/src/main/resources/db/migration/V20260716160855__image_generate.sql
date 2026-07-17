insert into feature_definition (
    id, workspace_id, code, display_name, description, status,
    current_version, result_type, renderer_key, execution_mode,
    sort_order, created_at, updated_at
) values (
    '20000000-0000-0000-0000-000000000100',
    '10000000-0000-0000-0000-000000000003',
    'image.generate',
    'AI 生图',
    '根据文字描述和可选参考图片生成一张新图片。',
    'INTERNAL',
    1,
    'image',
    'image',
    'ASYNC',
    10,
    now(),
    now()
);

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
) values (
    '30000000-0000-0000-0000-000000000100',
    '20000000-0000-0000-0000-000000000100',
    1,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["prompt","aspectRatio"],
      "properties":{
        "prompt":{"type":"string","minLength":1,"maxLength":500,"title":"画面描述","description":"描述主体、场景、风格、构图和光线。"},
        "referenceImages":{"type":"array","maxItems":3,"items":{"type":"string","format":"uuid"},"title":"参考图片","description":"可选上传最多 3 张主体、构图或风格参考图。"},
        "aspectRatio":{"type":"string","enum":["1:1","16:9","9:16"],"default":"1:1","title":"图片比例","description":"选择生成图片的横竖比例。"}
      },
      "additionalProperties":false
    }',
    '{
      "order":["prompt","referenceImages","aspectRatio"],
      "widgets":{"prompt":"textarea","referenceImages":"image","aspectRatio":"segmented"},
      "enumLabels":{"aspectRatio":{"1:1":"1:1","16:9":"16:9","9:16":"9:16"}},
      "fieldOptions":{"referenceImages":{
        "acceptedMimeTypes":["image/png","image/jpeg","image/webp"],
        "allowedExtensions":[".png",".jpg",".jpeg",".webp"],
        "maxItems":3,
        "maxFileSizeBytes":10485760,
        "maxTotalSizeBytes":31457280,
        "showPreview":true
      }},
      "feeNotice":"生成图片将调用付费模型，费用按所选模型实际计费。点击“生成图片”即表示确认本次调用。",
      "submitLabel":"生成图片",
      "revisionSubmitLabel":"生成新版本"
    }',
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["assetId"],
      "properties":{
        "assetId":{"type":"string","format":"uuid"},
        "revisedPrompts":{"type":"array","items":{"type":"string"}}
      },
      "additionalProperties":false
    }',
    '{"modelAlias":"image.generation.default","capabilities":["IMAGE_GENERATION"],"outputCount":1,"maxReferenceImages":3,"maxReferenceImageBytes":10485760,"maxReferenceImagesTotalBytes":31457280}',
    now()
);

insert into feature_model_policy (
    id, feature_code, capability, default_deployment_code,
    allow_user_selection, created_at, updated_at
) values (
    '43000000-0000-0000-0000-000000000100',
    'image.generate',
    'IMAGE_GENERATION',
    'codex2api-gpt-image-2-image',
    true,
    now(),
    now()
);

insert into feature_model_option (
    policy_id, deployment_code, display_name, description, sort_order, enabled
) values
    (
        '43000000-0000-0000-0000-000000000100',
        'codex2api-gpt-image-2-image',
        'GPT Image 2',
        '适合高质量图片生成与多参考图修改，通过 Codex2API 中转调用。',
        10,
        true
    ),
    (
        '43000000-0000-0000-0000-000000000100',
        'aliyun-qwen-image-2-0',
        'Qwen Image 2.0',
        '适合中文文字和通用图片生成，通过阿里云百炼官方接口调用。',
        20,
        true
    );

update model_deployment
set config_json = config_json || '{
  "imageSizeMap":{"1:1":"1024x1024","16:9":"1536x864","9:16":"864x1536"},
  "imagePartName":"image[]"
}'::jsonb,
    updated_at = now()
where code = 'codex2api-gpt-image-2-image';

update model_deployment
set config_json = config_json || '{
  "imageSizeMap":{"1:1":"1024x1024","16:9":"1536x864","9:16":"864x1536"}
}'::jsonb,
    updated_at = now()
where code = 'aliyun-qwen-image-2-0';
