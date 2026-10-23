-- 永洪 BI 直接使用的一单一行宽表；列名按当前已发布日报的业务分段固定。
-- 历史版本通过 workshop_daily_report_field_values 的稳定 field_name 映射到同一列。
CREATE OR REPLACE VIEW reporting.workshop_daily_report_ledger_wide AS
WITH wide_fields AS (
    SELECT form_data_id,
           MAX(value_text) FILTER (WHERE field_name = '开班前-计划与复盘 / 异常记录')
               AS "开班前_计划与复盘_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '开班前-计划与复盘 / 异常是否关闭')
               AS "开班前_计划与复盘_异常是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '开班前-交接班确认 / 交接人员')
               AS "开班前_交接班确认_交接人员",
           MAX(value_text) FILTER (WHERE field_name = '开班前-交接班确认 / 是否完成交接')
               AS "开班前_交接班确认_是否完成交接",
           MAX(value_text) FILTER (WHERE field_name = '开班前-交接班确认 / 是否内容遗漏')
               AS "开班前_交接班确认_是否内容遗漏",
           MAX(value_text) FILTER (WHERE field_name = '开班前-交接班确认 / 需要重点关注内容')
               AS "开班前_交接班确认_需要重点关注内容",

           MAX(value_number) FILTER (WHERE field_name = '开班前-班前检查 / 应到多少人')
               AS "开班前_班前检查_应到人数",
           MAX(value_number) FILTER (WHERE field_name = '开班前-班前检查 / 实到多少人')
               AS "开班前_班前检查_实到人数",
           MAX(value_text) FILTER (WHERE field_name = '开班前-班前检查 / 是否具备开班条件')
               AS "开班前_班前检查_是否具备开班条件",
           MAX(value_text) FILTER (WHERE field_name = '开班前-班前检查 / 异常记录')
               AS "开班前_班前检查_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '开班前-班前检查 / 是否关闭')
               AS "开班前_班前检查_是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '班前会 / 异常记录')
               AS "班前会_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '班前会 / 异常是否关闭')
               AS "班前会_异常是否关闭",

           MAX(value_number) FILTER (WHERE field_name = '开班后-现场第一轮巡查&设备点检记录复核 / 抽查员工（人）')
               AS "开班后_第一轮巡查_抽查员工人数",
           MAX(value_text) FILTER (WHERE field_name = '开班后-现场第一轮巡查&设备点检记录复核 / 异常记录')
               AS "开班后_第一轮巡查_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '开班后-现场第一轮巡查&设备点检记录复核 / 异常是否关闭')
               AS "开班后_第一轮巡查_异常是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '开班后-首件确认与过程参数巡检 / 异常记录')
               AS "开班后_首件确认与过程参数巡检_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '开班后-首件确认与过程参数巡检 / 异常是否关闭')
               AS "开班后_首件确认与过程参数巡检_异常是否关闭",

           MAX(value_number) FILTER (WHERE field_name = '班中-工艺纪律执行巡查 / 巡查工位（个）')
               AS "班中_工艺纪律执行巡查_巡查工位数",
           MAX(value_text) FILTER (WHERE field_name = '班中-工艺纪律执行巡查 / 异常记录')
               AS "班中_工艺纪律执行巡查_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '班中-工艺纪律执行巡查 / 异常是否关闭')
               AS "班中_工艺纪律执行巡查_异常是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '班中-现场跟线+异常处理 / 异常记录')
               AS "班中_现场跟线与异常处理_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '班中-现场跟线+异常处理 / 异常是否关闭')
               AS "班中_现场跟线与异常处理_异常是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '班中-在制品质量抽查 / 异常记录')
               AS "班中_在制品质量抽查_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '班中-在制品质量抽查 / 异常是否关闭')
               AS "班中_在制品质量抽查_异常是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '班中-能耗与物料消耗巡检 / 异常记录')
               AS "班中_能耗与物料消耗巡检_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '班中-能耗与物料消耗巡检 / 异常问题是否关闭')
               AS "班中_能耗与物料消耗巡检_异常问题是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '班中-安全巡查 / 异常记录')
               AS "班中_安全巡查_异常记录",
           MAX(value_text) FILTER (WHERE field_name = '班中-安全巡查 / 异常是否关闭')
               AS "班中_安全巡查_异常是否关闭",

           MAX(value_number) FILTER (WHERE field_name = '班中-关注员工状态 / 关注员工（人）')
               AS "班中_关注员工状态_关注员工人数",
           MAX(value_number) FILTER (WHERE field_name = '班中-关注员工状态 / 新员工（人）')
               AS "班中_关注员工状态_新员工人数",
           MAX(value_number) FILTER (WHERE field_name = '班中-关注员工状态 / 状态异常（人）')
               AS "班中_关注员工状态_状态异常人数",
           MAX(value_text) FILTER (WHERE field_name = '班中-关注员工状态 / 岗位调整情况')
               AS "班中_关注员工状态_岗位调整情况",
           MAX(value_text) FILTER (WHERE field_name = '班中-关注员工状态 / 异常检查')
               AS "班中_关注员工状态_异常检查",
           MAX(value_text) FILTER (WHERE field_name = '班中-关注员工状态 / 异常是否关闭')
               AS "班中_关注员工状态_异常是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '下班前-下班综合巡查 / 异常检查')
               AS "下班前_下班综合巡查_异常检查",
           MAX(value_text) FILTER (WHERE field_name = '下班前-下班综合巡查 / 异常是否关闭')
               AS "下班前_下班综合巡查_异常是否关闭",

           MAX(value_text) FILTER (WHERE field_name = '日度总结 / 未解决异常情况记录')
               AS "日度总结_未解决异常情况记录",

           MAX(value_text) FILTER (WHERE field_name = '主管领导（部长/副部长）巡视记录 / 完整度')
               AS "主管领导巡视记录_完整度",
           MAX(value_text) FILTER (WHERE field_name = '主管领导（部长/副部长）巡视记录 / 有效性')
               AS "主管领导巡视记录_有效性",
           MAX(value_text) FILTER (WHERE field_name = '主管领导（部长/副部长）巡视记录 / 工作建议')
               AS "主管领导巡视记录_工作建议"
    FROM reporting.workshop_daily_report_field_values
    GROUP BY form_data_id
)
SELECT ledger.form_data_id,
       ledger.business_no,
       ledger.form_version,
       ledger.submitted_at,
       ledger.submitter_id,
       ledger.submitter_username,
       ledger.submitter_name,
       ledger.department_name,
       ledger.process_instance_id,
       ledger.process_status,
       ledger.attachment_count,
       ledger.image_path,
       wide_fields."开班前_计划与复盘_异常记录",
       wide_fields."开班前_计划与复盘_异常是否关闭",
       wide_fields."开班前_交接班确认_交接人员",
       wide_fields."开班前_交接班确认_是否完成交接",
       wide_fields."开班前_交接班确认_是否内容遗漏",
       wide_fields."开班前_交接班确认_需要重点关注内容",
       wide_fields."开班前_班前检查_应到人数",
       wide_fields."开班前_班前检查_实到人数",
       wide_fields."开班前_班前检查_是否具备开班条件",
       wide_fields."开班前_班前检查_异常记录",
       wide_fields."开班前_班前检查_是否关闭",
       wide_fields."班前会_异常记录",
       wide_fields."班前会_异常是否关闭",
       wide_fields."开班后_第一轮巡查_抽查员工人数",
       wide_fields."开班后_第一轮巡查_异常记录",
       wide_fields."开班后_第一轮巡查_异常是否关闭",
       wide_fields."开班后_首件确认与过程参数巡检_异常记录",
       wide_fields."开班后_首件确认与过程参数巡检_异常是否关闭",
       wide_fields."班中_工艺纪律执行巡查_巡查工位数",
       wide_fields."班中_工艺纪律执行巡查_异常记录",
       wide_fields."班中_工艺纪律执行巡查_异常是否关闭",
       wide_fields."班中_现场跟线与异常处理_异常记录",
       wide_fields."班中_现场跟线与异常处理_异常是否关闭",
       wide_fields."班中_在制品质量抽查_异常记录",
       wide_fields."班中_在制品质量抽查_异常是否关闭",
       wide_fields."班中_能耗与物料消耗巡检_异常记录",
       wide_fields."班中_能耗与物料消耗巡检_异常问题是否关闭",
       wide_fields."班中_安全巡查_异常记录",
       wide_fields."班中_安全巡查_异常是否关闭",
       wide_fields."班中_关注员工状态_关注员工人数",
       wide_fields."班中_关注员工状态_新员工人数",
       wide_fields."班中_关注员工状态_状态异常人数",
       wide_fields."班中_关注员工状态_岗位调整情况",
       wide_fields."班中_关注员工状态_异常检查",
       wide_fields."班中_关注员工状态_异常是否关闭",
       wide_fields."下班前_下班综合巡查_异常检查",
       wide_fields."下班前_下班综合巡查_异常是否关闭",
       wide_fields."日度总结_未解决异常情况记录",
       wide_fields."主管领导巡视记录_完整度",
       wide_fields."主管领导巡视记录_有效性",
       wide_fields."主管领导巡视记录_工作建议",
       ledger.form_content_summary
FROM reporting.workshop_daily_report_ledger ledger
LEFT JOIN wide_fields ON wide_fields.form_data_id = ledger.form_data_id;

REVOKE ALL ON TABLE reporting.workshop_daily_report_ledger_wide FROM PUBLIC;
