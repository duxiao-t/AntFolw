package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.antflow.authz.AuthenticatedOnly;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
public class MobileOrgController {
    private final MobileOrgService service;

    @GetMapping("/users")
    @AuthenticatedOnly
    public List<MobilePickerUserDto> users(@RequestParam(required = false) String keyword) {
        principal();
        return service.searchUsers(keyword);
    }

    @GetMapping("/users/{id}")
    @AuthenticatedOnly
    public MobilePickerUserDto user(@org.springframework.web.bind.annotation.PathVariable long id) {
        principal();
        return service.user(id);
    }

    @GetMapping("/departments")
    @AuthenticatedOnly
    public List<MobilePickerDepartmentDto> departments(@RequestParam(required = false) String keyword) {
        principal();
        return service.searchDepartments(keyword);
    }

    @GetMapping("/departments/{id}")
    @AuthenticatedOnly
    public MobilePickerDepartmentDto department(
        @org.springframework.web.bind.annotation.PathVariable long id) {
        principal();
        return service.department(id);
    }

    /** 流程配置里的角色选择器：登录即可用，只返回 id/code/name。 */
    @GetMapping("/roles")
    @AuthenticatedOnly
    public List<MobilePickerRoleDto> roles(@RequestParam(required = false) String keyword) {
        principal();
        return service.searchRoles(keyword);
    }

    private static PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }
}
