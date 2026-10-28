package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.TopicRouteSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayOriginalRunTest {
    @Test void personalSharedAndNoRuntimeKeepSeparateProgressIdentities() {
        TopicRouteSession session = new TopicRouteSession(); session.setPlayInstanceId("run:original");
        assertEquals("run:original", new PlayOriginalRun(session, 71L, null, null).progressInstance());
        PlayOriginalRun shared = new PlayOriginalRun(null, 81L, "epoch:original", 3);
        assertEquals("game:81", shared.progressInstance()); assertTrue(shared.hasRuntime());
        PlayOriginalRun absent = new PlayOriginalRun(null, 0L, null, null);
        assertEquals("default", absent.progressInstance()); assertFalse(absent.hasRuntime());
    }
    @Test void sharedTaskCannotFallThroughIntoPersonalCompletionOrRewards() {
        PlayNodeCompletionApplication completion = new PlayNodeCompletionApplication();
        var runtime = org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        var progress = org.mockito.Mockito.mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(completion, "topicRouteRuntimeService", runtime);
        org.springframework.test.util.ReflectionTestUtils.setField(completion, "playerNodeProgressService", progress);
        var failure = assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> completion.advanceProgress(7L, new com.chengyinhub.business.domain.CmsTopicNode(), 2L,
                        null, null, null, com.chengyinhub.common.constant.RewardPolicy.FULL,
                        "original-action", 1, null, null, new PlayOriginalRun(null, 81L, "shared", 3)));
        assertEquals(409, failure.getCode());
        org.mockito.Mockito.verifyNoInteractions(runtime, progress);
    }
    @Test void photoReceiptProbeUsesOriginalInstanceAndAllOwnershipDimensions() {
        var progress = org.mockito.Mockito.mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class);
        var access = new com.chengyinhub.business.service.support.PlayNodeAccessService(
                null, null, null, null, progress, null, null, null, null, null, null);
        var stored = new com.chengyinhub.business.domain.PlayerNodeProgress();
        stored.setId(101L); stored.setStatus(1); stored.setPicUrl("original-photo");
        org.mockito.Mockito.when(progress.selectPlayerNodeProgressList(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.PlayerNodeProgress probe = call.getArgument(0);
            assertEquals(7L, probe.getMemberId()); assertEquals(2L, probe.getActivityId());
            assertEquals(3L, probe.getTopicId()); assertEquals(4L, probe.getNodeId());
            assertEquals("run:71", probe.getPlayInstanceId());
            return java.util.List.of(stored);
        });
        var context = new PlayNodeContext();
        org.springframework.test.util.ReflectionTestUtils.setField(context, "playNodeAccessService", access);
        TopicRouteSession session = new TopicRouteSession(); session.setPlayInstanceId("run:71");
        var actual = context.findNodeProgressRow(7L, 2L, 3L, 4L, new PlayOriginalRun(session, 71L, null, null));
        assertEquals(101L, actual.getId()); assertEquals("original-photo", actual.getPicUrl());
    }
}
