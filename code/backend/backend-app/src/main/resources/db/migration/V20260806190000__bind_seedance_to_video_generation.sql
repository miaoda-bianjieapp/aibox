update feature_version
set input_schema_json = jsonb_set(
        input_schema_json,
        '{properties,durationSeconds,enum}',
        '[4,8,12,15,16,20]'::jsonb,
        true
    )
where feature_id = (
    select id
    from feature_definition
    where code = 'video.generate'
)
and version = 1;

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
    options.deployment_code,
    options.display_name,
    options.description,
    options.sort_order,
    true
from feature_model_policy policy
cross join (
    values
        (
            'newapi-seedance-2-0-video',
            'Seedance 2.0',
            'Relay Seedance 2.0: 4, 8, 12, or 15 seconds at 720p.',
            30
        ),
        (
            'newapi-seedance-2-0-fast-video',
            'Seedance 2.0 Fast',
            'Relay Seedance 2.0 Fast: speed priority, 4, 8, 12, or 15 seconds at 720p.',
            40
        )
) as options(deployment_code, display_name, description, sort_order)
where policy.feature_code = 'video.generate'
  and policy.capability = 'VIDEO_GENERATION'
on conflict (policy_id, deployment_code) do update
set display_name = excluded.display_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true;
