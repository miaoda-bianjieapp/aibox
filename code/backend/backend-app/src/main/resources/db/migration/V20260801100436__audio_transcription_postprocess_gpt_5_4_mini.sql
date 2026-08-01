insert into model_route (
    id,
    model_alias,
    capability,
    deployment_code,
    priority,
    enabled,
    created_at
) values (
    '57ac0156-74d0-420e-87ee-8de9d53cab48',
    'text.audio-transcription-postprocess',
    'TEXT_GENERATION',
    'codex2api-gpt-5-4-mini-text',
    10,
    true,
    now()
)
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = excluded.enabled;

update model_route
set enabled = false
where model_alias = 'text.audio-transcription-postprocess'
  and capability = 'TEXT_GENERATION'
  and deployment_code <> 'codex2api-gpt-5-4-mini-text';

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
    'codex2api-gpt-5-4-mini-text',
    'GPT-5.4 Mini',
    '用于将完整逐字稿整理为摘要或会议纪要。',
    10,
    true
from feature_model_policy policy
where policy.feature_code = 'audio.transcription'
  and policy.capability = 'TEXT_GENERATION'
on conflict (policy_id, deployment_code) do update
set display_name = excluded.display_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = excluded.enabled;

update feature_model_policy
set default_deployment_code = 'codex2api-gpt-5-4-mini-text',
    updated_at = now()
where feature_code = 'audio.transcription'
  and capability = 'TEXT_GENERATION';
