with feature as (
    select id
    from feature_definition
    where code = 'document.compare'
),
previous as (
    select
        feature.id as feature_id,
        version.input_schema_json,
        version.ui_schema_json,
        version.output_schema_json,
        version.config_json
    from feature
    join feature_version version
      on version.feature_id = feature.id
     and version.version = 4
),
baseline_csv_mimes as (
    select
        previous.*,
        jsonb_set(
            previous.ui_schema_json,
            '{fieldOptions,baselineDocument,acceptedMimeTypes}',
            previous.ui_schema_json
                #> '{fieldOptions,baselineDocument,acceptedMimeTypes}'
                || '["application/csv","text/comma-separated-values"]'::jsonb
        ) as schema_json
    from previous
),
comparison_csv_mimes as (
    select
        baseline_csv_mimes.*,
        jsonb_set(
            schema_json,
            '{fieldOptions,comparisonDocuments,acceptedMimeTypes}',
            schema_json
                #> '{fieldOptions,comparisonDocuments,acceptedMimeTypes}'
                || '["application/csv","text/comma-separated-values"]'::jsonb
        ) as final_schema_json
    from baseline_csv_mimes
)
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
    '65ca73a1-1dd9-488b-b24f-c832abc5f76f',
    feature_id,
    5,
    input_schema_json,
    final_schema_json,
    output_schema_json,
    config_json,
    now()
from comparison_csv_mimes
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 5,
    updated_at = now()
where code = 'document.compare'
  and current_version = 4;
