package com.antflow.form;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Schema validation unit test — runs without Postgres.
 */
class FormDefinitionServiceSchemaTest {

    private FormDefinitionService service;
    private FormDefinitionMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        mapper = Mockito.mock(FormDefinitionMapper.class);
        service = new FormDefinitionService(mapper, json);
    }

    @Test void publishAcceptsNonEmptySchema() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setVersion(1);
        fd.setSchema("[{\"id\":\"a\",\"type\":\"text\"}]");
        when(mapper.selectById(1L)).thenReturn(fd);

        var pub = service.publish(1L);
        assertThat(pub.getStatus()).isEqualTo("PUBLISHED");
        assertThat(pub.getVersion()).isEqualTo(2);
    }

    @Test void publishRejectsEmptySchema() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("[]");
        when(mapper.selectById(1L)).thenReturn(fd);
        assertThatThrownBy(() -> service.publish(1L)).isInstanceOf(BizException.class);
    }

    @Test void publishRejectsNonArraySchema() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("{\"id\":\"a\"}");
        when(mapper.selectById(1L)).thenReturn(fd);
        assertThatThrownBy(() -> service.publish(1L)).isInstanceOf(BizException.class);
    }

    @Test void saveDraftTranslatesObjectToJsonString() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(FormDefinition.class))).thenAnswer(inv -> {
            FormDefinition fd = inv.getArgument(0);
            fd.setId(42L);
            return 1;
        });
        var fd = service.saveDraft(null, "leave_req", "请假",
            List.of(Map.of("id", "a", "type", "text", "label", "x", "props", Map.of())),
            null, 1L);
        assertThat(fd.getId()).isEqualTo(42L);
        assertThat(fd.getSchema()).startsWith("[");
    }
}
