package com.antflow.form.runtime;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.FormGrantService;
import com.antflow.common.FormalNumberService;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.form.FormDefinitionService;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class FormDataServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private FormDataMapper formDataMapper;
    private FormDefinitionMapper formDefinitionMapper;
    private FormalNumberService formalNumberService;
    private UserMapper userMapper;
    private FormDataService service;

    @BeforeEach
    void setUp() {
        formDataMapper = Mockito.mock(FormDataMapper.class);
        formDefinitionMapper = Mockito.mock(FormDefinitionMapper.class);
        formalNumberService = Mockito.mock(FormalNumberService.class);
        userMapper = Mockito.mock(UserMapper.class);
        var formDefinitionService = new FormDefinitionService(formDefinitionMapper, json,
            Mockito.mock(FormGrantService.class));
        service = new FormDataService(formDataMapper, formDefinitionService, json,
            formalNumberService, Mockito.mock(AuthorizationService.class), userMapper,
            formDefinitionMapper);

        Mockito.when(formalNumberService.businessNo()).thenReturn("000000000001");
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
    }

    @Test
    void adminPageReturnsUsernameAndNamedFieldValues() {
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
        Mockito.when(formDataMapper.selectPage(any(), any())).thenReturn(page);
        Mockito.when(userMapper.selectBatchIds(any())).thenReturn(java.util.List.of(user));
        Mockito.when(formDefinitionMapper.selectBatchIds(any()))
            .thenReturn(java.util.List.of(publishedNoWorkflowForm()));

        FormData result = service.adminPage(1, 20, 10L, null, null).getRecords().get(0);

        assertThat(result.getCreatedByUsername()).isEqualTo("zhangsan");
        assertThat(result.getFieldValues()).containsExactly(
            new FormData.FieldValue("applicant", "申请人", "张三"),
            new FormData.FieldValue("unknown", "unknown", 123));
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
