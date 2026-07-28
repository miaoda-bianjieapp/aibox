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
    '7fa6a5a3-1ca2-4440-b4b4-43c321d32eda',
    feature.id,
    3,
    jsonb_set(
        previous.input_schema_json,
        '{properties,targetLanguage,enum}',
        '["zh-CN","zh-TW","en","ja","ko","fr","de","es","ru"]'::jsonb,
        true
    ),
    previous.ui_schema_json #- '{enumLabels,targetLanguage,ar}',
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 2
where feature.code = 'document.translate';

update feature_definition
set current_version = 3,
    updated_at = now()
where code = 'document.translate';
