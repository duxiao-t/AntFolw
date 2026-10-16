package com.antflow.org;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4a 归属解析：**按身份分档，不按"有没有部门"**。
 *
 * <p>这里守的是"请求参数不能当身份用"：只要能拿一个 {@code departmentId}/公司 id 当身份，
 * 无部门的普通账号直接调 API 就能把单记到任意公司名下。所以身份一律取自
 * {@link PrincipalHolder}，请求里的公司只对"无部门的全局管理员"有意义。
 */
class CompanyAttributionTest {

    private DepartmentMapper departmentMapper;
    private CompanyMapper companyMapper;
    private CompanyAttribution attribution;

    @BeforeEach
    void setUp() {
        departmentMapper = Mockito.mock(DepartmentMapper.class);
        companyMapper = Mockito.mock(CompanyMapper.class);
        attribution = new CompanyAttribution(departmentMapper, companyMapper);
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    // ---------- 有部门：按身份推导 ----------

    @Test
    void departmentUserDerivesCompanyFromTheirOwnIdentity() {
        setPrincipal(7L, "user", 5L);
        Mockito.when(departmentMapper.selectById(5L)).thenReturn(department(5L, 9L));

        assertThat(attribution.resolve(null)).isEqualTo(9L);
    }

    @Test
    void departmentUserCannotBeBookedIntoAnotherCompany() {
        setPrincipal(7L, "user", 5L);
        Mockito.when(departmentMapper.selectById(5L)).thenReturn(department(5L, 9L));

        // 有部门的人**只能**归属到自己公司：否则任何人传个 companyId 就能把单记到别家名下。
        assertThatThrownBy(() -> attribution.resolve(7L))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("BAD_COMPANY"));
    }

    @Test
    void departmentUserAcceptsAnExplicitCompanyThatMatchesTheirOwn() {
        setPrincipal(7L, "user", 5L);
        Mockito.when(departmentMapper.selectById(5L)).thenReturn(department(5L, 9L));

        assertThat(attribution.resolve(9L)).isEqualTo(9L);
    }

    @Test
    void departmentWithoutCompanyFailsClosedInsteadOfWritingNull() {
        setPrincipal(7L, "user", 5L);
        Mockito.when(departmentMapper.selectById(5L)).thenReturn(department(5L, null));

        assertThatThrownBy(() -> attribution.resolve(null))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("BAD_COMPANY"));
    }

    @Test
    void deletedDepartmentFailsClosed() {
        setPrincipal(7L, "user", 5L);
        // 部门被删了、身份里还挂着旧 id：不能当成"没有部门"降级放行。
        Mockito.when(departmentMapper.selectById(5L)).thenReturn(null);

        assertThatThrownBy(() -> attribution.resolve(null))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("BAD_COMPANY"));
    }

    // ---------- 无部门 + 全局管理员：必须显式指定 ----------

    @Test
    void departmentlessAdminMustNameACompany() {
        setPrincipal(7L, "admin", null);

        assertThatThrownBy(() -> attribution.resolve(null))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("BAD_COMPANY"));
    }

    @Test
    void departmentlessAdminNamingAnUnknownCompanyIsRejected() {
        setPrincipal(7L, "admin", null);
        Mockito.when(companyMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> attribution.resolve(999L))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("BAD_COMPANY"));
    }

    @Test
    void departmentlessAdminNamingAnExistingCompanyIsAccepted() {
        setPrincipal(7L, "admin", null);
        Company company = new Company();
        company.setId(999L);
        Mockito.when(companyMapper.selectById(999L)).thenReturn(company);

        assertThat(attribution.resolve(999L)).isEqualTo(999L);
    }

    // ---------- 无部门 + 非管理员：拒绝创建 ----------

    @Test
    void departmentlessNonAdminCannotCreateAtAll() {
        setPrincipal(7L, "user", null);

        // 就算他伪造一个存在的公司也不行——身份里没有部门就是没有归属。
        assertThatThrownBy(() -> attribution.resolve(1L))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("BAD_COMPANY"));
        Mockito.verifyNoInteractions(companyMapper);
    }

    @Test
    void missingPrincipalIsAnAuthenticationFailureNotABusinessError() {
        // 没有身份时连"猜一个公司"都不该发生。
        assertThatThrownBy(() -> attribution.resolve(1L))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- 草稿：只认行里的快照 ----------

    @Test
    void draftKeepsItsOwnSnapshotEvenAfterTheUserMovedToAnotherCompany() {
        // 调岗后的用户（现属部门 8 → 公司 20）来提交 A 公司（9）建的草稿：仍归 A。
        setPrincipal(7L, "user", 8L);
        Mockito.when(departmentMapper.selectById(8L)).thenReturn(department(8L, 20L));

        assertThat(attribution.forDraft(9L, null)).isEqualTo(9L);
        // 关键：**没有查任何部门**——草稿路径绝不按当前身份重解析。
        Mockito.verifyNoInteractions(departmentMapper);
    }

    @Test
    void draftWithNoSnapshotIsBlockedWithAPendingCompanyMessage() {
        // V54 之前建的历史草稿：保留草稿、阻断转正式，提交时绝不猜公司。
        assertThatThrownBy(() -> attribution.forDraft(null, null))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("COMPANY_PENDING"));
    }

    @Test
    void draftRejectsAConflictingCompanyFromTheRequest() {
        assertThatThrownBy(() -> attribution.forDraft(9L, 20L))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("BAD_COMPANY"));
    }

    @Test
    void draftAcceptsARequestThatRepeatsItsOwnCompany() {
        assertThat(attribution.forDraft(9L, 9L)).isEqualTo(9L);
    }

    private static void setPrincipal(long userId, String role, Long departmentId) {
        PrincipalHolder.set(new PrincipalHolder.Principal(userId, "u" + userId, "用户" + userId,
            Set.of(role), Set.of(), 0L, departmentId, null));
    }

    private static Department department(long id, Long companyId) {
        Department department = new Department();
        department.setId(id);
        department.setCompanyId(companyId);
        return department;
    }
}
