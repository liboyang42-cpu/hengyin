package com.chengyinhub.web.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerTopicTagService;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.*;

class PreferencePlayServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void idempotentReplayReturnsTheStoredAuthoritativeProgressWithoutAdvancingAgain() {
        PreferencePlayService service = new PreferencePlayService();
        PlayAttemptLogMapper attempts = mock(PlayAttemptLogMapper.class);
        IPlayerTopicTagService tags = mock(IPlayerTopicTagService.class);
        ReflectionTestUtils.setField(service, "attemptMapper", attempts);
        ReflectionTestUtils.setField(service, "tagService", tags);
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime = mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        when(runtime.playInstances(anyList())).thenAnswer(call -> {
            java.util.List<com.chengyinhub.business.domain.TopicRouteSession> scopes=call.getArgument(0);
            assertEquals(1,scopes.size());com.chengyinhub.business.domain.TopicRouteSession scope=scopes.get(0);
            assertEquals(Long.valueOf(7),scope.getMemberId());assertEquals(Long.valueOf(9),scope.getTopicId());assertEquals(Long.valueOf(10),scope.getActivityId());
            return Collections.singletonMap(com.chengyinhub.business.service.ITopicRouteRuntimeService.sessionKey(7L,9L,10L),"default");
        });
        ReflectionTestUtils.setField(service,"routeRuntimeService",runtime);
        PlayAttemptLog existing = new PlayAttemptLog();
        existing.setMemberId(7L);existing.setTopicId(9L);existing.setActivityId(10L);existing.setNodeId(11L);existing.setAttemptType(6);existing.setResult(1);existing.setIdempotencyKey("preference:7:9:10:11");
        existing.setResultJson("{\"evaluation\":{\"resultCode\":\"compact\"},"
                + "\"progress\":{\"completed\":true,\"xp\":12},\"availableTagValues\":[\"compact\"]}");
        when(attempts.selectByIdempotencyKey("preference:7:9:10:11")).thenReturn(existing);
        when(tags.pendingForNode(7L, 9L, 11L)).thenReturn(Collections.emptyList());
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setPreferenceJson(validConfig());

        Map<String, Object> data = service.submit(7L, 9L, 10L, 11L, template,
                new com.chengyinhub.business.domain.dto.PreferenceSubmitRequest(),
                outcomeCode -> { throw new AssertionError("重放不得再次推进进度"); });

        Map<String, Object> progress = (Map<String, Object>) data.get("progress");
        assertEquals(Boolean.TRUE, progress.get("completed"));
        assertEquals(12, progress.get("xp"));
        verify(runtime,never()).beginOriginalNodeAward(anyLong(),anyLong(),anyLong(),anyLong(),anyString());
        verify(runtime,never()).playInstance(anyLong(),anyLong(),anyLong());
    }

    @Test
    void operatingSystemStaysClosedUntilTheCompletionRuleIsTrue() {
        PreferencePlayService service = new PreferencePlayService();
        PlayAttemptLogMapper attempts = mock(PlayAttemptLogMapper.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        ReflectionTestUtils.setField(service, "attemptMapper", attempts);
        ReflectionTestUtils.setField(service, "topicService", topics);
        ReflectionTestUtils.setField(service, "registrationService", registrations);
        when(topics.selectCmsTopicById(9L)).thenReturn(new CmsTopic());
        when(registrations.selectCmsRegistrationList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());

        assertEquals("完成主题后才能生成新生活操作系统", assertThrows(IllegalArgumentException.class,
                () -> service.operatingSystem(7L, 9L)).getMessage());
        verify(attempts).selectPreferenceResults(7L, 9L);
    }

    @Test
    void operatingSystemUsesTheActivityOnThePreferenceAttemptToFindTheCompletedExplorePass() {
        PreferencePlayService service = new PreferencePlayService();
        PlayAttemptLogMapper attempts = mock(PlayAttemptLogMapper.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IPerkLedgerService ledger = mock(IPerkLedgerService.class);
        IPlayerTopicTagService tags = mock(IPlayerTopicTagService.class);
        ReflectionTestUtils.setField(service, "attemptMapper", attempts);
        ReflectionTestUtils.setField(service, "topicService", topics);
        ReflectionTestUtils.setField(service, "registrationService", registrations);
        ReflectionTestUtils.setField(service, "perkLedgerService", ledger);
        ReflectionTestUtils.setField(service, "tagService", tags);

        PlayAttemptLog attempt = new PlayAttemptLog();
        attempt.setActivityId(10L);
        attempt.setResultJson("{\"evaluation\":{\"title\":\"减负\",\"body\":\"已确认\","
                + "\"nextStep\":\"清柜子\",\"nextStepDays\":7}}");
        CmsRegistration explore = new CmsRegistration();
        explore.setId(12L);
        explore.setOwnerType(2L);
        explore.setOwnerId(10L);
        explore.setRegistrationStatus(2);
        explore.setPurchaseKind(PurchaseKind.EXPLORE_PASS);

        when(topics.selectCmsTopicById(9L)).thenReturn(new CmsTopic());
        when(attempts.selectPreferenceResults(7L, 9L)).thenReturn(Collections.singletonList(attempt));
        when(registrations.selectCmsRegistrationList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(explore));
        when(ledger.isExploreCompleted(12L, 9L)).thenReturn(true);
        when(tags.confirmedForOwner(7L, 9L)).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.operatingSystem(7L, 9L);

        assertEquals("启动版", result.get("edition"));
        assertEquals(Collections.singletonList("清柜子"), result.get("actions7Days"));
        verify(ledger).isExploreCompleted(12L, 9L);
    }

    private String validConfig() {
        return "{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"选\",\"options\":["
                + "{\"key\":\"A\",\"text\":\"小\",\"scores\":{\"compact\":2}},"
                + "{\"key\":\"B\",\"text\":\"大\",\"scores\":{\"compact\":1}}]}],"
                + "\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"减负\","
                + "\"body\":\"因为{{choices}}\",\"nextStep\":\"清柜子\",\"nextStepDays\":7}},"
                + "\"tagOutput\":{\"code\":\"space_constraints\",\"fromDimension\":true,"
                + "\"purpose\":\"减少重复提问\",\"recipientLabel\":\"下一站\",\"revocable\":true}}";
    }
}
