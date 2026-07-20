insert into feature_version (
    id,
    feature_id,
    version,
    input_schema_json,
    ui_schema_json,
    output_schema_json,
    config_json,
    created_at
)
select
    'da784b1c-3d5e-4a58-b66f-62298e5dc50c',
    feature_id,
    3,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":[
        "sourceImage",
        "preservationMode",
        "operationMode",
        "ratioMode",
        "expansionScaleMode"
      ],
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
        "operationMode":{
          "type":"string",
          "enum":["change_ratio","expand"],
          "default":"change_ratio",
          "title":"处理方式",
          "description":"改比例会扩展到指定画布比例；扩图会保持原图比例并按倍数向四周扩展。"
        },
        "ratioMode":{
          "type":"string",
          "enum":["preset","custom"],
          "default":"preset",
          "title":"比例方式",
          "description":"仅在改比例模式下使用。"
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
          "title":"倍数方式",
          "description":"仅在扩图模式下使用。"
        },
        "presetExpansionScale":{
          "type":"string",
          "enum":["1.0","1.25","1.5","2.0"],
          "default":"1.25",
          "title":"扩展倍数",
          "description":"保持原图宽高比，将画布宽度和高度按该倍数向四周扩展。"
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
        "operationMode",
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
        "operationMode":"segmented",
        "ratioMode":"segmented",
        "presetAspectRatio":"select",
        "customAspectRatio":"text",
        "expansionScaleMode":"segmented",
        "presetExpansionScale":"select",
        "customExpansionScale":"number"
      },
      "visibility":{
        "ratioMode":{"field":"operationMode","equals":"change_ratio"},
        "presetAspectRatio":{"all":[
          {"field":"operationMode","equals":"change_ratio"},
          {"field":"ratioMode","equals":"preset"}
        ]},
        "customAspectRatio":{"all":[
          {"field":"operationMode","equals":"change_ratio"},
          {"field":"ratioMode","equals":"custom"}
        ]},
        "expansionScaleMode":{"field":"operationMode","equals":"expand"},
        "presetExpansionScale":{"all":[
          {"field":"operationMode","equals":"expand"},
          {"field":"expansionScaleMode","equals":"preset"}
        ]},
        "customExpansionScale":{"all":[
          {"field":"operationMode","equals":"expand"},
          {"field":"expansionScaleMode","equals":"custom"}
        ]}
      },
      "enumLabels":{
        "preservationMode":{"strict":"严格保留","flexible":"自然重绘"},
        "operationMode":{"change_ratio":"改比例","expand":"扩图"},
        "ratioMode":{"preset":"预设比例","custom":"自定义"},
        "expansionScaleMode":{"preset":"预设倍数","custom":"自定义"},
        "presetAspectRatio":{
          "1:1":"1:1",
          "3:4":"3:4",
          "16:9":"16:9",
          "9:16":"9:16",
          "4:5":"4:5"
        },
        "presetExpansionScale":{
          "1.0":"1.0×",
          "1.25":"1.25×",
          "1.5":"1.5×",
          "2.0":"2.0×"
        }
      },
      "fieldOptions":{"sourceImage":{
        "acceptedMimeTypes":["image/png","image/jpeg","image/webp"],
        "allowedExtensions":[".png",".jpg",".jpeg",".webp"],
        "maxItems":1,
        "maxFileSizeBytes":10485760,
        "maxTotalSizeBytes":10485760,
        "showPreview":true
      }},
      "feeNotice":"图片处理将调用付费图片模型，费用按实际调用计费。点击“开始生成”即表示确认本次调用。",
      "submitLabel":"开始生成",
      "revisionSubmitLabel":"生成新版本"
    }'::jsonb,
    output_schema_json,
    config_json || '{
      "operationModes":["change_ratio","expand"],
      "changeRatioExpansionScale":1.0,
      "expandUsesSourceAspectRatio":true
    }'::jsonb,
    now()
from feature_version
where feature_id = 'af9b8384-1ca6-4cc8-888d-a71b6965ea50'
  and version = 2;

update feature_definition
set current_version = 3,
    description = '可选择改变画布比例，或保持原图比例按倍数向四周扩展。',
    updated_at = now()
where code = 'image.expand';

update model_deployment
set config_json = config_json || '{"imageExpansionDimensionMultiple":1}'::jsonb,
    updated_at = now()
where code = 'codex2api-gpt-image-2-image';

update model_deployment
set config_json = config_json || '{"imageExpansionDimensionMultiple":16}'::jsonb,
    updated_at = now()
where code = 'aliyun-qwen-image-2-0';
