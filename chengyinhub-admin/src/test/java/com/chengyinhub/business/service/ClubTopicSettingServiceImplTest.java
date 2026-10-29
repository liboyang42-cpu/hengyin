package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.domain.vo.ClubEventCancellationResult;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.ClubTopicSettingServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Figma J4 三个开关的写闸。重点是「少传一个不等于关掉它」。 */
class ClubTopicSettingServiceImplTest {
    private static final Long CLUB_ID = 7L;
    private static final Long TOPIC_ID = 501L;

    private IClubAccessService accessService;
    private ICmsTopicService topicService;
    private ICmsTopicChapterService chapterService;
    private ICmsTopicNodeService nodeService;
    private CmsTopicMapper topicMapper;
    private ICmsActivityService activityService;
    private IClubEventOpsService eventOpsService;
    private ITeamGroupService teamGroupService;
    private ClubTopicSettingServiceImpl service;
    private final Map<Long,CmsActivity> originalActivities=new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        originalActivities.clear();
        accessService = mock(IClubAccessService.class);
        topicService = mock(ICmsTopicService.class);
        chapterService = mock(ICmsTopicChapterService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        topicMapper = mock(CmsTopicMapper.class);
        service = new ClubTopicSettingServiceImpl();
        ReflectionTestUtils.setField(service, "clubAccessService", accessService);
        ReflectionTestUtils.setField(service, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(service, "cmsTopicChapterService", chapterService);
        ReflectionTestUtils.setField(service, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        activityService = mock(ICmsActivityService.class);
        eventOpsService = mock(IClubEventOpsService.class);
        teamGroupService = mock(ITeamGroupService.class);
        ReflectionTestUtils.setField(service, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(service, "clubEventOpsService", eventOpsService);
        ReflectionTestUtils.setField(service, "teamGroupService", teamGroupService);
        var activities=mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        ReflectionTestUtils.setField(service,"cancellationActivityMapper",activities);
        ReflectionTestUtils.setField(service,"cancellationTransactions",CancellationPhaseTestSupport.memoryTransactions());
        CancellationPhaseTestSupport.wireReceiptMocks(activities,topicMapper);
        when(activityService.selectCmsActivityById(anyLong())).thenAnswer(call -> originalActivities.get(call.getArgument(0)));

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setClubId(CLUB_ID);
        topic.setMemberId(1L);
        topic.setName("外滩夜行 · 霓虹拾光");
        topic.setLifecycle(3);
        topic.setUserStatus(1);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(topicService.casUserStatus(anyLong(),anyInt(),anyInt())).thenAnswer(call -> {
            if(!topic.getUserStatus().equals(call.getArgument(1))) return 0;
            topic.setUserStatus(call.getArgument(2));return 1;
        });
        when(chapterService.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.<CmsTopicChapter>emptyList());
        when(nodeService.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.<CmsTopicNode>emptyList());
        when(topicMapper.updateClubDisplaySettings(anyLong(), anyLong(), anyInt(), anyInt(), anyInt()))
                .thenReturn(1);
    }

    private void actingAs(String roleCode) {
        when(accessService.resolve(1L, CLUB_ID, null)).thenReturn(new ClubAccessContext(
                1L, CLUB_ID, 1L, null, new LinkedHashSet<>(Collections.singletonList(roleCode))));
    }

    @Test
    void 存量主题读到null时按开着解释而不是关着() {
        // 加这三列之前不存在这个闸,等价于一直开着;读成 false 会让存量主题突然从商家列表消失
        actingAs(ClubRoleCode.CLUB_OWNER);
        Map<String, Object> detail = service.detail(1L, CLUB_ID, TOPIC_ID);
        assertEquals(Boolean.TRUE, detail.get("coopOpen"));
        assertEquals(Boolean.FALSE, detail.get("pinned"));
        assertEquals(Boolean.FALSE, detail.get("memberOnly"));
        assertEquals("已开卖", detail.get("lifecycleText"));
    }

    @Test
    void 少传一个开关就拒绝而不是把它当false关掉() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        assertThrows(ServiceException.class,
                () -> service.save(1L, CLUB_ID, TOPIC_ID, Boolean.TRUE, null, Boolean.FALSE));
        verify(topicMapper, never()).updateClubDisplaySettings(anyLong(), anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void 只读岗位不能改开关() {
        actingAs(ClubRoleCode.CLUB_OPERATOR); // 有 activity:read,没有 activity:manage
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.save(1L, CLUB_ID, TOPIC_ID, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE));
        assertEquals(403, e.getCode());
        verify(topicMapper, never()).updateClubDisplaySettings(anyLong(), anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void 写入时把clubId一并带进where() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        service.save(1L, CLUB_ID, TOPIC_ID, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE);
        // clubId 进 where 是防越权的那一层,不能只靠上层记得校验
        verify(topicMapper).updateClubDisplaySettings(eq(TOPIC_ID), eq(CLUB_ID), eq(0), eq(1), eq(1));
    }

    @Test
    void 别的俱乐部的主题一律403() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        CmsTopic foreign = new CmsTopic();
        foreign.setId(TOPIC_ID);
        foreign.setClubId(CLUB_ID + 1);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(foreign);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.detail(1L, CLUB_ID, TOPIC_ID));
        assertEquals(403, e.getCode());
    }

    // ---------- 结束主题 = 停售 + 逐场全额退款(稿 J5 164:118 正文承诺的就是这件事) ----------

    private CmsActivity session(Long id, String name) {
        CmsActivity a = new CmsActivity();
        a.setId(id);
        a.setName(name);
        a.setTopicId(TOPIC_ID);
        a.setClubId(CLUB_ID);
        originalActivities.put(id,a);
        return a;
    }

    private ClubEventCancellationResult refunded(int orders, int manual) {
        ClubEventCancellationResult r = new ClubEventCancellationResult();
        r.setRefundedOrders(orders);
        r.setManualOrders(manual);
        r.setClosedPendingOrders(0);
        return r;
    }

    @Test
    void 结束主题会先停售再把每一场都取消并把退款笔数加起来() throws Exception {
        actingAs(ClubRoleCode.CLUB_OWNER);
        CmsTopic onSale = topicService.selectCmsTopicById(TOPIC_ID);
        onSale.setUserStatus(1);
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Arrays.asList(session(11L, "8月31日场"), session(12L, "9月7日场")));
        when(eventOpsService.cancelOccurrence(anyLong(), any(), any(ClubEventCancellationRequest.class)))
                .thenReturn(refunded(3, 1), refunded(2, 0));

        Map<String, Object> out = service.endTopic(1L, "林岸", CLUB_ID, TOPIC_ID, null);

        // 停售必须先发生:还在卖的时候退款,退到一半又有人买票
        verify(topicService).casUserStatus(TOPIC_ID, 1, 0);
        assertEquals(2, out.get("cancelledSessions"));
        assertEquals(5, out.get("refundedOrders"));
        // 含已核销票的整单退不掉,这个数要原样报出去,吞掉等于钱在系统里蒸发
        assertEquals(1, out.get("manualOrders"));
        assertEquals(Collections.emptyList(), out.get("failedSessions"));
    }

    @Test
    void 不是场次系列物化出来的活动改走通用取消而不是跳过() throws Exception {
        actingAs(ClubRoleCode.CLUB_OWNER);
        CmsActivity standalone=session(21L,"单场活动");standalone.setClubId(null);
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenAnswer(call -> call.getArgument(0,CmsActivity.class).getClubId()==null
                        ?Collections.singletonList(standalone):Collections.emptyList());
        when(eventOpsService.cancelOccurrence(anyLong(), any(), any(ClubEventCancellationRequest.class)))
                .thenThrow(new ServiceException("场次不存在或不属于当前俱乐部"));
        ActivityCancelResult fallback = new ActivityCancelResult(4, 0);
        when(teamGroupService.cancelActivityByClubOperator(eq(21L), anyLong(), anyLong(), any()))
                .thenReturn(fallback);

        Map<String, Object> out = service.endTopic(1L, "林岸", CLUB_ID, TOPIC_ID, "场地临时关闭");

        // 跳过等于这一场的钱没人退 —— 必须落到第二条退款链路上
        verify(teamGroupService).cancelActivityByClubOperator(eq(21L), anyLong(), anyLong(), eq("场地临时关闭"));
        assertEquals(1, out.get("cancelledSessions"));
        assertEquals(4, out.get("refundedOrders"));
    }

    @Test
    void 一场退失败不能把已经退掉的那几场吞掉要指名报出来() throws Exception {
        actingAs(ClubRoleCode.CLUB_OWNER);
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Arrays.asList(session(31L, "能退的那场"), session(32L, "退不掉的那场")));
        when(eventOpsService.cancelOccurrence(anyLong(), any(), any(ClubEventCancellationRequest.class)))
                .thenReturn(refunded(2, 0))
                .thenThrow(new ServiceException("场次已失效，无法取消"));
        when(teamGroupService.cancelActivityByClubOperator(eq(32L), anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("活动已取消,请勿重复操作"));

        Map<String, Object> out = service.endTopic(1L, "林岸", CLUB_ID, TOPIC_ID, null);

        assertEquals(1, out.get("cancelledSessions"));
        assertEquals(2, out.get("refundedOrders"));
        List<?> failed = (List<?>) out.get("failedSessions");
        assertEquals(1, failed.size());
        assertEquals(true, String.valueOf(failed.get(0)).startsWith("退不掉的那场："));
    }

    @Test
    void 自由探索默认场次未填俱乐部字段也必须进入既有取消链() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        CmsActivity shell = session(41L, "自由探索默认场次");
        shell.setClubId(null);
        shell.setMemberId(1L);
        when(activityService.selectCmsActivityList(any(CmsActivity.class))).thenAnswer(invocation -> {
            CmsActivity probe = invocation.getArgument(0);
            return probe.getClubId() == null ? Collections.singletonList(shell) : Collections.emptyList();
        });
        when(eventOpsService.cancelOccurrence(anyLong(), any(), any(ClubEventCancellationRequest.class)))
                .thenThrow(new ServiceException("不是俱乐部系列场次"));
        when(teamGroupService.cancelActivityByClubOperator(41L, 1L, 1L, "测试结束"))
                .thenReturn(new ActivityCancelResult(1, 0));

        Map<String, Object> out = service.endTopic(1L, "林岸", CLUB_ID, TOPIC_ID, "测试结束");

        verify(teamGroupService).cancelActivityByClubOperator(41L, 1L, 1L, "测试结束");
        assertEquals(1, out.get("sessionTotal"));
        assertEquals(1, out.get("cancelledSessions"));
        assertEquals(1, out.get("refundedOrders"));
        assertEquals(Collections.emptyList(), out.get("failedSessions"));
    }

