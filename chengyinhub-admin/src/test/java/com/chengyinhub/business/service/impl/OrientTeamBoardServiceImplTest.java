package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.business.service.support.OrientSwitches;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Doc2 F1d:队伍大厅 / 进度矩阵 / 排名在成员退出后仍正确;安全规则保险不强制。 */
class OrientTeamBoardServiceImplTest {

    private static final long TOPIC = 300L;
    private static final long ACTIVITY = 500L;
    private static final long OWNER = 900L;

    private OrientH2Fixture db;
    private OrientTeamBoardServiceImpl board;
    private OrientSafetyServiceImpl safety;
    private Map<String, String> config;
    private PlayTeamMapper playTeamMapper;

    @BeforeEach
    void setUp() throws Exception {
        db = new OrientH2Fixture("orient_board_" + System.nanoTime());
        config = new HashMap<>();
        config.put(OrientSwitches.KEY_TEAM_BOARD, "1");
        config.put(OrientSwitches.KEY_SAFETY, "true");
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey(anyString())).thenAnswer(inv -> config.get(inv.getArgument(0)));
        OrientSwitches switches = new OrientSwitches();
        ReflectionTestUtils.setField(switches, "configService", configService);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setProductType(ProductType.ORIENTEERING);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(List.of(node(11, 1L), node(12, 1L), node(13, 2L)));

        playTeamMapper = mock(PlayTeamMapper.class);
        board = new OrientTeamBoardServiceImpl();
        ReflectionTestUtils.setField(board, "orientMapper", db.mapper);
        ReflectionTestUtils.setField(board, "playTeamMapper", playTeamMapper);
        ReflectionTestUtils.setField(board, "topicService", topicService);
        ReflectionTestUtils.setField(board, "activityService", activityService);
        ReflectionTestUtils.setField(board, "topicNodeService", nodeService);
        ReflectionTestUtils.setField(board, "switches", switches);

        safety = new OrientSafetyServiceImpl();
        ReflectionTestUtils.setField(safety, "orientMapper", db.mapper);
        ReflectionTestUtils.setField(safety, "topicService", topicService);
        ReflectionTestUtils.setField(safety, "activityService", activityService);
        ReflectionTestUtils.setField(safety, "memberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(safety, "switches", switches);

        db.exec("insert into ums_member values (1,'阿一','a1'),(2,'阿二','a2'),(3,'阿三','a3'),(4,'阿四','a4')");
        // 两支场次队伍 + 一支邀请制队伍(不进大厅)+ 一支已解散(不排名)
        db.exec("insert into play_team(id, owner_type, owner_id, leader_member_id, invite_code, title, join_mode,"
                + " max_members, joined_count, status, create_time) values"
                + " (70, 2, 500, 1, 'SECRET70', '快队', 2, 4, 3, 0, timestamp '2026-10-22 09:00:00'),"
                + " (71, 2, 500, 4, 'SECRET71', '慢队', 2, 4, 1, 0, timestamp '2026-10-22 09:01:00'),"
                + " (72, 2, 500, 4, 'SECRET72', '私密队', 1, 4, 1, 0, timestamp '2026-10-22 09:02:00'),"
                + " (73, 2, 500, 4, 'SECRET73', '散了', 2, 4, 0, 4, timestamp '2026-10-22 09:03:00')");
        db.exec("insert into play_team_member(team_id, member_id, role, status, create_time) values"
                + " (70, 1, 1, 1, timestamp '2026-10-22 09:00:00'),"
                + " (70, 2, 0, 1, timestamp '2026-10-22 09:00:01'),"
                + " (70, 3, 0, 1, timestamp '2026-10-22 09:00:02'),"
                + " (71, 4, 1, 1, timestamp '2026-10-22 09:01:00')");
        // 1、2 号走定向替代完成 11,3 号只走主路径完成 12;4 号(慢队)完成 11
        db.exec("insert into orient_task_completion(member_id, activity_id, topic_id, play_instance_id, task_id,"
                + " completed_via, completed_time) values"
                + " (1, 500, 300, 'default', 11, 'SCAN', timestamp '2026-10-22 10:00:00'),"
                + " (2, 500, 300, 'default', 11, 'PHOTO', timestamp '2026-10-22 10:05:00'),"
                + " (4, 500, 300, 'default', 11, 'QUIZ', timestamp '2026-10-22 10:30:00')");
        db.exec("insert into player_node_progress(member_id, activity_id, topic_id, node_id, status, checkin_time, del_flag)"
                + " values (3, 500, 300, 12, 1, timestamp '2026-10-22 10:10:00', 0),"
                + " (1, 500, 300, 12, 0, timestamp '2026-10-22 10:11:00', 0),"
                + " (2, 500, 300, 12, 1, timestamp '2026-10-22 10:12:00', 1)");
        PlayTeam team70 = new PlayTeam();
        team70.setId(70L);
        team70.setOwnerType(2);
        team70.setOwnerId(ACTIVITY);
        team70.setTitle("快队");
        when(playTeamMapper.selectPlayTeamById(70L)).thenReturn(team70);
        when(playTeamMapper.selectActiveMember(70L, 1L)).thenReturn(new PlayTeamMember());
    }

