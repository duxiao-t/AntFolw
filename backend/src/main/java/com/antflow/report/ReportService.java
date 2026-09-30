package com.antflow.report;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.InstanceScopeSql;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 审批统计（报表中心与看板取的同一份数据，导出也走它的口径）。
 *
 * <p>口径先钉死，免得两个页面各算一套、数字对不上：
 * <ul>
 *   <li><b>按发起时间归期</b>：发起时间落在范围内的实例算本期，状态取**当前**状态。
 *       不按完成时间二次归期——那会让"分母是本期、分子含上期"。</li>
 *   <li><b>通过率 = 通过 / (通过 + 驳回)</b>：未决（进行中/撤回）不进分母，
 *       与流程监控页的驳回率同口径。</li>
 *   <li><b>平均耗时 = AVG(完成时间 - 发起时间)</b>，只算已终态（通过/驳回）且有完成时间的。</li>
 *   <li><b>数据范围</b>落在流程实例上（发起人 / 发起部门），与监控页共用
 *       {@link InstanceScopeSql}；取不到范围恒假（fail-closed）。</li>
 * </ul>
 *
 * <p>两条<b>刻意不抄监控页</b>的地方：① 不按 `node_id` 分组——监控页的节点驳回率按 node_id
 * 跨流程合并、名字取 MAX(process_snapshot)，不同流程复用节点 id 时会把两件事混成一条；
 * 这里只按表单与部门分组。② 状态桶要含 `other`：`t_process_instance.status` 没有白名单约束，
 * 未知状态若不计入，就会出现"提交量 ≠ 各状态之和"。
 */
@Service
@RequiredArgsConstructor
public class ReportService {
    /** 报表页在菜单里配的就是这个能力（默认 SELF 范围）。 */
    private static final String READ_PERMISSION = PermissionCodes.FORM_DATA_READ;

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public ApprovalSummary summary(LocalDate from, LocalDate to, List<Long> formDefIds,
                                   List<Long> deptIds, int tzOffsetMinutes) {
        authorization.requirePermission(READ_PERMISSION);
        LocalDate safeFrom = from == null ? LocalDate.now().minusDays(29) : from;
        LocalDate safeTo = to == null ? LocalDate.now() : to;
        if (safeTo.isBefore(safeFrom)) {
            throw new BizException("REPORT_RANGE_INVALID", "结束日期不能早于开始日期");
        }
        if (safeFrom.plusYears(5).isBefore(safeTo)) {
            throw new BizException("REPORT_RANGE_INVALID", "时间范围不要超过 5 年");
        }
        // 客户端时区：前端传 -getTimezoneOffset()（东八区 = 480）。日期区间与"按天"分桶都用它——
        // 库里的 started_at 是 timestamptz（本机 docker 栈按 UTC 存），不换算的话用户看到的"今天"
        // 会比实际早 8 小时。
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(
            Math.max(-18 * 60, Math.min(18 * 60, tzOffsetMinutes)) * 60);
        OffsetDateTime start = safeFrom.atStartOfDay().atOffset(offset);
        OffsetDateTime end = safeTo.plusDays(1).atStartOfDay().atOffset(offset);

        InstanceScopeSql.Scope scope = InstanceScopeSql.forPermission(
            authorization, READ_PERMISSION, "instance");
        List<Object> base = new ArrayList<>();
        base.add(start);
        base.add(end);
        StringBuilder filters = new StringBuilder();
        if (formDefIds != null && !formDefIds.isEmpty()) {
            filters.append(" AND definition.id IN (").append(placeholders(formDefIds.size())).append(")");
            base.addAll(formDefIds);
        }
        if (deptIds != null && !deptIds.isEmpty()) {
            filters.append(" AND instance.started_dept_id IN (")
                .append(placeholders(deptIds.size())).append(")");
            base.addAll(deptIds);
        }
        String joins = " FROM t_process_instance instance"
            + " JOIN t_form_data data ON data.id = instance.form_data_id"
            + " JOIN t_form_definition definition ON definition.id = data.form_def_id";
        String where = " WHERE instance.started_at >= ? AND instance.started_at < ?"
            + filters + " AND (" + scope.sql() + ")";
        Object[] args = withScope(base, scope);

        Map<String, Object> totals = jdbc.queryForMap("SELECT" + countsSelect() + joins + where, args);
        return new ApprovalSummary(
            new ReportTotals(number(totals, "started"), number(totals, "approved"),
                number(totals, "rejected"), number(totals, "withdrawn"), number(totals, "running"),
                number(totals, "other"), rate(totals), hours(totals)),
            jdbc.queryForList("SELECT definition.id AS form_def_id, definition.name AS form_name,"
                    + countsSelect() + joins + where
                    + " GROUP BY definition.id, definition.name"
                    + " ORDER BY COUNT(*) DESC, definition.id", args)
                .stream().map(ReportService::formRow).toList(),
            jdbc.queryForList("SELECT instance.started_dept_id AS dept_id,"
                    + " department.name AS dept_name," + countsSelect() + joins
                    + " LEFT JOIN t_department department ON department.id = instance.started_dept_id"
                    + where + " GROUP BY instance.started_dept_id, department.name"
                    + " ORDER BY COUNT(*) DESC, instance.started_dept_id NULLS LAST", args)
                .stream().map(ReportService::deptRow).toList(),
            byDay(start, end, tzOffsetMinutes, joins, where, filters.toString(), scope, base,
                safeFrom, safeTo));
    }

