package com.antflow.process;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Validates the linear-flow invariant + FOR_FORM_NOT_PUBLISHED gate
 * (no Postgres required).
 */
class ProcessDefinitionServiceValidationTest {

    private ProcessDefinitionMapper pdMapper;
    private FormDefinitionService formDefService;
    private final ObjectMapper json = new ObjectMapper();
    private ProcessDefinitionService service;

    @BeforeEach void setup() {
        pdMapper = Mockito.mock(ProcessDefinitionMapper.class);
        formDefService = Mockito.mock(FormDefinitionService.class);
        service = new ProcessDefinitionService(pdMapper, formDefService, json);
    }

    private ProcessDefinition pd(String status) {
        var pd = new ProcessDefinition();
        pd.setId(1L);
        pd.setFormDefId(42L);
        pd.setStatus(status);
        pd.setVersion(1);
        pd.setNodes("[{\"id\":\"start\",\"type\":\"start\",\"x\":0,\"y\":0,\"props\":{}}," +
                     "{\"id\":\"a1\",\"type\":\"approval\",\"x\":120,\"y\":40," +
                     "\"assignee\":{\"type\":\"user\",\"ids\":[1]},\"props\":{}}," +
                     "{\"id\":\"end\",\"type\":\"end\",\"x\":240,\"y\":40,\"props\":{}}]");
        pd.setEdges("[{\"from\":\"start\",\"to\":\"a1\"},{\"from\":\"a1\",\"to\":\"end\"}]");
        return pd;
    }

    @Test void publishRequiresFormPublished() {
        when(pdMapper.selectById(1L)).thenReturn(pd("DRAFT"));
        when(formDefService.getById(42L)).thenReturn(formDef("DRAFT"));
        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .matches(e -> ((BizException) e).getCode().equals("FOR_FORM_NOT_PUBLISHED"));
    }

    @Test void publishAcceptsLinearFlow() {
        when(pdMapper.selectById(1L)).thenReturn(pd("DRAFT"));
        when(formDefService.getById(42L)).thenReturn(formDef("PUBLISHED"));
        var pub = service.publish(1L);
        org.assertj.core.api.Assertions.assertThat(pub.getStatus()).isEqualTo("PUBLISHED");
        org.assertj.core.api.Assertions.assertThat(pub.getVersion()).isEqualTo(2);
    }

    @Test void publishRejectsFork() {
        var forked = pd("DRAFT");
        // Add a second outgoing edge from `start` so the start node has out-degree 2.
        forked.setEdges("[{\"from\":\"start\",\"to\":\"a1\"},{\"from\":\"start\",\"to\":\"end\"}]");
        when(pdMapper.selectById(1L)).thenReturn(forked);
        when(formDefService.getById(42L)).thenReturn(formDef("PUBLISHED"));
        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .matches(e -> ((BizException) e).getCode().equals("BAD_FLOW"));
    }

    @Test void publishRejectsUnknownNodeType() {
        var bad = pd("DRAFT");
        bad.setNodes(bad.getNodes().replace("approval", "weirdtype"));
        when(pdMapper.selectById(1L)).thenReturn(bad);
        when(formDefService.getById(42L)).thenReturn(formDef("PUBLISHED"));
        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class);
    }

    private FormDefinition formDef(String status) {
        var fd = new FormDefinition();
        fd.setId(42L);
        fd.setCode("t");
        fd.setStatus(status);
        fd.setVersion(1);
        fd.setSchema("[]");
        fd.setSettings("{}");
        return fd;
    }
}
