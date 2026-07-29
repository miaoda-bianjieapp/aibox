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
    '07e5b8dd-8ef1-4636-98e7-ca3edf1b28cd',
    feature.id,
    2,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{fieldHelp,document,text}',
        to_jsonb(
            '可翻译正文最多 3 万字符；扫描 PDF 最多 20 页。系统按文档结构分批，最多调用模型 5 次。复杂 DOC 结构可能无法写回，届时请转换为 DOCX。PDF 译文会优先扩大原文字区域并逐步缩小到 5pt；极端长译文仍放不下时，将自动追加对应原页的补充译文页，不会截断内容。'::text
        ),
        true
    ),
    previous.output_schema_json,
    jsonb_set(
        jsonb_set(
            jsonb_set(
                previous.config_json,
                '{minPdfFontPoints}',
                '5'::jsonb,
                true
            ),
            '{pdfOverflowStrategy}',
            '"EXPAND_THEN_APPEND_PAGES"'::jsonb,
            true
        ),
        '{allowSupplementalPdfPages}',
        'true'::jsonb,
        true
    ),
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 1
where feature.code = 'document.translate';

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'document.translate';
