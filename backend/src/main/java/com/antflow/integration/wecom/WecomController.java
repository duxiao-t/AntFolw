package com.antflow.integration.wecom;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/wecom")
public class WecomController {
    private final WecomService service;

    public WecomController(WecomService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public WecomService.SettingsDto settings(@RequestParam long companyId) {
        return service.settings(companyId);
    }

    @PutMapping("/settings")
    public WecomService.SettingsDto save(@Valid @RequestBody SaveSettingsRequest request) {
        if (request.agentId() == null && request.agentSecret() == null
            && request.oauthEnabled() == null && request.jsSdkEnabled() == null
            && request.messageEnabled() == null) {
            return service.saveSettings(request.companyId(), request.corpId(), request.secret());
        }
        return service.saveSettings(request.companyId(), request.corpId(), request.secret(),
            request.agentId(), request.agentSecret(), Boolean.TRUE.equals(request.oauthEnabled()),
            Boolean.TRUE.equals(request.jsSdkEnabled()), Boolean.TRUE.equals(request.messageEnabled()));
    }

    @PostMapping("/sync-jobs")
    public WecomService.JobDto start(@Valid @RequestBody StartJobRequest request) {
        return service.start(request.companyId());
    }

    @GetMapping("/sync-jobs/{id}")
    public WecomService.JobDto job(@PathVariable long id) {
        return service.job(id);
    }

    public record SaveSettingsRequest(@NotNull Long companyId,
                                      @NotBlank @Size(max = 128) String corpId,
                                      @Size(max = 512) String secret,
                                      Integer agentId,
                                      @Size(max = 512) String agentSecret,
                                      Boolean oauthEnabled,
                                      Boolean jsSdkEnabled,
                                      Boolean messageEnabled) { }
    public record StartJobRequest(@NotNull Long companyId) { }
}
