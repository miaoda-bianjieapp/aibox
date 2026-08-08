-- Expose only parameters that D-ID actually uses and make duration audio-derived.
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

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '42000000-0000-0000-0000-000000000715',
    definition.id,
    15,
    jsonb_set(
      jsonb_set(
        previous.input_schema_json,
        '{properties,aspectRatio,enum}',
        '["9:16","16:9","21:9","SOURCE"]'::jsonb,
        true
      ),
      '{properties,resolution,enum}',
      '["720p","1080p","SOURCE"]'::jsonb,
      true
    ),
    previous.ui_schema_json,
    previous.output_schema_json,
    previous.config_json || '{"durationMode":"audio-derived","modelParameterConstraints":true}'::jsonb,
    now()
from feature_definition definition
join feature_version previous on previous.feature_id = definition.id and previous.version = 14
where definition.code = 'video.digital_human'
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 15,
    updated_at = now()
where code = 'video.digital_human';
