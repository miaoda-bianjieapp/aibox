update model_deployment
set config_json = config_json || '{
      "gateway":"new-api",
      "gatewayVersion":"v1.0.0-rc.21",
      "videoPath":"/video/generations",
      "videoStatusPath":"/videos/{requestId}"
    }'::jsonb,
    updated_at = now()
where code = 'codex2api-grok-imagine-video';

update feature_model_option
set description = 'Grok Imagine Video 1.5 through New API using separate submit and status routes.'
where deployment_code = 'codex2api-grok-imagine-video';
