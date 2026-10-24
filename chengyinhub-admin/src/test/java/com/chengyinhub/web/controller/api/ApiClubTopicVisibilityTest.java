package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /api/club/topics 是公开读侧，不能复用可查草稿的运营查询。 */
class ApiClubTopicVisibilityTest {

    @Test
    void topicListExposesOnlyFailClosedGameModuleAvailabilityFlag() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopic ordinary = topic(1L, null);
        CmsTopic enabled = topic(2L,
                "{\"schemaVersion\":1,\"roles\":[{\"code\":\"DETECTIVE\"}],"
                        + "\"stations\":[{\"code\":\"CLOCK\"}]}");
        CmsTopic malformed = topic(3L, "{not-json");
        when(topicService.selectPublicCmsTopicList(any())).thenReturn(Arrays.asList(ordinary, enabled, malformed));

        ApiClubController controller = new ApiClubController();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        IGameSessionRuntimeService runtimeService = mock(IGameSessionRuntimeService.class);
        when(runtimeService.isGameSessionEnabled(ordinary)).thenReturn(false);
        when(runtimeService.isGameSessionEnabled(enabled)).thenReturn(true);
        when(runtimeService.isGameSessionEnabled(malformed)).thenReturn(false);
        ReflectionTestUtils.setField(controller, "gameSessionRuntimeService", runtimeService);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(controller, "cmsActivityService",
                mock(com.chengyinhub.business.service.ICmsActivityService.class));
        IClubService clubService = mock(IClubService.class);
        Club activeClub = new Club();
        activeClub.setId(73L);
        when(clubService.selectClubById(73L)).thenReturn(activeClub);
        ReflectionTestUtils.setField(controller, "clubService", clubService);

        Club body = new Club();
        body.setId(73L);
        Object data = controller.topics(body).get("data");
        List<?> rows = (List<?>) data;

