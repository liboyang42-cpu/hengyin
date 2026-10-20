package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.service.IClubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** V5a SC-CL-APPROVE:/my-apply 返回最新的一条申请(含驳回原因),不再是无序的 limit 1。 */
class ApiClubMyApplyNewestFirstTest {
    private static final long MEMBER_ID = 42L;
    private MockMvc mvc;
    private IClubService clubService;

    @BeforeEach
    void setUp() {
        ApiClubController controller = spy(new ApiClubController());
        doReturn(MEMBER_ID).when(controller).getAppUserId();
        clubService = mock(IClubService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void myApplyReturnsTheNewestApplicationWithItsRejectReason() throws Exception {
        Club newest = club(15L, 2L, "城市不明确");
        Club older = club(11L, 1L, null);
        when(clubService.selectClubsByMemberId(MEMBER_ID)).thenReturn(Arrays.asList(newest, older));
        when(clubService.selectClubByMemberId(MEMBER_ID)).thenReturn(older);

        mvc.perform(post("/api/club/my-apply"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(15))
                .andExpect(jsonPath("$.data.rejectReason").value("城市不明确"));
    }

    private Club club(Long id, Long status, String reason) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(MEMBER_ID);
        club.setName("club-" + id);
        club.setStatus(status);
        club.setRejectReason(reason);
        return club;
    }
}
