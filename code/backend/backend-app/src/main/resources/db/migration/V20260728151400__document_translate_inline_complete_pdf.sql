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
    'aea57ed6-b32d-41a9-92dc-701ecdf8d8b0',
    feature.id,
    5,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{fieldHelp,document,text}',
        to_jsonb(
            '可翻译正文最多 3 万字符；扫描 PDF 最多 20 页。系统按文档结构分批，最多调用模型 5 次。复杂 DOC 结构可能无法写回，届时请转换为 DOCX。PDF 不生成补充译文页，输出页数与原文一致；系统优先保持原文字区域和字号，空间不足时在原页内避让图片、适度扩展文字区域并缩小字号，最低 4pt。成功结果必须包含全部译文；仍无法完整容纳时任务失败，不输出缺译或截断文件。'::text
        ),
        true
    ),
    previous.output_schema_json,
    previous.config_json || jsonb_build_object(
        'minPdfFontPoints', 4,
        'pdfOverflowStrategy', 'INLINE_SAFE_REFLOW_OR_FAIL',
        'allowPdfLayoutExpansion', true,
        'preservePdfPageCount', true,
        'requireCompleteTranslation', true
    ),
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 4
where feature.code = 'document.translate';

update feature_definition
set current_version = 5,
    updated_at = now()
where code = 'document.translate';
