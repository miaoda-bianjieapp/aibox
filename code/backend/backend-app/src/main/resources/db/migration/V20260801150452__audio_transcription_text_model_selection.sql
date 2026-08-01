update model_route
set priority = case deployment_code
        when 'zhipu-glm-4-5-air-text' then 10
        when 'codex2api-gpt-5-4-mini-text' then 20
        else priority
    end,
    enabled = true
where model_alias = 'text.audio-transcription-postprocess'
  and capability = 'TEXT_GENERATION'
  and deployment_code in (
      'zhipu-glm-4-5-air-text',
      'codex2api-gpt-5-4-mini-text'
  );

update feature_model_policy
set default_deployment_code = 'zhipu-glm-4-5-air-text',
    allow_user_selection = true,
    updated_at = now()
where feature_code = 'audio.transcription'
  and capability = 'TEXT_GENERATION';

insert into feature_model_option (
    policy_id,
    deployment_code,
    display_name,
    description,
    sort_order,
    enabled
)
select
    policy.id,
    option.deployment_code,
    option.display_name,
    option.description,
    option.sort_order,
    true
from feature_model_policy policy
cross join (
    values
        (
            'zhipu-glm-4-5-air-text',
            'GLM-4.5-Air',
            '推荐，适合稳定生成摘要和结构化会议纪要。',
            10
        ),
        (
            'codex2api-gpt-5-4-mini-text',
            'GPT-5.4 Mini',
            '实验选项，适合较短内容；复杂会议纪要可能需要重试。',
            20
        )
) as option(deployment_code, display_name, description, sort_order)
where policy.feature_code = 'audio.transcription'
  and policy.capability = 'TEXT_GENERATION'
on conflict (policy_id, deployment_code) do update
set display_name = excluded.display_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = excluded.enabled;

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
    'e3cd5d03-62f7-49ea-9bfa-cd847c096e53',
    definition.id,
    3,
    version.input_schema_json,
    jsonb_set(
        version.ui_schema_json,
        '{modelSelectors}',
        '{
          "TEXT_GENERATION":{
            "label":"摘要/会议纪要模型",
            "widget":"segmented",
            "showSelectedIcon":false,
            "compact":true,
            "labelMaxLines":1
          }
        }'::jsonb,
        true
    ),
    version.output_schema_json,
    version.config_json,
    now()
from feature_definition definition
join feature_version version
  on version.feature_id = definition.id
 and version.version = 2
where definition.code = 'audio.transcription'
  and not exists (
      select 1
      from feature_version existing
      where existing.feature_id = definition.id
        and existing.version = 3
  );

update feature_definition
set current_version = 3,
    updated_at = now()
where code = 'audio.transcription';
