package com.antflow.form.runtime;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.antflow.mobile.workflow.MobileFileRef;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/forms/data")
@RequiredArgsConstructor
public class FormDataController {
    private final FormDataService service;
    private final AuthorizationService authorizationService;
    private final com.antflow.audit.AuditService auditService;

    /**
     * 把台账导成 Excel（默认）或 CSV。两种格式同一份「列 + 值」模型：列按字段 id、表头取字段标签、
     * 值取后端解析好的显示文本——所以导出的内容与页面、与另一种格式完全一致。
     *
     * <p>走的是与列表一致的过滤与**行级数据范围**（同一个 mapper 语句），所以"你导出的绝不会
     * 比你看到的更多"；上限 {@link FormDataService#EXPORT_LIMIT} 行，截断会在审计里记一笔。
     *
     * <p>同时要求 `form:data:read`：行级范围是跟着**读**那个能力走的（`DataPermissionRules.FORM_DATA`
     * 就是按它注入的），只有导出权限的角色会被注入恒假条件、拿到一个空文件——那比直接 403
     * 更难排查。导出本来就是读取的一种，缺权限就明说。
     */
    @GetMapping("/admin/export")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DATA_EXPORT + "')")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long formDefId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String submitterKeyword,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate to,
            @RequestParam(defaultValue = "0") int tzOffsetMinutes,
            @RequestParam(defaultValue = "xlsx") String format) {
        authorizationService.requirePermission(PermissionCodes.FORM_DATA_EXPORT);
        authorizationService.requirePermission(PermissionCodes.FORM_DATA_READ);
        if (!java.util.Set.of("csv", "xlsx").contains(format)) {
            throw new com.antflow.engine.BizException("EXPORT_FORMAT_UNSUPPORTED",
                "只支持 csv 或 xlsx");
        }
        // 与报表同一套时区换算：库里按 UTC 存，不换算导出里会比用户看到的早 8 小时。
        java.time.ZoneOffset offset = java.time.ZoneOffset.ofTotalSeconds(
            Math.max(-18 * 60, Math.min(18 * 60, tzOffsetMinutes)) * 60);
        java.time.OffsetDateTime start = from == null ? null
            : from.atStartOfDay().atOffset(offset);
        java.time.OffsetDateTime end = to == null ? null
            : to.plusDays(1).atStartOfDay().atOffset(offset);

        java.util.List<FormData> rows = service.exportRows(formDefId, status, submitterKeyword,
            start, end);
        // 计数必须带同一组时间范围，否则"预览 N 行"和实际导出的行数对不上，truncated 还会误报。
        long total = service.countForExport(formDefId, status, submitterKeyword, start, end);
        var model = com.antflow.report.FormDataExport.model(rows, offset);
        byte[] body = "csv".equals(format)
            ? com.antflow.report.FormDataExport.csv(model)
            : com.antflow.report.FormDataExport.xlsx(model);
        auditService.success("form.data.export", "FORM_DATA", null,
            com.antflow.audit.AuditService.RiskLevel.HIGH, Map.of(),
            Map.of("formDefId", formDefId == null ? 0 : formDefId, "rowCount", rows.size(),
                "truncated", rows.size() < total, "limit", FormDataService.EXPORT_LIMIT,
                "format", format));
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType("csv".equals(format)
                ? "text/csv;charset=UTF-8"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment()
                    .filename("antflow-form-data." + format).build().toString())
            .body(body);
    }

    @PostMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_RUNTIME_READ + "')")
    public Map<String, Object> submit(@RequestBody SubmitRequest body) {
        authorizationService.requirePermission(PermissionCodes.FORM_RUNTIME_READ);
        var p = PrincipalHolder.current().orElseThrow();
        FormDataService.SubmitResult result = service.submit(
            body.formCode(),
            body.status(),
            body.data(),
            p.userId(),
            body.files() == null ? List.of() : body.files(),
            body.draftId());
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("dataId", result.dataId());
        response.put("businessNo", result.businessNo());
        return response;
    }

    @GetMapping
    @PreAuthorize("@authz.consoleEntry()")
    public List<FormData> mySubmissions(@RequestParam(required = false) String formCode) {
        var p = PrincipalHolder.current().orElseThrow();
        return service.mySubmissions(p.userId(), formCode);
    }

    @GetMapping("/admin")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DATA_READ + "')")
    public Page<FormData> adminPage(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) Long formDefId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long createdBy,
                                    // 按姓名/工号筛提交人：列表上显示的是姓名，筛选就不该再收数字 id。
                                    @RequestParam(required = false) String submitterKeyword) {
        authorizationService.requirePermission(PermissionCodes.FORM_DATA_READ);
        var principal = PrincipalHolder.current().orElseThrow();
        return service.authorizedPage(page, size, formDefId, status, createdBy, submitterKeyword,
            principal.userId(), principal.isAdmin());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.consoleEntry()")
    public FormData get(@PathVariable Long id) {
        authorizationService.requireReadableFormData(id);
        return service.getById(id);
    }

    public record SubmitRequest(String formCode, String status, Object data,
                                List<MobileFileRef> files, Long draftId) { }
}
