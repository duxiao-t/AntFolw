package com.antflow.org;

import com.antflow.mobile.workflow.MobileOrgService;
import com.antflow.mobile.workflow.MobilePickerDepartmentDto;
import com.antflow.mobile.workflow.MobilePickerUserDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pickers")
@RequiredArgsConstructor
public class PickerController {
    private final MobileOrgService service;

    @GetMapping("/users")
    @PreAuthorize("@authz.consoleEntry()")
    public List<MobileOrgService.RuntimePickerUserDto> users(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Long deptId,
                           @RequestParam(required = false) Boolean includeDescendants,
                           @RequestParam(required = false) Boolean leaderOnly,
                           @RequestParam(required = false) String position,
                           @RequestParam(required = false) List<Long> userIds,
                           @RequestParam(required = false) String scopeType) {
        // 指名范围但名单为空 = 零候选（空数组会被序列化丢掉，靠 scopeType 区分）。
        if ("user".equals(scopeType) && (userIds == null || userIds.isEmpty())) return List.of();
        return service.searchUsers(
            UserService.UserQuery.of(keyword, deptId, includeDescendants, leaderOnly, position, userIds));
    }

    @GetMapping("/users/selected")
    @PreAuthorize("@authz.consoleEntry()")
    public List<MobilePickerUserDto> selectedUsers(@RequestParam List<Long> ids) {
        return service.selectedUsers(ids);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("@authz.consoleEntry()")
    public MobilePickerUserDto user(@PathVariable long id) {
        return service.user(id);
    }

    /**
     * 选择器用的部门清单。与 /api/departments 的区别是门禁：那个要 ORG_DEPARTMENT_READ，
     * 而配置选择器的人（如表单设计师）不一定持有，所以这里只要求已进管理端。
     */
    @GetMapping("/departments")
    @PreAuthorize("@authz.consoleEntry()")
    public List<MobilePickerDepartmentDto> departments() {
        return service.allDepartments();
    }

    @GetMapping("/roles")
    @PreAuthorize("@authz.consoleEntry()")
    public List<PickerRoleDto> roles(@RequestParam(required = false) String keyword) {
        return service.searchRoles(keyword);
    }

    @GetMapping("/roles/selected")
    @PreAuthorize("@authz.consoleEntry()")
    public List<PickerRoleDto> selectedRoles(@RequestParam List<Long> ids) {
        return service.selectedRoles(ids);
    }
}
