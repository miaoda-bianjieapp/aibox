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
    '9ad8f2f0-2bd8-4d86-a0bb-8b21fd2aa652',
    definition.id,
    5,
    jsonb_set(
        version.input_schema_json,
        '{properties,postProcess,description}',
        to_jsonb('逐字稿始终生成；选择会议纪要时，明显不是会议的内容会自动改为摘要。'::text),
        true
    ),
    jsonb_set(
        version.ui_schema_json,
        '{fieldHelp,postProcess}',
        '{
          "text":"选择会议纪要时，若内容明显属于朗读、课程、播报或故事等非会议内容，将自动生成摘要。"
        }'::jsonb,
        true
    ),
    version.output_schema_json,
    version.config_json,
    now()
from feature_definition definition
join feature_version version
  on version.feature_id = definition.id
 and version.version = 4
where definition.code = 'audio.transcription';

update feature_definition
set current_version = 5,
    updated_at = now()
where code = 'audio.transcription';
