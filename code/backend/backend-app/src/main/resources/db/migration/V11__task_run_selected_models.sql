alter table task_run
    add column selected_models_json jsonb not null default '{}'::jsonb;

update task_run run
set selected_models_json = jsonb_build_object(
    coalesce(
        (
            select policy.capability
            from feature_model_policy policy
            where policy.feature_code = run.feature_code
              and policy.default_deployment_code = run.selected_model_code
            order by policy.capability
            limit 1
        ),
        (
            select policy.capability
            from feature_model_policy policy
            where policy.feature_code = run.feature_code
            order by policy.capability
            limit 1
        ),
        'TEXT_GENERATION'
    ),
    run.selected_model_code
)
where run.selected_model_code is not null;
