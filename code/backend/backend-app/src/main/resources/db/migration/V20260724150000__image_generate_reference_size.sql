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
    '5f9e46d1-2203-4a88-9600-000000000003',
    feature.id,
    3,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{fieldOptions,referenceImages,maxFileSizeBytes}',
        '20971520'::jsonb,
        true
    ),
    previous.output_schema_json,
    jsonb_set(
        previous.config_json,
        '{maxReferenceImageBytes}',
        '20971520'::jsonb,
        true
    ),
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 2
where feature.code = 'image.generate'
on conflict do nothing;

update feature_definition
set current_version = 3,
    updated_at = now()
where code = 'image.generate'
  and current_version = 2;
