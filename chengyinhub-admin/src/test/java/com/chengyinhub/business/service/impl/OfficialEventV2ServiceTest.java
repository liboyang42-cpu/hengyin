package com.chengyinhub.business.service.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialEventArrivalVerification;
import com.chengyinhub.business.domain.OfficialEventBinding;
import com.chengyinhub.business.domain.OfficialEventFulfillmentRequirement;
import com.chengyinhub.business.domain.OfficialEventMission;
import com.chengyinhub.business.domain.OfficialEventMissionEvidence;
import com.chengyinhub.business.domain.OfficialEventOperatorAuthorization;
import com.chengyinhub.business.domain.OfficialEventParticipation;
import com.chengyinhub.business.domain.OfficialEventParty;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.OfficialEventV2DraftRequest;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.mapper.OfficialEventV2Mapper;
import com.chengyinhub.business.mapper.OfficialBroadcastMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.domain.dto.OfficialEventV2PublishCheck;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.system.service.ISysUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V2 发布门槛：用服务接口验证主办、承接和未验定位能力，避免 UI 假绿。 */
class OfficialEventV2ServiceTest {

    private OfficialEventV2ServiceImpl service(OfficialEventMapper eventMapper, OfficialEventV2Mapper v2Mapper) {
        return service(eventMapper, v2Mapper, mock(IOfficialBroadcastService.class), mock(OfficialBroadcastMapper.class));
    }

    private OfficialEventV2ServiceImpl service(OfficialEventMapper eventMapper, OfficialEventV2Mapper v2Mapper,
                                               IOfficialBroadcastService officialBroadcastService,
                                               OfficialBroadcastMapper officialBroadcastMapper) {
        OfficialEventV2ServiceImpl service = new OfficialEventV2ServiceImpl();
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "v2Mapper", v2Mapper);
        ReflectionTestUtils.setField(service, "signupMapper", mock(EventSignupMapper.class));
        ReflectionTestUtils.setField(service, "roamMapper", mock(RoamMapper.class));
        ReflectionTestUtils.setField(service, "merchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(service, "clubMapper", mock(ClubMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("official_publish_whitelist")).thenReturn("1,2,9,101,202");
        ReflectionTestUtils.setField(service, "configService", configService);
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        when(umsMemberService.selectUmsMemberById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(activeMember());
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ISysUserService sysUserService = mock(ISysUserService.class);
        com.chengyinhub.common.core.domain.entity.SysUser activeUser = new com.chengyinhub.common.core.domain.entity.SysUser();
        activeUser.setStatus("0");
        activeUser.setDelFlag("0");
        when(sysUserService.selectUserById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(activeUser);
        ReflectionTestUtils.setField(service, "sysUserService", sysUserService);
        ReflectionTestUtils.setField(service, "officialBroadcastService", officialBroadcastService);
        ReflectionTestUtils.setField(service, "officialBroadcastMapper", officialBroadcastMapper);
        return service;
    }

    private UmsMember activeMember() {
        UmsMember member = new UmsMember();
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }

    private OfficialEventV2PublishCheck publishCheck(OfficialEventV2ServiceImpl service, Long eventId) {
        return (OfficialEventV2PublishCheck) ReflectionTestUtils.invokeMethod(service, "checkPublishableInternal", eventId);
    }

    private OfficialEvent event(String policy) {
        long now = System.currentTimeMillis();
        OfficialEvent event = new OfficialEvent();
        event.setId(7L);
        event.setContractVersion(2);
        event.setCreatorIdentitySource(OfficialEventParty.IDENTITY_UMS_MEMBER);
        event.setFulfillmentPolicy(policy);
        event.setSignupStart(new Date(now + 60_000));
        event.setActivityStart(new Date(now + 120_000));
        event.setActivityEnd(new Date(now + 180_000));
        event.setSettleTime(new Date(now + 240_000));
        return event;
    }

    private OfficialEventBinding roamBinding() {
        OfficialEventBinding binding = new OfficialEventBinding();
        binding.setId(11L);
        binding.setBindingCode("poi-1");
        binding.setBindingType(OfficialEventBinding.TYPE_ROAM_POI);
        binding.setSourceMode(OfficialEventBinding.SOURCE_REFERENCE);
        binding.setSourceRefId(99L);
        binding.setStatus(OfficialEventBinding.STATUS_DRAFT);
        return binding;
    }

    private OfficialEventMission roamMission() {
        OfficialEventMission mission = new OfficialEventMission();
        mission.setId(12L);
        mission.setBindingId(11L);
        mission.setMissionCode("arrive-poi-1");
        mission.setMissionType(OfficialEventMission.TYPE_ROAM_POI_ARRIVAL);
        mission.setTitle("到达 POI");
        mission.setRuleJson("{}");
        mission.setRequiredCount(1);
        return mission;
    }

    private OfficialEventParty organizer() {
        OfficialEventParty party = new OfficialEventParty();
        party.setPartyType(OfficialEventParty.TYPE_OFFICIAL);
        party.setPartyId(1L);
        party.setIdentitySource(OfficialEventParty.IDENTITY_UMS_MEMBER);
        party.setOrganizerAuthority(1);
        party.setRole(OfficialEventParty.ROLE_ORGANIZER);
        party.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        return party;
    }

    @Test
    void publishCheck_缺少唯一有效主办方必须阻塞发布() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());

        OfficialEventV2PublishCheck check = publishCheck(service(eventMapper, v2Mapper), 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("主办方"));
    }

    @Test
    void publishCheck_REQUIRED_FULFILLMENT缺少精确已接受覆盖必须阻塞发布() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventFulfillmentRequirement requirement = new OfficialEventFulfillmentRequirement();
        requirement.setId(18L);
        requirement.setTargetType(OfficialEventBinding.TYPE_ROAM_POI);
        requirement.setTargetId(99L);
        requirement.setRequiredRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
        requirement.setRequiredCapacity(20);
        requirement.setCritical(1);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("REQUIRED_FULFILLMENT"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.singletonList(requirement));

