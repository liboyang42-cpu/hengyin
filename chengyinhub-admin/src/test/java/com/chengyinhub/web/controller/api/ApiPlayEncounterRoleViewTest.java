package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayEncounterServiceImpl;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * R16-DUO 双人视角只读合同(/api/play/encounter 的 roleView):
 * 单人 / 不在队伍 → two halves(SOLO);队伍里 A 只见 A、B 只见 B、另给对方 label;
 * 超过 2 人按在册次序回卷轮转(1A、2B、3A、4B……);在册名单里查不到本人 → roleMissing
 * 且一个 view 都不给;冻结后改模板不影响进行中的局。
 *
 * <p>判定来源是现有队伍机制(play_team + play_team_member 的在册次序),
 * 本类把「不按角色过滤(两半都给)」当作负控:那两支部队的用例会立刻红。
 */
class ApiPlayEncounterRoleViewTest {

    private static final Long MEMBER = 71L;
    private static final Long PARTNER = 72L;
    private static final Long THIRD = 73L;
    private static final Long FOURTH = 74L;
    private static final Long TOPIC = 81L;
    private static final Long NODE = 102L;
    private static final Long TEMPLATE = 502L;
    private static final Long RUN = 9002L;
    private static final Long TEAM = 660L;
    private static final Long ACTIVITY = 11L;

    private static final String CONFIG = "{\"schemaVersion\":1,\"roleViews\":{"
            + "\"enabled\":true,"
            + "\"roles\":[{\"id\":\"A\",\"label\":\"看实物\"},{\"id\":\"B\",\"label\":\"查档案\"}],"
            + "\"views\":["
            + "{\"roleId\":\"A\",\"title\":\"A 的实物清单\",\"body\":\"先看实物\","
            + "\"items\":[{\"label\":\"铜牌\",\"text\":\"边缘磨亮了\"}]},"
            + "{\"roleId\":\"B\",\"title\":\"B 的档案摘录\",\"body\":\"再查档案\","
            + "\"items\":[{\"label\":\"旧账\",\"text\":\"第三页缺角\"}]}]}}";

    private ApiPlayEncounterController controller;
    private CmsMemberTemplate template;
    private TopicRouteSession session;
    private PlayTeamMapper teamMapper;
    private IShopDayProofService shopDayProofService;

