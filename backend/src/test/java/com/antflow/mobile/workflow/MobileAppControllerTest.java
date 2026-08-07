package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.audit.AuditService;
import com.antflow.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MobileAppControllerTest {
    private MobileAppService mobileAppService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mobileAppService = Mockito.mock(MobileAppService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MobileAppController(mobileAppService))
            .setControllerAdvice(new GlobalExceptionHandler(Mockito.mock(AuditService.class)))
            .build();
    }

    @AfterEach
    void tearDown() {
        PrincipalHolder.clear();
    }

    @Test
    void listsAppsForAnAuthenticatedUser() throws Exception {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "mobile-user", List.of("user")));
        when(mobileAppService.list("请假", "other")).thenReturn(List.of(
            new MobileAppDto(11L, "leave", "请假申请", null, "other", "其他", null)));

        mockMvc.perform(get("/api/mobile/apps")
                .param("keyword", "请假")
                .param("category", "other"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].formId").value(11))
            .andExpect(jsonPath("$[0].code").value("leave"));
    }

    @Test
    void savesFavoritesForTheCurrentUser() throws Exception {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "mobile-user", List.of("user")));

        mockMvc.perform(put("/api/mobile/preferences/apps")
                .contentType("application/json")
                .content("{\"formIds\":[11,12]}"))
            .andExpect(status().isNoContent());

        verify(mobileAppService).saveFavorites(7L, List.of(11L, 12L));
    }

    @Test
    void rejectsRequestsWithoutAnAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/mobile/apps"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
