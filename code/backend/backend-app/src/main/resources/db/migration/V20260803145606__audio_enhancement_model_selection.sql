update feature_model_policy
set allow_user_selection = true,
    updated_at = now()
where feature_code = 'audio.enhancement'
  and capability = 'AUDIO_ENHANCEMENT';
