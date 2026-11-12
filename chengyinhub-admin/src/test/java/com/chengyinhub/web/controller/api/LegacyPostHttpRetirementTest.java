package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyPostHttpRetirementTest {
    @Test
    void obsoleteUrlsRemainExplicitlyGoneEvenIfHistoricalWriteFlagIsEnabled() throws Exception {
        ApiCreativeSquareController controller = new ApiCreativeSquareController();
        IUmsCreativeSquareService legacy = mock(IUmsCreativeSquareService.class);
        IViewCreativeSquareService views = mock(IViewCreativeSquareService.class);
        ICommunityPostService canonical = mock(ICommunityPostService.class);
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", legacy);
        ReflectionTestUtils.setField(controller, "viewCreativeSquareService", views);
        ReflectionTestUtils.setField(controller, "communityPostService", canonical);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        for (String route : new String[]{"action", "list", "info", "bookmark", "delete", "report", "like"}) {
            mvc.perform(post("/api/creativesquare/" + route).param("id", "7")
                            .param("contents", "不得写入旧表").param("request_id", "retired-post-request"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value(410))
                    .andExpect(jsonPath("$.msg").value("旧版广场接口已停用，请更新客户端后使用社区帖文"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        verifyNoInteractions(legacy, views, canonical);
    }
}
