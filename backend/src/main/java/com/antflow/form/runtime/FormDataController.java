package com.antflow.form.runtime;

import com.antflow.auth.PrincipalHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/forms/data")
@RequiredArgsConstructor
public class FormDataController {
    private final FormDataService service;

    @PostMapping
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        var p = PrincipalHolder.current().orElseThrow();
        Long id = service.submit(
            (String) body.get("formCode"),
            (String) body.get("status"),
            body.get("data"),
            p.userId());
        return Map.of("dataId", id);
    }

    @GetMapping
    public List<FormData> mySubmissions(@RequestParam(required = false) String formCode) {
        var p = PrincipalHolder.current().orElseThrow();
        return service.mySubmissions(p.userId(), formCode);
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('admin')")
    public Page<FormData> adminPage(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) Long formDefId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long createdBy) {
        return service.adminPage(page, size, formDefId, status, createdBy);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public FormData get(@PathVariable Long id) {
        return service.getById(id);
    }
}
