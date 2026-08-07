-- Register D-ID Official Talks and make it the only video option for digital_human v12.
insert into model_provider (
    id, code, display_name, protocol, provider_kind, enabled, created_at, updated_at
) values (
    '40000000-0000-0000-0000-000000000051',
    'd-id-official',
    'D-ID Official',
    'd-id',
    'OFFICIAL',
    true,
    now(),
    now()
) on conflict (code) do update set
    display_name = excluded.display_name,
    protocol = excluded.protocol,
    provider_kind = excluded.provider_kind,
    enabled = true,
    updated_at = now();

insert into model_deployment (
    id, code, provider_code, display_name, description, capability, provider_model,
    enabled, selectable, config_json, created_at, updated_at
) values (
    '41000000-0000-0000-0000-000000000051',
    'did-talks-v1-video',
    'd-id-official',
    'D-ID Talks',
    'Official asynchronous talking-avatar video generated from one confirmed image and one confirmed audio asset.',
    'VIDEO_GENERATION',
    'talks',
    true,
    true,
    $deployment$
    {
      "source":"official",
      "apiVersion":"v1",
      "videoProtocol":"d-id-talks-async",
      "imageUploadPath":"/images",
      "audioUploadPath":"/audios",
      "videoPath":"/talks",
      "videoStatusPath":"/talks/{taskId}",
      "videoPollIntervalMillis":5000,
      "videoPollTimeoutSeconds":900,
      "supportsReferenceImages":true,
      "requiresImageInput":true,
      "supportsExternalAudio":true,
      "supportsNativeAudio":false,
      "supportsAudioPreview":false,
      "supportsNegativePrompt":false,
      "aspectRatioMode":"source-image",
      "resolutionMode":"source-image",
      "parameterOptions":{
        "avatarSource":["UPLOAD","HISTORY","AI_GENERATED"],
        "audioSource":["TEXT_TO_SPEECH","UPLOAD_AUDIO"],
        "voiceGenerationMode":["TTS"],
        "aspectRatio":["9:16","16:9","21:9"],
        "resolution":["720p","1080p"]
      }
    }
    $deployment$::jsonb,
    now(),
    now()
) on conflict (code) do update set
    provider_code = excluded.provider_code,
    display_name = excluded.display_name,
    description = excluded.description,
    capability = excluded.capability,
    provider_model = excluded.provider_model,
    enabled = true,
    selectable = true,
    config_json = excluded.config_json,
    updated_at = now();

insert into model_route (
    id, model_alias, capability, deployment_code, priority, enabled, created_at
) values (
    '42000000-0000-0000-0000-000000000751',
    'video.did.default',
    'VIDEO_GENERATION',
    'did-talks-v1-video',
    10,
    true,
    now()
) on conflict (model_alias, capability, deployment_code) do update set
    priority = 10,
    enabled = true;

