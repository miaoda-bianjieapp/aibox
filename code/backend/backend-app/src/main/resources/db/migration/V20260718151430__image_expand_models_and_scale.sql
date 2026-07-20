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
    'f953d99e-a01b-4b5f-b11f-b2061782b337',
    'af9b8384-1ca6-4cc8-888d-a71b6965ea50',
    2,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["sourceImage","preservationMode","ratioMode","expansionScaleMode"],
      "properties":{
        "sourceImage":{
          "type":"array",
          "minItems":1,
          "maxItems":1,
          "items":{"type":"string","format":"uuid"},
          "title":"原图",
          "description":"上传一张需要扩展画布的图片。"
        },
        "preservationMode":{
          "type":"string",
          "enum":["strict","flexible"],
          "default":"strict",
          "title":"保真方式",
          "description":"严格保留会确保原图区域像素不变；自然重绘允许模型轻微调整原图区域。"
        },
        "ratioMode":{
          "type":"string",
          "enum":["preset","custom"],
          "default":"preset",
          "title":"比例方式"
        },
        "presetAspectRatio":{
          "type":"string",
          "enum":["1:1","3:4","16:9","9:16","4:5"],
          "default":"1:1",
          "title":"目标比例",
          "description":"选择扩图后的画布比例。"
        },
        "customAspectRatio":{
          "type":"string",
          "minLength":3,
          "maxLength":7,
          "pattern":"^[1-9][0-9]{0,2}:[1-9][0-9]{0,2}$",
          "title":"自定义比例",
          "description":"输入宽:高，例如 7:5；支持范围为 1:3 至 3:1。"
        },
        "expansionScaleMode":{
          "type":"string",
          "enum":["preset","custom"],
          "default":"preset",
          "title":"倍数方式"
        },
        "presetExpansionScale":{
          "type":"string",
          "enum":["1.0","1.25","1.5","2.0"],
          "default":"1.25",
          "title":"扩展倍数",
          "description":"在最小目标比例画布基础上继续等比扩展。"
        },
        "customExpansionScale":{
          "type":"number",
          "minimum":1.0,
          "maximum":3.0,
          "multipleOf":0.05,
          "title":"自定义倍数",
          "description":"输入 1.0 至 3.0，例如 1.8。"
        }
      },
      "additionalProperties":false
    }'::jsonb,
    '{
      "order":[
        "sourceImage",
        "preservationMode",
        "ratioMode",
        "presetAspectRatio",
        "customAspectRatio",
        "expansionScaleMode",
        "presetExpansionScale",
        "customExpansionScale"
      ],
      "widgets":{
        "sourceImage":"image",
        "preservationMode":"segmented",
        "ratioMode":"segmented",
        "presetAspectRatio":"select",
        "customAspectRatio":"text",
        "expansionScaleMode":"segmented",
        "presetExpansionScale":"select",
        "customExpansionScale":"number"
      },
      "visibility":{
        "presetAspectRatio":{"field":"ratioMode","equals":"preset"},
        "customAspectRatio":{"field":"ratioMode","equals":"custom"},
        "presetExpansionScale":{"field":"expansionScaleMode","equals":"preset"},
        "customExpansionScale":{"field":"expansionScaleMode","equals":"custom"}
      },
      "enumLabels":{
        "preservationMode":{"strict":"严格保留","flexible":"自然重绘"},
        "ratioMode":{"preset":"预设比例","custom":"自定义"},
        "expansionScaleMode":{"preset":"预设倍数","custom":"自定义"},
        "presetAspectRatio":{"1:1":"1:1","3:4":"3:4","16:9":"16:9","9:16":"9:16","4:5":"4:5"},
        "presetExpansionScale":{"1.0":"1.0×","1.25":"1.25×","1.5":"1.5×","2.0":"2.0×"}
      },
      "fieldOptions":{"sourceImage":{
        "acceptedMimeTypes":["image/png","image/jpeg","image/webp"],
        "allowedExtensions":[".png",".jpg",".jpeg",".webp"],
        "maxItems":1,
        "maxFileSizeBytes":10485760,
        "maxTotalSizeBytes":10485760,
        "showPreview":true
      }},
      "feeNotice":"扩图将调用付费图片模型，费用按实际调用计费。点击“开始扩图”即表示确认本次调用。",
      "submitLabel":"开始扩图",
      "revisionSubmitLabel":"生成新版本"
    }'::jsonb,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["assetId"],
      "properties":{"assetId":{"type":"string","format":"uuid"}},
      "additionalProperties":false
    }'::jsonb,
    '{
      "modelAlias":"image.generation.default",
      "capabilities":["IMAGE_GENERATION"],
      "outputCount":1,
      "maxSourceImages":1,
      "maxSourceImageBytes":10485760,
      "minExpansionScale":1.0,
      "maxExpansionScale":3.0,
      "revisionUsesBaseArtifactImage":true
    }'::jsonb,
    now()
);

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'image.expand';

update feature_model_policy
set allow_user_selection = true,
    updated_at = now()
where feature_code = 'image.expand'
  and capability = 'IMAGE_GENERATION';

insert into feature_model_option (
    policy_id,
    deployment_code,
    display_name,
    description,
    sort_order,
    enabled
) values (
    '62aafc57-9d39-4bc0-b50f-126e09b58aae',
    'aliyun-qwen-image-2-0',
    'Qwen Image 2.0',
    '适合自然扩图和中文场景，通过阿里云百炼官方图像编辑接口调用。',
    20,
    true
);

update model_deployment
set config_json = config_json || '{
  "imageExpansionProtocol":"openai-edit",
  "imageExpansionMinPixels":655360,
  "imageExpansionMaxPixels":8294400,
  "imageExpansionMaxEdge":3840,
  "imageExpansionMaxUploadBytes":52428800,
  "imageExpansionSupportsMask":true
}'::jsonb,
    updated_at = now()
where code = 'codex2api-gpt-image-2-image';

update model_deployment
set config_json = config_json || '{
  "imageExpansionProtocol":"dashscope-multimodal",
  "imageExpansionPath":"https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
  "imageExpansionMinPixels":262144,
  "imageExpansionMaxPixels":4194304,
  "imageExpansionMaxEdge":3072,
  "imageExpansionMaxUploadBytes":10485760,
  "imageExpansionSupportsMask":false
}'::jsonb,
    updated_at = now()
where code = 'aliyun-qwen-image-2-0';
