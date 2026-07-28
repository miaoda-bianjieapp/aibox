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
    '271dc41e-a250-4269-b57b-f4f4b0d11621',
    feature.id,
    4,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{fieldHelp,document,text}',
        to_jsonb(
            '可翻译正文最多 3 万字符；扫描 PDF 最多 20 页。系统按文档结构分批，最多调用模型 5 次。复杂 DOC 结构可能无法写回，届时请转换为 DOCX。PDF 译文只在原文字区域内逐步缩小到 8pt，不扩大遮盖相邻图片或表格；仍放不下时统一在全部原页面之后追加补充译文页，不改变原页面顺序，也不截断内容。'::text
        ),
        true
    ),
    previous.output_schema_json,
    jsonb_set(
        jsonb_set(
            jsonb_set(
                previous.config_json,
                '{minPdfFontPoints}',
                '8'::jsonb,
                true
            ),
            '{pdfOverflowStrategy}',
            '"ORIGINAL_BOX_THEN_APPEND_AT_END"'::jsonb,
            true
        ),
        '{allowPdfLayoutExpansion}',
        'false'::jsonb,
        true
    ),
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 3
where feature.code = 'document.translate';

update feature_definition
set current_version = 4,
    updated_at = now()
where code = 'document.translate';
