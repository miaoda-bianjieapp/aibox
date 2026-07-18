insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    'b7d0f14e-2ce6-4e6e-8eb4-58c8b938d1f2',
    feature.id,
    2,
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
          "title":"第一张：主体原图",
          "description":"必传。上传需要识别主体并处理背景的 PNG 或 JPG 图片；最长边不超过 8192 像素。"
        },
        "backgroundImage":{
          "type":"string",
          "format":"uuid",
          "title":"第二张：背景参考图",
          "description":"换背景时可选。上传希望使用或参考的 PNG 或 JPG 背景图片；也可以只填写背景描述。"
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
          "acceptedMimeTypes":["image/png","image/jpeg"],
          "allowedExtensions":[".png",".jpg",".jpeg"],
          "maxItems":1,
          "maxFileSizeBytes":10485760,
          "maxTotalSizeBytes":10485760,
          "showPreview":true,
          "uploadLabel":"上传主体原图"
        },
        "backgroundImage":{
          "acceptedMimeTypes":["image/png","image/jpeg"],
          "allowedExtensions":[".png",".jpg",".jpeg"],
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
from feature_definition feature
where feature.code = 'image.background_edit';

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'image.background_edit';
