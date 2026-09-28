package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.org.UserService;
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
    public List<MobileOrgService.RuntimePickerUserDto> users(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Boolean includeDescendants,
            @RequestParam(required = false) Boolean leaderOnly,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) List<Long> userIds,
            @RequestParam(required = false) String scopeType) {
        principal();
        // 同 UserController：指名范围但名单为空 = 零候选（空数组会被序列化丢掉）。
        if ("user".equals(scopeType) && (userIds == null || userIds.isEmpty())) return List.of();
        return service.searchUsers(UserService.UserQuery.of(
            keyword, deptId, includeDescendants, leaderOnly, position, userIds));
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

    private static PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }
}
