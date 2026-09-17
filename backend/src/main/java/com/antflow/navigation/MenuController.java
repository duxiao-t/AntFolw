package com.antflow.navigation;

import com.antflow.authz.PermissionCodes;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MenuController {
    private final MenuService menuService;

    /** 当前用户可见菜单：登录即可，内容按能力过滤。 */
    @GetMapping("/api/navigation")
    @PreAuthorize("@authz.capability('" + PermissionCodes.CONSOLE_ACCESS + "')")
    public List<MenuService.NavNode> navigation() {
        return menuService.navigation();
    }

    @GetMapping("/api/menu")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_MENU_MANAGE + "')")
    public MenuService.MenuDocument menu() {
        return menuService.menu();
    }

    @PutMapping("/api/menu")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_MENU_MANAGE + "')")
    public MenuService.MenuDocument replace(@RequestBody MenuService.MenuDocument request) {
        return menuService.replace(request);
    }
}