insert into feature_definition (
    id, workspace_id, code, display_name, description, status,
    current_version, result_type, renderer_key, execution_mode,
    sort_order, created_at, updated_at
) values (
    '20000000-0000-0000-0000-000000000710',
    '10000000-0000-0000-0000-000000000005',
    'video.digital_human',
    '数字人口播',
    'Confirm one avatar image and one TTS or uploaded audio asset, then generate a lip-synced video with D-ID Talks.',
    'INTERNAL',
    12,
    'video',
    'video',
    'ASYNC',
    20,
    now(),
    now()
) on conflict (code) do update set
    workspace_id = excluded.workspace_id,
    display_name = excluded.display_name,
    description = excluded.description,
    status = excluded.status,
    result_type = excluded.result_type,
    renderer_key = excluded.renderer_key,
    execution_mode = excluded.execution_mode,
    sort_order = excluded.sort_order,
    updated_at = now();

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '42000000-0000-0000-0000-000000000712',
    definition.id,
    12,
    $input${"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["avatarSource","avatarConfirmed","audioSource","audioConfirmed","aspectRatio","resolution","outputCount","fps","durationSeconds"],"properties":{"avatarSource":{"type":"string","enum":["UPLOAD","HISTORY","AI_GENERATED"],"default":"UPLOAD","title":"人物来源"},"avatarImage":{"type":"string","format":"uuid","title":"人物图片"},"avatarPrompt":{"type":"string","minLength":1,"maxLength":500,"title":"人物描述","description":"用于生成候选人物形象。"},"avatarConfirmed":{"type":"boolean","const":true,"title":"确认人物形象"},"audioSource":{"type":"string","enum":["TEXT_TO_SPEECH","UPLOAD_AUDIO"],"default":"TEXT_TO_SPEECH","title":"声音来源"},"script":{"type":"string","maxLength":300,"title":"口播文案","description":"最多 300 个字符。"},"audioFile":{"type":"string","format":"uuid","title":"上传音频"},"audioConfirmed":{"type":"boolean","const":true,"title":"确认使用音频"},"voiceGenerationMode":{"type":"string","default":"TTS","title":"语音生成方式","description":"独立 TTS 会先生成可试听、可确认的音频；VIDEO_NATIVE 仅在视频模型能提供提交前音频预览时开放，当前 D-ID 部署默认关闭。","enum":["TTS","VIDEO_NATIVE"]},"voice":{"type":"string","enum":["science_female","gentle_female"],"default":"gentle_female","title":"音色"},"speed":{"type":"number","minimum":0.5,"maximum":2.0,"multipleOf":0.05,"default":1.0,"title":"语速"},"emotion":{"type":"string","enum":["natural"],"default":"natural","title":"情绪"},"aspectRatio":{"type":"string","enum":["9:16","16:9","21:9","1:1"],"default":"9:16","title":"视频比例"},"resolution":{"type":"string","enum":["720p","1080p"],"default":"720p","title":"分辨率"},"performancePrompt":{"type":"string","maxLength":500,"title":"角色表现","description":"描述角色动作、情绪和镜头表现。"},"negativePrompt":{"type":"string","maxLength":500,"title":"负面提示词","description":"描述不希望出现的动作、镜头或画面。"},"outputCount":{"type":"integer","const":1,"default":1,"title":"输出数量"},"fps":{"type":"integer","const":30,"default":30,"title":"帧率"},"durationSeconds":{"type":"integer","minimum":1,"maximum":60,"default":5,"title":"视频时长（秒）","description":"用于控制最终视频长度；文本原生语音模式会根据文案自动建议。"}},"allOf":[{"if":{"properties":{"avatarSource":{"enum":["UPLOAD","HISTORY","AI_GENERATED"]}}},"then":{"required":["avatarImage"]}},{"if":{"properties":{"avatarSource":{"const":"AI_GENERATED"}}},"then":{"required":["avatarPrompt"]}},{"if":{"properties":{"audioSource":{"const":"TEXT_TO_SPEECH"},"voiceGenerationMode":{"const":"TTS"}}},"then":{"required":["script","voice","speed","emotion"]}},{"if":{"properties":{"audioSource":{"const":"TEXT_TO_SPEECH"},"voiceGenerationMode":{"const":"VIDEO_NATIVE"}}},"then":{"required":["script"]}},{"if":{"properties":{"audioSource":{"const":"UPLOAD_AUDIO"}}},"then":{"required":["audioFile"]}}],"additionalProperties":false}$input$::jsonb,
    $ui${"pageKey":"video.digital_human","order":["avatarSource","avatarImage","avatarPrompt","avatarConfirmed","audioSource","script","audioFile","audioConfirmed","voiceGenerationMode","voice","speed","emotion","aspectRatio","resolution","durationSeconds","performancePrompt","negativePrompt","outputCount","fps"],"widgets":{"avatarSource":"segmented","avatarImage":"image","avatarPrompt":"textarea","avatarConfirmed":"boolean","audioSource":"segmented","script":"textarea","audioFile":"audio","audioConfirmed":"boolean","voice":"dropdown","speed":"slider","emotion":"segmented","aspectRatio":"segmented","resolution":"segmented","performancePrompt":"textarea","negativePrompt":"textarea","outputCount":"hidden","fps":"hidden","voiceGenerationMode":"segmented","durationSeconds":"number"},"visibility":{"avatarPrompt":{"field":"avatarSource","equals":"AI_GENERATED"},"script":{"field":"audioSource","equals":"TEXT_TO_SPEECH"},"voice":{"all":[{"field":"audioSource","equals":"TEXT_TO_SPEECH"},{"field":"voiceGenerationMode","equals":"TTS"}]},"speed":{"all":[{"field":"audioSource","equals":"TEXT_TO_SPEECH"},{"field":"voiceGenerationMode","equals":"TTS"}]},"emotion":{"all":[{"field":"audioSource","equals":"TEXT_TO_SPEECH"},{"field":"voiceGenerationMode","equals":"TTS"}]},"audioFile":{"field":"audioSource","equals":"UPLOAD_AUDIO"},"voiceGenerationMode":{"field":"audioSource","equals":"TEXT_TO_SPEECH"},"avatarImage":{"any":[{"field":"avatarSource","equals":"UPLOAD"},{"field":"avatarSource","equals":"HISTORY"},{"field":"avatarSource","equals":"AI_GENERATED"}]}},"fieldOptions":{"avatarImage":{"acceptedMimeTypes":["image/png","image/jpeg"],"allowedExtensions":[".png",".jpg",".jpeg"],"maxItems":1,"maxFileSizeBytes":10485760,"maxTotalSizeBytes":10485760,"showPreview":true},"audioFile":{"acceptedMimeTypes":["audio/wav","audio/mpeg","audio/mp4","audio/aac","audio/ogg"],"allowedExtensions":[".wav",".mp3",".m4a",".aac",".ogg"],"maxItems":1,"maxFileSizeBytes":6291456,"maxTotalSizeBytes":6291456,"showPreview":true},"avatarConfirmed":{"default":false},"audioConfirmed":{"default":false},"speed":{"step":0.05,"suffix":"×","decimalPlaces":2},"performancePrompt":{"maxLines":4},"negativePrompt":{"maxLines":4},"durationSeconds":{"min":1,"max":60,"step":1}},"enumLabels":{"avatarSource":{"UPLOAD":"上传图片","HISTORY":"历史图片","AI_GENERATED":"AI 生成"},"audioSource":{"TEXT_TO_SPEECH":"文本配音","UPLOAD_AUDIO":"上传音频"},"voice":{"science_female":"科普视频女声","gentle_female":"温柔女声"},"emotion":{"natural":"自然"},"aspectRatio":{"9:16":"9:16","16:9":"16:9","21:9":"21:9","1:1":"1:1"},"resolution":{"720p":"720p","1080p":"1080p"},"voiceGenerationMode":{"TTS":"独立 TTS","VIDEO_NATIVE":"视频模型原生语音"}},"conditionalOptions":{"voiceGenerationMode":{"VIDEO_NATIVE":{"requiresModelCapability":"nativeAudioOutput"}}},"fieldHelp":{"avatarConfirmed":{"text":"请先预览并确认人物形象。"},"audioConfirmed":{"text":"文本配音必须先生成并确认音频；上传音频也必须确认。"},"negativePrompt":{"text":"可选，用于避免不希望出现的动作、镜头或画面。"},"voiceGenerationMode":{"text":"只有视频 Deployment 提供可在最终提交前试听并确认的独立音频时，才开放视频模型原生语音。当前 D-ID 模型使用独立 TTS 或上传音频。"}},"feeNotice":"人物图片、语音和视频模型可能产生费用。点击生成即表示确认本次调用。","submitLabel":"生成数字人口播","revisionSubmitLabel":"生成新版本","nativeVoiceEnabled":false,"modelSelectors":{"IMAGE_GENERATION":{"label":"人物生图模型","widget":"dropdown"},"TEXT_TO_SPEECH":{"label":"配音模型","widget":"dropdown"},"VIDEO_GENERATION":{"label":"视频生成模型","widget":"dropdown"}},"modelSelectorPlacement":{"IMAGE_GENERATION":"avatarPrompt","TEXT_TO_SPEECH":"script","VIDEO_GENERATION":"aspectRatio"},"modelSelectorVisibility":{"IMAGE_GENERATION":{"field":"avatarSource","equals":"AI_GENERATED"},"TEXT_TO_SPEECH":{"all":[{"field":"audioSource","equals":"TEXT_TO_SPEECH"},{"field":"voiceGenerationMode","equals":"TTS"}]},"VIDEO_GENERATION":{}},"confirmationDependencies":{"avatarConfirmed":["avatarSource","avatarImage","avatarPrompt"],"audioConfirmed":["audioSource","script","audioFile","voiceGenerationMode","voice","speed","emotion"]}}$ui$::jsonb,
    $output${"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["assetId"],"properties":{"assetId":{"type":"string","format":"uuid"}},"additionalProperties":false}$output$::jsonb,
    $config${"modelAliases":{"IMAGE_GENERATION":"digital_human.avatar_image","TEXT_TO_SPEECH":"digital_human.speech","VIDEO_GENERATION":"digital_human.video"},"maxAvatarCandidates":4,"maxScriptCharacters":300,"maxDurationSeconds":60,"maxReferenceImageBytes":10485760,"maxAudioBytes":20971520,"maxTotalInputBytes":31457280,"nativeVoiceEnabled":false,"capabilities":["IMAGE_GENERATION","TEXT_TO_SPEECH","VIDEO_GENERATION"]}$config$::jsonb,
    now()
from feature_definition definition
where definition.code = 'video.digital_human'
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 12,
    description = 'Confirm one avatar image and one TTS or uploaded audio asset, then generate a lip-synced MP4 with D-ID Talks.',
    updated_at = now()
where code = 'video.digital_human';

insert into feature_model_policy (
    id, feature_code, capability, default_deployment_code,
    allow_user_selection, created_at, updated_at
) values
    ('43000000-0000-0000-0000-000000000710','video.digital_human','IMAGE_GENERATION','codex2api-gpt-image-2-image',true,now(),now()),
    ('43000000-0000-0000-0000-000000000711','video.digital_human','TEXT_TO_SPEECH','openai2api-index-tts2-tts',true,now(),now()),
    ('43000000-0000-0000-0000-000000000712','video.digital_human','VIDEO_GENERATION','did-talks-v1-video',true,now(),now())
on conflict (feature_code, capability) do update set
    default_deployment_code = excluded.default_deployment_code,
    allow_user_selection = excluded.allow_user_selection,
    updated_at = now();

insert into feature_model_option (
    policy_id, deployment_code, display_name, description, sort_order, enabled
) values
    ('43000000-0000-0000-0000-000000000710','codex2api-gpt-image-2-image','GPT Image 2','Generate clear avatar candidate images.',10,true),
    ('43000000-0000-0000-0000-000000000710','aliyun-qwen-image-2-0','Qwen Image 2.0','Generate avatar candidates from Chinese descriptions.',20,true),
    ('43000000-0000-0000-0000-000000000711','openai2api-index-tts2-tts','IndexTTS2 Chinese Voice','Chinese TTS with confirmable audio output.',10,true),
    ('43000000-0000-0000-0000-000000000711','openai2api-omnivoice-tts','OmniVoice Multilingual','Multilingual TTS with confirmable audio output.',20,true)
on conflict (policy_id, deployment_code) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true;

update feature_model_option option
set enabled = false
from feature_model_policy policy
where option.policy_id = policy.id
  and policy.feature_code = 'video.digital_human'
  and policy.capability = 'VIDEO_GENERATION';

insert into feature_model_option (
    policy_id, deployment_code, display_name, description, sort_order, enabled
)
select
    policy.id,
    'did-talks-v1-video',
    'D-ID Talks',
    'Official D-ID video generation using a confirmed avatar image and confirmed TTS or uploaded audio.',
    10,
    true
from feature_model_policy policy
where policy.feature_code = 'video.digital_human'
  and policy.capability = 'VIDEO_GENERATION'
on conflict (policy_id, deployment_code) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    sort_order = 10,
    enabled = true;
