package com.chengyinhub.business.service.impl;

import java.util.Collections;
import java.util.Date;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.OfficialEventBinding;
import com.chengyinhub.business.domain.OfficialEventMission;
import com.chengyinhub.business.domain.OfficialEventParty;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.OfficialEventV2DraftRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.mapper.OfficialEventV2Mapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.system.service.ISysUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-20 分级审核:官方活动先审后发。
 * V1 轻发布 / V2 提交排期都只落待审(status=0 + PENDING_REVIEW)并建 OFFICIAL_EVENT 审核单;
 * 审核台通过才公开,驳回保持不公开;明确违规文本直接拒;白名单与 V2 结构化发布检查不变。
 */
class OfficialEventPreReviewTest {

    private static final Long ORGANIZER = 1L;

    private OfficialEventMapper eventMapper;
    private OfficialEventV2Mapper v2Mapper;
    private CmsTopicMapper topicMapper;
    private IContentSafetyGate gate;
    private IAuditTaskService auditTaskService;
    private OfficialEventV2ServiceImpl v2;

    @BeforeEach
    void setUp() {
        eventMapper = mock(OfficialEventMapper.class);
        v2Mapper = mock(OfficialEventV2Mapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        gate = mock(IContentSafetyGate.class);
        auditTaskService = mock(IAuditTaskService.class);
        when(gate.decide(any())).thenReturn(SafetyDecision.pass());
        when(auditTaskService.submit(anyString(), anyLong(), anyLong(), anyString(), anyString())).thenReturn(501L);

        v2 = new OfficialEventV2ServiceImpl();
        ReflectionTestUtils.setField(v2, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(v2, "v2Mapper", v2Mapper);
        ReflectionTestUtils.setField(v2, "signupMapper", mock(EventSignupMapper.class));
        ReflectionTestUtils.setField(v2, "roamMapper", mock(RoamMapper.class));
        ReflectionTestUtils.setField(v2, "merchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(v2, "clubMapper", mock(ClubMapper.class));
        ReflectionTestUtils.setField(v2, "cmsTopicMapper", topicMapper);
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("official_publish_whitelist")).thenReturn("1,2");
        ReflectionTestUtils.setField(v2, "configService", configService);
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setWxOpenId("openid-organizer");
        when(members.selectUmsMemberById(anyLong())).thenReturn(member);
        ReflectionTestUtils.setField(v2, "umsMemberService", members);
        ReflectionTestUtils.setField(v2, "sysUserService", mock(ISysUserService.class));
        ReflectionTestUtils.setField(v2, "officialBroadcastService", mock(IOfficialBroadcastService.class));
        ReflectionTestUtils.setField(v2, "officialBroadcastMapper", mock(OfficialBroadcastMapper.class));
        ReflectionTestUtils.setField(v2, "contentSafetyGate", gate);
        ReflectionTestUtils.setField(v2, "auditTaskService", auditTaskService);

        CmsTopic topic = new CmsTopic();
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(99L)).thenReturn(topic);
    }

    // ===================== V2:提交排期 =====================

    @Test
    void v2CleanScheduleStaysDraftPendingWithAuditTaskAndIsNotActivated() {
        OfficialEvent draft = publishableDraft();

        assertNull(v2.schedule(7L, ORGANIZER));

        assertEquals(OfficialEvent.ST_DRAFT, draft.getStatus());
        assertEquals(OfficialEvent.CONTROL_PENDING_REVIEW, draft.getControlState());
        verify(eventMapper).updateOfficialEvent(draft);
        verify(auditTaskService).submit(eq(AuditTask.BIZ_OFFICIAL_EVENT), eq(7L), eq(ORGANIZER), eq("user_publish"), anyString());
        verify(v2Mapper, never()).activateDraftBindings(anyLong());
        verify(v2Mapper, never()).activateDraftMissions(anyLong());
        ArgumentCaptor<PublishSafetyRequest> safety = ArgumentCaptor.forClass(PublishSafetyRequest.class);
        verify(gate).decide(safety.capture());
        assertEquals(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name(), safety.getValue().getReviewMode());
        assertEquals(AuditTask.BIZ_OFFICIAL_EVENT, safety.getValue().getContentType());
        assertEquals("openid-organizer", safety.getValue().getOpenid());
        assertTrue(safety.getValue().getText().contains("城市夜跑"));
    }

    @Test
    void v2PendingEventIsNotPubliclyVisible() {
        OfficialEvent draft = publishableDraft();
        assertNull(v2.schedule(7L, ORGANIZER));
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(draft);

        // 玩家公开详情只认已排期及之后;待审仍是草稿
        assertThrows(ServiceException.class, () -> v2.detail(7L, 5L));
    }

    @Test
    void v2RiskyTextIsRefusedWithoutAuditTaskOrStateChange() {
        OfficialEvent draft = publishableDraft();
        when(gate.decide(any())).thenReturn(SafetyDecision.block("TEXT_RISK"));

        assertEquals("活动内容包含违规信息，请修改后重试", v2.schedule(7L, ORGANIZER));

        assertEquals("NORMAL", draft.getControlState());
        verify(eventMapper, never()).updateOfficialEvent(any());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void v2StructuralPublishCheckStillRunsFirstAndBlocks() {
        publishableDraft();
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.emptyList());

        String err = v2.schedule(7L, ORGANIZER);

        assertTrue(err.startsWith("发布检查未通过："), err);
        assertTrue(err.contains("参与型活动至少需要一项任务"), err);
        verify(gate, never()).decide(any());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void v2NonOrganizerStillRefusedBeforeGate() {
        publishableDraft();

        assertEquals("仅本场有效主办方账号可执行该命令", v2.schedule(7L, 2L));
        verify(gate, never()).decide(any());
    }

    @Test
    void v2PendingEventCannotBeResubmittedOrEdited() {
        OfficialEvent draft = publishableDraft();
        draft.setControlState(OfficialEvent.CONTROL_PENDING_REVIEW);

        assertEquals("活动已提交审核，请等待审核结果", v2.schedule(7L, ORGANIZER));

        OfficialEventV2DraftRequest edit = new OfficialEventV2DraftRequest();
        OfficialEvent body = new OfficialEvent();
        body.setId(7L);
        body.setTitle("改过的标题");
        edit.setEvent(body);
        ServiceException e = assertThrows(ServiceException.class, () -> v2.saveDraft(edit, ORGANIZER));
        assertEquals("活动审核中，暂不能修改", e.getMessage());
    }

    @Test
    void v2ApprovalSchedulesEvent() {
        OfficialEvent draft = publishableDraft();
        draft.setControlState(OfficialEvent.CONTROL_PENDING_REVIEW);

        v2.applyAuditDecision(7L, true, null, ORGANIZER);

        assertEquals(OfficialEvent.ST_SCHEDULED, draft.getStatus());
        assertEquals("NORMAL", draft.getControlState());
        verify(v2Mapper).activateDraftBindings(7L);
        verify(v2Mapper).activateDraftMissions(7L);
        verify(v2Mapper).activateDraftRequirements(7L);
        verify(v2Mapper).insertAuditLog(eq(7L), eq("SCHEDULED"), eq(ORGANIZER), anyString(), any(), any());
    }

    @Test
    void v2RejectionKeepsEventHiddenAsEditableDraft() {
        OfficialEvent draft = publishableDraft();
        draft.setControlState(OfficialEvent.CONTROL_PENDING_REVIEW);

        v2.applyAuditDecision(7L, false, "标题夸大", ORGANIZER);

        assertEquals(OfficialEvent.ST_DRAFT, draft.getStatus());
        assertEquals("NORMAL", draft.getControlState());
        verify(v2Mapper, never()).activateDraftBindings(anyLong());
        verify(v2Mapper).insertAuditLog(eq(7L), eq("REVIEW_REJECTED"), eq(ORGANIZER), anyString(), any(), any());
    }

    @Test
    void v2ApprovalReRunsPublishCheckAndRefusesStaleEvent() {
        OfficialEvent draft = publishableDraft();
        draft.setControlState(OfficialEvent.CONTROL_PENDING_REVIEW);
        draft.setSignupStart(null);

        assertThrows(ServiceException.class, () -> v2.applyAuditDecision(7L, true, null, ORGANIZER));
        assertEquals(OfficialEvent.ST_DRAFT, draft.getStatus());
        verify(v2Mapper, never()).activateDraftBindings(anyLong());
    }

    @Test
    void v2DecisionOnNonPendingEventIsRefused() {
        publishableDraft();

        assertThrows(ServiceException.class, () -> v2.applyAuditDecision(7L, true, null, ORGANIZER));
        verify(v2Mapper, never()).activateDraftBindings(anyLong());
    }

    // ===================== V1:轻发布 =====================

    @Test
    void v1PublishIgnoresClientStatusAndStaysPendingWithAuditTask() {
        OfficialEventServiceImpl v1 = v1Service();
        doAnswer(inv -> {
            ((OfficialEvent) inv.getArgument(0)).setId(42L);
            return 1;
        }).when(eventMapper).insertOfficialEvent(any());
        OfficialEvent body = new OfficialEvent();
        body.setTitle("官方城市漫游");
        body.setStatus(OfficialEvent.ST_LIVE); // 请求体伪造已上架

        OfficialEvent saved = v1.submitForReview(body, ORGANIZER);

        assertEquals(42L, saved.getId());
        assertEquals(OfficialEvent.ST_DRAFT, saved.getStatus());
        assertEquals(OfficialEvent.CONTROL_PENDING_REVIEW, saved.getControlState());
        verify(auditTaskService).submit(eq(AuditTask.BIZ_OFFICIAL_EVENT), eq(42L), eq(ORGANIZER), eq("user_publish"), anyString());

        // 公开详情不出待审活动(公开列表 SQL 只取 status>=1,见 OfficialEventPublicListMapperContractTest)
        when(eventMapper.selectOfficialEventById(42L)).thenReturn(saved);
        assertNull(v1.detail(42L, 5L));
    }

    @Test
    void v1ApprovalPublishesAndRejectionTakesOffline() {
        OfficialEventServiceImpl v1 = v1Service();
        OfficialEvent pending = v1Pending(42L);
        when(eventMapper.selectOfficialEventByIdForUpdate(42L)).thenReturn(pending);

        v1.applyAuditDecision(42L, true, null, ORGANIZER);
        ArgumentCaptor<OfficialEvent> upd = ArgumentCaptor.forClass(OfficialEvent.class);
        verify(eventMapper).updateOfficialEvent(upd.capture());
        assertEquals(OfficialEvent.ST_SIGNUP, upd.getValue().getStatus());
        assertEquals("NORMAL", upd.getValue().getControlState());

        OfficialEvent pending2 = v1Pending(43L);
        when(eventMapper.selectOfficialEventByIdForUpdate(43L)).thenReturn(pending2);
        v1.applyAuditDecision(43L, false, "不合规", ORGANIZER);
        ArgumentCaptor<OfficialEvent> rej = ArgumentCaptor.forClass(OfficialEvent.class);
        verify(eventMapper, org.mockito.Mockito.times(2)).updateOfficialEvent(rej.capture());
        assertEquals(43L, rej.getValue().getId());
        assertEquals(OfficialEvent.ST_OFFLINE, rej.getValue().getStatus());
    }

    @Test
    void v1DecisionOnAlreadyPublishedEventIsRefused() {
        OfficialEventServiceImpl v1 = v1Service();
        OfficialEvent live = v1Pending(44L);
        live.setStatus(OfficialEvent.ST_SIGNUP);
        live.setControlState("NORMAL");
        when(eventMapper.selectOfficialEventByIdForUpdate(44L)).thenReturn(live);

        assertThrows(ServiceException.class, () -> v1.applyAuditDecision(44L, false, "x", ORGANIZER));
        verify(eventMapper, never()).updateOfficialEvent(any());
    }

    @Test
    void v1EntryDispatchesV2DecisionToV2Service() {
        OfficialEventServiceImpl v1 = v1Service();
        IOfficialEventV2Service v2Mock = mock(IOfficialEventV2Service.class);
        ReflectionTestUtils.setField(v1, "officialEventV2Service", v2Mock);
        OfficialEvent v2Event = new OfficialEvent();
        v2Event.setId(7L);
        v2Event.setContractVersion(2);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(v2Event);

        v1.applyAuditDecision(7L, true, null, ORGANIZER);

        verify(v2Mock).applyAuditDecision(7L, true, null, ORGANIZER);
    }

    // ===================== fixtures =====================

    private OfficialEventServiceImpl v1Service() {
        OfficialEventServiceImpl v1 = new OfficialEventServiceImpl();
        ReflectionTestUtils.setField(v1, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(v1, "signupMapper", mock(EventSignupMapper.class));
        ReflectionTestUtils.setField(v1, "officialEventV2Service", v2);
        ReflectionTestUtils.setField(v1, "auditTaskService", auditTaskService);
        return v1;
    }

    private OfficialEvent v1Pending(Long id) {
        OfficialEvent e = new OfficialEvent();
        e.setId(id);
        e.setContractVersion(1);
        e.setTitle("官方城市漫游");
        e.setStatus(OfficialEvent.ST_DRAFT);
        e.setControlState(OfficialEvent.CONTROL_PENDING_REVIEW);
        return e;
    }

    /** 能通过 V2 结构化发布检查的草稿:唯一已接受主办 + 引用在架主题的 THEME_VERIFIED_FINISH 任务。 */
    private OfficialEvent publishableDraft() {
        long now = System.currentTimeMillis();
        OfficialEvent event = new OfficialEvent();
        event.setId(7L);
        event.setContractVersion(2);
        event.setStatus(OfficialEvent.ST_DRAFT);
        event.setControlState("NORMAL");
        event.setTitle("城市夜跑");
        event.setStory("一起跑完五公里");
        event.setSenderAccount(ORGANIZER);
        event.setCreatorIdentitySource(OfficialEventParty.IDENTITY_UMS_MEMBER);
        event.setFulfillmentPolicy("SELF_RUN");
        event.setSignupStart(new Date(now + 60_000));
        event.setActivityStart(new Date(now + 120_000));
        event.setActivityEnd(new Date(now + 180_000));
        event.setSettleTime(new Date(now + 240_000));

        OfficialEventParty organizer = new OfficialEventParty();
        organizer.setPartyType(OfficialEventParty.TYPE_OFFICIAL);
        organizer.setPartyId(ORGANIZER);
        organizer.setIdentitySource(OfficialEventParty.IDENTITY_UMS_MEMBER);
        organizer.setOrganizerAuthority(1);
        organizer.setRole(OfficialEventParty.ROLE_ORGANIZER);
        organizer.setStatus(OfficialEventParty.STATUS_ACCEPTED);

        OfficialEventBinding binding = new OfficialEventBinding();
        binding.setId(11L);
        binding.setBindingCode("theme-1");
        binding.setBindingType(OfficialEventBinding.TYPE_THEME);
        binding.setSourceMode(OfficialEventBinding.SOURCE_REFERENCE);
        binding.setSourceRefId(99L);
        binding.setStatus(OfficialEventBinding.STATUS_DRAFT);

        OfficialEventMission mission = new OfficialEventMission();
        mission.setId(12L);
        mission.setBindingId(11L);
        mission.setMissionCode("finish-theme-1");
        mission.setMissionType(OfficialEventMission.TYPE_THEME_VERIFIED_FINISH);
        mission.setTitle("完成主题");
        mission.setRuleJson("{}");
        mission.setRequiredCount(1);

        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(event);
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer));
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Collections.singletonList(organizer));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(binding));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(mission));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());
        return event;
    }
}
