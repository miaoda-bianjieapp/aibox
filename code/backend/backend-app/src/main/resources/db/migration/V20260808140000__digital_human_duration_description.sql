-- Follow up without modifying the already-applied v16 migration.
insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '42000000-0000-0000-0000-000000000717',
    definition.id,
    17,
    jsonb_set(
      previous.input_schema_json,
      '{properties,durationSeconds,description}',
      to_jsonb('由已确认音频自动计算；上传音频读取真实时长，文本模式按文案和语速估算，不能手动填写。'::text),
      true
    ),
    previous.ui_schema_json,
    previous.output_schema_json,
    previous.config_json || '{"durationDescriptionClarified":true}'::jsonb,
    now()
from feature_definition definition
join feature_version previous on previous.feature_id = definition.id and previous.version = 16
where definition.code = 'video.digital_human'
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 17,
    updated_at = now()
where code = 'video.digital_human';