    @BeforeEach
    void setUp() {
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        TopicRouteSessionMapper routeSessionMapper = mock(TopicRouteSessionMapper.class);
        shopDayProofService = mock(IShopDayProofService.class);
        IPlayerNodeProgressService progressService = mock(IPlayerNodeProgressService.class);
        NpcProfileService npcProfileService = mock(NpcProfileService.class);
        teamMapper = mock(PlayTeamMapper.class);

        PlayEncounterServiceImpl service = new PlayEncounterServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(service, "topicRouteSessionMapper", routeSessionMapper);
        ReflectionTestUtils.setField(service, "shopDayProofService", shopDayProofService);
        ReflectionTestUtils.setField(service, "npcProfileService", npcProfileService);
        ReflectionTestUtils.setField(service, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(service, "playTeamMapper", teamMapper);

        controller = spy(new ApiPlayEncounterController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "playEncounterService", service);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setName("码头仓房");
        node.setTemplateId(TEMPLATE);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);

        template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setTitle("对不上的两本账");
        template.setAdvancedConfigJson(CONFIG);
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        session = new TopicRouteSession();
        session.setId(RUN);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setActivityId(0L);
        session.setVersion(1);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[]}");
        when(routeSessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session));
        // Current lookup follows this fixture's exact scope, including activity changes in team cases.
        // The legacy list above still controls missing/ambiguous/foreign omitted-activity gates.
        when(routeSessionMapper.selectCurrentByOwner(any(), any(), any())).thenAnswer(invocation -> {
            Long activity = invocation.getArgument(0);
            Long topic = invocation.getArgument(1);
            Long memberId = invocation.getArgument(2);
            return session != null && java.util.Objects.equals(activity, session.getActivityId())
                    && java.util.Objects.equals(topic, session.getTopicId())
                    && java.util.Objects.equals(memberId, session.getMemberId()) ? session : null;
        });

        when(shopDayProofService.hasArrived(any(), any(), any())).thenReturn(false);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(AjaxResult response) {
        assertEquals(200, response.get("code"));
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> roleView(AjaxResult response) {
        return (Map<String, Object>) data(response).get("roleView");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> views(Map<String, Object> roleView) {
        return (List<Map<String, Object>>) roleView.get("views");
    }

    private PlayTeamMember member(Long memberId) {
        PlayTeamMember member = new PlayTeamMember();
        member.setTeamId(TEAM);
        member.setMemberId(memberId);
        member.setStatus(PlayTeamMember.STATUS_JOINED);
        return member;
    }

    private void givenTeam(Long activityId, int status, PlayTeamMember... members) {
        session.setActivityId(activityId);
        PlayTeam team = new PlayTeam();
        team.setId(TEAM);
        team.setOwnerType(2);
        team.setOwnerId(activityId);
        team.setStatus(status);
        when(teamMapper.selectMyTeams(MEMBER)).thenReturn(Collections.singletonList(team));
        when(teamMapper.selectActiveMembers(TEAM)).thenReturn(Arrays.asList(members));
    }

    /** 自玩局(activityId=0)没有队伍可谈:两半都给。 */
    @Test
    void soloRunGetsBothHalves() {
        Map<String, Object> roleView = roleView(controller.encounter(TOPIC, null, NODE));

        assertEquals("SOLO", roleView.get("role"));
        assertFalse(roleView.containsKey("roleMissing"));
        List<Map<String, Object>> views = views(roleView);
        assertEquals(2, views.size());
        assertEquals("A", views.get(0).get("roleId"));
        assertEquals("A 的实物清单", views.get(0).get("title"));
        assertEquals("B", views.get(1).get("roleId"));
        assertEquals("B 的档案摘录", views.get(1).get("title"));
    }

    /** 场次里的独行玩家(没队伍)同样是完整单人版本。 */
    @Test
    void activityPlayerWithoutTeamGetsBothHalves() {
        session.setActivityId(ACTIVITY);
        when(teamMapper.selectMyTeams(MEMBER)).thenReturn(Collections.emptyList());

        Map<String, Object> roleView = roleView(controller.encounter(TOPIC, null, NODE));

        assertEquals("SOLO", roleView.get("role"));
        assertEquals(2, views(roleView).size());
        assertFalse(roleView.containsKey("otherRoleLabel"));
    }

    /**
     * 在册第一人(队长位)按 A 下发:只剩本人那一半,对方只给 label。
     *
     * <p>负控:把 appendRoleView 里的按角色过滤去掉(队伍里也走 soloRoleView),
     * 本用例立刻红 —— 序列化结果里会出现 B 的档案摘录。
     */
    @Test
    void teamMemberWithRoleASeesOnlyHalfA() throws Exception {
        givenTeam(ACTIVITY, PlayTeam.STATUS_STARTED, member(MEMBER), member(PARTNER));

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        Map<String, Object> roleView = roleView(response);
        assertEquals("A", roleView.get("role"));
        assertEquals("查档案", roleView.get("otherRoleLabel"));
        List<Map<String, Object>> views = views(roleView);
        assertEquals(1, views.size());
        assertEquals("A", views.get(0).get("roleId"));
        assertEquals("先看实物", views.get(0).get("body"));

        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(json.contains("A 的实物清单"));
        assertTrue(json.contains("查档案"), "对方角色只给 label:" + json);
        assertFalse(json.contains("B 的档案摘录"), "队伍里不许把对方那一半下发:" + json);
        assertFalse(json.contains("第三页缺角"), "对方 items 也不许下发:" + json);
        assertFalse(json.contains("再查档案"), "对方 view 的正文不许下发:" + json);
    }

    /** 在册第二人按 B 下发,对方 label 反过来是 A 的。 */
    @Test
    void teamMemberWithRoleBSeesOnlyHalfB() throws Exception {
        givenTeam(ACTIVITY, PlayTeam.STATUS_LOCKED, member(PARTNER), member(MEMBER));

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        Map<String, Object> roleView = roleView(response);
        assertEquals("B", roleView.get("role"));
        assertEquals("看实物", roleView.get("otherRoleLabel"));
        List<Map<String, Object>> views = views(roleView);
        assertEquals(1, views.size());
        assertEquals("B", views.get(0).get("roleId"));

        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(json.contains("B 的档案摘录"));
        assertTrue(json.contains("看实物"), "对方角色只给 label:" + json);
        assertFalse(json.contains("A 的实物清单"), "队伍里不许把对方那一半下发:" + json);
        assertFalse(json.contains("边缘磨亮了"));
        assertFalse(json.contains("先看实物"));
    }

    /** 队员比角色多不再落空:在册次序对角色数取模回卷,第 3 人拿回 A,对方仍是 B。 */
    @Test
    void memberBeyondRoleCountWrapsBackToRoleA() throws Exception {
        givenTeam(ACTIVITY, PlayTeam.STATUS_STARTED, member(PARTNER), member(THIRD), member(MEMBER));

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        Map<String, Object> roleView = roleView(response);
        assertEquals("A", roleView.get("role"));
        assertEquals("查档案", roleView.get("otherRoleLabel"));
        List<Map<String, Object>> views = views(roleView);
        assertEquals(1, views.size());
        assertEquals("A", views.get(0).get("roleId"));
        assertEquals("A 的实物清单", views.get(0).get("title"));

        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(json.contains("A 的实物清单"));
        assertFalse(json.contains("B 的档案摘录"), "第 3 人是 A,不许把 B 那一半下发:" + json);
        assertFalse(json.contains("第三页缺角"));
    }

    /** 第 4 人回到 B:轮转在 3、4 人上继续,A 那一半一个字段都不漏。 */
    @Test
    void fourthMemberWrapsBackToRoleB() throws Exception {
        givenTeam(ACTIVITY, PlayTeam.STATUS_STARTED,
                member(PARTNER), member(THIRD), member(FOURTH), member(MEMBER));

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        Map<String, Object> roleView = roleView(response);
        assertEquals("B", roleView.get("role"));
        assertEquals("看实物", roleView.get("otherRoleLabel"));
        List<Map<String, Object>> views = views(roleView);
        assertEquals(1, views.size());
        assertEquals("B", views.get(0).get("roleId"));
        assertEquals("B 的档案摘录", views.get(0).get("title"));

        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(json.contains("B 的档案摘录"));
        assertFalse(json.contains("A 的实物清单"), "第 4 人是 B,不许把 A 那一半下发:" + json);
        assertFalse(json.contains("边缘磨亮了"));
        assertFalse(json.contains("先看实物"));
    }

    /**
     * 前位队员退队:退队者(status≠1)不再出现在在册名单里,后面的人整体前移一位,
     * 角色跟着轮转前移 —— 同一个本人从第 4 人(B)变成第 3 人(A)。
     */
    @Test
    void memberExitShiftsLaterMembersUpOneRole() {
        givenTeam(ACTIVITY, PlayTeam.STATUS_STARTED,
                member(PARTNER), member(THIRD), member(FOURTH), member(MEMBER));
        assertEquals("B", roleView(controller.encounter(TOPIC, null, NODE)).get("role"));

        when(teamMapper.selectActiveMembers(TEAM))
                .thenReturn(Arrays.asList(member(THIRD), member(FOURTH), member(MEMBER)));

        Map<String, Object> roleView = roleView(controller.encounter(TOPIC, null, NODE));
        assertEquals("A", roleView.get("role"));
        assertEquals("查档案", roleView.get("otherRoleLabel"));
    }

    /** 在册名单里查不到本人(半个队员):同样 roleMissing,不外泄任何一半。 */
    @Test
    void memberMissingFromActiveRosterGetsRoleMissing() {
        givenTeam(ACTIVITY, PlayTeam.STATUS_STARTED, member(PARTNER), member(THIRD));

        Map<String, Object> roleView = roleView(controller.encounter(TOPIC, null, NODE));

        assertEquals(Boolean.TRUE, roleView.get("roleMissing"));
        assertTrue(views(roleView).isEmpty());
    }

    /** 已结束 / 已解散的队伍不算队伍(与 inferTeamId 同一 status ≤ STARTED 过滤),回单人完整版。 */
    @Test
    void endedTeamFallsBackToSolo() {
        givenTeam(ACTIVITY, PlayTeam.STATUS_ENDED, member(MEMBER), member(PARTNER));

        Map<String, Object> roleView = roleView(controller.encounter(TOPIC, null, NODE));

        assertEquals("SOLO", roleView.get("role"));
        assertEquals(2, views(roleView).size());
    }

    /** 别场次的队伍与本局无关:回单人完整版。 */
    @Test
    void teamOfAnotherActivityFallsBackToSolo() {
        session.setActivityId(ACTIVITY);
        PlayTeam team = new PlayTeam();
        team.setId(TEAM);
        team.setOwnerType(2);
        team.setOwnerId(ACTIVITY + 1);
        team.setStatus(PlayTeam.STATUS_STARTED);
        when(teamMapper.selectMyTeams(MEMBER)).thenReturn(Collections.singletonList(team));

        Map<String, Object> roleView = roleView(controller.encounter(TOPIC, null, NODE));

        assertEquals("SOLO", roleView.get("role"));
        assertEquals(2, views(roleView).size());
    }

    /** 老配置(没配 roleViews)不冒出这个键 —— 只追加,不是恒有。 */
    @Test
    void configWithoutRoleViewsKeepsLegacyShape() {
        template.setAdvancedConfigJson("{\"schemaVersion\":1}");

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertFalse(data.containsKey("roleView"));
    }

    /**
     * R19-INT 收口 a:线性主题只配 roleViews 也建局(建局判定见
     * NodeJourneySnapshot#needsJourneyState),到店后本人只拿自己那一半 —— 那半读的是
     * 建局冻结的 roleViews,对方那一半与 items 都不下发。
     */
    @Test
    void arrivedMemberOfRoleViewsOnlyTopicGetsOwnHalf() throws Exception {
        givenTeam(ACTIVITY, PlayTeam.STATUS_STARTED, member(PARTNER), member(MEMBER));
        when(shopDayProofService.hasArrived(any(), any(), any())).thenReturn(true);

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        assertEquals(Boolean.TRUE, data(response).get("arrived"));
        Map<String, Object> roleView = roleView(response);
        assertEquals("B", roleView.get("role"));
        List<Map<String, Object>> views = views(roleView);
        assertEquals(1, views.size());
        assertEquals("B 的档案摘录", views.get(0).get("title"));
        assertEquals("旧账",
                ((List<Map<String, Object>>) views.get(0).get("items")).get(0).get("label"));

        String json = new ObjectMapper().writeValueAsString(response);
        assertFalse(json.contains("A 的实物清单"), "本人是 B,不许把 A 那一半下发:" + json);
        assertFalse(json.contains("边缘磨亮了"));
    }

    /**
     * 开局冻结:建局时 roleViews 被拷进 session 快照的 nodeJourney,开局后改模板不影响这一局。
     *
     * <p>负控:把 NodeJourneySnapshot.JOURNEY_SEGMENTS 里的 roleViews 去掉,本用例立刻红
     * (快照里根本没有这一段,响应读回实时模板「改过的实物」)。
     */
    @Test
    void frozenRoleViewsSurviveTemplateEdit() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode frozenBlock = json.createObjectNode();
        NodeJourneySnapshot.copyJourneySegments(json.readTree(CONFIG), frozenBlock);
        com.fasterxml.jackson.databind.node.ObjectNode snapshot = json.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.putObject("nodeJourney").set(String.valueOf(NODE), frozenBlock);
        session.setConfigSnapshotJson(json.writeValueAsString(snapshot));
        template.setAdvancedConfigJson(CONFIG.replace("A 的实物清单", "改过的实物")
                .replace("B 的档案摘录", "改过的档案"));

        Map<String, Object> roleView = roleView(controller.encounter(TOPIC, null, NODE));

        assertEquals("SOLO", roleView.get("role"));
        List<Map<String, Object>> views = views(roleView);
        assertEquals("A 的实物清单", views.get(0).get("title"));
        assertEquals("B 的档案摘录", views.get(1).get("title"));
        assertEquals("铜牌",
                ((List<Map<String, Object>>) views.get(0).get("items")).get(0).get("label"));
    }
}
