package com.antflow.form;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forms/definitions")
@RequiredArgsConstructor
public class FormDefinitionController {
    private final FormDefinitionService service;
    private final FormDefinitionMapper mapper;

    @GetMapping
    @PreAuthorize("hasRole('admin')")
    public Page<FormDefinition> list(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "20") long size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status) {
        return service.list(page, size, keyword, status);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public FormDefinition get(@PathVariable Long id) {
        return mapper.selectById(id);
    }

    @GetMapping("/by-code/{code}")
    public FormDefinition byCode(@PathVariable String code) {
        FormDefinition fd = service.getPublishedByCode(code);
        if (fd == null) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + code);
        }
        return fd;
    }

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    public FormDefinition save(@RequestBody SaveBody body) {
        var p = PrincipalHolder.current().orElseThrow();
        return service.saveDraft(body.id(), body.code(), body.name(),
            body.description(), body.schema(), body.settings(), p.userId());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public FormDefinition update(@PathVariable Long id, @RequestBody SaveBody body) {
        return service.update(id, body.name(), body.description(), body.status(),
            body.schema(), body.settings());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('admin')")
    public FormDefinition publish(@PathVariable Long id) {
        return service.publish(id);
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('admin')")
    public FormDefinition disable(@PathVariable Long id) {
        return service.disable(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public void delete(@PathVariable Long id) {
        service.softDelete(id);
    }

    public record SaveBody(Long id, String code, String name, String description,
                           String status, Object schema, Object settings) {}
}
