package com.antflow.form.runtime;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.FormGrantService;
import com.antflow.authz.PermissionCodes;
import com.antflow.common.FormalNumberService;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.form.FormDefinitionService;
import com.antflow.engine.BizException;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.mobile.workflow.MobileFileLinkService;
import com.antflow.mobile.workflow.MobileDraftService;
import com.antflow.process.DefinitionVersionRepository;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import com.antflow.mobile.workflow.MobileFileRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class FormDataServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private FormDataMapper formDataMapper;
    private FormDefinitionMapper formDefinitionMapper;
    private FormalNumberService formalNumberService;
    private UserMapper userMapper;
    private com.antflow.org.DepartmentMapper departmentMapper;
    private FormDataService service;
    private MobileFileLinkService fileLinkService;
    private MobileDraftService draftService;
    private AuthorizationService authorizationService;
    private com.antflow.form.options.OptionRuntimeService optionRuntime;

    @BeforeEach
    void setUp() {
        formDataMapper = Mockito.mock(FormDataMapper.class);
        formDefinitionMapper = Mockito.mock(FormDefinitionMapper.class);
        formalNumberService = Mockito.mock(FormalNumberService.class);
        userMapper = Mockito.mock(UserMapper.class);
        departmentMapper = Mockito.mock(com.antflow.org.DepartmentMapper.class);
        var formDefinitionService = new FormDefinitionService(formDefinitionMapper, json,
            Mockito.mock(FormGrantService.class));
        fileLinkService = Mockito.mock(MobileFileLinkService.class);
        draftService = Mockito.mock(MobileDraftService.class);
        authorizationService = Mockito.mock(AuthorizationService.class);
        optionRuntime = Mockito.mock(com.antflow.form.options.OptionRuntimeService.class);
        service = new FormDataService(formDataMapper, formDefinitionService, json,
            formalNumberService, authorizationService, userMapper, departmentMapper,
            optionRuntime, fileLinkService, draftService);

        Mockito.when(formalNumberService.businessNo()).thenReturn("000000000001");
        Mockito.when(authorizationService.currentUserId()).thenReturn(7L);
        Mockito.when(formDataMapper.insert(any(FormData.class))).thenAnswer(invocation -> {
            FormData data = invocation.getArgument(0);
            data.setId(100L);
            return 1;
        });
    }

    @Test
    void directSubmitAcceptsFlatValuesFromLayoutContainers() throws Exception {
        Mockito.when(formDefinitionMapper.selectOne(any())).thenReturn(publishedNoWorkflowForm());

        Long id = service.submit(
            "expense",
            "SUBMITTED",
            Map.of("applicant", "张三", "reason", "报销"),
            7L
        );

        assertThat(id).isEqualTo(100L);
        ArgumentCaptor<FormData> captor = ArgumentCaptor.forClass(FormData.class);
        Mockito.verify(formDataMapper).insert(captor.capture());
        FormData saved = captor.getValue();
        assertThat(saved.getBusinessNo()).isEqualTo("000000000001");
        assertThat(saved.getStatus()).isEqualTo("SUBMITTED");
        assertThat(json.readTree(saved.getData()).path("applicant").asText()).isEqualTo("张三");
        assertThat(json.readTree(saved.getData()).path("reason").asText()).isEqualTo("报销");
        assertThat(json.readTree(saved.getData()).has("row")).isFalse();
        Mockito.verify(authorizationService).requireFormUse(10L);
    }

    @Test
    void directSubmitIsRefusedWhenTheFormHasAPublishedProcess() {
        Mockito.when(formDefinitionMapper.selectOne(any())).thenReturn(publishedNoWorkflowForm());
        var versions = Mockito.mock(DefinitionVersionRepository.class);
        Mockito.when(versions.hasPublishedProcess(10L)).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "versions", versions);

        // 有已发布流程的表单只能走引擎发起（/api/instances/start）；直提会造出没有
        // t_process_instance 的记录，等于绕过审批。
        assertThatThrownBy(() -> service.submit("expense", "SUBMITTED",
            Map.of("applicant", "张三"), 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("审批流程");
        Mockito.verify(formDataMapper, Mockito.never()).insert(Mockito.any(FormData.class));
    }

    @Test
    void draftIsStillAllowedWhenTheFormHasAPublishedProcess() {
        Mockito.when(formDefinitionMapper.selectOne(any())).thenReturn(publishedNoWorkflowForm());
        var versions = Mockito.mock(DefinitionVersionRepository.class);
        Mockito.when(versions.hasPublishedProcess(10L)).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "versions", versions);

        // 存草稿不进审批，不该被拦。
        Long id = service.submit("expense", "DRAFT",
            Map.of("applicant", "张三", "reason", "报销"), 7L);

        assertThat(id).isEqualTo(100L);
    }

    @Test
    void directSubmitLinksUploadedFilesInTheSubmissionTransaction() {
        Mockito.when(formDefinitionMapper.selectOne(any())).thenReturn(publishedNoWorkflowForm());
        UUID fileId = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        List<MobileFileRef> refs = List.of(new MobileFileRef(fileId, "voice", 0));

        service.submit("expense", "SUBMITTED",
            Map.of("applicant", "张三", "reason", "报销"), 7L, refs);

        Mockito.verify(fileLinkService).append(100L, refs, 7L);
    }

    @Test
    void directSubmitConsumesItsSourceDraftInTheSubmissionTransaction() {
        Mockito.when(formDefinitionMapper.selectOne(any())).thenReturn(publishedNoWorkflowForm());

        service.submit("expense", "SUBMITTED",
            Map.of("applicant", "张三", "reason", "报销"), 7L, List.of(), 101L);

        Mockito.verify(draftService).deleteAfterSubmit(101L, 10L, 7L);
    }

    @Test
    void directSubmitRejectsMismatchedCaller() {
        Mockito.when(formDefinitionMapper.selectOne(any())).thenReturn(publishedNoWorkflowForm());
        Mockito.when(authorizationService.currentUserId()).thenReturn(8L);

        assertThatThrownBy(() -> service.submit("expense", "SUBMITTED",
            Map.of("applicant", "张三", "reason", "报销"), 7L))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        Mockito.verify(formDataMapper, Mockito.never()).insert(Mockito.any(FormData.class));
    }

    @Test
    void adminPageReturnsSubmitterIdentityAndNamedFieldValues() {
        FormData record = new FormData();
        record.setId(100L);
        record.setFormDefId(10L);
        record.setCreatedBy(7L);
        record.setData("{\"applicant\":\"张三\",\"unknown\":123}");
        Page<FormData> page = Page.of(1, 20, 1);
        page.setRecords(java.util.List.of(record));
        User user = new User();
        user.setId(7L);
        user.setUsername("zhangsan");
        user.setDisplayName("张三");
        user.setEmployeeNo("000007");
        user.setDeptId(4L);
        var department = new com.antflow.org.Department();
        department.setId(4L);
        department.setName("技术部");
        Mockito.when(formDataMapper.selectPage(any(), any())).thenReturn(page);
        Mockito.when(formDataMapper.selectSchemas(any()))
            .thenReturn(java.util.List.of(new FormDataMapper.SchemaRow(100L, publishedNoWorkflowForm().getSchema())));
        Mockito.when(userMapper.selectBatchIds(any())).thenReturn(java.util.List.of(user));
        Mockito.when(departmentMapper.selectBatchIds(any())).thenReturn(java.util.List.of(department));

        FormData result = service.adminPage(1, 20, 10L, null, null).getRecords().get(0);

        // 台账三列：姓名 / 工号 / 部门。
        assertThat(result.getCreatedByName()).isEqualTo("张三");
        assertThat(result.getCreatedByEmployeeNo()).isEqualTo("000007");
        assertThat(result.getCreatedByDeptName()).isEqualTo("技术部");
        assertThat(result.getFieldValues()).containsExactly(
            new FormData.FieldValue("applicant", "申请人", "张三", "张三", "张三"),
            new FormData.FieldValue("unknown", "unknown", 123, "123", "123"));
    }

    /** 同一页里混着两个版本的记录：各按**自己那版**的标签与选项显示，不能一张表一个字典。 */
    @Test
    void adminPageResolvesEachRecordsOwnFormVersion() {
        FormData old = new FormData();
        old.setId(100L);
        old.setFormDefId(10L);
        old.setFormDefVersion(1);
        old.setData("{\"craft\":\"option_1\"}");
        FormData current = new FormData();
        current.setId(101L);
        current.setFormDefId(10L);
        current.setFormDefVersion(2);
        current.setData("{\"craft\":\"option_1\"}");
        Page<FormData> page = Page.of(1, 20, 2);
        page.setRecords(java.util.List.of(old, current));
        Mockito.when(formDataMapper.selectPage(any(), any())).thenReturn(page);
        Mockito.when(formDataMapper.selectSchemas(any())).thenReturn(java.util.List.of(
            new FormDataMapper.SchemaRow(100L, versionedSchema("工艺项", "车削")),
            new FormDataMapper.SchemaRow(101L, versionedSchema("工艺", "镗削"))));

        java.util.List<FormData> records = service.adminPage(1, 20, 10L, null, null).getRecords();

        assertThat(records.get(0).getFieldValues()).containsExactly(
            new FormData.FieldValue("craft", "工艺项", "option_1", "车削", "车削"));
        assertThat(records.get(1).getFieldValues()).containsExactly(
            new FormData.FieldValue("craft", "工艺", "option_1", "镗削", "镗削"));
    }

    private static String versionedSchema(String label, String optionLabel) {
        return "[{\"id\":\"craft\",\"type\":\"select\",\"label\":\"" + label + "\",\"props\":"
            + "{\"options\":[{\"value\":\"option_1\",\"label\":\"" + optionLabel + "\"}]}}]";
    }

    @Test
    void adminPageFallsBackToUsernameAndToleratesMissingSubmitters() {
        FormData named = new FormData();
        named.setId(100L);
        named.setFormDefId(10L);
        named.setCreatedBy(7L);
        named.setData("{}");
        FormData anonymous = new FormData();
        anonymous.setId(101L);
        anonymous.setFormDefId(10L);
        anonymous.setData("{}");
        // created_by 为空的历史数据：不能把空 ID 集合丢给 selectBatchIds（会拼出 IN ()），
        // 也不该拿别人的部门名糊上去。整页都没有提交人时一次批查都不该发生。
        Page<FormData> page = Page.of(1, 20, 2);
        page.setRecords(java.util.List.of(anonymous));
        Page<FormData> mixed = Page.of(1, 20, 2);
        mixed.setRecords(java.util.List.of(named, anonymous));
        User user = new User();
        user.setId(7L);
        user.setUsername("zhangsan");
        // display_name 为空 → 显示名回落账号；没有部门 → 部门名留空。
        Mockito.when(formDataMapper.selectPage(any(), any())).thenReturn(page);
        Mockito.when(formDataMapper.selectSchemas(any()))
            .thenReturn(java.util.List.of(new FormDataMapper.SchemaRow(100L, "[]"),
                new FormDataMapper.SchemaRow(101L, "[]")));
        Mockito.when(userMapper.selectBatchIds(any())).thenReturn(java.util.List.of(user));

        FormData onlyAnonymous = service.adminPage(1, 20, 10L, null, null).getRecords().get(0);

        assertThat(onlyAnonymous.getCreatedByName()).isNull();
        assertThat(onlyAnonymous.getCreatedByDeptName()).isNull();
        Mockito.verify(userMapper, Mockito.never()).selectBatchIds(any());
        Mockito.verify(departmentMapper, Mockito.never()).selectBatchIds(any());

        Mockito.when(formDataMapper.selectPage(any(), any())).thenReturn(mixed);

        java.util.List<FormData> records = service.adminPage(1, 20, 10L, null, null).getRecords();

        assertThat(records.get(0).getCreatedByName()).isEqualTo("zhangsan");
        assertThat(records.get(0).getCreatedByEmployeeNo()).isNull();
        Mockito.verify(departmentMapper, Mockito.never()).selectBatchIds(any());
    }

    private static FormDefinition publishedNoWorkflowForm() {
        FormDefinition form = new FormDefinition();
        form.setId(10L);
        form.setCode("expense");
        form.setName("费用报销");
        form.setVersion(3);
        form.setStatus("PUBLISHED");
        form.setSettings("{\"workflowEnabled\":false}");
        form.setSchema("""
            [
              {"id":"row","type":"span_layout","label":"基本信息","children":[
                {"id":"applicant","type":"text","label":"申请人","props":{"required":true}}
              ]},
              {"id":"reason","type":"text","label":"事由","props":{"required":true}}
            ]
            """);
        return form;
    }
}
