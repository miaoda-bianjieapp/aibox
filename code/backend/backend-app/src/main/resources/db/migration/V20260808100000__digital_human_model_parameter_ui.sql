-- Make model-specific controls visible in the catalog and in the persisted digital human form.
update model_deployment
set config_json = config_json || $constraints$
{
  "parameterOptions": {
    "avatarSource": ["UPLOAD", "HISTORY", "AI_GENERATED"],
    "audioSource": ["TEXT_TO_SPEECH", "UPLOAD_AUDIO"],
    "voiceGenerationMode": ["TTS"],
    "aspectRatio": ["SOURCE"],
    "resolution": ["SOURCE"]
  },
  "parameterConstraints": {
    "aspectRatio": {"mode":"source-image", "editable":false, "allowedValues":["SOURCE"]},
    "resolution": {"mode":"provider-derived", "editable":false, "allowedValues":["SOURCE"]},
    "durationSeconds": {"mode":"audio-derived", "editable":false, "min":1, "max":60},
    "performancePrompt": {"mode":"facial-expression-plan", "maxLength":500},
    "negativePrompt": {"mode":"unsupported", "editable":false}
  },
  "maxDurationSeconds": 60
}
$constraints$::jsonb,
    updated_at = now()
where code = 'did-talks-v1-video';

update model_deployment
set config_json = config_json || $constraints$
{
  "parameterConstraints": {
    "aspectRatio": {"allowedValues":["1:1","16:9","9:16"]},
    "resolution": {"allowedValues":["720p","1080p"]}
  }
}
$constraints$::jsonb,
    updated_at = now()
where code in ('codex2api-gpt-image-2-image', 'aliyun-qwen-image-2-0');

update model_deployment
set config_json = config_json || $constraints$
{
  "parameterConstraints": {
    "speed": {"min":0.5,"max":2.0,"step":0.05},
    "script": {"maxLength":300}
  }
}
$constraints$::jsonb,
    updated_at = now()
where code in ('openai2api-index-tts2-tts', 'openai2api-omnivoice-tts');

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '42000000-0000-0000-0000-000000000716',
    definition.id,
    16,
    jsonb_set(
      jsonb_set(
        previous.input_schema_json,
        '{properties,aspectRatio,enum}',
        '["9:16","16:9","21:9","1:1","SOURCE"]'::jsonb,
        true
      ),
      '{properties,resolution,enum}',
      '["720p","1080p","SOURCE"]'::jsonb,
      true
    ),
    jsonb_set(
      jsonb_set(
        jsonb_set(
          jsonb_set(
            previous.ui_schema_json,
            '{enumLabels,aspectRatio,SOURCE}',
            to_jsonb('由人物图片决定'::text),
            true
          ),
          '{enumLabels,resolution,SOURCE}',
          to_jsonb('由 Provider 决定'::text),
          true
        ),
        '{fieldHelp,durationSeconds,text}',
        to_jsonb('时长由已确认音频自动决定：文本模式按文案和语速估算，上传音频读取真实时长；超过当前模型上限时不能提交。'::text),
        true
      ),
      '{fieldOptions,durationSeconds,readOnly}',
      'true'::jsonb,
      true
    ),
    previous.output_schema_json,
    previous.config_json || '{"durationMode":"audio-derived","modelParameterConstraints":true}'::jsonb,
    now()
from feature_definition definition
join feature_version previous on previous.feature_id = definition.id and previous.version = 15
where definition.code = 'video.digital_human'
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 16,
    updated_at = now()
where code = 'video.digital_human';
