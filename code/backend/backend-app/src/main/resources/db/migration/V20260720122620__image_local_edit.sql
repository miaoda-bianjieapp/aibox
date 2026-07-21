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
    '3ecffe9d-176d-462a-9a62-f14969905676',
    workspace.id,
    'image.local_edit',
    '图片局部编辑',
    '涂抹需要修改的图片区域，并通过文字指令只重绘选区内容。',
    'INTERNAL',
    1,
    'image',
    'image',
    'ASYNC',
    40,
    now(),
    now()
from workspace
where workspace.code = 'image';

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
    '3d18ddd6-d06d-407f-aaf6-d5eac9fa9dcc',
    '3ecffe9d-176d-462a-9a62-f14969905676',
    1,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["sourceImage","maskImage","instruction"],
      "properties":{
        "sourceImage":{
          "type":"string",
          "format":"uuid",
          "title":"原始图片",
          "description":"上传需要局部修改的 PNG、JPG、JPEG 或 WebP 图片。"
        },
        "maskImage":{
          "type":"string",
          "format":"uuid",
          "title":"编辑区域",
          "description":"在原图上涂抹允许模型修改的区域，未涂抹区域会保留原图像素。"
        },
        "instruction":{
          "type":"string",
          "minLength":1,
          "maxLength":500,
          "title":"修改指令",
          "description":"说明涂抹区域需要变成什么，避免要求修改选区外内容。"
        }
      },
      "additionalProperties":false
    }'::jsonb,
    '{
      "order":["sourceImage","maskImage","instruction"],
      "widgets":{
        "sourceImage":"image",
        "maskImage":"image_mask",
        "instruction":"textarea"
      },
      "fieldOptions":{
        "sourceImage":{
          "acceptedMimeTypes":["image/png","image/jpeg","image/webp"],
          "allowedExtensions":[".png",".jpg",".jpeg",".webp"],
          "maxItems":1,
          "maxFileSizeBytes":10485760,
          "maxTotalSizeBytes":10485760,
          "showPreview":true,
          "uploadLabel":"上传原始图片"
        },
        "maskImage":{
          "sourceField":"sourceImage",
          "acceptedMimeTypes":["image/png"],
          "allowedExtensions":[".png"],
          "maxItems":1,
          "maxFileSizeBytes":10485760,
          "maxTotalSizeBytes":10485760,
          "showPreview":true,
          "editorLabel":"涂抹编辑区域"
        }
      },
      "feeNotice":"图片局部编辑会调用 1 次 GPT Image 2 付费模型。点击“开始编辑”即表示确认本次调用。",
      "submitLabel":"开始编辑",
      "revisionSubmitLabel":"生成新版本"
    }'::jsonb,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["assetId"],
      "properties":{
        "assetId":{"type":"string","format":"uuid"},
        "revisedPrompts":{"type":"array","items":{"type":"string"}}
      },
      "additionalProperties":false
    }'::jsonb,
    '{
      "modelAlias":"image.generation.default",
      "capabilities":["IMAGE_GENERATION"],
      "outputCount":1,
      "revisionSourceAssetField":"sourceImage",
      "revisionResetFields":["maskImage"],
      "maskSemantics":"transparent_is_edit",
      "preserveUnmaskedPixels":true,
      "maxSourceImageBytes":10485760,
      "maxMaskImageBytes":10485760,
      "maxInputImagesTotalBytes":20971520,
      "maxImageDimension":8192,
      "maxImagePixels":40000000,
      "maxOutputImageBytes":52428800
    }'::jsonb,
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
) values (
    '58c6d3db-55cc-46d1-a7aa-929736e25478',
    'image.local_edit',
    'IMAGE_GENERATION',
    'codex2api-gpt-image-2-image',
    false,
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
) values (
    '58c6d3db-55cc-46d1-a7aa-929736e25478',
    'codex2api-gpt-image-2-image',
    'GPT Image 2 局部编辑',
    '支持通过独立蒙版限定重绘区域，并保留未涂抹区域。',
    10,
    true
);

update model_deployment
set config_json = config_json || '{
      "supportsImageMask":true,
      "maskPartName":"mask"
    }'::jsonb,
    updated_at = now()
where code = 'codex2api-gpt-image-2-image';

update workspace
set search_terms_json = search_terms_json || '[
      "局部编辑","涂抹","改图","重绘"
    ]'::jsonb
where code = 'image';
