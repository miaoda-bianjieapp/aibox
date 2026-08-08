-- Use a structured facial-expression plan instead of one undifferentiated performance prompt.
update model_deployment
set config_json = config_json || $settings$
{
  "stitch": false,
  "motionFactor": 1.0,
  "alignExpandFactor": 0.3,
  "expressionTransitionFrames": 24,
  "performancePromptMode": "structured-facial-expression-plan",
  "performancePromptSections": ["baseline", "change", "release"]
}
$settings$::jsonb,
    updated_at = now()
where code = 'did-talks-v1-video';

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '42000000-0000-0000-0000-000000000714',
    definition.id,
    14,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{fieldHelp,performancePrompt,text}',
        to_jsonb('建议按三段组织：基线情绪；中段变化；结尾收束。例如：基线保持自然克制；随后眉头微蹙、目光凝重；最后恢复平静。只填写面部表情、视线和自然眨眼，不要填写手势、全身动作、运镜或背景。'::text),
        true
    ),
    previous.output_schema_json,
    previous.config_json || '{"facialPerformancePromptFormat":"baseline-change-release","nativeVoiceEnabled":false}'::jsonb,
    now()
from feature_definition definition
join feature_version previous on previous.feature_id = definition.id and previous.version = 13
where definition.code = 'video.digital_human'
on conflict (feature_id, version) do nothing;

update feature_definition
set current_version = 14,
    updated_at = now()
where code = 'video.digital_human';
