package com.antflow.form;

import com.antflow.auth.PrincipalHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/forms/definitions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class FormDefinitionController {
    private final FormDefinitionService service;
    private final FormDefinitionMapper mapper;

    @GetMapping
    public List<FormDefinition> list() {
        return mapper.selectList(null);
    }

    @GetMapping("/{id}")
    public FormDefinition get(@PathVariable Long id) {
        return mapper.selectById(id);
    }

    @GetMapping("/by-code/{code}")
    public FormDefinition byCode(@PathVariable String code) {
        return service.getByCode(code);
    }

    @PostMapping
    public FormDefinition save(@RequestBody SaveBody body) {
        var p = PrincipalHolder.current().orElseThrow();
        return service.saveDraft(body.id(), body.code(), body.name(),
            body.schema(), body.settings(), p.userId());
    }

    @PostMapping("/{id}/publish")
    public FormDefinition publish(@PathVariable Long id) {
        return service.publish(id);
    }

    public record SaveBody(Long id, String code, String name, Object schema, Object settings) {}
}