    /** 每个状态一个桶（含 other 兜底），外加已终态的平均耗时。 */
    private static String countsSelect() {
        return " COUNT(*) AS started,"
            + " COUNT(*) FILTER (WHERE instance.status = 'APPROVED') AS approved,"
            + " COUNT(*) FILTER (WHERE instance.status = 'REJECTED') AS rejected,"
            + " COUNT(*) FILTER (WHERE instance.status = 'WITHDRAWN') AS withdrawn,"
            + " COUNT(*) FILTER (WHERE instance.status IN ('RUNNING', 'REWORK')) AS running,"
            + " COUNT(*) FILTER (WHERE instance.status NOT IN"
            + " ('APPROVED','REJECTED','WITHDRAWN','RUNNING','REWORK')) AS other,"
            + " AVG(EXTRACT(EPOCH FROM (instance.finished_at - instance.started_at)) / 3600.0)"
            + " FILTER (WHERE instance.status IN ('APPROVED','REJECTED')"
            + " AND instance.finished_at IS NOT NULL) AS avg_hours";
    }

    private List<DayRow> byDay(OffsetDateTime start, OffsetDateTime end, int tzOffsetMinutes,
                               String joins, String where, String filters,
                               InstanceScopeSql.Scope scope, List<Object> base,
                               LocalDate safeFrom, LocalDate safeTo) {
        // 两条序列（按发起、按完成）各自落在区间内，再按**本地日期**合并。
        // 时区用显式偏移：timestamptz 先取 UTC 墙上时间再加偏移，不能直接 ::date——
        // 那会用会话时区（容器里是 UTC），与用户看到的日期对不上。
        String dayOf = "(instance.%s AT TIME ZONE 'UTC' + (? * interval '1 minute'))::date";
        String sql = "SELECT day, SUM(started) AS started, SUM(finished) AS finished FROM ("
            + " SELECT " + dayOf.formatted("started_at") + " AS day, 1 AS started, 0 AS finished"
            + joins + where
            + " UNION ALL"
            + " SELECT " + dayOf.formatted("finished_at") + " AS day, 0 AS started, 1 AS finished"
            + joins + " WHERE instance.finished_at >= ? AND instance.finished_at < ?"
            + filters + " AND (" + scope.sql() + ")"
            + ") series GROUP BY day ORDER BY day";
        List<Object> args = new ArrayList<>();
        args.add(tzOffsetMinutes);
        args.addAll(base);
        args.addAll(scope.args());
        args.add(tzOffsetMinutes);
        args.addAll(base);
        args.addAll(scope.args());

        Map<LocalDate, long[]> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
            LocalDate day = ((java.sql.Date) row.get("day")).toLocalDate();
            long[] counts = byDate.computeIfAbsent(day, ignored -> new long[2]);
            counts[0] += number(row, "started");
            counts[1] += number(row, "finished");
        }
        // 没有数据的日期补 0：折线断成几截，比"那天就是 0 件"更难读。
        List<DayRow> days = new ArrayList<>();
        for (LocalDate day = safeFrom; !day.isAfter(safeTo); day = day.plusDays(1)) {
            long[] counts = byDate.getOrDefault(day, new long[2]);
            days.add(new DayRow(day.toString(), counts[0], counts[1]));
        }
        return days;
    }

    private static FormRow formRow(Map<String, Object> row) {
        return new FormRow(number(row, "form_def_id"), text(row, "form_name"),
            number(row, "started"), number(row, "approved"), number(row, "rejected"),
            number(row, "withdrawn"), number(row, "running"), number(row, "other"),
            rate(row), hours(row));
    }

    private static DeptRow deptRow(Map<String, Object> row) {
        Object deptId = row.get("dept_id");
        return new DeptRow(deptId == null ? null : ((Number) deptId).longValue(),
            text(row, "dept_name"), number(row, "started"), number(row, "approved"),
            number(row, "rejected"), number(row, "withdrawn"), number(row, "running"),
            number(row, "other"), rate(row), hours(row));
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Object[] withScope(List<Object> base, InstanceScopeSql.Scope scope) {
        List<Object> all = new ArrayList<>(base);
        all.addAll(scope.args());
        return all.toArray();
    }

    /** 分母只算已决（通过 + 驳回）；一条都没决就是 null（前端显示 —，而不是 0%）。 */
    private static Double rate(Map<String, Object> row) {
        long approved = number(row, "approved");
        long rejected = number(row, "rejected");
        long decided = approved + rejected;
        return decided == 0 ? null : Math.round(approved * 1000.0 / decided) / 10.0;
    }

    private static Double hours(Map<String, Object> row) {
        Object value = row.get("avg_hours");
        return value instanceof Number number ? Math.round(number.doubleValue() * 10.0) / 10.0 : null;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public record ApprovalSummary(ReportTotals totals, List<FormRow> byForm,
                                  List<DeptRow> byDepartment, List<DayRow> byDay) { }

    public record ReportTotals(long started, long approved, long rejected, long withdrawn,
                               long running, long other, Double approvalRate,
                               Double avgDurationHours) { }

    public record FormRow(long formDefId, String formName, long started, long approved,
                          long rejected, long withdrawn, long running, long other,
                          Double approvalRate, Double avgDurationHours) { }

    public record DeptRow(Long deptId, String deptName, long started, long approved,
                          long rejected, long withdrawn, long running, long other,
                          Double approvalRate, Double avgDurationHours) { }

    public record DayRow(String date, long started, long finished) { }
}
