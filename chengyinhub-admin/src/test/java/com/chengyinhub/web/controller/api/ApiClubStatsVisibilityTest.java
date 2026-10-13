package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 俱乐部数据看板是当前消费面，不能统计已解散俱乐部的主题。 */
class ApiClubStatsVisibilityTest {

    @Test
    void clubDashboardRequestsOnlyTopicsOwnedByActiveClubs() {
        long memberId = 42L;
        ApiStatsController controller = spy(new ApiStatsController());
        doReturn(memberId).when(controller).getAppUserId();

        IClubService clubService = mock(IClubService.class);
        Club club = activeClub(9L, memberId);
        when(clubService.selectClubById(9L)).thenReturn(club);
        ReflectionTestUtils.setField(controller, "clubService", clubService);

        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(Collections.<CmsTopic>emptyList());
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        Map<String, Object> body = new HashMap<>();
        body.put("clubId", 9L);
        assertEquals(200, controller.club(body).get("code"));
        ArgumentCaptor<CmsTopic> query = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicService).selectCmsTopicList(query.capture());
        assertEquals(Long.valueOf(9L), query.getValue().getClubId());
        assertEquals(null, query.getValue().getMemberId(), "多俱乐部看板不能再按 owner 汇总所有俱乐部");
        assertTrue(Boolean.TRUE.equals(query.getValue().getActiveClubOnly()),
                "看板必须走 activeClubOnly SQL，解散团主题不能继续贡献节点/玩家进度");
    }

    @Test
    void rejectsMissingCrossOwnedOrInactiveClubBeforeReadingTopics() {
        long memberId = 42L;
        ApiStatsController controller = spy(new ApiStatsController());
        doReturn(memberId).when(controller).getAppUserId();
        IClubService clubService = mock(IClubService.class);
        when(clubService.selectClubById(10L)).thenReturn(activeClub(10L, 99L));
        Club inactive = activeClub(11L, memberId);
        inactive.setStatus(0L);
        when(clubService.selectClubById(11L)).thenReturn(inactive);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        assertEquals(500, controller.club(Collections.<String, Object>emptyMap()).get("code"));
        assertEquals(500, controller.club(Collections.<String, Object>singletonMap("clubId", 10L)).get("code"));
        assertEquals(500, controller.club(Collections.<String, Object>singletonMap("clubId", 11L)).get("code"));
        verify(topicService, never()).selectCmsTopicList(any(CmsTopic.class));
    }

    private static Club activeClub(Long id, Long ownerMemberId) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(ownerMemberId);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }
}
