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
    '85435eee-bf94-4bed-a7d5-5d349c9bbba1',
    workspace.id,
    'writing.rewrite_polish',
    '改写与润色',
    '在保持事实、核心含义和原文语言的前提下改写或润色文本。',
    'INTERNAL',
    1,
    'rich_text',
    'rich_text_editor',
    'ASYNC',
    20,
    now(),
    now()
from workspace
where workspace.code = 'writing';

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
    'ddfb3d08-5bd1-402c-809e-2e16604409fc',
    '85435eee-bf94-4bed-a7d5-5d349c9bbba1',
    1,
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
        }
      },
      "additionalProperties":false
    }',
    '{
      "order":["mode","sourceText"],
      "widgets":{"mode":"segmented","sourceText":"textarea"},
      "enumLabels":{"mode":{"rewrite":"改写","polish":"润色"}},
      "examples":{
        "sourceText":"我们团队最近完成了产品的新版本开发，这个版本加入了多个实用功能，也解决了一些之前存在的问题，希望能够给用户带来更好的使用体验。"
      },
      "actions":{"showReset":true}
    }',
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["format","text"],
      "properties":{"format":{"const":"markdown"},"text":{"type":"string","minLength":1}},
      "additionalProperties":false
    }',
    '{
      "modelAlias":"text.default",
      "maxOutputTokens":3000,
      "capabilities":["TEXT_GENERATION"],
      "revisionSourceField":"sourceText"
    }',
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
    'ef5d2b24-a813-4952-b487-9aea754fa045',
    'writing.rewrite_polish',
    'TEXT_GENERATION',
    'codex2api-gpt-5-6-text',
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
        'ef5d2b24-a813-4952-b487-9aea754fa045',
        'codex2api-gpt-5-4-mini-text',
        'GPT-5.4 Mini',
        'Balanced text generation through a third-party relay',
        10,
        true
    ),
    (
        'ef5d2b24-a813-4952-b487-9aea754fa045',
        'codex2api-gpt-5-6-text',
        'GPT-5.6',
        'High-quality text generation through a third-party relay',
        20,
        true
    ),
    (
        'ef5d2b24-a813-4952-b487-9aea754fa045',
        'zhipu-glm-5v-turbo-text',
        'GLM-5V Turbo',
        'General-purpose drafting and rewriting model',
        30,
        true
    );
