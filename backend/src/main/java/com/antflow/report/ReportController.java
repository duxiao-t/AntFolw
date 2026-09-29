package com.antflow.report;

import com.antflow.authz.PermissionCodes;
import com.antflow.form.runtime.FormDataService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表接口。门禁与菜单一致（`form:data:read`，默认 SELF 范围）——普通用户看到的是
 * "自己可见的数据"的统计，管理员才是全量；范围口径与流程监控共用一份实现。
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService service;
    private final FormDataService formDataService;
    private final com.antflow.authz.AuthorizationService authorization;

    /**
     * 审批统计：一次给 4 块（合计 / 按表单 / 按部门 / 按天），报表中心与看板共用同一份，
     * 避免两个页面各算一套、数字对不上。
     *
     * @param tzOffsetMinutes 客户端时区偏移（分钟，东八区 = 480）：日期区间与按天分桶都用它
     */
    @GetMapping("/approval-summary")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DATA_READ + "')")
    public ReportService.ApprovalSummary approvalSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> formDefIds,
            @RequestParam(required = false) List<Long> deptIds,
            @RequestParam(defaultValue = "0") int tzOffsetMinutes) {
        return service.summary(from, to, formDefIds, deptIds, tzOffsetMinutes);
    }

    /**
     * 导出页用：先算"这次会导出多少行"，让用户在点下载之前就知道拿到什么。
     * 走的是与导出**同一个**过滤与范围收窄（同一个 mapper 语句），不会出现"提示 30 行、导出 300 行"。
     *
     * <p>门禁与导出一致：`form:data:export` + `form:data:read`（行级范围跟着读权限走，
     * 只有导出权限会拿到恒假的空结果，那比明说缺权限更难排查）。
     */
    @GetMapping("/form-data-count")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DATA_EXPORT + "')")
    public Map<String, Object> formDataCount(@RequestParam(required = false) Long formDefId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String submitterKeyword) {
        authorization.requirePermission(PermissionCodes.FORM_DATA_READ);
        return Map.of("total", formDataService.countForExport(formDefId, status, submitterKeyword),
            "limit", FormDataService.EXPORT_LIMIT);
    }
}
