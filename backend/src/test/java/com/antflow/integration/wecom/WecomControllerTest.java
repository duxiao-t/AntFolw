package com.antflow.integration.wecom;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WecomControllerTest {
    private WecomService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = org.mockito.Mockito.mock(WecomService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WecomController(service)).build();
    }

    @Test
    void settingsNeverExposeSecret() throws Exception {
        when(service.settings(1)).thenReturn(new WecomService.SettingsDto(
            1, "ww-corp", true, job()));

        mvc.perform(get("/api/integrations/wecom/settings").param("companyId", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.corpId").value("ww-corp"))
            .andExpect(jsonPath("$.secretConfigured").value(true))
            .andExpect(jsonPath("$.secret").doesNotExist())
            .andExpect(jsonPath("$.latestJob.status").value("SUCCESS"));
    }

    @Test
    void blankSecretIsAcceptedForPreservingExistingValue() throws Exception {
        when(service.saveSettings(1, "ww-corp", "")).thenReturn(
            new WecomService.SettingsDto(1, "ww-corp", true, null));

        mvc.perform(put("/api/integrations/wecom/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":1,\"corpId\":\"ww-corp\",\"secret\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.secret").doesNotExist());

        verify(service).saveSettings(1, "ww-corp", "");
    }

    private static WecomService.JobDto job() {
        return new WecomService.JobDto(9, 1, "SUCCESS", "COMPLETED", 100,
            2, 2, 1, 1, 0, "完成", List.of(), null, null);
    }
}
