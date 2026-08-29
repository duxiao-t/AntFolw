package com.antflow.integration.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WecomServiceRulesTest {
    @Test
    void choosesDeclaredMainDepartmentThenFallsBackToFirst() {
        WecomClient.WecomUser declared = user(List.of(1L, 2L), 2);
        WecomClient.WecomUser missing = user(List.of(1L, 2L), 9);

        assertThat(WecomService.primaryDepartment(declared)).isEqualTo(2);
        assertThat(WecomService.primaryDepartment(missing)).isEqualTo(1);
    }

    @Test
    void rejectsAmbiguousPhoneOrEmailMatches() {
        assertThatThrownBy(() -> WecomService.resolveMatch(List.of(1L, 2L), List.of()))
            .isInstanceOf(WecomService.SyncUserException.class);
        assertThatThrownBy(() -> WecomService.resolveMatch(List.of(1L), List.of(2L)))
            .isInstanceOf(WecomService.SyncUserException.class);
        assertThat(WecomService.resolveMatch(List.of(1L), List.of(1L))).isEqualTo(1);
    }

    @Test
    void ordersParentsBeforeChildrenAndRejectsCycles() {
        WecomClient.WecomDepartment child = department(2, 1);
        WecomClient.WecomDepartment root = department(1, 0);
        assertThat(WecomService.orderDepartments(List.of(child, root)))
            .extracting(WecomClient.WecomDepartment::id).containsExactly(1L, 2L);
        assertThatThrownBy(() -> WecomService.orderDepartments(
            List.of(department(1, 2), department(2, 1))))
            .isInstanceOf(WecomClient.WecomApiException.class);
    }

    @Test
    void generatesStableCompanyScopedUsernames() {
        assertThat(WecomService.deterministicUsername(1, "zhangsan"))
            .isEqualTo(WecomService.deterministicUsername(1, "zhangsan"))
            .isNotEqualTo(WecomService.deterministicUsername(2, "zhangsan"))
            .startsWith("wx_");
    }

    private static WecomClient.WecomUser user(List<Long> departments, long main) {
        return new WecomClient.WecomUser("u1", "User", departments, main, "", "", "", "0",
            1, List.of());
    }

    private static WecomClient.WecomDepartment department(long id, long parentId) {
        return new WecomClient.WecomDepartment(id, parentId, 0, "D" + id, List.of());
    }
}
