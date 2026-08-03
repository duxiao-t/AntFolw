package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileAppServiceTest {
    @Mock
    private FormDefinitionMapper formDefinitionMapper;
    @Mock
    private MobileAppPreferenceMapper preferenceMapper;

    private MobileAppService service;

    @BeforeEach
    void setUp() {
        service = new MobileAppService(formDefinitionMapper, preferenceMapper, new ObjectMapper());
    }

    @Test
    void listsPublishedFormsAsMobileApps() {
        when(formDefinitionMapper.selectList(any())).thenReturn(List.of(form(3L), form(4L)));

        List<MobileAppDto> result = service.list("请假", null);

        assertEquals(List.of(3L, 4L), result.stream().map(MobileAppDto::formId).toList());
        assertEquals("其他", result.get(0).categoryLabel());
    }

    @Test
    void usesPublishedFormsAsDefaultsBeforePreferencesAreSaved() {
        when(preferenceMapper.selectById(7L)).thenReturn(null);
        when(formDefinitionMapper.selectList(any())).thenReturn(List.of(form(3L)));

        assertEquals(List.of(3L), service.favorites(7L).stream().map(MobileAppDto::formId).toList());
    }

    @Test
    void preservesSavedFavoriteOrderAndDropsUnavailableForms() {
        MobileAppPreference preference = new MobileAppPreference();
        preference.setUserId(7L);
        preference.setFormIds("[4,3,99]");
        when(preferenceMapper.selectById(7L)).thenReturn(preference);
        when(formDefinitionMapper.selectList(any())).thenReturn(List.of(form(3L), form(4L)));

        assertEquals(List.of(4L, 3L), service.favorites(7L).stream().map(MobileAppDto::formId).toList());
    }

    @Test
    void persistsAnExplicitEmptyFavoriteList() {
        when(preferenceMapper.selectById(7L)).thenReturn(null);

        service.saveFavorites(7L, List.of());

        ArgumentCaptor<MobileAppPreference> captor = ArgumentCaptor.forClass(MobileAppPreference.class);
        verify(preferenceMapper).insert(captor.capture());
        assertEquals("[]", captor.getValue().getFormIds());
    }

    @Test
    void rejectsMoreThanEightFavorites() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        BizException exception = assertThrows(BizException.class,
            () -> service.saveFavorites(7L, ids));

        assertEquals("TOO_MANY_FAVORITES", exception.getCode());
    }

    private FormDefinition form(long id) {
        FormDefinition form = new FormDefinition();
        form.setId(id);
        form.setCode("form-" + id);
        form.setName("表单 " + id);
        form.setDescription("测试表单");
        form.setStatus("PUBLISHED");
        return form;
    }
}
