package com.antflow.org;

import com.antflow.mobile.workflow.MobileOrgService;
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
    public List<MobilePickerUserDto> users(@RequestParam(required = false) String keyword) {
        return service.searchUsers(keyword);
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
