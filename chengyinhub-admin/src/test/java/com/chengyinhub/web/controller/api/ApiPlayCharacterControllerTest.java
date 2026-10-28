package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.ThemeCharacterService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 实际 MVC 参数绑定；不把 mock 服务当作数据库归属或鉴权链验收。 */
class ApiPlayCharacterControllerTest {
    private ThemeCharacterService service;
    private ApiPlayCharacterController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ThemeCharacterService.class);
        controller = spy(new ApiPlayCharacterController());
        doReturn(71L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "themeCharacterService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rejectsRoundedNonCanonicalAndOverflowIdsBeforeAnyRead() throws Exception {
        for (String id : new String[]{"", "0", "-1", "01", " 1", "1 ", "1.9", "1e3", "+1",
                "9223372036854775808", "18446744073709551617", "NaN"}) {
            mvc.perform(get("/api/play/character").param("topicId", id))
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.errorCode").value("CHARACTER_BAD_REQUEST"));
        }
        mvc.perform(get("/api/play/character"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void preservesLongMaxAndUsesAuthenticatedMemberNotQueryMember() throws Exception {
        when(service.card(71L, Long.MAX_VALUE))
                .thenReturn(Collections.singletonMap("topicId", Long.toString(Long.MAX_VALUE)));
        mvc.perform(get("/api/play/character").param("topicId", Long.toString(Long.MAX_VALUE))
                        .param("memberId", "999"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.topicId").value("9223372036854775807"));
        verify(service).card(71L, Long.MAX_VALUE);
        verifyNoMoreInteractions(service);
    }

    @Test
    void anonymousReadNeverReachesCharacterService() throws Exception {
        doReturn(0L).when(controller).getAppUserId();
        mvc.perform(get("/api/play/character").param("topicId", "901"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.errorCode").value("CHARACTER_LOGIN_REQUIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void preservesServiceDenialCodeWithoutFabricatingATemplate() throws Exception {
        when(service.card(71L, 901L)).thenThrow(new ServiceException("主题不存在", 404)
                .setDetailMessage("CHARACTER_TOPIC_NOT_FOUND"));
        mvc.perform(get("/api/play/character").param("topicId", "901"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.errorCode").value("CHARACTER_TOPIC_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
