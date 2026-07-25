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
    '30000000-0000-0000-0000-000000000022',
    feature.id,
    2,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{fieldOptions,documents,acceptedMimeTypes}',
        (
            previous.ui_schema_json
                #> '{fieldOptions,documents,acceptedMimeTypes}'
        ) || '["text/comma-separated-values"]'::jsonb
    ),
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 1
where feature.code = 'document.qa'
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'document.qa'
  and current_version = 1;