        OfficialEventV2PublishCheck check = publishCheck(service(eventMapper, v2Mapper), 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("关键履约缺口"));
    }

    @Test
    void publishCheck_引用POI不可用必须阻塞发布() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());

        OfficialEventV2PublishCheck check = publishCheck(service(eventMapper, v2Mapper), 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("引用 POI 不可用"));
    }

    @Test
    void settle_未进入结算窗口必须拒绝提前发奖() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent scheduled = event("SELF_RUN");
        scheduled.setStatus(OfficialEvent.ST_LIVE);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(scheduled);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service(eventMapper, v2Mapper).settle(7L));
    }

    @Test
    void revokeEvidence_已发奖证据撤销必须进入人工处置并留审计() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventMissionEvidence evidence = new OfficialEventMissionEvidence();
        evidence.setId(31L);
        evidence.setEventId(7L);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectEvidenceById(31L)).thenReturn(evidence);
        when(v2Mapper.revokeEvidenceById(31L, "事实被撤销", 9L)).thenReturn(1);

        String err = service(eventMapper, v2Mapper).revokeEvidence(31L, 9L, "事实被撤销");

        assertTrue(err == null);
        verify(eventMapper).selectOfficialEventByIdForUpdate(7L);
        verify(v2Mapper).markRewardManualReviewByEvidenceId(31L, "证据已撤销：事实被撤销");
        verify(v2Mapper).insertAuditLog(anyLong(), org.mockito.ArgumentMatchers.eq("EVIDENCE_REVOKED"), anyLong(),
                org.mockito.ArgumentMatchers.contains("31"), org.mockito.ArgumentMatchers.eq("VALID"), org.mockito.ArgumentMatchers.eq("REVOKED"));
    }

    @Test
    void saveDraft_请求体不能伪造活动状态归属或承接接受态() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent stored = event("SELF_RUN");
        stored.setStatus(OfficialEvent.ST_DRAFT);
        stored.setSenderAccount(101L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(stored);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(stored);

        OfficialEvent incoming = event("SELF_RUN");
        incoming.setTitle("受控草稿");
        incoming.setStatus(OfficialEvent.ST_LIVE);
        incoming.setSenderAccount(999L);
        OfficialEventParty merchant = new OfficialEventParty();
        merchant.setPartyType(OfficialEventParty.TYPE_MERCHANT);
        merchant.setPartyId(88L);
        merchant.setRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
        merchant.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        OfficialEventV2DraftRequest request = new OfficialEventV2DraftRequest();
        request.setEvent(incoming);
        request.setParties(Collections.singletonList(merchant));

        service(eventMapper, v2Mapper).saveDraft(request, 101L);

        ArgumentCaptor<OfficialEvent> eventCaptor = ArgumentCaptor.forClass(OfficialEvent.class);
        verify(eventMapper).updateOfficialEvent(eventCaptor.capture());
        assertEquals(OfficialEvent.ST_DRAFT, eventCaptor.getValue().getStatus());
        assertEquals(101L, eventCaptor.getValue().getSenderAccount());
        ArgumentCaptor<OfficialEventParty> partyCaptor = ArgumentCaptor.forClass(OfficialEventParty.class);
        verify(v2Mapper, org.mockito.Mockito.times(2)).insertParty(partyCaptor.capture());
        OfficialEventParty savedMerchant = partyCaptor.getAllValues().stream()
                .filter(item -> OfficialEventParty.TYPE_MERCHANT.equals(item.getPartyType())).findFirst().orElseThrow(AssertionError::new);
        OfficialEventParty savedOrganizer = partyCaptor.getAllValues().stream()
                .filter(item -> OfficialEventParty.ROLE_ORGANIZER.equals(item.getRole())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(OfficialEventParty.STATUS_INVITED, savedMerchant.getStatus());
        assertEquals(OfficialEventParty.STATUS_ACCEPTED, savedOrganizer.getStatus());
        assertEquals(101L, savedOrganizer.getPartyId());
        assertEquals(OfficialEventParty.IDENTITY_UMS_MEMBER, savedOrganizer.getIdentitySource());
        assertEquals(1, savedOrganizer.getOrganizerAuthority());
    }

    @Test
    void saveDraft_非创建人不能修改草稿() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent stored = event("SELF_RUN");
        stored.setStatus(OfficialEvent.ST_DRAFT);
        stored.setSenderAccount(101L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(stored);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(stored);
        OfficialEventV2DraftRequest request = new OfficialEventV2DraftRequest();
        OfficialEvent incoming = event("SELF_RUN");
        incoming.setTitle("越权草稿");
        request.setEvent(incoming);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service(eventMapper, v2Mapper).saveDraft(request, 202L));
        verify(eventMapper, org.mockito.Mockito.never()).updateOfficialEvent(org.mockito.ArgumentMatchers.any(OfficialEvent.class));
    }

    @Test
    void saveDraft_创建人与主办方分离时只保存受邀主办关系() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent stored = event("SELF_RUN");
        stored.setStatus(OfficialEvent.ST_DRAFT);
        stored.setSenderAccount(101L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(stored);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(stored);
        OfficialEvent incoming = event("SELF_RUN");
        incoming.setTitle("主办方归属校验");
        OfficialEventParty organizer = new OfficialEventParty();
        organizer.setPartyType(OfficialEventParty.TYPE_OFFICIAL);
        organizer.setPartyId(202L);
        organizer.setRole(OfficialEventParty.ROLE_ORGANIZER);
        organizer.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        OfficialEventV2DraftRequest request = new OfficialEventV2DraftRequest();
        request.setEvent(incoming);
        request.setParties(Collections.singletonList(organizer));

        service(eventMapper, v2Mapper).saveDraft(request, 101L);

        ArgumentCaptor<OfficialEventParty> partyCaptor = ArgumentCaptor.forClass(OfficialEventParty.class);
        verify(v2Mapper).insertParty(partyCaptor.capture());
        assertEquals(OfficialEventParty.STATUS_INVITED, partyCaptor.getValue().getStatus());
        assertEquals(0, partyCaptor.getValue().getOrganizerAuthority());
        assertEquals(OfficialEventParty.IDENTITY_UMS_MEMBER, partyCaptor.getValue().getIdentitySource());
    }

    @Test
    void publishCheck_有限时段需求不能被空服务时段假覆盖() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventFulfillmentRequirement requirement = new OfficialEventFulfillmentRequirement();
        requirement.setTargetType(OfficialEventBinding.TYPE_ROAM_POI);
        requirement.setTargetId(99L);
        requirement.setRequiredRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
        requirement.setRequiredCapacity(20);
        requirement.setCritical(1);
        long now = System.currentTimeMillis();
        requirement.setWindowStart(new Date(now + 120_000));
        requirement.setWindowEnd(new Date(now + 180_000));
        OfficialEventParty merchant = new OfficialEventParty();
        merchant.setPartyType(OfficialEventParty.TYPE_MERCHANT);
        merchant.setPartyId(88L);
        merchant.setRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
        merchant.setTargetType(OfficialEventBinding.TYPE_ROAM_POI);
        merchant.setTargetId(99L);
        merchant.setCapacity(20);
        merchant.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("REQUIRED_FULFILLMENT"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Arrays.asList(organizer(), merchant));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.singletonList(requirement));

        OfficialEventV2PublishCheck check = publishCheck(service(eventMapper, v2Mapper), 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("关键履约缺口"));
    }

    @Test
    void runLifecycle_live活动先进入待结算再结算() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        long now = System.currentTimeMillis();
        live.setStatus(OfficialEvent.ST_LIVE);
        live.setActivityStart(new Date(now - 120_000));
        live.setActivityEnd(new Date(now - 60_000));
        live.setSettleTime(new Date(now + 60_000));
        when(eventMapper.selectActiveForFlow()).thenReturn(Collections.singletonList(live));
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(live);
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectActiveParticipationMemberIds(7L)).thenReturn(Collections.emptyList());

        service(eventMapper, v2Mapper).runLifecycle();

        ArgumentCaptor<OfficialEvent> captor = ArgumentCaptor.forClass(OfficialEvent.class);
        verify(eventMapper).updateOfficialEvent(captor.capture());
        assertEquals(OfficialEvent.ST_SETTLING, captor.getValue().getStatus());
        verify(v2Mapper).insertAuditLog(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("SETTLEMENT"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void runLifecycle_暂停活动到期仍必须进入待结算() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent paused = event("SELF_RUN");
        long now = System.currentTimeMillis();
        paused.setStatus(OfficialEvent.ST_LIVE);
        paused.setControlState("PAUSED");
        paused.setActivityStart(new Date(now - 120_000));
        paused.setActivityEnd(new Date(now - 60_000));
        paused.setSettleTime(new Date(now + 60_000));
        when(eventMapper.selectActiveForFlow()).thenReturn(Collections.singletonList(paused));
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(paused);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(paused);
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectActiveParticipationMemberIds(7L)).thenReturn(Collections.emptyList());

        service(eventMapper, v2Mapper).runLifecycle();

        ArgumentCaptor<OfficialEvent> captor = ArgumentCaptor.forClass(OfficialEvent.class);
        verify(eventMapper).updateOfficialEvent(captor.capture());
        assertEquals(OfficialEvent.ST_SETTLING, captor.getValue().getStatus());
    }

    @Test
    void resume_跨开场恢复后主办仍能再次暂停() {
        ResumeFixture fixture = new ResumeFixture();
        assertNull(fixture.service.pause(7L, 1L, "场地检查", "本场参与者", "等待检查", "站内通知"));
        fixture.moveIntoRunningWindow();
        assertNull(fixture.service.resume(7L, 1L, "检查完成"));
        assertEquals(OfficialEvent.ST_LIVE, fixture.event.getStatus());
        assertNull(fixture.service.pause(7L, 1L, "再次检查", "本场参与者", "等待检查", "站内通知"));
        assertEquals("PAUSED", fixture.event.getControlState());
        fixture.assertAcceptedPartiesActive();
    }

    @Test
    void resume_跨开场后可转交且只有新主办可以继续控制() {
        ResumeFixture fixture = new ResumeFixture();
        assertNull(fixture.service.pause(7L, 1L, "场地检查", "本场参与者", "等待检查", "站内通知"));
        fixture.moveIntoRunningWindow();
        assertNull(fixture.service.resume(7L, 1L, "检查完成"));
        assertNull(fixture.service.transferOrganizer(7L, 1L, 62L, "负责人交接"));
        assertEquals(OfficialEventParty.STATUS_WITHDRAWN, fixture.current.getStatus());
        assertEquals(0, fixture.current.getOrganizerAuthority());
        assertEquals(OfficialEventParty.STATUS_ACTIVE, fixture.successor.getStatus());
        assertEquals(1, fixture.successor.getOrganizerAuthority());
        assertEquals("仅本场有效主办方账号可执行该命令",
                fixture.service.pause(7L, 1L, "检查", "本场参与者", "等待", "站内通知"));
        assertNull(fixture.service.pause(7L, 202L, "检查", "本场参与者", "等待", "站内通知"));
        assertNull(fixture.service.resume(7L, 202L, "检查完成"));
        assertEquals(OfficialEventParty.STATUS_WITHDRAWN, fixture.current.getStatus());
        assertEquals(0, fixture.current.getOrganizerAuthority());
    }

    @Test
    void resume_自然开场与跨时恢复得到同样的有效承接关系() {
        ResumeFixture natural = new ResumeFixture();
        ResumeFixture resumed = new ResumeFixture();
        assertNull(resumed.service.pause(7L, 1L, "检查", "本场参与者", "等待", "站内通知"));
        natural.moveIntoRunningWindow();
        resumed.moveIntoRunningWindow();
        natural.service.runLifecycle();
        assertNull(resumed.service.resume(7L, 1L, "检查完成"));
        natural.assertAcceptedPartiesActive();
        resumed.assertAcceptedPartiesActive();
        assertEquals(natural.event.getStatus(), resumed.event.getStatus());
        assertNull(natural.service.pause(7L, 1L, "检查", "本场参与者", "等待", "站内通知"));
        assertNull(natural.service.resume(7L, 1L, "检查完成"));
        natural.assertAcceptedPartiesActive();
    }

    @Test
    void resume_开场前恢复保留ACCEPTED不提前激活() {
        ResumeFixture fixture = new ResumeFixture();
        assertNull(fixture.service.pause(7L, 1L, "检查", "本场参与者", "等待", "站内通知"));
        assertNull(fixture.service.resume(7L, 1L, "检查完成"));
        assertEquals(OfficialEvent.ST_SCHEDULED, fixture.event.getStatus());
        for (OfficialEventParty party : fixture.parties) assertEquals(OfficialEventParty.STATUS_ACCEPTED, party.getStatus());
        verify(fixture.v2Mapper, org.mockito.Mockito.never()).activateAcceptedParties(7L);
    }

    @Test
    void resume_跨结束时间只能进入待结算不能恢复或激活() {
        ResumeFixture fixture = new ResumeFixture();
        assertNull(fixture.service.pause(7L, 1L, "检查", "本场参与者", "等待", "站内通知"));
        fixture.moveIntoRunningWindow();
        fixture.event.setActivityEnd(new Date(System.currentTimeMillis() - 30_000));
        assertEquals("活动已结束，已转入待结算，不能恢复运行", fixture.service.resume(7L, 1L, "检查完成"));
        assertEquals(OfficialEvent.ST_SETTLING, fixture.event.getStatus());
        assertEquals("PAUSED", fixture.event.getControlState());
        verify(fixture.v2Mapper, org.mockito.Mockito.never()).activateAcceptedParties(7L);
    }

    @Test
    void resume_恢复后自然结束仍结算并归档承接关系() {
        ResumeFixture fixture = new ResumeFixture();
        assertNull(fixture.service.pause(7L, 1L, "检查", "本场参与者", "等待", "站内通知"));
        fixture.moveIntoRunningWindow();
        assertNull(fixture.service.resume(7L, 1L, "检查完成"));
        fixture.event.setActivityEnd(new Date(System.currentTimeMillis() - 30_000));
        fixture.event.setSettleTime(new Date(System.currentTimeMillis() - 10_000));
        fixture.service.runLifecycle();
        assertEquals(OfficialEvent.ST_ENDED, fixture.event.getStatus());
        for (OfficialEventParty party : fixture.parties) assertEquals(OfficialEventParty.STATUS_COMPLETED, party.getStatus());
        verify(fixture.v2Mapper).archiveActiveParties(7L);
    }

    /** 有状态 Mapper 夹具：只模拟既有持久化命令，不绕过 Service 权限及发布检查。 */
    private class ResumeFixture {
        final OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        final OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        final OfficialEvent event = event("OPTIONAL_PARTNERS");
        final OfficialEventParty current = organizer();
        final OfficialEventParty successor = organizer();
        final OfficialEventParty cohost = organizer();
        final OfficialEventParty merchant = new OfficialEventParty();
        final java.util.List<OfficialEventParty> parties = Arrays.asList(current, successor, cohost, merchant);
        final OfficialEventV2ServiceImpl service = service(eventMapper, v2Mapper);

        ResumeFixture() {
            event.setStatus(OfficialEvent.ST_SCHEDULED);
            event.setControlState("NORMAL");
            current.setId(61L);
            successor.setId(62L);
            successor.setPartyId(202L);
            successor.setOrganizerAuthority(0);
            cohost.setId(63L);
            cohost.setPartyId(2L);
            cohost.setOrganizerAuthority(0);
            cohost.setRole(OfficialEventParty.ROLE_CO_ORGANIZER);
            merchant.setId(64L);
            merchant.setPartyType(OfficialEventParty.TYPE_MERCHANT);
            merchant.setPartyId(88L);
            merchant.setRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
            merchant.setStatus(OfficialEventParty.STATUS_ACCEPTED);
            when(eventMapper.selectOfficialEventById(7L)).thenReturn(event);
            when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(event);
            when(eventMapper.selectActiveForFlow()).thenReturn(Collections.singletonList(event));
            when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(parties);
            when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(parties);
            when(v2Mapper.activateAcceptedParties(7L)).thenAnswer(call -> {
                int changed = 0;
                for (OfficialEventParty party : parties) {
                    if (OfficialEventParty.STATUS_ACCEPTED.equals(party.getStatus())) {
                        party.setStatus(OfficialEventParty.STATUS_ACTIVE);
                        changed++;
                    }
                }
                return changed;
            });
            when(v2Mapper.updatePartyStatusAndOrganizerAuthority(anyLong(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt(), anyLong(), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
                Long id = call.getArgument(0);
                for (OfficialEventParty party : parties) {
                    if (id.equals(party.getId())) {
                        party.setStatus(call.getArgument(1));
                        party.setOrganizerAuthority(call.getArgument(2));
                        return 1;
                    }
                }
                return 0;
            });
            when(v2Mapper.archiveActiveParties(7L)).thenAnswer(call -> {
                int changed = 0;
                for (OfficialEventParty party : parties) {
                    if (OfficialEventParty.STATUS_ACCEPTED.equals(party.getStatus()) || OfficialEventParty.STATUS_ACTIVE.equals(party.getStatus())) {
                        party.setStatus(OfficialEventParty.STATUS_COMPLETED);
                        changed++;
                    }
                }
                return changed;
            });
            OfficialEventBinding binding = roamBinding();
            binding.setBindingType(OfficialEventBinding.TYPE_THEME);
            OfficialEventMission mission = roamMission();
            mission.setMissionType(OfficialEventMission.TYPE_THEME_VERIFIED_FINISH);
            when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(binding));
            when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(mission));
            com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
            topic.setStatus(1); topic.setUserStatus(1); topic.setDelFlag(0);
            CmsTopicMapper topics = mock(CmsTopicMapper.class);
            when(topics.selectCmsTopicById(99L)).thenReturn(topic);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
            MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
            com.chengyinhub.business.domain.MmsMerchant available = new com.chengyinhub.business.domain.MmsMerchant();
            available.setStatus(1L); available.setDelFlag(0);
            when(merchants.selectMmsMerchantById(88L)).thenReturn(available);
            ReflectionTestUtils.setField(service, "merchantMapper", merchants);
        }

        void moveIntoRunningWindow() {
            long now = System.currentTimeMillis();
            event.setSignupStart(new Date(now - 120_000));
            event.setActivityStart(new Date(now - 60_000));
            event.setActivityEnd(new Date(now + 120_000));
            event.setSettleTime(new Date(now + 180_000));
        }

        void assertAcceptedPartiesActive() {
            for (OfficialEventParty party : parties) assertEquals(OfficialEventParty.STATUS_ACTIVE, party.getStatus());
            assertEquals(1, current.getOrganizerAuthority());
            assertEquals(0, successor.getOrganizerAuthority());
        }
    }

    @Test
    void pause_必须记录影响替代与通知且由有效主办方执行() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        live.setStatus(OfficialEvent.ST_LIVE);
        OfficialEventParty organizer = organizer();
        organizer.setPartyId(1L);
        organizer.setStatus(OfficialEventParty.STATUS_ACTIVE);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(live);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Collections.singletonList(organizer));

        String err = service(eventMapper, v2Mapper).pause(7L, 1L, "场地异常", "已报名 42 人", "改期并保留资格", "站内信和订阅消息");

        assertNull(err);
        ArgumentCaptor<OfficialEvent> captor = ArgumentCaptor.forClass(OfficialEvent.class);
        verify(eventMapper).updateOfficialEvent(captor.capture());
        assertEquals("PAUSED", captor.getValue().getControlState());
        assertEquals("已报名 42 人", captor.getValue().getPauseImpactScope());
        assertEquals("改期并保留资格", captor.getValue().getPauseAlternativePlan());
        assertEquals("站内信和订阅消息", captor.getValue().getPauseNotificationPlan());
        assertEquals("PENDING", captor.getValue().getPauseNotificationStatus());
    }

    @Test
    void pause_进入结算后不能制造暂停假状态() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent settling = event("SELF_RUN");
        settling.setStatus(OfficialEvent.ST_SETTLING);
        OfficialEventParty organizer = organizer();
        organizer.setPartyId(1L);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(settling);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Collections.singletonList(organizer));

        assertEquals("当前生命周期不能暂停", service(eventMapper, v2Mapper).pause(7L, 1L,
                "场地异常", "已报名 42 人", "改期并保留资格", "站内信和订阅消息"));
        verify(eventMapper, org.mockito.Mockito.never()).updateOfficialEvent(org.mockito.ArgumentMatchers.any(OfficialEvent.class));
    }

    @Test
    void respondParty_暂停期间必须冻结外部履约动作() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent paused = event("SELF_RUN");
        paused.setStatus(OfficialEvent.ST_LIVE);
        paused.setControlState("PAUSED");
        OfficialEventParty party = new OfficialEventParty();
        party.setId(33L);
        party.setEventId(7L);
        party.setPartyType(OfficialEventParty.TYPE_MERCHANT);
        party.setStatus(OfficialEventParty.STATUS_INVITED);
        when(v2Mapper.selectPartyById(33L)).thenReturn(party);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(paused);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Collections.singletonList(party));

        assertEquals("活动已暂停，暂停期间不能变更外部履约", service(eventMapper, v2Mapper).respondParty(33L, 88L, "ACCEPT", null));
        verify(v2Mapper, org.mockito.Mockito.never()).updatePartyStatus(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void schedule_非有效主办方账号不能提交排期() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent draft = event("SELF_RUN");
        draft.setStatus(OfficialEvent.ST_DRAFT);
        OfficialEventParty organizer = organizer();
        organizer.setPartyId(1L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(draft);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(draft);
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer));
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Collections.singletonList(organizer));

        assertEquals("仅本场有效主办方账号可执行该命令", service(eventMapper, v2Mapper).schedule(7L, 2L));
    }

    @Test
    void publishCheck_俱乐部不能伪装商家履约覆盖() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventFulfillmentRequirement requirement = new OfficialEventFulfillmentRequirement();
        requirement.setTargetType(OfficialEventBinding.TYPE_ROAM_POI);
        requirement.setTargetId(99L);
        requirement.setRequiredRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
        requirement.setRequiredCapacity(1);
        requirement.setCritical(1);
        long now = System.currentTimeMillis();
        requirement.setWindowStart(new Date(now + 120_000));
        requirement.setWindowEnd(new Date(now + 180_000));
        OfficialEventParty clubPretendingMerchant = new OfficialEventParty();
        clubPretendingMerchant.setPartyType(OfficialEventParty.TYPE_CLUB);
        clubPretendingMerchant.setPartyId(88L);
        clubPretendingMerchant.setRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
        clubPretendingMerchant.setTargetType(OfficialEventBinding.TYPE_ROAM_POI);
        clubPretendingMerchant.setTargetId(99L);
        clubPretendingMerchant.setWindowStart(requirement.getWindowStart());
        clubPretendingMerchant.setWindowEnd(requirement.getWindowEnd());
        clubPretendingMerchant.setCapacity(1);
        clubPretendingMerchant.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("REQUIRED_FULFILLMENT"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Arrays.asList(organizer(), clubPretendingMerchant));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.singletonList(requirement));

        OfficialEventV2PublishCheck check = publishCheck(service(eventMapper, v2Mapper), 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("关键履约缺口"));
    }

    @Test
    void recordThemeCompletion_同一来源事实命中多项任务时拒绝跨任务复用() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent firstEvent = event("SELF_RUN");
        OfficialEvent secondEvent = event("SELF_RUN");
        secondEvent.setId(8L);
        firstEvent.setStatus(OfficialEvent.ST_LIVE);
        secondEvent.setStatus(OfficialEvent.ST_LIVE);
        long now = System.currentTimeMillis();
        firstEvent.setActivityStart(new Date(now - 60_000));
        firstEvent.setActivityEnd(new Date(now + 60_000));
        secondEvent.setActivityStart(new Date(now - 60_000));
        secondEvent.setActivityEnd(new Date(now + 60_000));
        OfficialEventMission firstMission = new OfficialEventMission();
        firstMission.setId(11L);
        firstMission.setEventId(7L);
        firstMission.setMissionCode("theme-one");
        firstMission.setMissionType(OfficialEventMission.TYPE_THEME_VERIFIED_FINISH);
        firstMission.setStatus(OfficialEventMission.STATUS_ACTIVE);
        OfficialEventMission secondMission = new OfficialEventMission();
        secondMission.setId(12L);
        secondMission.setEventId(8L);
        secondMission.setMissionCode("theme-two");
        secondMission.setMissionType(OfficialEventMission.TYPE_THEME_VERIFIED_FINISH);
        secondMission.setStatus(OfficialEventMission.STATUS_ACTIVE);
        OfficialEventParticipation participation = new OfficialEventParticipation();
        participation.setState(OfficialEventParticipation.STATE_ACTIVE);
        when(v2Mapper.selectActiveThemeMissions(99L)).thenReturn(Arrays.asList(firstMission, secondMission));
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(firstEvent);
        when(eventMapper.selectOfficialEventById(8L)).thenReturn(secondEvent);
        when(v2Mapper.selectParticipation(7L, 101L)).thenReturn(participation);
        when(v2Mapper.selectParticipation(8L, 101L)).thenReturn(participation);
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(firstMission));
        when(v2Mapper.selectMissionsByEvent(8L)).thenReturn(Collections.singletonList(secondMission));

        service(eventMapper, v2Mapper).recordThemeCompletion(101L, 99L, "badge:77", new Date());

        verify(v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void verifyArrival_活动结束时钟已过即使状态滞后也拒绝新证据() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        long now = System.currentTimeMillis();
        live.setStatus(OfficialEvent.ST_LIVE);
        live.setActivityStart(new Date(now - 120_000));
        live.setActivityEnd(new Date(now - 60_000));
        OfficialEventMission mission = roamMission();
        mission.setStatus(OfficialEventMission.STATUS_ACTIVE);
        OfficialEventParticipation participation = new OfficialEventParticipation();
        participation.setState(OfficialEventParticipation.STATE_ACTIVE);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(live);
        when(v2Mapper.selectMissionByEventAndCode(7L, "arrive-poi-1")).thenReturn(mission);
        when(v2Mapper.selectParticipation(7L, 101L)).thenReturn(participation);

        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = new com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest();
        request.setMissionCode("arrive-poi-1");
        request.setRequestId("after-end");
        java.util.Map<String, Object> result = service(eventMapper, v2Mapper).verifyArrival(7L, 101L, request);

        assertEquals("MISSION_NOT_AVAILABLE", result.get("reason"));
        verify(v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    /**
     * ★ 改判：完成 = 每个 ACTIVE 任务各有一条有效 evidence，<b>与先后顺序无关</b>。
     *
     * <p>原用例锁的是「前置 evidence 必须严格早于本任务，同刻/乱序不算完成」——那是
     * 「顺序与前置」能力，被裁决表 §1.5 的漫游免费层红线整组作废（免费层里任务的完成
     * 不得取决于另一个任务）。运行期求值、发布期校验、后台输入三处已一并移除，
     * 本文件由 roam-redline-gate 的 R3 持续拦住。
     *
     * <p>下半段是这条断言的牙齿：少一条 evidence 仍然不算完成 —— 否则「取消顺序」
     * 会被写成「谁都算通关」，而这个用例照样绿。
     */
    @Test
    void publicProgress_完成只看每个任务有没有证据_不再看先后顺序() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        live.setStatus(OfficialEvent.ST_LIVE);
        OfficialEventMission first = new OfficialEventMission();
        first.setId(11L);
        first.setMissionCode("first");
        first.setStatus(OfficialEventMission.STATUS_ACTIVE);
        OfficialEventMission second = new OfficialEventMission();
        second.setId(12L);
        second.setMissionCode("second");
        second.setPrerequisiteJson("[\"first\"]");
        second.setStatus(OfficialEventMission.STATUS_ACTIVE);
        OfficialEventMissionEvidence secondEvidence = new OfficialEventMissionEvidence();
        secondEvidence.setMissionId(12L);
        secondEvidence.setStatus(OfficialEventMissionEvidence.STATUS_VALID);
        secondEvidence.setOccurredAt(new Date(1_000L));
        OfficialEventMissionEvidence firstEvidence = new OfficialEventMissionEvidence();
        firstEvidence.setMissionId(11L);
        firstEvidence.setStatus(OfficialEventMissionEvidence.STATUS_VALID);
        firstEvidence.setOccurredAt(new Date(1_000L));
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(live);
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Arrays.asList(first, second));
        when(v2Mapper.selectActiveParticipationMemberIds(7L)).thenReturn(Collections.singletonList(101L));

        when(v2Mapper.selectEvidenceByEventAndMember(7L, 101L)).thenReturn(Arrays.asList(secondEvidence, firstEvidence));

        assertEquals(1, service(eventMapper, v2Mapper).publicProgress(7L).get("done"),
                "两个任务各有有效证据 = 完成；同刻/乱序不再是拦路条件");

        OfficialEventV2Mapper missingOne = mock(OfficialEventV2Mapper.class);
        when(missingOne.selectMissionsByEvent(7L)).thenReturn(Arrays.asList(first, second));
        when(missingOne.selectActiveParticipationMemberIds(7L)).thenReturn(Collections.singletonList(101L));
        when(missingOne.selectEvidenceByEventAndMember(7L, 101L))
                .thenReturn(Collections.singletonList(secondEvidence));
        assertEquals(0, service(eventMapper, missingOne).publicProgress(7L).get("done"),
                "缺一条证据仍然不算完成 —— 取消顺序不等于取消判据");
    }

    @Test
    void publishCheck_旧未知官方身份不能按同号会员盲目复用() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventParty legacyOrganizer = organizer();
        legacyOrganizer.setIdentitySource(OfficialEventParty.IDENTITY_LEGACY_UNRESOLVED);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(legacyOrganizer));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());

        OfficialEventV2PublishCheck check = publishCheck(service(eventMapper, v2Mapper), 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("主办方"));
    }

    @Test
    void canManage_未接受的候任主办不能读取活动发布检查() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventParty invited = organizer();
        invited.setPartyId(202L);
        invited.setOrganizerAuthority(0);
        invited.setStatus(OfficialEventParty.STATUS_INVITED);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(invited));

        assertFalse(service(eventMapper, v2Mapper).canManage(7L, 202L));
    }

    @Test
    void managedRead_非本场有效主办或协办被服务层拒绝() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventParty otherOrganizer = organizer();
        otherOrganizer.setPartyId(202L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(otherOrganizer));

        OfficialEventV2ServiceImpl target = service(eventMapper, v2Mapper);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.draftForOperator(7L, 101L));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.checkPublishableForOperator(7L, 101L));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.activity360ForOperator(7L, 101L));
    }

    @Test
    void R9_24_活动结束后原主办只读回看草稿发布检查与360但不能再操作() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent ended = event("SELF_RUN");
        ended.setStatus(OfficialEvent.ST_ENDED);
        OfficialEventParty completed = organizer();
        completed.setPartyId(101L);
        completed.setStatus(OfficialEventParty.STATUS_COMPLETED);
        OfficialBroadcast broadcast = new OfficialBroadcast();
        broadcast.setEventId(7L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(ended);
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(completed));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());
        OfficialEventV2ServiceImpl target = service(eventMapper, v2Mapper);

        assertTrue(target.canReadControlledEvent(7L, 101L));
        assertEquals(ended, target.draftForOperator(7L, 101L).get("event"));
        target.checkPublishableForOperator(7L, 101L);
        target.activity360ForOperator(7L, 101L);
        // 写权限不随历史身份恢复
        assertFalse(target.canOperateControlledEvent(7L, 101L));
        assertFalse(target.canManage(7L, 101L));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.publishBroadcastForOperator(broadcast, 101L));
        // 负控：非本场参与者、撤出的主办仍拒读
        assertFalse(target.canReadControlledEvent(7L, 202L));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.activity360ForOperator(7L, 202L));
        completed.setPartyId(303L); // 已撤官方白名单的原主办
        assertFalse(target.canReadControlledEvent(7L, 303L));
        completed.setPartyId(101L);
        completed.setStatus(OfficialEventParty.STATUS_WITHDRAWN);
        assertFalse(target.canReadControlledEvent(7L, 101L));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.draftForOperator(7L, 101L));
    }

    @Test
    void draftCreator_无有效主办前仍可读取检查并配置本活动通知() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        IOfficialBroadcastService broadcasts = mock(IOfficialBroadcastService.class);
        OfficialBroadcastMapper broadcastMapper = mock(OfficialBroadcastMapper.class);
        OfficialEvent draft = event("SELF_RUN");
        draft.setStatus(OfficialEvent.ST_DRAFT);
        draft.setSenderAccount(101L);
        OfficialEventParty invitedOrganizer = organizer();
        invitedOrganizer.setPartyId(202L);
        invitedOrganizer.setStatus(OfficialEventParty.STATUS_INVITED);
        OfficialBroadcast broadcast = new OfficialBroadcast();
        broadcast.setEventId(7L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(draft);
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(invitedOrganizer));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());
        when(broadcasts.publish(broadcast, 101L)).thenReturn(broadcast);
        OfficialEventV2ServiceImpl target = service(eventMapper, v2Mapper, broadcasts, broadcastMapper);

        assertFalse(target.canManage(7L, 101L));
        assertTrue(target.canOperateControlledEvent(7L, 101L));
        assertEquals(draft, target.draftForOperator(7L, 101L).get("event"));
        assertFalse(target.checkPublishableForOperator(7L, 101L).isPass());
        target.publishBroadcastForOperator(broadcast, 101L);

        assertEquals(101L, broadcast.getSenderAccount());
        verify(broadcasts).publish(broadcast, 101L);
    }

    @Test
    void activity360_回读本活动通知真源并保留首条预览顺序() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        IOfficialBroadcastService broadcasts = mock(IOfficialBroadcastService.class);
        OfficialBroadcastMapper broadcastMapper = mock(OfficialBroadcastMapper.class);
        OfficialBroadcast first = new OfficialBroadcast();
        first.setId(71L);
        first.setTitle("报名即将开始");
        first.setStatus(OfficialBroadcast.ST_PENDING);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectParticipantRewardRows(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectFulfillmentCoverageRows(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectMission360Rows(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectAuditLogs(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectActiveParticipationMemberIds(7L)).thenReturn(Collections.emptyList());
        when(broadcastMapper.selectByEvent(7L)).thenReturn(Collections.singletonList(first));

        java.util.Map<String, Object> data = service(eventMapper, v2Mapper, broadcasts, broadcastMapper).activity360ForOperator(7L, 1L);

        assertEquals(Collections.singletonList(first), data.get("notifications"));
        verify(broadcastMapper).selectByEvent(7L);
    }

    @Test
    void managedBroadcast_非本场有效主办或协办不能写入或读取通知复盘() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        IOfficialBroadcastService broadcasts = mock(IOfficialBroadcastService.class);
        OfficialBroadcastMapper broadcastMapper = mock(OfficialBroadcastMapper.class);
        OfficialEventParty otherOrganizer = organizer();
        otherOrganizer.setPartyId(202L);
        OfficialBroadcast broadcast = new OfficialBroadcast();
        broadcast.setId(71L);
        broadcast.setEventId(7L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(otherOrganizer));
        when(broadcasts.getById(71L, 101L)).thenReturn(broadcast);
        OfficialEventV2ServiceImpl target = service(eventMapper, v2Mapper, broadcasts, broadcastMapper);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.publishBroadcastForOperator(broadcast, 101L));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.broadcastStatsForOperator(71L, 101L));
        verify(broadcasts, org.mockito.Mockito.never()).publish(broadcast, 101L);
        verify(broadcasts, org.mockito.Mockito.never()).reachStats(71L, 101L);
    }

    @Test
    void R9_24_活动结束后原主办可按只读闸回看历史通知触达统计() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        IOfficialBroadcastService broadcasts = mock(IOfficialBroadcastService.class);
        OfficialBroadcastMapper broadcastMapper = mock(OfficialBroadcastMapper.class);
        OfficialEvent ended = event("SELF_RUN");
        ended.setStatus(OfficialEvent.ST_ENDED);
        OfficialEventParty completed = organizer();
        completed.setPartyId(101L);
        completed.setStatus(OfficialEventParty.STATUS_COMPLETED);
        OfficialBroadcast broadcast = new OfficialBroadcast();
        broadcast.setId(71L);
        broadcast.setEventId(7L);
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("reached", 12);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(ended);
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(completed));
        when(broadcasts.getById(71L, 101L)).thenReturn(broadcast);
        when(broadcasts.getById(71L, 202L)).thenReturn(broadcast);
        when(broadcasts.reachStats(71L, 101L)).thenReturn(stats);
        OfficialEventV2ServiceImpl target = service(eventMapper, v2Mapper, broadcasts, broadcastMapper);

        assertEquals(stats, target.broadcastStatsForOperator(71L, 101L),
                "触达统计是纯读复盘:COMPLETED 原主办按历史参与身份只读放行");

        // 负控:非参与者读闸不放行,拒绝且不读统计
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> target.broadcastStatsForOperator(71L, 202L));
        verify(broadcasts, org.mockito.Mockito.never()).reachStats(71L, 202L);
    }

    @Test
    void managedBroadcast_服务端以当前有效主办身份覆写发布者() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        IOfficialBroadcastService broadcasts = mock(IOfficialBroadcastService.class);
        OfficialBroadcastMapper broadcastMapper = mock(OfficialBroadcastMapper.class);
        OfficialBroadcast broadcast = new OfficialBroadcast();
        broadcast.setEventId(7L);
        broadcast.setSenderAccount(999L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));
        when(broadcasts.publish(broadcast, 1L)).thenReturn(broadcast);

        service(eventMapper, v2Mapper, broadcasts, broadcastMapper).publishBroadcastForOperator(broadcast, 1L);

        assertEquals(1L, broadcast.getSenderAccount());
        verify(broadcasts).publish(broadcast, 1L);
    }

    @Test
    void acceptOrganizerInvitation_无现任主办时受邀会员成为唯一有效主办() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent draft = event("SELF_RUN");
        draft.setStatus(OfficialEvent.ST_DRAFT);
        OfficialEventParty invited = organizer();
        invited.setId(51L);
        invited.setEventId(7L);
        invited.setPartyId(202L);
        invited.setOrganizerAuthority(0);
        invited.setStatus(OfficialEventParty.STATUS_INVITED);
        when(v2Mapper.selectPartyById(51L)).thenReturn(invited);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(draft);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Collections.singletonList(invited));

        assertNull(service(eventMapper, v2Mapper).acceptOrganizerInvitation(51L, 202L, "确认主办职责"));
        verify(eventMapper).selectOfficialEventByIdForUpdate(7L);
        verify(v2Mapper).updatePartyStatusAndOrganizerAuthority(51L, OfficialEventParty.STATUS_ACCEPTED, 1, 202L, "确认主办职责");
    }

    @Test
    void transferOrganizer_已接受继任者与旧主办在同一事务中切换唯一权限() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent draft = event("SELF_RUN");
        draft.setStatus(OfficialEvent.ST_DRAFT);
        OfficialEventParty current = organizer();
        current.setId(61L);
        current.setPartyId(1L);
        OfficialEventParty successor = organizer();
        successor.setId(62L);
        successor.setPartyId(202L);
        successor.setOrganizerAuthority(0);
        successor.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(draft);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Arrays.asList(current, successor));

        assertNull(service(eventMapper, v2Mapper).transferOrganizer(7L, 1L, 62L, "负责人交接"));
        verify(eventMapper).selectOfficialEventByIdForUpdate(7L);
        verify(v2Mapper).updatePartyStatusAndOrganizerAuthority(61L, OfficialEventParty.STATUS_WITHDRAWN, 0, 1L, "负责人交接");
        verify(v2Mapper).updatePartyStatusAndOrganizerAuthority(62L, OfficialEventParty.STATUS_ACCEPTED, 1, 1L, "负责人交接");
    }

    @Test
    void inviteOrganizer_主办变更先锁活动避免越过结算边界() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent draft = event("SELF_RUN");
        draft.setStatus(OfficialEvent.ST_DRAFT);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(draft);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Collections.singletonList(organizer()));

        assertNull(service(eventMapper, v2Mapper).inviteOrganizer(7L, 1L, 202L, "授权继任主办"));

        verify(eventMapper).selectOfficialEventByIdForUpdate(7L);
        verify(v2Mapper).insertParty(org.mockito.ArgumentMatchers.any(OfficialEventParty.class));
    }

    @Test
    void declineOrganizerInvitation_主办变更先锁活动避免越过结算边界() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent draft = event("SELF_RUN");
        draft.setStatus(OfficialEvent.ST_DRAFT);
        OfficialEventParty invited = organizer();
        invited.setId(51L);
        invited.setEventId(7L);
        invited.setPartyId(202L);
        invited.setOrganizerAuthority(0);
        invited.setStatus(OfficialEventParty.STATUS_INVITED);
        when(v2Mapper.selectPartyById(51L)).thenReturn(invited);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(draft);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Collections.singletonList(invited));

        assertNull(service(eventMapper, v2Mapper).declineOrganizerInvitation(51L, 202L, "暂不承担"));

        verify(eventMapper).selectOfficialEventByIdForUpdate(7L);
        verify(v2Mapper).updatePartyStatusAndOrganizerAuthority(51L, OfficialEventParty.STATUS_DECLINED, 0, 202L, "暂不承担");
    }

    @Test
    void invalidateParticipation_既有奖励必须进入人工复核而不能继续发放() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventParticipation participation = new OfficialEventParticipation();
        participation.setState(OfficialEventParticipation.STATE_ACTIVE);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectParticipation(7L, 101L)).thenReturn(participation);

        assertNull(service(eventMapper, v2Mapper).invalidateParticipation(7L, 101L,
                OfficialEventParticipation.STATE_BANNED, 9L, "风控命中"));

        verify(v2Mapper).markRewardManualReviewByEventAndMember(7L, 101L, "参与资格失效（BANNED）：风控命中");
    }

    @Test
    void resolveAuthorizedOfficialMember_仅在后台账号和白名单会员均有效时返回会员身份() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventOperatorAuthorization authorization = new OfficialEventOperatorAuthorization();
        authorization.setSysUserId(55L);
        authorization.setMemberId(101L);
        authorization.setStatus(OfficialEventOperatorAuthorization.STATUS_ACTIVE);
        when(v2Mapper.selectActiveOperatorAuthorization(55L)).thenReturn(authorization);

        assertEquals(101L, service(eventMapper, v2Mapper).resolveAuthorizedOfficialMember(55L));
    }

    @Test
    void saveDraft_主办转交后原创建人不能覆盖现任主办关系() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent stored = event("SELF_RUN");
        stored.setStatus(OfficialEvent.ST_DRAFT);
        stored.setSenderAccount(101L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(stored);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(stored);

        OfficialEventParty formerOrganizer = organizer();
        formerOrganizer.setId(61L);
        formerOrganizer.setPartyId(101L);
        formerOrganizer.setOrganizerAuthority(0);
        formerOrganizer.setStatus(OfficialEventParty.STATUS_WITHDRAWN);
        OfficialEventParty successor = organizer();
        successor.setId(62L);
        successor.setPartyId(202L);
        successor.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Arrays.asList(formerOrganizer, successor));

        OfficialEvent incoming = event("SELF_RUN");
        incoming.setTitle("旧创建人覆盖尝试");
        OfficialEventV2DraftRequest request = new OfficialEventV2DraftRequest();
        request.setEvent(incoming);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service(eventMapper, v2Mapper).saveDraft(request, 101L));
        verify(v2Mapper, org.mockito.Mockito.never()).deletePartiesByEvent(7L);
    }

    @Test
    void publishCheck_ROAM_POI必须使用可供到达适配器核验的引用来源() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEventBinding malformed = roamBinding();
        malformed.setSourceMode(OfficialEventBinding.SOURCE_EVENT_OWNED);
        malformed.setSourceRefId(null);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(malformed));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());

        OfficialEventV2PublishCheck check = publishCheck(service(eventMapper, v2Mapper), 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("来源模式"));
    }

    @Test
    void revokeThemeCompletion_必须把撤证审计写入所有受影响活动360而不是活动零号() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        when(v2Mapper.selectEventIdsByThemeExploreEvidence(301L, 99L)).thenReturn(Arrays.asList(7L, 8L));
        when(v2Mapper.selectEventIdsByThemeExploreEvidenceForUpdate(301L, 99L)).thenReturn(Arrays.asList(7L, 8L));
        OfficialEvent first = event("SELF_RUN");
        OfficialEvent second = event("SELF_RUN");
        second.setId(8L);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(first);
        when(eventMapper.selectOfficialEventByIdForUpdate(8L)).thenReturn(second);
        when(v2Mapper.revokeThemeExploreEvidence(301L, 99L, "主题核销已撤销", 9L)).thenReturn(2);

        service(eventMapper, v2Mapper).revokeThemeCompletion(301L, 99L, null, 9L);

        verify(v2Mapper).insertAuditLog(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("THEME_EVIDENCE_REVOKED"), org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.contains("registration=301"), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        verify(v2Mapper).insertAuditLog(org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq("THEME_EVIDENCE_REVOKED"), org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.contains("registration=301"), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        verify(v2Mapper, org.mockito.Mockito.never()).insertAuditLog(org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq("THEME_EVIDENCE_REVOKED"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(eventMapper).selectOfficialEventByIdForUpdate(7L);
        verify(eventMapper).selectOfficialEventByIdForUpdate(8L);
    }

    @Test
    void signup_报名限额检查必须先锁定活动行以避免并发超额() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent signup = event("SELF_RUN");
        signup.setStatus(OfficialEvent.ST_SIGNUP);
        signup.setSignupLimit(1);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(signup);
        when(v2Mapper.selectParticipation(7L, 101L)).thenReturn(null);
        when(v2Mapper.countActiveParticipations(7L)).thenReturn(1);

        assertEquals("报名人数已满", service(eventMapper, v2Mapper).signup(7L, 101L));
        verify(eventMapper).selectOfficialEventByIdForUpdate(7L);
    }

    /**
     * F21 2026-09-15 产品裁决:官方活动的"报名"是玩家侧免费一键报名(现码事实),
     * 缺口只在**商家承接口径**上标记,不许拦玩家报名。这条负控锁住该裁决。
     */
    @Test
    void signup_按裁决关键缺口不拦截玩家免费报名() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent signup = event("REQUIRED_FULFILLMENT");
        signup.setStatus(OfficialEvent.ST_SIGNUP);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(signup);
        when(v2Mapper.selectParticipation(7L, 101L)).thenReturn(null);
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.singletonList(criticalMerchantRequirement()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));

        assertNull(service(eventMapper, v2Mapper).signup(7L, 101L),
                "关键缺口不得拦截玩家报名 —— 商家侧缺口只做展示标记");
        verify(v2Mapper).insertParticipationIgnore(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPublic_必须承接模式关键承接方缺失时标记招募缺口() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent signup = event("REQUIRED_FULFILLMENT");
        signup.setStatus(OfficialEvent.ST_SIGNUP);
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 7L);
        when(v2Mapper.selectPublicV2Events(null, 101L)).thenReturn(new java.util.ArrayList<>(
                Collections.singletonList(row)));
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(signup);
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.singletonList(criticalMerchantRequirement()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));

        java.util.List<java.util.Map<String, Object>> rows = service(eventMapper, v2Mapper).listPublic(null, 101L);

        assertEquals(Boolean.TRUE, rows.get(0).get("recruitmentBlocked"));
        assertEquals("关键承接方缺失，待补位", rows.get(0).get("recruitmentBlockedReason"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPublic_补位承接方接受后缺口标记消失() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent signup = event("REQUIRED_FULFILLMENT");
        signup.setStatus(OfficialEvent.ST_SIGNUP);
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 7L);
        when(v2Mapper.selectPublicV2Events(null, 101L)).thenReturn(new java.util.ArrayList<>(
                Collections.singletonList(row)));
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(signup);
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.singletonList(criticalMerchantRequirement()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Arrays.asList(organizer(), fulfillmentMerchant()));
        OfficialEventV2ServiceImpl impl = service(eventMapper, v2Mapper);
        stubActiveFulfillmentMerchant(impl);

        java.util.List<java.util.Map<String, Object>> rows = impl.listPublic(null, 101L);

        assertEquals(Boolean.FALSE, rows.get(0).get("recruitmentBlocked"));
        assertNull(rows.get(0).get("recruitmentBlockedReason"));
    }

    /** 负控:关键缺口只在必须承接模式进标记;可选合作/自办不标记。 */
    @Test
    @SuppressWarnings("unchecked")
    void listPublic_可选合作模式关键缺口不进招募标记() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent signup = event("OPTIONAL_PARTNERS");
        signup.setStatus(OfficialEvent.ST_SIGNUP);
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 7L);
        when(v2Mapper.selectPublicV2Events(null, 101L)).thenReturn(new java.util.ArrayList<>(
                Collections.singletonList(row)));
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(signup);

        java.util.List<java.util.Map<String, Object>> rows = service(eventMapper, v2Mapper).listPublic(null, 101L);

        assertEquals(Boolean.FALSE, rows.get(0).get("recruitmentBlocked"));
        verify(v2Mapper, org.mockito.Mockito.never()).selectRequirementsByEvent(7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detail_必须承接模式的关键缺口对商家口径可见() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("REQUIRED_FULFILLMENT");
        live.setStatus(OfficialEvent.ST_LIVE);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(live);
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.countActiveParticipations(7L)).thenReturn(3);
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.singletonList(criticalMerchantRequirement()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));

        java.util.Map<String, Object> detail = service(eventMapper, v2Mapper).detail(7L, null);

        assertEquals(Boolean.TRUE, detail.get("recruitmentBlocked"));
        assertEquals("关键承接方缺失，待补位", detail.get("recruitmentBlockedReason"));
        assertEquals(3, detail.get("participants"));
    }

    private OfficialEventFulfillmentRequirement criticalMerchantRequirement() {
        long now = System.currentTimeMillis();
        OfficialEventFulfillmentRequirement requirement = new OfficialEventFulfillmentRequirement();
        requirement.setId(18L);
        requirement.setTargetType(OfficialEventBinding.TYPE_ROAM_POI);
        requirement.setTargetId(99L);
        requirement.setRequiredRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
        requirement.setRequiredCapacity(20);
        requirement.setCritical(1);
        requirement.setWindowStart(new Date(now + 60_000));
        requirement.setWindowEnd(new Date(now + 180_000));
        return requirement;
    }

    private OfficialEventParty fulfillmentMerchant() {
        long now = System.currentTimeMillis();
        OfficialEventParty merchant = new OfficialEventParty();
        merchant.setPartyType(OfficialEventParty.TYPE_MERCHANT);
        merchant.setPartyId(88L);
        merchant.setRole(OfficialEventParty.ROLE_FULFILLMENT_MERCHANT);
        merchant.setTargetType(OfficialEventBinding.TYPE_ROAM_POI);
        merchant.setTargetId(99L);
        merchant.setCapacity(20);
        merchant.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        merchant.setWindowStart(new Date(now + 30_000));
        merchant.setWindowEnd(new Date(now + 240_000));
        return merchant;
    }

    /** 覆盖判定会核承接主体当前可用性:库里必须有审核通过且启用中的门店。 */
    private void stubActiveFulfillmentMerchant(OfficialEventV2ServiceImpl impl) {
        MmsMerchantMapper merchantMapper =
                (MmsMerchantMapper) ReflectionTestUtils.getField(impl, "merchantMapper");
        com.chengyinhub.business.domain.MmsMerchant merchant = new com.chengyinhub.business.domain.MmsMerchant();
        merchant.setId(88L);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantById(88L)).thenReturn(merchant);
    }

    @Test
    void saveDraft_转交后的现任主办可保存但必须保留已响应主体关系() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent stored = event("SELF_RUN");
        stored.setStatus(OfficialEvent.ST_DRAFT);
        stored.setSenderAccount(101L);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(stored);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(stored);

        OfficialEventParty formerOrganizer = organizer();
        formerOrganizer.setId(61L);
        formerOrganizer.setPartyId(101L);
        formerOrganizer.setOrganizerAuthority(0);
        formerOrganizer.setStatus(OfficialEventParty.STATUS_WITHDRAWN);
        OfficialEventParty successor = organizer();
        successor.setId(62L);
        successor.setPartyId(202L);
        successor.setStatus(OfficialEventParty.STATUS_ACCEPTED);
        when(v2Mapper.selectPartiesByEventForUpdate(7L)).thenReturn(Arrays.asList(formerOrganizer, successor));

        OfficialEvent incoming = event("SELF_RUN");
        incoming.setTitle("继任主办更新草稿");
        OfficialEventV2DraftRequest request = new OfficialEventV2DraftRequest();
        request.setEvent(incoming);

        service(eventMapper, v2Mapper).saveDraft(request, 202L);

        verify(v2Mapper).deleteInvitedPartiesByEvent(7L);
        verify(v2Mapper, org.mockito.Mockito.never()).deletePartiesByEvent(7L);
        verify(v2Mapper, org.mockito.Mockito.never()).insertParty(org.mockito.ArgumentMatchers.any(OfficialEventParty.class));
    }

    @Test
    void saveDraft_ROAM_POI使用活动自有来源必须在写入前被拒绝() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent incoming = event("SELF_RUN");
        incoming.setId(null);
        incoming.setTitle("错误来源草稿");
        OfficialEventBinding malformed = roamBinding();
        malformed.setSourceMode(OfficialEventBinding.SOURCE_EVENT_OWNED);
        OfficialEventV2DraftRequest request = new OfficialEventV2DraftRequest();
        request.setEvent(incoming);
        request.setBindings(Collections.singletonList(malformed));

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service(eventMapper, v2Mapper).saveDraft(request, 101L));
        verify(v2Mapper, org.mockito.Mockito.never()).insertBinding(org.mockito.ArgumentMatchers.any(OfficialEventBinding.class));
    }

    @Test
    void canManage_活动转交后旧创建人不再因senderAccount获得管理读取权限() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent transferred = event("SELF_RUN");
        transferred.setSenderAccount(101L);
        OfficialEventParty successor = organizer();
        successor.setPartyId(202L);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(transferred);
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(successor));

        assertFalse(service(eventMapper, v2Mapper).canManage(7L, 101L));
        assertTrue(service(eventMapper, v2Mapper).canManage(7L, 202L));
    }

    @Test
    void verifyArrival_不同请求不能把已完成任务再次写成ACCEPTED事实() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        long now = System.currentTimeMillis();
        live.setStatus(OfficialEvent.ST_LIVE);
        live.setActivityStart(new Date(now - 60_000));
        live.setActivityEnd(new Date(now + 60_000));
        OfficialEventMission mission = roamMission();
        mission.setStatus(OfficialEventMission.STATUS_ACTIVE);
        OfficialEventMissionEvidence existing = new OfficialEventMissionEvidence();
        existing.setStatus(OfficialEventMissionEvidence.STATUS_VALID);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(live);
        when(v2Mapper.selectMissionByEventAndCode(7L, "arrive-poi-1")).thenReturn(mission);
        when(v2Mapper.selectEvidenceByDedupeKey("7:12:101")).thenReturn(existing);

        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = new com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest();
        request.setMissionCode("arrive-poi-1");
        request.setRequestId("second-request");
        java.util.Map<String, Object> result = service(eventMapper, v2Mapper).verifyArrival(7L, 101L, request);

        assertFalse((Boolean) result.get("accepted"));
        assertEquals("MISSION_ALREADY_COMPLETED", result.get("reason"));
        ArgumentCaptor<OfficialEventArrivalVerification> verificationCaptor = ArgumentCaptor.forClass(OfficialEventArrivalVerification.class);
        verify(v2Mapper).insertArrivalVerificationIgnore(verificationCaptor.capture());
        assertEquals(OfficialEventArrivalVerification.RESULT_REJECTED, verificationCaptor.getValue().getResult());
        verify(v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void verifyArrival_漫游POI未完成Gate0时不得把客户端坐标写成可计奖证据() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        long now = System.currentTimeMillis();
        live.setStatus(OfficialEvent.ST_LIVE);
        live.setActivityStart(new Date(now - 60_000));
        live.setActivityEnd(new Date(now + 60_000));
        OfficialEventMission mission = roamMission();
        mission.setStatus(OfficialEventMission.STATUS_ACTIVE);
        OfficialEventBinding binding = roamBinding();
        binding.setStatus(OfficialEventBinding.STATUS_ACTIVE);
        OfficialEventParticipation participation = new OfficialEventParticipation();
        participation.setState(OfficialEventParticipation.STATE_ACTIVE);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(live);
        when(v2Mapper.selectMissionByEventAndCode(7L, "arrive-poi-1")).thenReturn(mission);
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(mission));
        when(v2Mapper.selectBindingById(11L)).thenReturn(binding);
        when(v2Mapper.selectParticipation(7L, 101L)).thenReturn(participation);
        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = new com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest();
        request.setMissionCode("arrive-poi-1");
        request.setRequestId("gate0-roam-poi");
        request.setLatitude(30.2741D);
        request.setLongitude(120.1551D);
        request.setAccuracyM(10D);

        java.util.Map<String, Object> result = service(eventMapper, v2Mapper).verifyArrival(7L, 101L, request);

        assertFalse((Boolean) result.get("accepted"));
        assertEquals("ROAM_POI_ADAPTER_DISABLED", result.get("reason"));
        verify(v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void verifyArrival_负精度必须被拒绝而不能绕过精度闸门() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        live.setStatus(OfficialEvent.ST_LIVE);
        OfficialEventMission mission = roamMission();
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(live);
        when(v2Mapper.selectMissionByEventAndCode(7L, "arrive-poi-1")).thenReturn(mission);

        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = new com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest();
        request.setMissionCode("arrive-poi-1");
        request.setRequestId("negative-accuracy");
        request.setAccuracyM(-1D);
        java.util.Map<String, Object> result = service(eventMapper, v2Mapper).verifyArrival(7L, 101L, request);

        assertEquals("LOCATION_ACCURACY_INVALID", result.get("reason"));
        verify(v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void verifyArrival_非有限精度必须被拒绝而不能伪装成高精度() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        live.setStatus(OfficialEvent.ST_LIVE);
        OfficialEventMission mission = roamMission();
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(live);
        when(v2Mapper.selectMissionByEventAndCode(7L, "arrive-poi-1")).thenReturn(mission);

        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = new com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest();
        request.setMissionCode("arrive-poi-1");
        request.setRequestId("nan-accuracy");
        request.setAccuracyM(Double.NaN);
        java.util.Map<String, Object> result = service(eventMapper, v2Mapper).verifyArrival(7L, 101L, request);

        assertEquals("LOCATION_ACCURACY_INVALID", result.get("reason"));
        verify(v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void settle_资格与奖励处理必须先锁定活动行() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent settling = event("SELF_RUN");
        settling.setStatus(OfficialEvent.ST_SETTLING);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(settling);
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectActiveParticipationMemberIds(7L)).thenReturn(Collections.emptyList());

        assertEquals(0, service(eventMapper, v2Mapper).settle(7L));
        verify(eventMapper).selectOfficialEventByIdForUpdate(7L);
    }

    @Test
    void settle_历史VALID漫游证据不得再触发奖励投递() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent settling = event("SELF_RUN");
        settling.setStatus(OfficialEvent.ST_SETTLING);
        OfficialEventMission mission = roamMission();
        mission.setEventId(7L);
        mission.setStatus(OfficialEventMission.STATUS_ACTIVE);
        OfficialEventMissionEvidence evidence = new OfficialEventMissionEvidence();
        evidence.setMissionId(mission.getId());
        evidence.setStatus(OfficialEventMissionEvidence.STATUS_VALID);
        evidence.setOccurredAt(new Date());
        com.chengyinhub.business.domain.OfficialEventRewardIssuance issuance =
                new com.chengyinhub.business.domain.OfficialEventRewardIssuance();
        issuance.setId(99L);
        issuance.setStatus(com.chengyinhub.business.domain.OfficialEventRewardIssuance.PENDING_ISSUE);
        OfficialEventV2SettlementHelper helper = mock(OfficialEventV2SettlementHelper.class);
        OfficialEventV2ServiceImpl service = service(eventMapper, v2Mapper);
        ReflectionTestUtils.setField(service, "settlementHelper", helper);
        when(eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(settling);
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(mission));
        when(v2Mapper.selectActiveParticipationMemberIds(7L)).thenReturn(Collections.singletonList(101L));
        when(v2Mapper.selectEvidenceByEventAndMember(7L, 101L)).thenReturn(Collections.singletonList(evidence));
        when(v2Mapper.selectRewardIssuance(7L, 101L, "1")).thenReturn(issuance);

        assertEquals(0, service.settle(7L));

        verify(helper, org.mockito.Mockito.never()).issue(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void detail_停闸漫游任务不得暴露验证入口() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent live = event("SELF_RUN");
        live.setStatus(OfficialEvent.ST_LIVE);
        OfficialEventMission mission = roamMission();
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(live);
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(mission));
        when(v2Mapper.countActiveParticipations(7L)).thenReturn(3);

        java.util.Map<String, Object> detail = service(eventMapper, v2Mapper).detail(7L, null);
        java.util.List<java.util.Map<String, Object>> missions =
                (java.util.List<java.util.Map<String, Object>>) detail.get("missions");

        assertEquals(Boolean.FALSE, missions.get(0).get("canVerifyArrival"));
        assertEquals(3, detail.get("participants"));
    }

    @Test
    void detail_草稿不得通过玩家公开详情接口泄露() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        OfficialEvent draft = event("SELF_RUN");
        draft.setStatus(OfficialEvent.ST_DRAFT);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(draft);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service(eventMapper, v2Mapper).detail(7L, 101L));
    }

    @Test
    void settlementHelper_必须在外层活动锁事务的保存点内看到刚写入的待发奖励() throws Exception {
        Transactional transactional = OfficialEventV2SettlementHelper.class
                .getMethod("issue", com.chengyinhub.business.domain.OfficialEventRewardIssuance.class,
                        Long.class, Integer.class, boolean.class, Long.class, boolean.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.NESTED, transactional.propagation());
    }

    /* ==================== Gate 0：ROAM_POI_ARRIVAL 受控样本 + 负控回读 ====================
     * 开闸依据：正样本被接受且写入 VALID evidence；每条伪造/滥用路径都以明确 riskCode 被拒，
     * 且拒绝路径绝不写 evidence。开关本身双向可证（关=同一正样本被拒）。 */

    private static final String ROAM_ARRIVAL_FLAG = "official_event.roam_poi_arrival_enabled";

    private static class ArrivalFixture {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        RoamMapper roamMapper = mock(RoamMapper.class);
        OfficialEventV2ServiceImpl service;
    }

    /** 合法受控样本：进行中活动 + 本人进行中会话 + POI 半径内 + 合格精度；各负控在此基础上破坏单一条件。 */
    private ArrivalFixture arrivalFixture(boolean flagEnabled) {
        ArrivalFixture f = new ArrivalFixture();
        long now = System.currentTimeMillis();
        OfficialEvent live = event("SELF_RUN");
        live.setStatus(OfficialEvent.ST_LIVE);
        live.setSignupStart(new Date(now - 180_000));
        live.setActivityStart(new Date(now - 120_000));
        live.setActivityEnd(new Date(now + 120_000));
        live.setSettleTime(new Date(now + 240_000));
        when(f.eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(live);
        OfficialEventMission mission = roamMission();
        mission.setStatus(OfficialEventMission.STATUS_ACTIVE);
        when(f.v2Mapper.selectMissionByEventAndCode(7L, "arrive-poi-1")).thenReturn(mission);
        OfficialEventBinding binding = roamBinding();
        binding.setStatus(OfficialEventBinding.STATUS_ACTIVE);
        when(f.v2Mapper.selectBindingById(11L)).thenReturn(binding);
        OfficialEventParticipation participation = new OfficialEventParticipation();
        participation.setState(OfficialEventParticipation.STATE_ACTIVE);
        participation.setSignupId(55L);
        when(f.v2Mapper.selectParticipation(7L, 101L)).thenReturn(participation);
        com.chengyinhub.business.domain.RoamSession session = new com.chengyinhub.business.domain.RoamSession();
        session.setId(66L);
        session.setMemberId(101L);
        session.setStatus(0);
        when(f.roamMapper.selectSessionById(66L)).thenReturn(session);
        com.chengyinhub.business.domain.RoamPoi poi = new com.chengyinhub.business.domain.RoamPoi();
        poi.setId(99L);
        poi.setLat(new java.math.BigDecimal("31.2000"));
        poi.setLng(new java.math.BigDecimal("121.4000"));
        poi.setRadiusM(120);
        when(f.roamMapper.selectPoisByIds(Collections.singletonList(99L))).thenReturn(Collections.singletonList(poi));
        when(f.v2Mapper.insertArrivalVerificationIgnore(org.mockito.ArgumentMatchers.any(OfficialEventArrivalVerification.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(0, OfficialEventArrivalVerification.class).setId(88L);
                    return 1;
                });
        when(f.v2Mapper.insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class))).thenReturn(1);
        when(f.v2Mapper.countValidEvidence(7L, 12L, 101L)).thenReturn(1);
        f.service = service(f.eventMapper, f.v2Mapper);
        ReflectionTestUtils.setField(f.service, "roamMapper", f.roamMapper);
        if (flagEnabled) {
            ISysConfigService configService = (ISysConfigService) ReflectionTestUtils.getField(f.service, "configService");
            when(configService.selectConfigByKey(ROAM_ARRIVAL_FLAG)).thenReturn("true");
        }
        return f;
    }

    private com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest arrivalRequest() {
        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request =
                new com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest();
        request.setMissionCode("arrive-poi-1");
        request.setRequestId("gate0-req");
        request.setSessionId(66L);
        request.setLatitude(31.2001);
        request.setLongitude(121.4001);
        request.setAccuracyM(15D);
        return request;
    }

    private java.util.Map<String, Object> anchorLoc(long occurredAtMillis, String lat, String lng) {
        java.util.Map<String, Object> loc = new java.util.HashMap<>();
        loc.put("foundAt", new Date(occurredAtMillis));
        loc.put("lat", new java.math.BigDecimal(lat));
        loc.put("lng", new java.math.BigDecimal(lng));
        return loc;
    }

    @Test
    void gate0_客户端坐标即使开关开启也不能写入VALID证据() {
        ArrivalFixture f = arrivalFixture(true);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals(Boolean.FALSE, result.get("accepted"));
        assertEquals(Boolean.FALSE, result.get("completed"));
        assertEquals("ROAM_TRUSTED_LOCATION_REQUIRED", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_开关关闭时同一合法正样本必须被拒() {
        ArrivalFixture f = arrivalFixture(false);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("ROAM_POI_ADAPTER_DISABLED", result.get("reason"));
        verify(f.v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void gate0_负控_他人会话不能背书到达() {
        ArrivalFixture f = arrivalFixture(true);
        com.chengyinhub.business.domain.RoamSession foreign = new com.chengyinhub.business.domain.RoamSession();
        foreign.setId(66L);
        foreign.setMemberId(202L);
        foreign.setStatus(0);
        when(f.roamMapper.selectSessionById(66L)).thenReturn(foreign);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("SESSION_NOT_OWNED", result.get("reason"));
        verify(f.v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void gate0_负控_已结算会话不能背书到达() {
        ArrivalFixture f = arrivalFixture(true);
        com.chengyinhub.business.domain.RoamSession settled = new com.chengyinhub.business.domain.RoamSession();
        settled.setId(66L);
        settled.setMemberId(101L);
        settled.setStatus(1);
        when(f.roamMapper.selectSessionById(66L)).thenReturn(settled);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("SESSION_NOT_ACTIVE", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_缺漫游会话参数必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = arrivalRequest();
        request.setSessionId(null);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, request);

        assertEquals("ROAM_CONTEXT_REQUIRED", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_引用POI不可用必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        when(f.roamMapper.selectPoisByIds(Collections.singletonList(99L))).thenReturn(Collections.emptyList());

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("POI_UNAVAILABLE", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_活动不在进行中必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        OfficialEvent signup = event("SELF_RUN");
        signup.setStatus(OfficialEvent.ST_SIGNUP);
        when(f.eventMapper.selectOfficialEventByIdForUpdate(7L)).thenReturn(signup);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("ACTIVITY_NOT_LIVE", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_绑定不可用必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        OfficialEventBinding draft = roamBinding();
        when(f.v2Mapper.selectBindingById(11L)).thenReturn(draft);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("BINDING_UNAVAILABLE", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_绑定缺来源对象必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        OfficialEventBinding noSource = roamBinding();
        noSource.setStatus(OfficialEventBinding.STATUS_ACTIVE);
        noSource.setSourceRefId(null);
        when(f.v2Mapper.selectBindingById(11L)).thenReturn(noSource);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("BINDING_SOURCE_INVALID", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_坐标非法必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = arrivalRequest();
        request.setLatitude(999D);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, request);

        assertEquals("LOCATION_INVALID", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_已撤销证据占位时不能重写() {
        ArrivalFixture f = arrivalFixture(true);
        OfficialEventMissionEvidence revoked = new OfficialEventMissionEvidence();
        revoked.setStatus(OfficialEventMissionEvidence.STATUS_REVOKED);
        when(f.v2Mapper.selectEvidenceByDedupeKey("7:12:101")).thenReturn(revoked);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("MISSION_EVIDENCE_UNAVAILABLE", result.get("reason"));
        assertNoEvidence(f);
    }

    private void assertNoEvidence(ArrivalFixture f) {
        verify(f.v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void gate0_负控_距离超出半径加容差必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = arrivalRequest();
        request.setLatitude(31.2100);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, request);

        assertEquals("DISTANCE_TOO_FAR", result.get("reason"));
        verify(f.v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void gate0_负控_定位精度超过阈值必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest request = arrivalRequest();
        request.setAccuracyM(200D);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, request);

        assertEquals("LOCATION_ACCURACY_TOO_LOW", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_相对上次POI发现瞬移必须拒() {
        ArrivalFixture f = arrivalFixture(true);
        when(f.roamMapper.selectLastPoiFoundLoc(101L)).thenReturn(anchorLoc(System.currentTimeMillis() - 10_000, "30.0000", "120.0000"));

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("MOVE_TOO_FAST", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_负控_相对上次被接受到达瞬移必须拒() {
        // 两次到达验证之间不发现 POI 也不能瞬移：上一锚点是跨任务的最近一次 ACCEPTED 到达。
        ArrivalFixture f = arrivalFixture(true);
        when(f.v2Mapper.selectLastAcceptedArrivalLoc(101L)).thenReturn(anchorLoc(System.currentTimeMillis() - 60_000, "30.0000", "120.0000"));

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("MOVE_TOO_FAST", result.get("reason"));
        verify(f.v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void gate0_负控_同任务半分钟内重复验证必须节流() {
        ArrivalFixture f = arrivalFixture(true);
        OfficialEventArrivalVerification previous = new OfficialEventArrivalVerification();
        previous.setOccurredAt(new Date(System.currentTimeMillis() - 5_000));
        when(f.v2Mapper.selectLatestArrivalVerification(7L, 12L, 101L)).thenReturn(previous);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("ARRIVAL_TOO_FREQUENT", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void gate0_历史ACCEPTED幂等请求在停闸后重放必须转为拒绝() {
        ArrivalFixture f = arrivalFixture(true);
        OfficialEventArrivalVerification existing = new OfficialEventArrivalVerification();
        existing.setResult(OfficialEventArrivalVerification.RESULT_ACCEPTED);
        existing.setRequestId("gate0-req");
        existing.setDistanceM(12);
        when(f.v2Mapper.selectArrivalVerificationByRequest(7L, 12L, 101L, "gate0-req")).thenReturn(existing);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals(Boolean.FALSE, result.get("accepted"));
        assertEquals("ROAM_TRUSTED_LOCATION_REQUIRED", result.get("reason"));
        verify(f.v2Mapper, org.mockito.Mockito.never()).insertArrivalVerificationIgnore(org.mockito.ArgumentMatchers.any(OfficialEventArrivalVerification.class));
        verify(f.v2Mapper, org.mockito.Mockito.never()).insertEvidenceIgnore(org.mockito.ArgumentMatchers.any(OfficialEventMissionEvidence.class));
    }

    @Test
    void gate0_负控_未报名不能写证据() {
        ArrivalFixture f = arrivalFixture(true);
        when(f.v2Mapper.selectParticipation(7L, 101L)).thenReturn(null);

        java.util.Map<String, Object> result = f.service.verifyArrival(7L, 101L, arrivalRequest());

        assertEquals("NOT_SIGNED", result.get("reason"));
        assertNoEvidence(f);
    }

    @Test
    void publishCheck_缺可信位置事实时即使旧开关开启也必须阻塞ROAM_POI_ARRIVAL() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());
        OfficialEventV2ServiceImpl service = service(eventMapper, v2Mapper);
        RoamMapper roamMapper = mock(RoamMapper.class);
        com.chengyinhub.business.domain.RoamPoi poi = new com.chengyinhub.business.domain.RoamPoi();
        poi.setId(99L);
        when(roamMapper.selectPoisByIds(Collections.singletonList(99L))).thenReturn(Collections.singletonList(poi));
        ReflectionTestUtils.setField(service, "roamMapper", roamMapper);
        ISysConfigService configService = (ISysConfigService) ReflectionTestUtils.getField(service, "configService");
        when(configService.selectConfigByKey(ROAM_ARRIVAL_FLAG)).thenReturn("true");

        OfficialEventV2PublishCheck check = publishCheck(service, 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("ROAM_POI_ARRIVAL 尚未完成 Gate 0"));
    }

    @Test
    void publishCheck_开关关闭时ROAM_POI_ARRIVAL仍被Gate0阻塞() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(event("SELF_RUN"));
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.singletonList(roamBinding()));
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.singletonList(roamMission()));
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.singletonList(organizer()));
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());

        OfficialEventV2PublishCheck check = publishCheck(service(eventMapper, v2Mapper), 7L);

        assertFalse(check.isPass());
        assertTrue(check.getBlockers().toString().contains("ROAM_POI_ARRIVAL 尚未完成 Gate 0"));
    }
}
