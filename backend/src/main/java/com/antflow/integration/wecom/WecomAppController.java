package com.antflow.integration.wecom;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import com.antflow.authz.AuthenticatedOnly;
import com.antflow.authz.PublicEndpoint;
import com.antflow.authz.PermissionCodes;

@RestController
public class WecomAppController {
    private final WecomAppService service;

    public WecomAppController(WecomAppService service) { this.service = service; }

    @GetMapping("/api/public/auth/wecom/status")
    @PublicEndpoint
    public WecomAppService.Status status() { return service.status(); }

    @GetMapping("/api/integrations/wecom/access-info")
    @PreAuthorize("@authz.console('" + PermissionCodes.INTEGRATION_WECOM_MANAGE + "')")
    public WecomAppService.AccessInfo accessInfo() { return service.accessInfo(); }

    @GetMapping("/api/public/auth/wecom/authorize")
    @PublicEndpoint
    public void authorize(@RequestParam(required = false) String returnUrl,
                          HttpServletResponse response) throws IOException {
        response.sendRedirect(service.authorize(returnUrl).toString());
    }

    @GetMapping("/api/public/auth/wecom/callback")
    @PublicEndpoint
    public void callback(@RequestParam String state, @RequestParam String code,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(service.callback(state, code, request, response).toString());
    }

    @GetMapping("/api/mobile/wecom/js-sdk-config")
    @AuthenticatedOnly
    public WecomAppService.JsSdkConfig jsSdkConfig(@RequestParam String url) {
        return service.jsSdkConfig(url);
    }

    @PostMapping("/api/mobile/wecom/media/import")
    @PreAuthorize("@authz.capability('" + PermissionCodes.FILE_ATTACHMENT_UPLOAD + "')")
    public com.antflow.mobile.workflow.MobileFileDto importMedia(@RequestBody MediaRequest request) {
        return service.importMedia(request.serverId(), request.mediaType());
    }

    @GetMapping("/api/integrations/wecom/message-status")
    @PreAuthorize("@authz.console('" + PermissionCodes.INTEGRATION_WECOM_MANAGE + "')")
    public WecomAppService.DeliveryStatus messageStatus(@RequestParam long companyId) {
        return service.deliveryStatus(companyId);
    }

    @PostMapping("/api/integrations/wecom/test-message")
    @PreAuthorize("@authz.console('" + PermissionCodes.INTEGRATION_WECOM_MANAGE + "')")
    public ResponseEntity<Void> testMessage(@RequestBody CompanyRequest request) {
        service.sendTestMessage(request.companyId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/integrations/wecom/retry-messages")
    @PreAuthorize("@authz.console('" + PermissionCodes.INTEGRATION_WECOM_MANAGE + "')")
    public ResponseEntity<Void> retryMessages(@RequestBody CompanyRequest request) {
        service.retryDead(request.companyId());
        return ResponseEntity.noContent().build();
    }

    public record MediaRequest(String serverId, String mediaType) { }
    public record CompanyRequest(long companyId) { }
}
