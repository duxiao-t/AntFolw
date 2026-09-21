package com.antflow.form.options;

import com.antflow.authz.AuthenticatedOnly;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/runtime/form-options")
@RequiredArgsConstructor
public class OptionRuntimeController {
    private final OptionRuntimeService service;

    @PostMapping("/preview/{formId}")
    @PreAuthorize("@authz.console('form:definition:manage')")
    public OptionRuntimeService.OptionPage preview(@PathVariable long formId,
            @RequestBody OptionRuntimeService.PreviewQuery request) {
        return service.preview(formId, request);
    }

    @PostMapping("/query")
    @AuthenticatedOnly
    public OptionRuntimeService.OptionPage query(
            @RequestBody OptionRuntimeService.OptionQuery request) {
        return service.query(request);
    }
}
