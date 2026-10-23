-- 永洪多图明细：一张日报的每张有效图片各占一行，按 image_no 稳定排序。
CREATE OR REPLACE VIEW reporting.workshop_daily_report_images AS
SELECT data.id AS form_data_id,
       data.business_no,
       data.form_def_version AS form_version,
       data.created_at AS submitted_at,
       ROW_NUMBER() OVER (
           PARTITION BY data.id
           ORDER BY link.sort_order, file.created_at, file.id
       )::INT AS image_no,
       file.id AS file_id,
       link.field_id,
       link.sort_order,
       file.original_name AS image_name,
       file.content_type AS image_content_type,
       file.size_bytes AS image_size_bytes,
       '/api/public/report-images/' || data.id || '/' || file.id AS image_path
FROM t_form_data data
JOIN t_form_definition definition ON definition.id = data.form_def_id
JOIN t_form_data_file link ON link.form_data_id = data.id
JOIN t_mobile_file file ON file.id = link.file_id
WHERE definition.code = 'form_1788255825883'
  AND data.status = 'SUBMITTED'
  AND file.status = 'READY'
  AND file.deleted_at IS NULL
  AND file.content_type LIKE 'image/%';

REVOKE ALL ON TABLE reporting.workshop_daily_report_images FROM PUBLIC;
