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
    '2dfe60b8-f04f-469e-b4a2-adb446a44b58',
    workspace.id,
    'image.enhance',
    '清晰修复',
    '对单张图片执行放大、去模糊、降噪或老照片修复。',
    'INTERNAL',
    1,
    'image',
    'image',
    'ASYNC',
    30,
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
    'd43e4a82-55fc-4252-9c24-7045f560cfb3',
    '2dfe60b8-f04f-469e-b4a2-adb446a44b58',
    1,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["mode","sourceImage"],
      "properties":{
        "mode":{
          "type":"string",
          "enum":["upscale","deblur","denoise","old_photo_restore"],
          "default":"upscale",
          "title":"处理方式",
          "description":"每次只执行一种图片清晰化或修复处理。"
        },
        "sourceImage":{
          "type":"string",
          "format":"uuid",
          "title":"原始图片",
          "description":"上传需要提升清晰度或修复的单张图片。"
        },
        "scale":{
          "type":"string",
          "enum":["2x","4x"],
          "default":"2x",
          "title":"放大倍率",
          "description":"最终宽高严格放大为原图的 2 倍或 4 倍。"
        },
        "colorize":{
          "type":"boolean",
          "default":false,
          "title":"为黑白照片上色",
          "description":"仅在老照片修复时使用；关闭时保持原来的黑白或彩色状态。"
        }
      },
      "allOf":[{
        "if":{"properties":{"mode":{"const":"upscale"}},"required":["mode"]},
        "then":{"required":["scale"]}
      }],
      "additionalProperties":false
    }'::jsonb,
    '{
      "order":["mode","sourceImage","scale","colorize"],
      "widgets":{
        "mode":"segmented",
        "sourceImage":"image",
        "scale":"segmented",
        "colorize":"boolean"
      },
      "visibility":{
        "scale":{"field":"mode","equals":"upscale"},
        "colorize":{"field":"mode","equals":"old_photo_restore"}
      },
      "enumLabels":{
        "mode":{
          "upscale":"图片放大",
          "deblur":"去模糊",
          "denoise":"图片降噪",
          "old_photo_restore":"老照片修复"
        },
        "scale":{"2x":"2x","4x":"4x"}
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
        }
      },
      "feeNotice":"图片修复将调用所选付费模型，费用按实际模型计费；点击“开始修复”即表示确认本次调用。",
      "submitLabel":"开始修复",
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
      "maxSourceImageBytes":10485760,
      "maxImageDimension":8192,
      "maxImagePixels":40000000,
      "maxOutputImageBytes":52428800,
      "upscaleFactors":[2,4]
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
    '07f8984a-6031-4594-a277-6c017923670a',
    'image.enhance',
    'IMAGE_GENERATION',
    'codex2api-gpt-image-2-image',
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
        '07f8984a-6031-4594-a277-6c017923670a',
        'codex2api-gpt-image-2-image',
        'GPT Image 2',
        '默认模型，适合高质量图片编辑和生成式细节补全。',
        10,
        true
    ),
    (
        '07f8984a-6031-4594-a277-6c017923670a',
        'aliyun-qwen-image-2-0',
        'Qwen Image 2.0',
        '适合中文图片修复指令；需管理员完成阿里云接口配置后使用。',
        20,
        true
    );

update workspace
set groups_json = '["create","process","media"]'::jsonb,
    search_terms_json = '[
      "图片","设计","海报","配图","抠图","扩图",
      "清晰","修复","放大","去模糊","降噪","老照片"
    ]'::jsonb
where code = 'image';
