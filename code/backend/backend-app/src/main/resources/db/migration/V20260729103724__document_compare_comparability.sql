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
     and version.version = 2
),
output_with_required as (
    select
        previous.*,
        jsonb_set(
            previous.output_schema_json,
            '{required}',
            previous.output_schema_json -> 'required'
                || '["comparability"]'::jsonb
        ) as schema_json
    from previous
),
output_with_property as (
    select
        output_with_required.*,
        jsonb_set(
            schema_json,
            '{properties,comparability}',
            '{"$ref":"#/$defs/comparability"}'::jsonb,
            true
        ) as next_schema_json
    from output_with_required
),
output_with_terminal_rule as (
    select
        output_with_property.*,
        jsonb_set(
            next_schema_json,
            '{allOf}',
            $json$
            [
              {
                "if":{
                  "properties":{
                    "comparability":{
                      "properties":{
                        "status":{"enum":["IDENTICAL","NOT_COMPARABLE"]}
                      },
                      "required":["status"]
                    }
                  },
                  "required":["comparability"]
                },
                "then":{
                  "properties":{
                    "crossDocumentConclusion":{
                      "properties":{"findings":{"maxItems":0}}
                    },
                    "risks":{"maxItems":0}
                  }
                }
              }
            ]
            $json$::jsonb,
            true
        ) as terminal_schema_json
    from output_with_property
),
output_with_definition as (
    select
        output_with_terminal_rule.*,
        jsonb_set(
            terminal_schema_json,
            '{$defs,comparability}',
            $json$
            {
              "type":"object",
              "required":[
                "status","reason","sharedTopics","citationMarkers"
              ],
              "properties":{
                "status":{
                  "type":"string",
                  "enum":[
                    "IDENTICAL",
                    "COMPARABLE",
                    "PARTIALLY_COMPARABLE",
                    "NOT_COMPARABLE"
                  ]
                },
                "reason":{
                  "type":"string",
                  "minLength":1,
                  "maxLength":2000
                },
                "sharedTopics":{
                  "type":"array",
                  "maxItems":20,
                  "uniqueItems":true,
                  "items":{
                    "type":"string",
                    "minLength":1,
                    "maxLength":500
                  }
                },
                "citationMarkers":{
                  "type":"array",
                  "minItems":2,
                  "uniqueItems":true,
                  "items":{
                    "type":"string",
                    "pattern":"^S[1-9][0-9]*$"
                  }
                }
              },
              "allOf":[
                {
                  "if":{
                    "properties":{
                      "status":{"const":"PARTIALLY_COMPARABLE"}
                    },
                    "required":["status"]
                  },
                  "then":{
                    "properties":{
                      "sharedTopics":{"minItems":1}
                    }
                  }
                }
              ],
              "additionalProperties":false
            }
            $json$::jsonb,
            true
        ) as definition_schema_json
    from output_with_terminal_rule
),
output_with_difference_markers as (
    select
        output_with_definition.*,
        jsonb_set(
            definition_schema_json,
            '{$defs,difference,properties,citationMarkers,minItems}',
            '1'::jsonb
        ) as difference_schema_json
    from output_with_definition
),
output_with_pair_required as (
    select
        output_with_difference_markers.*,
        jsonb_set(
            difference_schema_json,
            '{$defs,pairwiseComparison,required}',
            difference_schema_json
                -> '$defs'
                -> 'pairwiseComparison'
                -> 'required'
                || '["comparability"]'::jsonb
        ) as pair_required_schema_json
    from output_with_difference_markers
),
output_with_pair_property as (
    select
        output_with_pair_required.*,
        jsonb_set(
            pair_required_schema_json,
            '{$defs,pairwiseComparison,properties,comparability}',
            '{"$ref":"#/$defs/comparability"}'::jsonb,
            true
        ) as pair_property_schema_json
    from output_with_pair_required
),
output_with_pair_rule as (
    select
        output_with_pair_property.*,
        jsonb_set(
            pair_property_schema_json,
            '{$defs,pairwiseComparison,allOf}',
            $json$
            [
              {
                "if":{
                  "properties":{
                    "comparability":{
                      "properties":{
                        "status":{"enum":["IDENTICAL","NOT_COMPARABLE"]}
                      },
                      "required":["status"]
                    }
                  },
                  "required":["comparability"]
                },
                "then":{
                  "properties":{
                    "differences":{"maxItems":0}
                  }
                }
              }
            ]
            $json$::jsonb,
            true
        ) as final_schema_json
    from output_with_pair_property
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
    'd47f0ab9-160a-4a60-b778-0a2e19a1d51f',
    feature_id,
    3,
    input_schema_json,
    ui_schema_json,
    final_schema_json,
    config_json,
    now()
from output_with_pair_rule
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 3,
    description = '对比最多五份文档，识别完全相同、可比、部分可比或不可比情况，并提供差异、风险和可追溯来源。',
    updated_at = now()
where code = 'document.compare'
  and current_version = 2;
