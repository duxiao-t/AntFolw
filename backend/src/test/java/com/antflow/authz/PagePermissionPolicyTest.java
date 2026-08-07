package com.antflow.authz;

import com.antflow.engine.BizException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagePermissionPolicyTest {
    @Test
    void pageRequiresItsMinimumReadPermissions() {
        assertThat(PagePermissionPolicy.dependencies(PermissionCodes.PAGE_ORG_CONTACTS))
            .containsExactlyInAnyOrder(PermissionCodes.ORG_DEPARTMENT_READ,
                PermissionCodes.ORG_USER_READ);
        assertThat(PagePermissionPolicy.dependencies(PermissionCodes.PAGE_REPORT_EXPORT))
            .containsExactlyInAnyOrder(PermissionCodes.FORM_DATA_READ,
                PermissionCodes.FORM_DATA_EXPORT);
    }

    @Test
    void missingDependencyIsRejectedForNonAdminRole() {
        Set<String> selected = new HashSet<>(Set.of(PermissionCodes.PAGE_ORG_CONTACTS,
            PermissionCodes.ORG_USER_READ));

        assertThatThrownBy(() -> PagePermissionPolicy.validate(selected, false))
            .isInstanceOf(BizException.class)
            .extracting("code")
            .isEqualTo("PERMISSION_DEPENDENCY_MISSING");
    }

    @Test
    void administratorOnlyPageCannotBeGrantedToCustomRole() {
        assertThatThrownBy(() -> PagePermissionPolicy.validate(
            Set.of(PermissionCodes.PAGE_SECURITY_USER_PERMISSIONS), false))
            .isInstanceOf(BizException.class)
            .extracting("code")
            .isEqualTo("ADMIN_PAGE_PROTECTED");
    }

    @Test
    void administratorMayHoldAllPagePermissions() {
        assertThatCode(() -> PagePermissionPolicy.validate(
            Set.of(PermissionCodes.PAGE_SECURITY_USER_PERMISSIONS), true))
            .doesNotThrowAnyException();
    }
}
