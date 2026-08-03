package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
public class MobileOrgController {
    private final MobileOrgService service;

    @GetMapping("/users")
    public List<MobilePickerUserDto> users(@RequestParam(required = false) String keyword) {
        principal();
        return service.searchUsers(keyword);
    }

    @GetMapping("/departments")
    public List<MobilePickerDepartmentDto> departments(@RequestParam(required = false) String keyword) {
        principal();
        return service.searchDepartments(keyword);
    }

    private static PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }
}
