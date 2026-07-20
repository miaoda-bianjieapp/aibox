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
    '8b3d752f-3988-46b8-8c7a-e25c49fa92f6',
    feature_id,
    4,
    jsonb_set(
        jsonb_set(
            jsonb_set(
                input_schema_json,
                '{properties,presetExpansionScale,default}',
                '"1.0"'::jsonb
            ),
            '{properties,customExpansionScale}',
            (input_schema_json #> '{properties,customExpansionScale}') - 'maximum'
        ),
        '{properties,customExpansionScale,description}',
        '"输入不小于 1.0 的倍数，例如 1.8；可用上限由所选模型和原图尺寸决定。"'::jsonb
    ),
    ui_schema_json,
    output_schema_json,
    (config_json - 'maxExpansionScale') || '{
      "defaultExpansionScale":1.0,
      "expansionScaleLimit":"selected-model"
    }'::jsonb,
    now()
from feature_version
where feature_id = 'af9b8384-1ca6-4cc8-888d-a71b6965ea50'
  and version = 3;

update feature_definition
set current_version = 4,
    updated_at = now()
where code = 'image.expand';
