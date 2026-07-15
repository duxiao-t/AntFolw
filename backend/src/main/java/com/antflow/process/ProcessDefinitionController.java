package com.antflow.process;

import com.antflow.auth.PrincipalHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/processes/definitions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class ProcessDefinitionController {
    private final ProcessDefinitionService service;
    private final ProcessDefinitionMapper mapper;

    @GetMapping
    public List<ProcessDefinition> list() {
        return service.list();
    }

    @GetMapping("/by-form/{formDefId}")
    public ProcessDefinition byForm(@PathVariable Long formDefId) {
        return service.latestPublishedForForm(formDefId);
    }

    @GetMapping("/{id}")
    public ProcessDefinition get(@PathVariable Long id) {
        return service.getById(id);
    }

    @PostMapping
    public ProcessDefinition save(@RequestBody SaveBody body) {
        var p = PrincipalHolder.current().orElseThrow();
        return service.saveOrUpdateDraft(body.id(), body.formDefId(),
            body.nodes(), body.edges(), p.userId());
    }

    @PostMapping("/{id}/publish")
    public ProcessDefinition publish(@PathVariable Long id) {
        return service.publish(id);
    }

    public record SaveBody(Long id, Long formDefId, Object nodes, Object edges) {}
}
