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
    '6f8dd788-6546-4080-8315-e228465c7654',
    definition.id,
    4,
    version.input_schema_json,
    jsonb_set(
        version.ui_schema_json,
        '{modelSelectors,TEXT_GENERATION}',
        '{
          "label":"摘要/会议纪要模型",
          "widget":"dropdown"
        }'::jsonb,
        true
    ),
    version.output_schema_json,
    version.config_json,
    now()
from feature_definition definition
join feature_version version
  on version.feature_id = definition.id
 and version.version = 3
where definition.code = 'audio.transcription';

update feature_definition
set current_version = 4,
    updated_at = now()
where code = 'audio.transcription';
