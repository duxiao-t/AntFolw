package com.antflow.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OidcController {
    private final OidcService service;

    public OidcController(OidcService service) {
        this.service = service;
    }

    @GetMapping("/api/public/auth/providers")
    public List<OidcService.PublicProvider> publicProviders() {
        return service.publicProviders();
    }

    @GetMapping("/api/public/auth/oidc/{code}/authorize")
    public void authorize(@PathVariable String code,
                          @RequestParam(required = false) String returnUrl,
                          HttpServletResponse response) throws IOException {
        response.sendRedirect(service.authorize(code, returnUrl).toString());
    }

    @GetMapping("/api/public/auth/oidc/{provider}/callback")
    public void callback(@PathVariable String provider, @RequestParam String state,
                         @RequestParam String code, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        response.sendRedirect(service.callback(provider, state, code, request, response).toString());
    }

    @GetMapping("/api/security/identity-providers")
    public List<OidcService.ProviderDto> providers() {
        return service.providers();
    }

    @PostMapping("/api/security/identity-providers")
    public OidcService.ProviderDto create(@RequestBody OidcService.SaveProvider request) {
        return service.save(null, request);
    }

    @PutMapping("/api/security/identity-providers/{id}")
    public OidcService.ProviderDto update(@PathVariable long id,
                                          @RequestBody OidcService.SaveProvider request) {
        return service.save(id, request);
    }

    @DeleteMapping("/api/security/identity-providers/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/security/identity-providers/{id}/bindings")
    public List<OidcService.BindingDto> bindings(@PathVariable long id) {
        return service.bindings(id);
    }

    @DeleteMapping("/api/security/identity-providers/{providerId}/bindings/{bindingId}")
    public ResponseEntity<Void> unbind(@PathVariable long providerId, @PathVariable long bindingId) {
        service.unbind(providerId, bindingId);
        return ResponseEntity.noContent().build();
    }
}
