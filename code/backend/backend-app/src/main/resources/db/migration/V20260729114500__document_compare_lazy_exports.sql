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
     and version.version = 3
),
output_with_required as (
    select
        previous.*,
        jsonb_set(
            previous.output_schema_json,
            '{required}',
            previous.output_schema_json -> 'required'
                || '["summary","exportOptions"]'::jsonb
        ) as schema_json
    from previous
),
output_with_summary as (
    select
        output_with_required.*,
        jsonb_set(
            schema_json,
            '{properties,summary}',
            '{"type":"string","minLength":1}'::jsonb,
            true
        ) as summary_schema_json
    from output_with_required
),
output_with_export_options as (
    select
        output_with_summary.*,
        jsonb_set(
            summary_schema_json,
            '{properties,exportOptions}',
            $json$
            {
              "type":"array",
              "minItems":1,
              "maxItems":2,
              "uniqueItems":true,
              "items":{"$ref":"#/$defs/exportOption"}
            }
            $json$::jsonb,
            true
        ) as option_schema_json
    from output_with_summary
),
output_with_export_definition as (
    select
        output_with_export_options.*,
        jsonb_set(
            option_schema_json,
            '{$defs,exportOption}',
            $json$
            {
              "type":"object",
              "required":["type","label","fileName","mediaType"],
              "properties":{
                "type":{
                  "type":"string",
                  "enum":["excel","annotatedBaseline"]
                },
                "label":{
                  "type":"string",
                  "minLength":1,
                  "maxLength":100
                },
                "fileName":{
                  "type":"string",
                  "minLength":1,
                  "maxLength":500
                },
                "mediaType":{
                  "type":"string",
                  "minLength":1,
                  "maxLength":160
                }
              },
              "additionalProperties":false
            }
            $json$::jsonb,
            true
        ) as final_schema_json
    from output_with_export_options
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
    'be20ad46-0121-45a1-b6e9-98f811944f07',
    feature_id,
    4,
    input_schema_json,
    ui_schema_json,
    final_schema_json,
    config_json,
    now()
from output_with_export_definition
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 4,
    description = '先生成可追溯的多文档文本对比结果，Excel 报告和基准标注文档在用户点击导出时按需生成。',
    updated_at = now()
where code = 'document.compare'
  and current_version = 3;
