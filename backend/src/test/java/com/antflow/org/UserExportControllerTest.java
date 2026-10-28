package com.antflow.org;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通讯录导出：以前是前端把**当前页**（一页 15 人）拼成 CSV，导出看着成功却少了人。
 * 现在走后端同一条 `listAuthorized`，这里的判据是"服务端返回多少人，文件里就有多少行"。
 */
class UserExportControllerTest {
    @BeforeEach
    void setPrincipal() {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "operator", List.of("admin")));
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    @Test
    void exportContainsEveryMatchingRowNotJustOnePage() {
        UserService userService = Mockito.mock(UserService.class);
        when(userService.listAuthorized(any())).thenReturn(List.of(
            user(1L, "张三", "000001", "zhangsan", "M"),
            user(2L, "李四", "000002", "lisi", "F"),
            user(3L, "王五", "000003", "wangwu", null)));
        UserController controller = controller(userService);

        String csv = body(controller.export(null, 9L, true, null, null, null, null, "csv"));

        assertThat(csv).contains("张三").contains("李四").contains("王五");
        assertThat(csv.lines().count()).isEqualTo(4);
        // 必须是"无分页"的那条查询：带上分页参数就会退化成只导当前页。
        verify(userService, never()).listAuthorizedPage(any(), any(), Mockito.anyBoolean(),
            Mockito.anyLong(), Mockito.anyLong());
    }

    /** 表头与值都要和前端导入器（Contacts.utils.ts 的 headerMap）对得上，导出才能原样导回。 */
    @Test
    void csvPinsHeadersAndGenderLabels() {
        UserService userService = Mockito.mock(UserService.class);
        when(userService.listAuthorized(any())).thenReturn(List.of(
            user(1L, "张三,主管", "000001", "zhangsan", "M")));
        UserController controller = controller(userService);

        String csv = body(controller.export(null, null, null, null, null, null, null, "csv"));

        assertThat(csv).startsWith("\uFEFF姓名,工号,账号,手机,邮箱,职务,性别\r\n");
        assertThat(csv).contains("\"张三,主管\",000001,zhangsan,,,组长,男");
    }

    @Test
    void departmentFilterIsPassedThroughAsUnpagedScope() {
        UserService userService = Mockito.mock(UserService.class);
        when(userService.listAuthorized(any())).thenReturn(List.of());
        UserController controller = controller(userService);

        controller.export(null, 9L, true, null, null, null, null, "csv");

        ArgumentCaptor<UserService.UserQuery> query =
            ArgumentCaptor.forClass(UserService.UserQuery.class);
        verify(userService).listAuthorized(query.capture());
        assertThat(query.getValue().departmentId()).isEqualTo(9L);
        assertThat(query.getValue().includeDescendants()).isTrue();
    }

    /** 指名范围但名单为空 = 零候选（与列表同一条判据），不能退化成"全员可导"。 */
    @Test
    void namedScopeWithoutMembersExportsNothing() {
        UserService userService = Mockito.mock(UserService.class);
        UserController controller = controller(userService);

        String csv = body(controller.export(null, null, null, null, null, List.of(), "user", "csv"));

        assertThat(csv).isEqualTo("\uFEFF姓名,工号,账号,手机,邮箱,职务,性别\r\n");
        verify(userService, never()).listAuthorized(any());
    }

    @Test
    void unsupportedFormatIsRejected() {
        UserController controller = controller(Mockito.mock(UserService.class));

        BizException error = assertThrows(BizException.class, () ->
            controller.export(null, null, null, null, null, null, null, "pdf"));

        assertThat(error.getMessage()).contains("csv 或 xlsx");
    }

    @Test
    void exportIsAuditedWithRowCount() {
        UserService userService = Mockito.mock(UserService.class);
        when(userService.listAuthorized(any())).thenReturn(List.of(user(1L, "张三", "1", "z", "M")));
        AuditService auditService = Mockito.mock(AuditService.class);
        UserController controller = new UserController(Mockito.mock(UserMapper.class), userService,
            Mockito.mock(AuthorizationService.class), auditService);

        controller.export(null, null, null, null, null, null, null, "csv");

        verify(auditService).success(eq("org.user.export"), eq("USER"), isNull(),
            eq(AuditService.RiskLevel.HIGH), any(), eq(Map.of("rowCount", 1, "truncated", false,
                "limit", UserController.EXPORT_LIMIT, "format", "csv")));
    }

    private static UserController controller(UserService userService) {
        return new UserController(Mockito.mock(UserMapper.class), userService,
            Mockito.mock(AuthorizationService.class), Mockito.mock(AuditService.class));
    }

    private static User user(Long id, String name, String employeeNo, String username,
                             String gender) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(name);
        user.setEmployeeNo(employeeNo);
        user.setUsername(username);
        user.setPosition("组长");
        user.setGender(gender);
        return user;
    }

    private static String body(org.springframework.http.ResponseEntity<byte[]> response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }
}
