update model_provider
set provider_kind = 'OFFICIAL',
    enabled = true,
    updated_at = now()
where code = 'zhipu-bigmodel';

insert into model_deployment (
    id,
    code,
    provider_code,
    display_name,
    description,
    capability,
    provider_model,
    enabled,
    selectable,
    config_json,
    created_at,
    updated_at
) values
    (
        '41000000-0000-0000-0000-000000000011',
        'zhipu-glm-5-2-text',
        'zhipu-bigmodel',
        'GLM-5.2',
        'Official Zhipu flagship text model for long-context writing tasks',
        'TEXT_GENERATION',
        'glm-5.2',
        true,
        true,
        '{"source":"official","discovery":"GET /models","protocol":"openai-compatible"}',
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000012',
        'zhipu-glm-4-5-air-text',
        'zhipu-bigmodel',
        'GLM-4.5-Air',
        'Official Zhipu lightweight text model for cost-sensitive writing tasks',
        'TEXT_GENERATION',
        'glm-4.5-air',
        true,
        true,
        '{"source":"official","discovery":"GET /models","protocol":"openai-compatible"}',
        now(),
        now()
    )
on conflict (code) do nothing;

update model_deployment
set enabled = false,
    selectable = false,
    updated_at = now()
where code = 'zhipu-glm-5v-turbo-text';

update model_route
set enabled = false
where model_alias = 'text.default'
  and capability = 'TEXT_GENERATION'
  and deployment_code = 'zhipu-glm-5v-turbo-text';

insert into model_route (
    id,
    model_alias,
    capability,
    deployment_code,
    priority,
    enabled,
    created_at
) values
    (
        '42000000-0000-0000-0000-000000000011',
        'text.default',
        'TEXT_GENERATION',
        'zhipu-glm-5-2-text',
        10,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000012',
        'text.default',
        'TEXT_GENERATION',
        'zhipu-glm-4-5-air-text',
        15,
        true,
        now()
    )
on conflict (model_alias, capability, deployment_code) do nothing;

update feature_model_policy
set default_deployment_code = 'zhipu-glm-5-2-text',
    allow_user_selection = true,
    updated_at = now()
where feature_code in (
    'writing.draft',
    'writing.rewrite_polish',
    'writing.translate',
    'writing.outline_ideas'
)
  and capability = 'TEXT_GENERATION';

update feature_model_option
set enabled = false
where deployment_code = 'zhipu-glm-5v-turbo-text';

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
    deployment.code,
    deployment.display_name,
    case deployment.code
        when 'zhipu-glm-5-2-text'
            then 'Official flagship model with a 200K context window for complex writing tasks'
        when 'zhipu-glm-4-5-air-text'
            then 'Official lightweight model for faster and more cost-sensitive writing tasks'
    end,
    case deployment.code
        when 'zhipu-glm-5-2-text' then 10
        when 'zhipu-glm-4-5-air-text' then 15
    end,
    true
from feature_model_policy policy
cross join model_deployment deployment
where policy.feature_code in (
    'writing.draft',
    'writing.rewrite_polish',
    'writing.translate',
    'writing.outline_ideas'
)
  and policy.capability = 'TEXT_GENERATION'
  and deployment.code in ('zhipu-glm-5-2-text', 'zhipu-glm-4-5-air-text')
on conflict (policy_id, deployment_code) do update
set display_name = excluded.display_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = excluded.enabled;
