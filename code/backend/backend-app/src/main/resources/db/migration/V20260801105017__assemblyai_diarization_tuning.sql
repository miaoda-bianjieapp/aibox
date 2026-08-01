update model_deployment
set config_json = coalesce(config_json, '{}'::jsonb)
        || '{
          "minSpeakersExpected":1,
          "maxSpeakersExpected":6
        }'::jsonb,
    updated_at = now()
where code in (
    'assemblyai-universal-3-5-pro-audio',
    'assemblyai-universal-2-audio'
);

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
    '6a96cb52-519c-4fd5-bf40-96834aa13bfc',
    definition.id,
    2,
    jsonb_set(
        version.input_schema_json,
        '{properties,speakerDiarization,description}',
        to_jsonb(
            '用于多人对话，自动将不同发言者标记为说话人 A、B、C；'
            || '单人朗读、角色配音或音质较差时可能误分。'::text
        ),
        true
    ),
    jsonb_set(
        version.ui_schema_json,
        '{fieldHelp,speakerDiarization}',
        '{
          "text":"仅建议用于多人对话。单人朗读、角色配音、背景音乐或音质较差时可能被误分为多个说话人。"
        }'::jsonb,
        true
    ),
    version.output_schema_json,
    version.config_json,
    now()
from feature_definition definition
join feature_version version
  on version.feature_id = definition.id
 and version.version = 1
where definition.code = 'audio.transcription';

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'audio.transcription';
