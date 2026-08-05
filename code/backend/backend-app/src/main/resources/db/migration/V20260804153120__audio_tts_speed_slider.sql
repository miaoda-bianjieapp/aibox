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
    'b8676aa7-13a3-4dfd-9ed4-2f7ef287199e',
    fv.feature_id,
    2,
    $json$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "text",
    "voice",
    "speed",
    "emotion"
  ],
  "properties": {
    "text": {
      "type": "string",
      "minLength": 1,
      "maxLength": 500,
      "title": "输入文字",
      "description": "输入需要合成为语音的中文内容，最多 500 字。"
    },
    "voice": {
      "type": "string",
      "enum": [
        "science_female",
        "gentle_female"
      ],
      "default": "gentle_female",
      "title": "声音"
    },
    "speed": {
      "type": "number",
      "minimum": 0.5,
      "maximum": 2,
      "multipleOf": 0.05,
      "default": 1,
      "title": "语速",
      "description": "拖动滑块调整语速，范围为 0.5× 到 2.0×。"
    },
    "emotion": {
      "type": "string",
      "enum": [
        "natural"
      ],
      "default": "natural",
      "title": "情绪"
    }
  },
  "additionalProperties": false
}
    $json$::jsonb,
    $json$
{
  "order": [
    "text",
    "voice",
    "speed",
    "emotion"
  ],
  "widgets": {
    "text": "textarea",
    "voice": "dropdown",
    "speed": "slider",
    "emotion": "segmented"
  },
  "enumLabels": {
    "voice": {
      "science_female": "科普视频女声",
      "gentle_female": "温柔女声"
    },
    "emotion": {
      "natural": "自然"
    }
  },
  "fieldOptions": {
    "speed": {
      "step": 0.05,
      "suffix": "×",
      "decimalPlaces": 2,
      "minimumFractionDigits": 1,
      "legacyValues": {
        "slow": 0.75,
        "normal": 1,
        "fast": 1.25,
        "very_fast": 1.5
      }
    },
    "emotion": {
      "showSelectedIcon": false,
      "compact": true,
      "labelMaxLines": 1
    }
  },
  "fieldHelp": {
    "text": {
      "text": "最多输入 500 字，每次生成一段 WAV 音频。"
    },
    "emotion": {
      "text": "首版仅支持自然情绪。"
    },
    "speed": {
      "text": "可在 0.5× 到 2.0× 之间调整语速。"
    }
  },
  "feeNotice": "文字转语音会调用付费语音模型。点击“开始生成”即表示确认本次调用。",
  "submitLabel": "开始生成",
  "revisionSubmitLabel": "重新生成并保存新版本"
}
    $json$::jsonb,
    fv.output_schema_json,
    jsonb_set(
        fv.config_json,
        '{speedRange}',
        '{"minimum":0.5,"maximum":2.0,"step":0.05,"default":1.0}'::jsonb,
        true
    ),
    now()
from feature_version fv
join feature_definition fd on fd.id = fv.feature_id
where fd.code = 'audio.text_to_speech'
  and fv.version = 1;

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'audio.text_to_speech';
