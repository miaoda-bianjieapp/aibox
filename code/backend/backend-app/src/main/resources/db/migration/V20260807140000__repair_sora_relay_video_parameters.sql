update model_deployment
set config_json = config_json || '{
      "parameterOptions":{
        "durationSeconds":["4","8","12"],
        "aspectRatio":["16:9","9:16"],
        "resolution":["720p"]
      }
    }'::jsonb,
    updated_at = now()
where code = 'codex2api-sora-2-video';

update feature_model_option
set description = 'Sora 2 relay supports 4, 8, or 12 second video generation; this whitelist exposes 720p.'
where deployment_code = 'codex2api-sora-2-video';
