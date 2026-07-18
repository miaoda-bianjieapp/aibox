insert into feature_definition (
    id, workspace_id, code, display_name, description, status,
    current_version, result_type, renderer_key, execution_mode,
    sort_order, created_at, updated_at
)
select
    'd72718f0-b5d2-4a2d-8e02-2af0ec5f4e2a',
    workspace.id,
    'image.background_edit',
    '抠图与换背景',
    '自动识别图片主体，移除背景或根据文字和参考图更换背景。',
    'INTERNAL',
    1,
    'image',
    'image',
    'ASYNC',
    20,
    now(),
    now()
from workspace
where workspace.code = 'image';

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
) values (
    '92635ecb-cc61-49de-89df-4528eb80701f',
    'd72718f0-b5d2-4a2d-8e02-2af0ec5f4e2a',
    1,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["mode","sourceImage"],
      "properties":{
        "mode":{
          "type":"string",
          "enum":["remove_background","replace_background"],
          "default":"remove_background",
          "title":"处理方式",
          "description":"抠图会移除背景并输出透明 PNG；换背景可使用文字描述、参考图或两者结合。"
        },
        "sourceImage":{
          "type":"string",
          "format":"uuid",
          "title":"主体原图",
          "description":"上传需要识别主体并处理背景的图片；最长边不超过 8192 像素。"
        },
        "backgroundImage":{
          "type":"string",
          "format":"uuid",
          "title":"背景参考图",
          "description":"可选。上传希望使用或参考的背景图片。"
        },
        "backgroundDescription":{
          "type":"string",
          "maxLength":500,
          "title":"背景描述",
          "description":"可选。描述目标背景、光线、氛围和需要保留的阴影；可与背景参考图一起使用。"
        }
      },
      "additionalProperties":false
    }'::jsonb,
    '{
      "order":["mode","sourceImage","backgroundImage","backgroundDescription"],
      "widgets":{
        "mode":"segmented",
        "sourceImage":"image",
        "backgroundImage":"image",
        "backgroundDescription":"textarea"
      },
      "visibility":{
        "backgroundImage":{"field":"mode","equals":"replace_background"},
        "backgroundDescription":{"field":"mode","equals":"replace_background"}
      },
      "enumLabels":{
        "mode":{
          "remove_background":"抠图",
          "replace_background":"换背景"
        }
      },
      "fieldOptions":{
        "sourceImage":{
          "acceptedMimeTypes":["image/png","image/jpeg","image/webp"],
          "allowedExtensions":[".png",".jpg",".jpeg",".webp"],
          "maxItems":1,
          "maxFileSizeBytes":10485760,
          "maxTotalSizeBytes":10485760,
          "showPreview":true,
          "uploadLabel":"上传主体原图"
        },
        "backgroundImage":{
          "acceptedMimeTypes":["image/png","image/jpeg","image/webp"],
          "allowedExtensions":[".png",".jpg",".jpeg",".webp"],
          "maxItems":1,
          "maxFileSizeBytes":10485760,
          "maxTotalSizeBytes":10485760,
          "showPreview":true,
          "uploadLabel":"上传背景参考图"
        }
      },
      "feeNotice":"图片处理将调用付费模型，费用按所选模型实际计费。点击“开始处理”即表示确认本次调用。",
      "submitLabel":"开始处理",
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
      "preserveSourceDimensions":true,
      "revisionSourceAssetField":"sourceImage",
      "maxSourceImageBytes":10485760,
      "maxBackgroundImageBytes":10485760,
      "maxInputImagesTotalBytes":20971520,
      "maxImageDimension":8192,
      "maxImagePixels":40000000
    }'::jsonb,
    now()
);

insert into feature_model_policy (
    id, feature_code, capability, default_deployment_code,
    allow_user_selection, created_at, updated_at
) values (
    '70daea0d-a317-4168-8d9c-235db21e429c',
    'image.background_edit',
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
        '70daea0d-a317-4168-8d9c-235db21e429c',
        'codex2api-gpt-image-2-image',
        'GPT Image 2',
        '默认图片编辑模型，支持主体图和可选背景参考图。',
        10,
        true
    ),
    (
        '70daea0d-a317-4168-8d9c-235db21e429c',
        'aliyun-qwen-image-2-0',
        'Qwen Image 2.0',
        '中文图片处理模型；需管理员完成阿里云接口配置后使用。',
        20,
        true
    );
