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
    'a4ee3bf2-75fa-465e-8d98-b097268d928c',
    feature.id,
    2,
    previous.input_schema_json,
    jsonb_set(
        jsonb_set(
            previous.ui_schema_json,
            '{fieldOptions,baselineDocument,excludeAssetsSelectedInFields}',
            '["comparisonDocuments"]'::jsonb
        ),
        '{fieldOptions,comparisonDocuments,excludeAssetsSelectedInFields}',
        '["baselineDocument"]'::jsonb
    ),
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 1
where feature.code = 'document.compare'
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'document.compare'
  and current_version = 1;
