package com.antflow.form.importer;

import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.form.importer.ImportDtos.ImportInspection;
import com.antflow.form.importer.ImportDtos.ImportOutcome;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 表单数据历史补录的三个端点。
 *
 * <p>**刻意不写类级 {@code @RequestMapping}**：{@code FormDataController} 已经占了
 * {@code /api/forms/data}，类级前缀会把本类的路径拼成 {@code /api/forms/data/api/forms/data/...}
 * 或与它的 {@code /{id}} 之类通配抢匹配——路径只在这里写全。
 *
 * <p>鉴权两层：{@code @PreAuthorize} 在进入方法前挡住无能力者并给出一致的 403；
 * 方法内再 {@code requirePermission} 是给"直接调用 service 的路径"留的口径，也方便审计知道是谁。
 */
@RestController
@RequiredArgsConstructor
public class FormDataImportController {

    private static final MediaType XLSX =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FormDataImportService importService;
    private final FormImportTemplateService templateService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    /** 下载回填模板（两行表头 + 隐藏元数据 + 填写说明）。 */
    @GetMapping("/api/forms/data/import/template")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DATA_IMPORT + "')")
    public ResponseEntity<byte[]> template(@RequestParam long formId) {
        authorizationService.requirePermission(PermissionCodes.FORM_DATA_IMPORT);
        byte[] body = templateService.render(formId);
        auditService.success("form.data.import.template", "FORM_DEFINITION", formId,
            AuditService.RiskLevel.NORMAL, Map.of(), Map.of());
        String filename = "form-" + formId + "-import.xlsx";
        return ResponseEntity.ok()
            .contentType(XLSX)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build().toString())
            .body(body);
    }

    /** 预检：解析 + 逐行校验，不落库。前端据此展示"能导多少、错在哪一行"。 */
    @PostMapping(path = "/api/forms/data/import/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DATA_IMPORT + "')")
    public ImportInspection inspect(@RequestParam long formId,
                                    @RequestParam(required = false) MultipartFile file,
                                    @RequestParam(required = false) String sheetName) {
        authorizationService.requirePermission(PermissionCodes.FORM_DATA_IMPORT);
        ImportInspection inspection = importService.inspect(formId, file, sheetName);
        auditService.success("form.data.import.inspect", "FORM_DEFINITION", formId,
            AuditService.RiskLevel.NORMAL, Map.of(),
            Map.of("rowCount", inspection.rowCount(),
                "importableCount", inspection.importableCount(),
                "errorCount", inspection.errorCount(),
                "skippedCount", inspection.skippedCount()));
        return inspection;
    }

    /**
     * 执行导入。
     *
     * @param skipErrorRows false（默认）原子模式；true 逐行部分成功
     * @param companyId     仅无部门的全局管理员需要显式指定
     */
    @PostMapping(path = "/api/forms/data/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DATA_IMPORT + "')")
    public ImportOutcome importData(@RequestParam long formId,
                                    @RequestParam(required = false) MultipartFile file,
                                    @RequestParam(required = false) String sheetName,
                                    @RequestParam(defaultValue = "false") boolean skipErrorRows,
                                    @RequestParam(required = false) Long companyId) {
        authorizationService.requirePermission(PermissionCodes.FORM_DATA_IMPORT);
        ImportOutcome outcome = importService.importData(formId, file, sheetName,
            skipErrorRows, companyId);
        auditService.success("form.data.import", "FORM_DEFINITION", formId,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", java.util.List.of("t_form_data")),
            Map.of("batchId", outcome.batchId(), "atomic", outcome.atomic(),
                "totalRows", outcome.totalRows(), "successCount", outcome.successCount(),
                "failureCount", outcome.failureCount(), "skippedCount", outcome.skippedCount()));
        return outcome;
    }
}
