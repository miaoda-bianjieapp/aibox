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
    'c1299460-36ab-4a99-85cd-a79530f5d197',
    feature.id,
    2,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["mode","sourceText"],
      "properties":{
        "mode":{
          "type":"string",
          "enum":["rewrite","polish"],
          "default":"rewrite",
          "title":"处理方式",
          "description":"改写会调整措辞、句式和段落结构；润色会尽量保留原结构。"
        },
        "sourceText":{
          "type":"string",
          "minLength":1,
          "maxLength":2000,
          "title":"原文内容",
          "description":"输入需要改写或润色的纯文本，输出将保持原文语言。"
        },
        "rewriteRequirements":{
          "type":"string",
          "maxLength":500,
          "title":"改写需求",
          "description":"可选，例如更口语化、压缩篇幅、增强节奏或保留幽默感。"
        },
        "polishRequirements":{
          "type":"string",
          "maxLength":500,
          "title":"润色需求",
          "description":"可选，例如表达更自然、更专业、修正标点或改善衔接。"
        }
      },
      "additionalProperties":false
    }'::jsonb,
    '{
      "order":["mode","sourceText","rewriteRequirements","polishRequirements"],
      "widgets":{
        "mode":"segmented",
        "sourceText":"textarea",
        "rewriteRequirements":"textarea",
        "polishRequirements":"textarea"
      },
      "visibility":{
        "rewriteRequirements":{"field":"mode","equals":"rewrite"},
        "polishRequirements":{"field":"mode","equals":"polish"}
      },
      "enumLabels":{"mode":{"rewrite":"改写","polish":"润色"}},
      "examples":{
        "sourceText":"我们团队最近完成了产品的新版本开发，这个版本加入了多个实用功能，也解决了一些之前存在的问题，希望能够给用户带来更好的使用体验。"
      },
      "actions":{"showReset":true}
    }'::jsonb,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["format","text"],
      "properties":{"format":{"const":"markdown"},"text":{"type":"string","minLength":1}},
      "additionalProperties":false
    }'::jsonb,
    '{
      "modelAlias":"text.default",
      "maxOutputTokens":3000,
      "capabilities":["TEXT_GENERATION"],
      "revisionSourceField":"sourceText"
    }'::jsonb,
    now()
from feature_definition feature
where feature.code = 'writing.rewrite_polish';

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'writing.rewrite_polish';