    @Test
    void lobbyListsOnlyOpenPublicTeamsWithoutInviteCodes() {
        List<Map<String, Object>> lobby = board.lobby(1L, 2, ACTIVITY);
        assertEquals(List.of(71L, 70L), lobby.stream().map(r -> r.get("teamId")).toList());
        assertEquals("阿一", lobby.get(1).get("leaderName"));
        assertFalse(lobby.toString().contains("SECRET"), "大厅不得泄露邀请码");
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailMatrixUnionsBothCompletionSourcesAndOnlyListedTasks() {
        Map<String, Object> detail = board.detail(1L, 70L);
        Map<String, Object> threshold = (Map<String, Object>) detail.get("threshold");
        assertEquals(3, threshold.get("activeCount"));
        assertEquals(3, threshold.get("required"));
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
        assertEquals(List.of(11L, 12L), tasks.stream().map(t -> t.get("taskId")).toList(), "下架节点不进矩阵");
        assertEquals(2, tasks.get(0).get("completedCount"));
        assertEquals(1, tasks.get(1).get("completedCount"), "主路径 status=0 / del_flag=1 的行不算完成");
        List<Map<String, Object>> members = (List<Map<String, Object>>) detail.get("members");
        assertEquals(List.of(11L), members.get(0).get("doneTaskIds"));
        assertEquals(List.of(12L), members.get(2).get("doneTaskIds"));
        assertEquals(0, ((Map<String, Object>) detail.get("summary")).get("teamDoneCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void memberLeavingRecomputesThresholdAndRanking() throws Exception {
        List<Map<String, Object>> before = board.ranking(1L, 2, ACTIVITY);
        assertEquals(List.of(71L, 70L), before.stream().map(r -> r.get("teamId")).toList(),
                "慢队 1 人完成 11 即达阈值;快队 3 人阈值下 0 个任务");
        assertEquals(true, before.get(1).get("mine"));
        assertFalse(before.stream().anyMatch(r -> Long.valueOf(73L).equals(r.get("teamId"))), "已解散不排名");

        db.exec("update play_team_member set status = 2 where team_id = 70 and member_id = 3");
        Map<String, Object> detail = board.detail(1L, 70L);
        Map<String, Object> threshold = (Map<String, Object>) detail.get("threshold");
        assertEquals(2, threshold.get("required"));
        Map<String, Object> summary = (Map<String, Object>) detail.get("summary");
        assertEquals(1, summary.get("teamDoneCount"), "11 由在岗 1、2 号完成");
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) detail.get("tasks");
        assertEquals(0, tasks.get(1).get("completedCount"), "3 号退队后,他完成的 12 不再计入");

        List<Map<String, Object>> after = board.ranking(1L, 2, ACTIVITY);
        assertEquals(List.of(70L, 71L), after.stream().map(r -> r.get("teamId")).toList(),
                "同为 1 个任务,快队 10:05 达阈值早于慢队 10:30");
        assertEquals(1, after.get(0).get("rank"));
    }

    @Test
    void detailIsMembersOnlyAndSwitchDefaultsOff() {
        assertEquals(OrientRejection.TEAM_NOT_MEMBER, assertThrows(OrientRejection.class,
                () -> board.detail(4L, 70L)).getErrorCode());
        config.remove(OrientSwitches.KEY_TEAM_BOARD);
        assertEquals(OrientRejection.DISABLED, assertThrows(OrientRejection.class,
                () -> board.ranking(1L, 2, ACTIVITY)).getErrorCode());
    }

    @Test
    void safetyRuleRejectsMandatoryInsuranceAndIsOwnerOnly() throws Exception {
        assertEquals(OrientRejection.INSURANCE_NOT_MANDATORY, assertThrows(OrientRejection.class,
                () -> safety.saveRule(OWNER, TOPIC, null, null, 0, null, null, 1)).getErrorCode());
        assertEquals(OrientRejection.NOT_OWNER, assertThrows(OrientRejection.class,
                () -> safety.saveRule(1L, TOPIC, null, null, 1, "台风", null, 0)).getErrorCode());
        Map<String, Object> saved = safety.saveRule(OWNER, TOPIC, 1260, 300, 1, "台风橙色预警", null, 0);
        assertEquals(false, saved.get("insuranceRequired"));
        safety.saveRule(OWNER, TOPIC, 1260, 300, 1, "台风橙色预警", null, null);
        assertEquals(1L, db.count("select count(*) from orient_safety_rule where insurance_required = 0"));

        Map<String, Object> check = safety.check(1L, TOPIC, 0L);
        assertEquals(false, check.get("allowed"));
        assertEquals(true, check.get("sosAvailable"));
        assertEquals(false, check.get("insuranceRequired"));
        assertTrue(check.get("blockers").toString().contains("WEATHER_BLOCKED"));
    }

    private static CmsTopicNode node(long id, Long status) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setName("站" + id);
        n.setStatus(status);
        return n;
    }
}
