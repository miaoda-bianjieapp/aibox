insert into feature_version (
    id,
    feature_id,
    version,
    input_schema_json,
    ui_schema_json,
    output_schema_json,
    config_json,
    created_at
)
select
    'f4f8c5d7-2d5a-47ad-9c91-f38fb6e27796',
    feature.id,
    3,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{feeNotice}',
        to_jsonb(
            '抠图会连续调用所选图片模型 2 次生成白底图和黑底图；换背景调用 1 次。费用按所选模型实际计费，点击“开始处理”即表示确认本次调用。'::text
        ),
        true
    ),
    previous.output_schema_json,
    previous.config_json || '{
      "removeBackgroundModelInvocationCount": 2,
      "alphaExtraction": "black_white_difference"
    }'::jsonb,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 2
where feature.code = 'image.background_edit';

update feature_definition
set current_version = 3,
    updated_at = now()
where code = 'image.background_edit';
