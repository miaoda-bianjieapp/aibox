update model_deployment
set config_json = jsonb_set(
        coalesce(config_json, '{}'::jsonb),
        '{voiceMap}',
        '{
          "science_female":"voice_069da16e0fe399b207c3_v2",
          "gentle_female":"voice_4cb4da6d4aaa4e48aab7_v4"
        }'::jsonb,
        true
    ),
    updated_at = now()
where code = 'openai2api-gpt-sovits-v2-tts';

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
    '91a4181a-a5d7-4d4c-a7ea-af6a8583cd1c',
    workspace.id,
    'audio.text_to_speech',
    '文字转语音',
    '将不超过 500 字的中文文字合成为可播放、下载和历史回看的 WAV 音频。',
    'INTERNAL',
    1,
    'audio',
    'audio',
    'ASYNC',
    30,
    now(),
    now()
from workspace
where workspace.code = 'audio';

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
    'a7b19529-2657-43ec-82da-02c5ad803a05',
    '91a4181a-a5d7-4d4c-a7ea-af6a8583cd1c',
    1,
    $json$
    {
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["text","voice","speed","emotion"],
      "properties":{
        "text":{
          "type":"string",
          "minLength":1,
          "maxLength":500,
          "title":"输入文字",
          "description":"输入需要合成为语音的中文内容，最多 500 字。"
        },
        "voice":{
          "type":"string",
          "enum":["science_female","gentle_female"],
          "default":"gentle_female",
          "title":"声音"
        },
        "speed":{
          "type":"string",
          "enum":["slow","normal","fast","very_fast"],
          "default":"normal",
          "title":"语速"
        },
        "emotion":{
          "type":"string",
          "enum":["natural"],
          "default":"natural",
          "title":"情绪"
        }
      },
      "additionalProperties":false
    }
    $json$::jsonb,
    $json$
    {
      "order":["text","voice","speed","emotion"],
      "widgets":{
        "text":"textarea",
        "voice":"dropdown",
        "speed":"segmented",
        "emotion":"segmented"
      },
      "enumLabels":{
        "voice":{
          "science_female":"科普视频女声",
          "gentle_female":"温柔女声"
        },
        "speed":{
          "slow":"较慢",
          "normal":"正常",
          "fast":"较快",
          "very_fast":"快速"
        },
        "emotion":{
          "natural":"自然"
        }
      },
      "fieldOptions":{
        "speed":{
          "showSelectedIcon":false,
          "compact":true,
          "labelMaxLines":1
        },
        "emotion":{
          "showSelectedIcon":false,
          "compact":true,
          "labelMaxLines":1
        }
      },
      "fieldHelp":{
        "text":{
          "text":"最多输入 500 字，每次生成一段 WAV 音频。"
        },
        "emotion":{
          "text":"首版仅支持自然情绪。"
        }
      },
      "feeNotice":"文字转语音会调用付费语音模型。点击“开始生成”即表示确认本次调用。",
      "submitLabel":"开始生成",
      "revisionSubmitLabel":"重新生成并保存新版本"
    }
    $json$::jsonb,
    $json$
    {
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["assetId","name"],
      "properties":{
        "assetId":{
          "type":"string",
          "format":"uuid"
        },
        "name":{
          "type":"string",
          "minLength":1,
          "maxLength":500
        }
      },
      "additionalProperties":false
    }
    $json$::jsonb,
    $json$
    {
      "modelAliases":{
        "TEXT_TO_SPEECH":"speech.default"
      },
      "maxInputFiles":0,
      "maxTextCharacters":500,
      "outputFormat":"wav",
      "capabilities":["TEXT_TO_SPEECH"]
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
) values (
    'fdea7533-b994-4dca-b025-687dae27293c',
    'audio.text_to_speech',
    'TEXT_TO_SPEECH',
    'openai2api-gpt-sovits-v2-tts',
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
    'fdea7533-b994-4dca-b025-687dae27293c',
    'openai2api-gpt-sovits-v2-tts',
    'GPT-SoVITS v2 中文语音',
    '通过 OpenAI2API Unified TTS 服务生成中文 WAV 语音。',
    10,
    true
);