        assertFalse(((CmsTopic) rows.get(0)).getGameModuleEnabled());
        assertTrue(((CmsTopic) rows.get(1)).getGameModuleEnabled());
        assertFalse(((CmsTopic) rows.get(2)).getGameModuleEnabled());
    }

    @Test
    void nonMemberUsesPublicClubScopedQueryInsteadOfDraftCapableQuery() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectPublicCmsTopicList(any())).thenReturn(Collections.emptyList());
        ApiClubController controller = new ApiClubController();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", mock(ICmsRegistrationService.class));
        ReflectionTestUtils.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        IClubService clubService = mock(IClubService.class);
        Club activeClub = new Club();
        activeClub.setId(73L);
        when(clubService.selectClubById(73L)).thenReturn(activeClub);
        ReflectionTestUtils.setField(controller, "clubService", clubService);

        Club body = new Club();
        body.setId(73L);
        controller.topics(body);

        ArgumentCaptor<CmsTopic> query = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicService).selectPublicCmsTopicList(query.capture());
        verify(topicService, never()).selectCmsTopicList(any());
        assertEquals(Long.valueOf(73L), query.getValue().getClubId(), "公开查询必须仍限定目标俱乐部");
    }

    @Test
    void publicTopicSqlPinsReviewPublishDeleteAndClubGates() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        String resource = "mapper/business/CmsTopicMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        CmsTopic query = new CmsTopic();
        query.setClubId(73L);
        String sql = configuration.getMappedStatement(
                        "com.chengyinhub.business.mapper.CmsTopicMapper.selectPublicCmsTopicList")
                .getBoundSql(query).getSql().replaceAll("\\s+", " ").trim().toLowerCase();

        assertTrue(sql.contains("status = 1 and user_status = 1 and del_flag = 0"), sql);
        assertTrue(sql.contains("club_id = ?"), sql);
        assertTrue(sql.contains("from coop_order") && sql.contains("to_type = 'club'")
                        && sql.contains("to_id = ?") && sql.contains("status = 0")
                        && sql.contains("del_flag = 0"),
                "执行俱乐部的生效合作主题必须进入公开列表:" + sql);
    }

    @Test
    void childListsReusePrivateClubVisibilityForEveryViewerState() {
        // public, legacy public, owner, member, outsider, pending, removed, anonymous,
        // identity failure, membership lookup failure.
        for (int scenario = 0; scenario < 10; scenario++) {
            final int state = scenario;
            ApiClubController controller = new ApiClubController() {
                @Override public Long getAppUserId() {
                    if (state == 8) throw new IllegalStateException("identity unavailable");
                    return state == 7 ? null : 42L;
                }
            };
            Club club = new Club();
            club.setId(73L);
            club.setMemberId(scenario == 2 ? 42L : 9L);
            club.setPublicVisible(scenario == 1 ? null : Integer.valueOf(scenario == 0 ? 1 : 0));
            IClubService clubs = mock(IClubService.class);
            when(clubs.selectClubById(73L)).thenReturn(club);
            IClubMemberService members = mock(IClubMemberService.class);
            if (scenario == 3 || scenario == 5 || scenario == 6) {
                ClubMember membership = new ClubMember();
                membership.setStatus(scenario == 3 ? 1 : scenario == 5 ? 0 : 2);
                when(members.selectMembershipRecord(73L, 42L)).thenReturn(membership);
            }
            if (scenario == 9) {
                when(members.selectMembershipRecord(73L, 42L))
                        .thenThrow(new IllegalStateException("membership unavailable"));
            }
            ICmsTopicService topics = mock(ICmsTopicService.class);
            when(topics.selectPublicCmsTopicList(any())).thenReturn(Collections.emptyList());
            IClubPostService posts = mock(IClubPostService.class);
            when(posts.selectClubPostPage(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Collections.emptyList());
            ReflectionTestUtils.setField(controller, "clubService", clubs);
            ReflectionTestUtils.setField(controller, "clubMemberService", members);
            ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
            ReflectionTestUtils.setField(controller, "clubPostService", posts);
            ClubPost postQuery = new ClubPost();
            postQuery.setClubId(73L);
            boolean allowed = scenario < 4;
            assertEquals(allowed ? 200 : 500, controller.topics(club).get("code"), "topics state " + scenario);
            assertEquals(allowed ? 200 : 500, controller.postList(postQuery).get("code"), "posts state " + scenario);
            verify(topics, org.mockito.Mockito.times(allowed ? 1 : 0)).selectPublicCmsTopicList(any());
            verify(posts, org.mockito.Mockito.times(allowed ? 1 : 0)).selectClubPostPage(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        }
    }

    @Test
    void rosterSessionLabelUsesShanghaiTimeAndPreservesDateOnlyMidnight() {
        ApiClubController controller = new ApiClubController();
        com.chengyinhub.business.domain.CmsActivity session = new com.chengyinhub.business.domain.CmsActivity();
        session.setStartDate(java.util.Date.from(java.time.Instant.parse("2026-10-15T06:00:00Z")));
        assertEquals("10月15日 14:00", ReflectionTestUtils.invokeMethod(controller, "sessionLabel", session));
        session.setStartDate(java.util.Date.from(java.time.Instant.parse("2026-10-14T16:00:00Z")));
        assertEquals("10月15日", ReflectionTestUtils.invokeMethod(controller, "sessionLabel", session));
        session.setStartDate(null);
        org.junit.jupiter.api.Assertions.assertNull(ReflectionTestUtils.invokeMethod(controller, "sessionLabel", session));
    }

    private CmsTopic topic(Long id, String config) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setGameModuleConfigJson(config);
        return topic;
    }

    // CU-C-19:团卡「已报名」必须与名册同口径 —— 主题票 + 本俱乐部该主题各场次的场次票(owner_type=2)。
    @Test
    void signupCountIncludesSessionTicketRegistrations() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectPublicCmsTopicList(any())).thenReturn(Collections.singletonList(topic(9L, null)));
        ApiClubController controller = new ApiClubController();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "gameSessionRuntimeService", mock(IGameSessionRuntimeService.class));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        com.chengyinhub.business.service.ICmsActivityService activityService =
                mock(com.chengyinhub.business.service.ICmsActivityService.class);
        com.chengyinhub.business.domain.CmsActivity session = new com.chengyinhub.business.domain.CmsActivity();
        session.setId(501L);
        when(activityService.selectCmsActivityList(any())).thenReturn(Collections.singletonList(session));
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        com.chengyinhub.business.domain.CmsRegistration paid = new com.chengyinhub.business.domain.CmsRegistration();
        when(registrationService.selectCmsRegistrationList(org.mockito.ArgumentMatchers.argThat(
                q -> q != null && Long.valueOf(2L).equals(q.getOwnerType()) && Long.valueOf(501L).equals(q.getOwnerId()))))
                .thenReturn(Arrays.asList(paid, paid, paid, paid));
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);
        IClubService clubService = mock(IClubService.class);
        Club club = new Club();
        club.setId(73L);
        when(clubService.selectClubById(73L)).thenReturn(club);
        ReflectionTestUtils.setField(controller, "clubService", clubService);

        Club body = new Club();
        body.setId(73L);
        List<?> rows = (List<?>) controller.topics(body).get("data");
        assertEquals(Integer.valueOf(4), ((CmsTopic) rows.get(0)).getSignupCount(), "4 张已付场次票必须计入团卡人数");
    }
}
