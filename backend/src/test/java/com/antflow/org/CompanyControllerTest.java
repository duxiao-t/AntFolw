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
import static org.mockito.Mockito.*;

class CompanyControllerTest {
    @Test
    void createTrimsAndValidatesNameWithoutAcceptingServerFields() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        AuditService audit = mock(AuditService.class);
        when(audit.execute(any(Supplier.class), any(Consumer.class))).thenAnswer(call ->
            ((Supplier<?>) call.getArgument(0)).get());
        CompanyController controller = new CompanyController(mapper,
            mock(AuthorizationService.class), audit, mock(OrgCompanyAccess.class));
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
        CompanyController controller = new CompanyController(mapper, mock(AuthorizationService.class),
            mock(AuditService.class), access);
        assertThat(controller.all()).isEqualTo(List.of());
        verify(mapper, never()).selectList(any());
    }
}
