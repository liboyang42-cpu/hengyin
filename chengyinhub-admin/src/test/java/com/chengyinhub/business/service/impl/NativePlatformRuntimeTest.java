package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual action adapter, ownership gate, CAS and durable-receipt shape. */
class NativePlatformRuntimeTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Instant now=Instant.parse("2026-10-02T10:00:00Z");
    private AdvancedGameRuntimeServiceImpl service;
    private GameRuntimeSessionMapper sessions;
    private PlayAttemptLogMapper receipts;
    private GameRuntimeSession session;
    private NativeStepProtocol protocol;
    private ITopicRouteRuntimeService journey;
    private CmsRegistrationMapper registrations;
    private CmsTopicNodeMapper nodes;
    private CmsMemberTemplateMapper templates;
    @BeforeEach void setup() {
        service=new AdvancedGameRuntimeServiceImpl();sessions=mock(GameRuntimeSessionMapper.class);receipts=mock(PlayAttemptLogMapper.class);
        journey=mock(ITopicRouteRuntimeService.class);registrations=mock(CmsRegistrationMapper.class);
        nodes=mock(CmsTopicNodeMapper.class);templates=mock(CmsMemberTemplateMapper.class);
        ReflectionTestUtils.setField(service,"sessionMapper",sessions);ReflectionTestUtils.setField(service,"attemptMapper",receipts);
        ReflectionTestUtils.setField(service,"nodeMapper",nodes);ReflectionTestUtils.setField(service,"templateMapper",templates);
        ReflectionTestUtils.setField(service,"registrationMapper",registrations);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",journey);
        ReflectionTestUtils.setField(service,"clock",(AdvancedGameClock)()->Date.from(now));
        protocol=new NativeStepProtocol();NativeStepAssertionVerifier verifier=mock(NativeStepAssertionVerifier.class);
        ReflectionTestUtils.setField(protocol,"enabled",true);ReflectionTestUtils.setField(protocol,"verifier",verifier);
        ReflectionTestUtils.setField(service,"nativeStepProtocol",protocol);
        when(verifier.isReady()).thenReturn(true);when(verifier.isEnrolledIPhoneKey(7,"device-key")).thenReturn(true);
        when(verifier.verifyAndConsume(any(),anyString())).thenReturn(1L);
        session=new GameRuntimeSession();session.setId(51L);session.setActivityId(0L);session.setTopicId(19L);session.setNodeId(29L);
        session.setOwnerType(1);session.setOwnerId(7L);session.setStatus(0);session.setVersion(0);session.setScore(0);session.setStateJson("{}");
        session.setStartedAt(Date.from(now.minusSeconds(60)));
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"steps\":{\"enabled\":true,\"goal\":10,\"xp\":30}}");
        when(sessions.selectAccessibleById(51L,7L)).thenReturn(session);when(sessions.updateStateCas(any())).thenReturn(1);
    }
    private Map<String,Object> issuePayload(){Map<String,Object> p=new LinkedHashMap<>();p.put("provider",NativeStepProtocol.PROVIDER);p.put("deviceKeyId","device-key");return p;}
    private Map<String,Object> issue(){return service.act(7L,51L,session.getVersion(),"issue-1",NativeStepProtocol.ISSUE,issuePayload());}
    @SuppressWarnings("unchecked") private Map<String,Object> nativeView(Map<String,Object> view){return (Map<String,Object>)((Map<?,?>)((Map<?,?>)view.get("playKit")).get("steps")).get("nativeSteps");}
    @Test void challengeUsesCasAndReceiptWithoutJourneyRewardOrCompletion(){
        Map<String,Object> view=issue();assertEquals(1,view.get("version"));assertEquals(false,view.get("readyForBase"));
        assertEquals("AUDIT_ONLY",nativeView(view).get("status"));assertEquals(false,nativeView(view).get("rewardEnabled"));
        verify(sessions).updateStateCas(any());verify(receipts).insertPlayAttemptLog(any());
        verify(journey,never()).recordPassed(anyLong(),anyLong(),anyLong(),anyLong(),anyString(),anyBoolean());
        assertEquals(0,session.getScore());assertFalse(session.getStateJson().contains("reached"));
    }
    @Test void exactReceiptReplayDoesNotMutateAndChangedPayloadRefuses() throws Exception {
        Map<String,Object> view=issue();PlayAttemptLog log=new PlayAttemptLog();log.setAttemptType(8);log.setMemberId(7L);log.setResultJson(json.writeValueAsString(view));
        when(receipts.selectByIdempotencyKey("issue-1")).thenReturn(log);
        Map<String,Object> again=service.act(7L,51L,0,"issue-1",NativeStepProtocol.ISSUE,issuePayload());assertEquals(1,again.get("version"));
        Map<String,Object> changed=issuePayload();changed.put("deviceKeyId","different");
        assertThrows(ServiceException.class,()->service.act(7L,51L,0,"issue-1",NativeStepProtocol.ISSUE,changed));
        verify(sessions,times(1)).updateStateCas(any());
    }
    @Test void crossAccountAndStaleVersionNeverIssueChallenge(){
        assertThrows(RuntimeException.class,()->service.act(8L,51L,0,"issue-1",NativeStepProtocol.ISSUE,issuePayload()));
        assertThrows(ServiceException.class,()->service.act(7L,51L,9,"issue-1",NativeStepProtocol.ISSUE,issuePayload()));
        verify(sessions,never()).updateStateCas(any());verify(receipts,never()).insertPlayAttemptLog(any());
    }
    @Test void losingCasCannotWriteReceipt(){
        when(sessions.updateStateCas(any())).thenReturn(0);assertThrows(IllegalStateException.class,this::issue);
        verify(receipts,never()).insertPlayAttemptLog(any());
    }
    @Test void lateReceiptCollisionThrowsToRollbackStateAndCounter() {
        when(receipts.insertPlayAttemptLog(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("raced receipt"));
        ServiceException e=assertThrows(ServiceException.class,this::issue);
        assertEquals("NATIVE_STEPS_IDEMPOTENCY_CONFLICT",((Map<?,?>)e.getData()).get("reasonCode"));
        verify(sessions).updateStateCas(any());
    }
    @Test void disabledNativeProviderCannotMutateAnything(){
        ReflectionTestUtils.setField(protocol,"enabled",false);assertThrows(ServiceException.class,this::issue);
        verify(sessions,never()).updateStateCas(any());verify(receipts,never()).insertPlayAttemptLog(any());
    }
    @Test void legacyStepPlaintextCannotBecomeNativeProof(){
        assertThrows(RuntimeException.class,()->service.act(7L,51L,0,"legacy","SUBMIT_STEPS",Collections.singletonMap("todaySteps",100000)));
        verify(sessions,never()).updateStateCas(any());
    }
    @Test void preStartReminderIsReadOnlyAndRetainsSelfPlayAccessChecks(){
        CmsTopicNode node=new CmsTopicNode();node.setId(29L);node.setTopicId(19L);node.setTemplateId(39L);when(nodes.selectCmsTopicNodeById(29L)).thenReturn(node);
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(39L);
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"timeWindow\":{\"enabled\":true,\"openFrom\":\"23:00\",\"openTo\":\"01:00\",\"timeZone\":\"Asia/Shanghai\"}}");
        when(templates.selectCmsMemberTemplateById(39L)).thenReturn(template);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        assertThrows(IllegalStateException.class,()->service.timeWindow(7L,0L,19L,29L));
        CmsRegistration pass=new CmsRegistration();pass.setExpiresAt(Date.from(now.plusSeconds(600)));
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(pass));
        when(journey.playInstance(7L,19L,0L)).thenReturn("default");
        Map<String,Object> view=service.timeWindow(7L,0L,19L,29L);
        assertEquals(false,view.get("openNow"));assertEquals("Asia/Shanghai",view.get("timeZone"));assertEquals("LOCAL_ONLY",view.get("notificationProvider"));
        assertEquals(Instant.parse("2026-10-02T15:00:00Z").toEpochMilli(),view.get("nextOpenAt"));assertNull(view.get("stateVersion"));
        verify(journey).requireOpeningAccessReadOnly(7L,19L,0L,29L);verify(journey,never()).requireOpeningAccess(anyLong(),anyLong(),anyLong(),anyLong());verify(sessions,never()).insert(any());verify(sessions,never()).updateStateCas(any());
        assertThrows(IllegalArgumentException.class,()->service.timeWindow(7L,0L,20L,29L));
    }
    private void reminderFixture() {
        CmsTopicNode node=new CmsTopicNode();node.setId(29L);node.setTopicId(19L);node.setTemplateId(39L);when(nodes.selectCmsTopicNodeById(29L)).thenReturn(node);
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(39L);template.setAdvancedConfigJson("{\"schemaVersion\":1,\"timeWindow\":{\"enabled\":true,\"openFrom\":\"23:00\",\"openTo\":\"01:00\",\"timeZone\":\"Asia/Shanghai\"}}");when(templates.selectCmsMemberTemplateById(39L)).thenReturn(template);
        CmsRegistration pass=new CmsRegistration();pass.setExpiresAt(Date.from(now.plusSeconds(600)));when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(pass));
    }
    @Test void missingMockInstanceFailsClosedAndOnlyPreciseLegacyTupleCanReadReminder() {
        reminderFixture();assertThrows(ServiceException.class,()->service.timeWindow(7L,0L,19L,29L));
        when(journey.playInstance(7L,19L,0L)).thenReturn(" ");assertThrows(ServiceException.class,()->service.timeWindow(7L,0L,19L,29L));
        when(journey.playInstance(7L,19L,0L)).thenReturn("default");assertDoesNotThrow(()->service.timeWindow(7L,0L,19L,29L));
        assertThrows(ServiceException.class,()->service.timeWindow(8L,0L,19L,29L));
        verify(journey,never()).requireOpeningAccess(anyLong(),anyLong(),anyLong(),anyLong());verify(sessions,never()).insert(any());verify(sessions,never()).updateStateCas(any());
    }
    @Test void actualNoRouteNonThemeReminderReturnsDefaultWithoutStartingAnySession() {
        reminderFixture();TopicRouteSessionMapper routeRows=mock(TopicRouteSessionMapper.class);CmsTopicMapper topicRows=mock(CmsTopicMapper.class);
        CmsTopic topic=new CmsTopic();topic.setId(19L);topic.setJourneyStoryJson("{}");when(topicRows.selectCmsTopicById(19L)).thenReturn(topic);
        TopicRouteRuntimeServiceImpl actual=new TopicRouteRuntimeServiceImpl(routeRows,topicRows,nodes,mock(PlayerNodeProgressMapper.class),mock(CmsActivityMapper.class),registrations);
        ReflectionTestUtils.setField(service,"topicRouteRuntimeService",actual);
        assertEquals("default",actual.playInstance(7L,19L,0L));Map<String,Object> read=service.timeWindow(7L,0L,19L,29L);assertNull(read.get("stateVersion"));assertEquals("LOCAL_ONLY",read.get("notificationProvider"));
        verify(routeRows,never()).insertSession(any());verify(routeRows,never()).updateStateCas(any());verify(sessions,never()).insert(any());verify(sessions,never()).updateStateCas(any());
    }
    @Test void actualUnstartedThemeAndForgedFirstOriginNeverFallBackToReminderDefault() {
        reminderFixture();TopicRouteSessionMapper routeRows=mock(TopicRouteSessionMapper.class);CmsTopicMapper topicRows=mock(CmsTopicMapper.class);
        CmsTopic topic=new CmsTopic();topic.setId(19L);topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");when(topicRows.selectCmsTopicById(19L)).thenReturn(topic);
        TopicRouteRuntimeServiceImpl actual=new TopicRouteRuntimeServiceImpl(routeRows,topicRows,nodes,mock(PlayerNodeProgressMapper.class),mock(CmsActivityMapper.class),registrations);
        ThemeRuntimeFeaturePolicy enabled=new ThemeRuntimeFeaturePolicy();ReflectionTestUtils.setField(enabled,"enabled",true);ReflectionTestUtils.setField(actual,"themeRuntimePolicy",enabled);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",actual);
        assertThrows(ServiceException.class,()->service.timeWindow(7L,0L,19L,29L));assertThrows(ServiceException.class,()->actual.assertCurrentPlayInstance(7L,19L,0L,"default"));
        TopicRouteSession fake=new TopicRouteSession();fake.setId(999L);fake.setMemberId(7L);fake.setTopicId(19L);fake.setActivityId(0L);fake.setPlayInstanceId("theme-first:19:0");fake.setConfigSnapshotJson("{\"themeConfig\":{}}");when(routeRows.selectCurrentByOwner(0L,19L,7L)).thenReturn(fake);
        assertThrows(ServiceException.class,()->service.timeWindow(7L,0L,19L,29L));fake.setMemberId(8L);assertThrows(ServiceException.class,()->service.timeWindow(7L,0L,19L,29L));
        verify(routeRows,never()).insertSession(any());verify(sessions,never()).insert(any());verify(sessions,never()).updateStateCas(any());
    }

}
