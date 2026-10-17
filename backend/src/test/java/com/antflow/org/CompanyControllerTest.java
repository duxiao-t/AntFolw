package com.antflow.org;

import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 公司管理的用例级测试：只钉住**控制器自己的职责**——名称校验、改名短路、两道停用守卫，
 * 以及与审计/数据范围的交互方式。真实的 SQL 与逻辑删除行为由集成测试与 Flyway 保证，这里不重复验证。
 */
class CompanyControllerTest {
    @Test
    void createTrimsAndValidatesNameWithoutAcceptingServerFields() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        AuditService audit = mock(AuditService.class);
        when(audit.execute(any(Supplier.class), any(Consumer.class))).thenAnswer(call ->
            ((Supplier<?>) call.getArgument(0)).get());
        CompanyController controller = newController(mapper, mock(DepartmentMapper.class), audit);
        assertThat(controller.create(new CompanyController.CreateCompanyRequest("  新公司  ")).getName())
            .isEqualTo("新公司");
        for (String name : new String[] {null, "  ", "中".repeat(129)}) {
            assertThat(assertThrows(BizException.class,
                () -> controller.create(new CompanyController.CreateCompanyRequest(name))).getCode())
                .isEqualTo("BAD_COMPANY_NAME");
        }
        verify(mapper, times(1)).insert(any(Company.class));
    }

    @Test
    void missingCompanyReturnsEmptyInsteadOfListingEveryCompany() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        OrgCompanyAccess access = mock(OrgCompanyAccess.class);
        when(access.currentCompanyId()).thenReturn(null);
        CompanyController controller = newController(mapper, mock(DepartmentMapper.class),
            mock(AuditService.class), access);
        assertThat(controller.all()).isEqualTo(List.of());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void viewReturnsTheCompanyWithinScope() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        when(mapper.selectById(7L)).thenReturn(company(7L, "本公司"));
        CompanyController controller = newController(mapper, mock(DepartmentMapper.class),
            mock(AuditService.class));
        assertThat(controller.one(7L).getName()).isEqualTo("本公司");
    }

    @Test
    void viewHidesMissingOrDeactivatedCompany() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        when(mapper.selectById(7L)).thenReturn(null);
        CompanyController controller = newController(mapper, mock(DepartmentMapper.class),
            mock(AuditService.class));
        assertThat(assertThrows(BizException.class, () -> controller.one(7L)).getCode())
            .isEqualTo("NOT_FOUND");
    }

    @Test
    void updateTrimsNameAndWritesOnce() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        AuditService audit = mock(AuditService.class);
        when(audit.execute(any(Supplier.class), any(Consumer.class))).thenAnswer(call ->
            ((Supplier<?>) call.getArgument(0)).get());
        when(mapper.selectById(7L)).thenReturn(company(7L, "旧名"));
        CompanyController controller = newController(mapper, mock(DepartmentMapper.class), audit);
        assertThat(controller.update(7L, new CompanyController.UpdateCompanyRequest("  新名  ")).getName())
            .isEqualTo("新名");
        verify(mapper, times(1)).updateById(any(Company.class));
    }

    @Test
    void updateWithUnchangedNameSkipsWriteAndAudit() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        when(mapper.selectById(7L)).thenReturn(company(7L, "同名"));
        CompanyController controller = newController(mapper, mock(DepartmentMapper.class),
            mock(AuditService.class));
        assertThat(controller.update(7L, new CompanyController.UpdateCompanyRequest("同名")).getName())
            .isEqualTo("同名");
        verify(mapper, never()).updateById(any(Company.class));
    }

    @Test
    void updateRejectsBlankName() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        when(mapper.selectById(7L)).thenReturn(company(7L, "旧名"));
        CompanyController controller = newController(mapper, mock(DepartmentMapper.class),
            mock(AuditService.class));
        assertThat(assertThrows(BizException.class, () ->
            controller.update(7L, new CompanyController.UpdateCompanyRequest("   "))).getCode())
            .isEqualTo("BAD_COMPANY_NAME");
    }

    @Test
    void deleteRejectsTheLastRemainingCompany() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        when(mapper.selectById(7L)).thenReturn(company(7L, "唯一的公司"));
        when(mapper.selectCount(any())).thenReturn(1L);
        CompanyController controller = newController(mapper, mock(DepartmentMapper.class),
            mock(AuditService.class));
        assertThat(assertThrows(BizException.class, () -> controller.delete(7L)).getCode())
            .isEqualTo("LAST_COMPANY");
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteRejectsCompanyThatStillHasDepartments() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        DepartmentMapper departments = mock(DepartmentMapper.class);
        when(mapper.selectById(7L)).thenReturn(company(7L, "有部门的公司"));
        when(mapper.selectCount(any())).thenReturn(2L);
        when(departments.selectCount(any())).thenReturn(3L);
        CompanyController controller = newController(mapper, departments, mock(AuditService.class));
        assertThat(assertThrows(BizException.class, () -> controller.delete(7L)).getCode())
            .isEqualTo("COMPANY_IN_USE");
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteSoftDeletesWhenNoDepartmentsRemain() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        DepartmentMapper departments = mock(DepartmentMapper.class);
        AuditService audit = mock(AuditService.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(audit).execute(any(Runnable.class), any(Runnable.class));
        when(mapper.selectById(7L)).thenReturn(company(7L, "待停用公司"));
        when(mapper.selectCount(any())).thenReturn(2L);
        when(departments.selectCount(any())).thenReturn(0L);
        CompanyController controller = newController(mapper, departments, audit);
        controller.delete(7L);
        // 逻辑删除下 deleteById 等价于 UPDATE deleted=1，不会物理删除
        verify(mapper, times(1)).deleteById(eq(7L));
    }

    private static CompanyController newController(CompanyMapper mapper, DepartmentMapper departments,
                                                   AuditService audit) {
        return newController(mapper, departments, audit, mock(OrgCompanyAccess.class));
    }

    private static CompanyController newController(CompanyMapper mapper, DepartmentMapper departments,
                                                   AuditService audit, OrgCompanyAccess access) {
        return new CompanyController(mapper, departments, mock(AuthorizationService.class), audit, access);
    }

    private static Company company(Long id, String name) {
        Company company = new Company();
        company.setId(id);
        company.setName(name);
        return company;
    }
}
