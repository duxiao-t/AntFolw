package com.antflow.authz;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PagePermissionPolicy {
    private static final Map<String, List<String>> DEPENDENCIES = Map.ofEntries(
        Map.entry(PermissionCodes.PAGE_ORG_CONTACTS,
            List.of(PermissionCodes.ORG_DEPARTMENT_READ, PermissionCodes.ORG_USER_READ)),
        Map.entry(PermissionCodes.PAGE_SECURITY_ROLES,
            List.of(PermissionCodes.SECURITY_PERMISSION_READ, PermissionCodes.SECURITY_ROLE_READ)),
        Map.entry(PermissionCodes.PAGE_SECURITY_USER_PERMISSIONS,
            List.of(PermissionCodes.SECURITY_ROLE_READ, PermissionCodes.SECURITY_USER_ROLE_READ)),
        Map.entry(PermissionCodes.PAGE_SECURITY_AUDIT_LOG,
            List.of(PermissionCodes.SECURITY_AUDIT_READ)),
        Map.entry(PermissionCodes.PAGE_APPROVAL_FORMS,
            List.of(PermissionCodes.FORM_DEFINITION_READ)),
        Map.entry(PermissionCodes.PAGE_APPROVAL_RECORDS,
            List.of(PermissionCodes.WORKFLOW_INSTANCE_READ)),
        Map.entry(PermissionCodes.PAGE_REPORT_CENTER,
            List.of(PermissionCodes.FORM_DATA_READ)),
        Map.entry(PermissionCodes.PAGE_REPORT_EXPORT,
            List.of(PermissionCodes.FORM_DATA_READ, PermissionCodes.FORM_DATA_EXPORT)),
        Map.entry(PermissionCodes.PAGE_REPORT_DASHBOARD,
            List.of(PermissionCodes.FORM_DATA_READ)),
        Map.entry(PermissionCodes.PAGE_SETTINGS_COMPANY,
            List.of(PermissionCodes.ORG_COMPANY_MANAGE))
    );

    private static final Set<String> ADMIN_ONLY = Set.of(
        PermissionCodes.PAGE_SECURITY_USER_PERMISSIONS
    );

    public static List<String> dependencies(String permissionCode) {
        return DEPENDENCIES.getOrDefault(permissionCode, List.of());
    }

    public static boolean adminOnly(String permissionCode) {
        return ADMIN_ONLY.contains(permissionCode);
    }

    public static void validate(Set<String> selectedPermissions, boolean adminRole) {
        if (adminRole) {
            return;
        }
        for (String permission : selectedPermissions) {
            if (adminOnly(permission)) {
                throw new com.antflow.engine.BizException(
                    "ADMIN_PAGE_PROTECTED", "管理员专属页面不能授予普通角色");
            }
            if (!selectedPermissions.containsAll(dependencies(permission))) {
                throw new com.antflow.engine.BizException(
                    "PERMISSION_DEPENDENCY_MISSING", "页面权限缺少必要的操作权限");
            }
        }
    }

    private PagePermissionPolicy() {
    }
}