    @Test
    void 补查默认场次不取消其他俱乐部或其他主题的活动() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        CmsActivity otherClub = session(42L, "其他俱乐部");
        otherClub.setClubId(99L);
        CmsActivity otherTopic = session(43L, "其他主题");
        otherTopic.setClubId(null);
        otherTopic.setTopicId(999L);
        when(activityService.selectCmsActivityList(any(CmsActivity.class))).thenAnswer(invocation -> {
            CmsActivity probe = invocation.getArgument(0);
            return probe.getClubId() == null ? Arrays.asList(otherClub, otherTopic) : Collections.emptyList();
        });

        Map<String, Object> out = service.endTopic(1L, "林岸", CLUB_ID, TOPIC_ID, "测试结束");

        assertEquals(0, out.get("sessionTotal"));
        verify(eventOpsService, never()).cancelOccurrence(anyLong(), any(), any());
        verify(teamGroupService, never()).cancelActivityByClubOperator(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void 默认场次仍以当前主理人走活动归属闸且拒绝时必须报失败() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        CmsActivity shell = session(44L, "归属不一致的默认场次");
        shell.setClubId(null);
        shell.setMemberId(99L);
        when(activityService.selectCmsActivityList(any(CmsActivity.class))).thenAnswer(invocation -> {
            CmsActivity probe = invocation.getArgument(0);
            return probe.getClubId() == null ? Collections.singletonList(shell) : Collections.emptyList();
        });
        when(eventOpsService.cancelOccurrence(anyLong(), any(), any(ClubEventCancellationRequest.class)))
                .thenThrow(new ServiceException("不是俱乐部系列场次"));
        when(teamGroupService.cancelActivityByClubOperator(44L, 1L, 1L, "测试结束"))
                .thenThrow(new ServiceException("无权取消该活动", 403));

        Map<String, Object> out = service.endTopic(1L, "林岸", CLUB_ID, TOPIC_ID, "测试结束");

        verify(teamGroupService).cancelActivityByClubOperator(44L, 1L, 1L, "测试结束");
        assertEquals(1, out.get("sessionTotal"));
        assertEquals(0, out.get("cancelledSessions"));
        assertEquals(0, out.get("refundedOrders"));
        assertEquals(Collections.singletonList("归属不一致的默认场次：无权取消该活动"), out.get("failedSessions"));
    }

