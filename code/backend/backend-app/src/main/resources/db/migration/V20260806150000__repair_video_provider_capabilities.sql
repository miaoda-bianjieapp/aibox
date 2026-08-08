update model_deployment
set provider_model = 'grok-imagine-video-1.5',
    config_json = config_json || '{
      "officialModel":"grok-imagine-video-1.5",
      "parameterOptions":{
        "durationSeconds":["4","8","12"],
        "aspectRatio":["16:9","9:16"],
        "resolution":["720p"]
      }
    }'::jsonb,
    updated_at = now()
where code = 'codex2api-grok-imagine-video';

update feature_model_option
set display_name = 'Grok Imagine Video 1.5',
    description = 'Supports text, reference images, and multiple reference images for video generation.'
where deployment_code = 'codex2api-grok-imagine-video';

update model_deployment
set config_json = config_json || '{
      "parameterOptions":{
        "durationSeconds":["16","20"],
        "aspectRatio":["16:9","9:16"],
        "resolution":["720p"]
      }
    }'::jsonb,
    updated_at = now()
where code = 'codex2api-sora-2-video';

update feature_model_option
set description = 'Sora 2 supports 16 or 20 second video generation; this whitelist currently exposes 720p.'
where deployment_code = 'codex2api-sora-2-video';
