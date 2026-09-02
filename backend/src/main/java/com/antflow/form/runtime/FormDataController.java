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

@RestController
@RequestMapping("/api/forms/data")
@RequiredArgsConstructor
public class FormDataController {
    private final FormDataService service;
    private final AuthorizationService authorizationService;

    @PostMapping
    public Map<String, Object> submit(@RequestBody SubmitRequest body) {
        authorizationService.requirePermission(PermissionCodes.FORM_RUNTIME_READ);
        var p = PrincipalHolder.current().orElseThrow();
        FormDataService.SubmitResult result = service.submit(
            body.formCode(),
            body.status(),
            body.data(),
            p.userId(),
            body.files() == null ? List.of() : body.files());
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("dataId", result.dataId());
        response.put("businessNo", result.businessNo());
        return response;
    }

    @GetMapping
    public List<FormData> mySubmissions(@RequestParam(required = false) String formCode) {
        var p = PrincipalHolder.current().orElseThrow();
        return service.mySubmissions(p.userId(), formCode);
    }

    @GetMapping("/admin")
    public Page<FormData> adminPage(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) Long formDefId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long createdBy) {
        authorizationService.requirePermission(PermissionCodes.FORM_DATA_READ);
        var principal = PrincipalHolder.current().orElseThrow();
        return service.authorizedPage(page, size, formDefId, status, createdBy,
            principal.userId(), principal.isAdmin());
    }

    @GetMapping("/{id}")
    public FormData get(@PathVariable Long id) {
        authorizationService.requireReadableFormData(id);
        return service.getById(id);
    }

    public record SubmitRequest(String formCode, String status, Object data,
                                List<MobileFileRef> files) { }
}