    @Test
    void lostResponseReplaysOriginalMultiSessionCountsEvenAfterCurrentListChanges() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Arrays.asList(session(61L,"第一场"),session(62L,"第二场")));
        when(eventOpsService.cancelOccurrence(anyLong(),any(),any(ClubEventCancellationRequest.class)))
                .thenReturn(refunded(3,1),refunded(2,0));
        Map<String,Object> original=service.endTopic(1L,"owner",CLUB_ID,TOPIC_ID,"原原因");
        assertEquals(5,original.get("refundedOrders"));assertEquals(1,original.get("manualOrders"));
        when(activityService.selectCmsActivityList(any(CmsActivity.class))).thenReturn(Collections.emptyList());
        org.mockito.Mockito.clearInvocations(eventOpsService,teamGroupService,topicService);
        assertEquals(original,service.endTopic(1L,"owner",CLUB_ID,TOPIC_ID,"原原因"));
        verify(eventOpsService,never()).cancelOccurrence(anyLong(),any(),any());
        verify(teamGroupService,never()).cancelActivityByClubOperator(anyLong(),anyLong(),anyLong(),any());
        verify(topicService,never()).casUserStatus(anyLong(),anyInt(),anyInt());
        assertThrows(ServiceException.class,() -> service.endTopic(1L,"owner",CLUB_ID,TOPIC_ID,"不同原因"));
        actingAs(ClubRoleCode.CLUB_OPERATOR);
        assertThrows(ServiceException.class,() -> service.endTopic(1L,"owner",CLUB_ID,TOPIC_ID,"原原因"));
    }

    @Test
    void partialFailureKeepsOriginalPlanAndNeverFallsBackToAnotherCancellationIdentity() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Arrays.asList(session(71L,"已完成"),session(72L,"待确认")));
        when(eventOpsService.cancelOccurrence(anyLong(),any(),any(ClubEventCancellationRequest.class)))
                .thenAnswer(call -> {
                    var request=call.getArgument(2,ClubEventCancellationRequest.class);
                    if(request.getActivityId().equals(72L)) throw new ServiceException("原渠道UNKNOWN",409);
                    return refunded(4,1);
                });
        Map<String,Object> partial=service.endTopic(1L,"owner",CLUB_ID,TOPIC_ID,"原原因");
        assertEquals("PENDING",partial.get("cancellationState"));assertEquals(1,partial.get("cancelledSessions"));
        assertEquals(4,partial.get("refundedOrders"));
        verify(teamGroupService,never()).cancelActivityByClubOperator(anyLong(),anyLong(),anyLong(),any());
        // The first child now returns its original receipt; the second completes its SAME original request.
        when(activityService.selectCmsActivityList(any(CmsActivity.class))).thenReturn(Collections.emptyList());
        when(eventOpsService.cancelOccurrence(anyLong(),any(),any(ClubEventCancellationRequest.class)))
                .thenAnswer(call -> refunded(call.getArgument(2,ClubEventCancellationRequest.class).getActivityId().equals(71L)?4:2,1));
        Map<String,Object> completed=service.endTopic(1L,"owner",CLUB_ID,TOPIC_ID,"原原因");
        assertEquals(2,completed.get("sessionTotal"));assertEquals(2,completed.get("cancelledSessions"));
        assertEquals(6,completed.get("refundedOrders"));assertEquals(2,completed.get("manualOrders"));
        assertEquals(Collections.emptyList(),completed.get("failedSessions"));
        org.mockito.ArgumentCaptor<ClubEventCancellationRequest> requests=org.mockito.ArgumentCaptor.forClass(ClubEventCancellationRequest.class);
        verify(eventOpsService,org.mockito.Mockito.times(4)).cancelOccurrence(eq(1L),any(),requests.capture());
        assertEquals(Arrays.asList("topic-end-501-71","topic-end-501-72","topic-end-501-71","topic-end-501-72"),
                requests.getAllValues().stream().map(ClubEventCancellationRequest::getRequestId).toList());
    }

    @Test
    void 两次查询返回同一默认场次时只取消一次() {
        actingAs(ClubRoleCode.CLUB_OWNER);
        CmsActivity shell = session(45L, "默认场次");
        shell.setClubId(null);
        shell.setMemberId(1L);
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.singletonList(shell));
        when(eventOpsService.cancelOccurrence(anyLong(), any(), any(ClubEventCancellationRequest.class)))
                .thenThrow(new ServiceException("不是俱乐部系列场次"));
        when(teamGroupService.cancelActivityByClubOperator(45L, 1L, 1L, "测试结束"))
                .thenReturn(new ActivityCancelResult(1, 0));

        Map<String, Object> out = service.endTopic(1L, "林岸", CLUB_ID, TOPIC_ID, "测试结束");

        verify(teamGroupService).cancelActivityByClubOperator(45L, 1L, 1L, "测试结束");
        assertEquals(1, out.get("sessionTotal"));
        assertEquals(1, out.get("refundedOrders"));
    }

    @Test
    void 没有管理权限的岗位结束不了主题() {
        actingAs(ClubRoleCode.CLUB_MEMBER);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.endTopic(1L, "小满", CLUB_ID, TOPIC_ID, null));
        assertEquals(403, e.getCode());
        verify(topicService, never()).casUserStatus(anyLong(), anyInt(), anyInt());
    }
}
