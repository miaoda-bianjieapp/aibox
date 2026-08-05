update feature_model_policy
set default_deployment_code = 'openai2api-omnivoice-tts',
    allow_user_selection = true,
    updated_at = now()
where feature_code = 'audio.text_to_speech'
  and capability = 'TEXT_TO_SPEECH';
