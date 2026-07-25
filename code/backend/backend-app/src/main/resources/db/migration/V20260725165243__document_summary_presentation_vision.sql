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
    'b63deab9-9105-4941-88c9-eb0c7e0f345d',
    feature.id,
    3,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{fieldHelp,document,text}',
        to_jsonb(
            '正文抽取后最多 15 万字符；超出时请拆分文档。扫描 PDF 和图片型 PPT/PPTX 会使用所选模型识别页面；图片型演示文稿最多 30 页。CSV、Markdown、TXT 和 JSON 仅支持 UTF-8 或 UTF-8 BOM。'::text
        ),
        true
    ),
    previous.output_schema_json,
    jsonb_set(
        previous.config_json,
        '{maxVisualPresentationSlides}',
        '30'::jsonb,
        true
    ),
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 2
where feature.code = 'document.summary';

update feature_definition
set current_version = 3,
    updated_at = now()
where code = 'document.summary';
