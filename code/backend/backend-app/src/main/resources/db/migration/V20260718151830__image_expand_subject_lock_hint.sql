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
    '9d01ec61-ffdd-4b8a-9041-e94567de6ea5',
    feature_id,
    5,
    input_schema_json,
    jsonb_set(
        ui_schema_json,
        '{fieldHelp}',
        '{
          "operationMode":{
            "when":{"field":"operationMode","equals":"change_ratio"},
            "text":"该选项会给改比例后的图片进行填充处理",
            "tone":"danger"
          }
        }'::jsonb
    ),
    output_schema_json,
    config_json || '{
      "changeRatioSubjectLock":true,
      "changeRatioEffectivePreservationMode":"strict",
      "changeRatioFillOutsideSourceOnly":true
    }'::jsonb,
    now()
from feature_version
where feature_id = 'af9b8384-1ca6-4cc8-888d-a71b6965ea50'
  and version = 4;

update feature_definition
set current_version = 5,
    updated_at = now()
where code = 'image.expand';
