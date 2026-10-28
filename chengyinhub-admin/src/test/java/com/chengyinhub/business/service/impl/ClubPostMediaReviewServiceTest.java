package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubPostMapper;
import com.chengyinhub.business.mapper.ClubPostMediaReviewOutboxMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubPostMediaReviewServiceTest {
    private static final Long TASK_ID = 17L;
    private static final Long POST_ID = 81L;
    private static final Long MEMBER_ID = 42L;
    private static final int POST_VERSION = 3;
    private static final String MEDIA_URL = "https://cdn.example.test/club/post.jpg";

    private ClubPostMediaReviewServiceImpl service;
    private ClubPostMediaReviewOutboxMapper outboxMapper;
    private ClubPostMediaReviewTransactionService transactionService;
    private ClubPostMapper postMapper;
    private UmsMemberMapper memberMapper;
    private IWxContentSecurityService securityService;
    private IWxContentCallbackInboxService callbackInboxService;
    private AlarmService alarmService;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(ClubPostMediaReviewOutboxMapper.class);
        transactionService = mock(ClubPostMediaReviewTransactionService.class);
        postMapper = mock(ClubPostMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        securityService = mock(IWxContentSecurityService.class);
        callbackInboxService = mock(IWxContentCallbackInboxService.class);
        alarmService = mock(AlarmService.class);
        service = new ClubPostMediaReviewServiceImpl(outboxMapper, transactionService, postMapper,
                memberMapper, securityService, callbackInboxService, alarmService);
        when(securityService.isEnabled()).thenReturn(true);
    }

    @Test
    void provenPreDispatchFailureRetriesOriginalTaskThenOneReceiptMakesItTerminal() {
        ClubPostMediaReviewOutbox firstAttempt = task(1);
        ClubPostMediaReviewOutbox secondAttempt = task(2);
        when(transactionService.claim(TASK_ID)).thenReturn(firstAttempt, secondAttempt, null);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(currentPost());
        when(memberMapper.selectUmsMemberById(MEMBER_ID))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("local read unavailable"))
                .thenReturn(member());
        when(securityService.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_CLUB_POST,
                POST_ID, MEDIA_URL, "wx-openid"))
                .thenReturn("trace-success");
        when(callbackInboxService.isRegistered("trace-success")).thenReturn(true);
        when(transactionService.markSubmitted(secondAttempt, "trace-success")).thenReturn(true);

        assertFalse(service.dispatchOne(TASK_ID), "外呼前读取失败不能把任务误标成功");
        assertTrue(service.dispatchOne(TASK_ID), "重试拿到 trace + inbox 回执后才能终态");
        assertFalse(service.dispatchOne(TASK_ID), "终态任务不能再次送审");

        verify(transactionService).retryTransient(firstAttempt, ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE);
        verify(transactionService).markSubmitted(secondAttempt, "trace-success");
        verify(securityService, times(1)).submitMediaCheckOne(
                IWxContentSecurityService.BIZ_TYPE_CLUB_POST, POST_ID, MEDIA_URL, "wx-openid");
    }

    @Test
    void poisonTaskIsIsolatedAndNextDueTaskStillGetsSubmitted() {
        Long poisonId = 18L;
        Long healthyId = 19L;
        ClubPostMediaReviewOutbox poison = task(1);
        poison.setId(poisonId);
        poison.setMediaHash("not-a-sha256");
        ClubPostMediaReviewOutbox healthy = task(1);
        healthy.setId(healthyId);
        when(outboxMapper.selectDueIds(50)).thenReturn(Arrays.asList(poisonId, healthyId));
        when(transactionService.claim(poisonId)).thenReturn(poison);
        when(transactionService.claim(healthyId)).thenReturn(healthy);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(currentPost());
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member());
        when(securityService.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_CLUB_POST,
                POST_ID, MEDIA_URL, "wx-openid")).thenReturn("trace-healthy");
        when(callbackInboxService.isRegistered("trace-healthy")).thenReturn(true);
        when(transactionService.markSubmitted(healthy, "trace-healthy")).thenReturn(true);

        assertEquals(1, service.retryDue());

        verify(transactionService).markPoisoned(poison, "TASK_INVALID");
        verify(transactionService).markSubmitted(healthy, "trace-healthy");
        verify(securityService, times(1)).submitMediaCheckOne(
                IWxContentSecurityService.BIZ_TYPE_CLUB_POST, POST_ID, MEDIA_URL, "wx-openid");
    }

    @Test
    void laterTextOrPinVersionStillSubmitsWhenTheReviewedMediaIsCurrent() {
        ClubPostMediaReviewOutbox pending = task(1);
        ClubPost newer = currentPost();
        newer.setVersion(POST_VERSION + 1);
        when(transactionService.claim(TASK_ID)).thenReturn(pending);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(newer);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member());
        when(securityService.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_CLUB_POST,
                POST_ID, MEDIA_URL, "wx-openid")).thenReturn("trace-after-text-edit");
        when(callbackInboxService.isRegistered("trace-after-text-edit")).thenReturn(true);
        when(transactionService.markSubmitted(pending, "trace-after-text-edit")).thenReturn(true);

        assertTrue(service.dispatchOne(TASK_ID));

        verify(transactionService).markSubmitted(pending, "trace-after-text-edit");
        verify(transactionService, never()).markStale(pending, "POST_VERSION_STALE");
    }

    @Test
    void removedMediaMakesOldTaskStaleWithoutCallingWechat() {
        ClubPostMediaReviewOutbox stale = task(1);
        ClubPost newer = currentPost();
        newer.setVersion(POST_VERSION + 1);
        newer.setImages("https://cdn.example.test/club/other.jpg");
        when(transactionService.claim(TASK_ID)).thenReturn(stale);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(newer);

        assertFalse(service.dispatchOne(TASK_ID));

        verify(transactionService).markStale(stale, "MEDIA_STALE");
        verify(securityService, never()).submitMediaCheckOne(eq(IWxContentSecurityService.BIZ_TYPE_CLUB_POST),
                eq(POST_ID), anyString(), anyString());
    }

    @Test
    void disabledContentSecurityDoesNotClaimOrConsumeAttempts() {
        when(securityService.isEnabled()).thenReturn(false);

        assertEquals(0, service.retryDue());
        assertFalse(service.dispatchOne(TASK_ID));

        verify(outboxMapper, never()).selectDueIds(50);
        verify(transactionService, never()).claim(TASK_ID);
    }

    @Test
    void recoveryStopsStartingNewClaimsAfterBudgetAndLeavesRemainingIdsUnclaimed() {
        Long firstId = 18L;
        Long secondId = 19L;
        when(outboxMapper.selectDueIds(50)).thenReturn(Arrays.asList(firstId, secondId));
        when(transactionService.claim(firstId)).thenReturn(null);
        AtomicInteger clockReads = new AtomicInteger();
        LongSupplier clock = () -> clockReads.getAndIncrement() == 0 ? 0L : 26_000_000_000L;
        ReflectionTestUtils.setField(service, "nanoTime", clock);

        assertEquals(0, service.retryDue());

        verify(transactionService).claim(firstId);
        verify(transactionService, never()).claim(secondId);
    }

    @Test
    void firstUnknownOutcomeStopsTheBatchWithoutRequeueingIt() {
        Long firstId = 18L;
        Long secondId = 19L;
        ClubPostMediaReviewOutbox first = task(1);
        first.setId(firstId);
        when(outboxMapper.selectDueIds(50)).thenReturn(Arrays.asList(firstId, secondId));
        when(transactionService.claim(firstId)).thenReturn(first);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(currentPost());
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member());
        when(securityService.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_CLUB_POST,
                POST_ID, MEDIA_URL, "wx-openid")).thenReturn(null);

        assertEquals(0, service.retryDue());

        verify(transactionService).markUnknown(first, null);
        verify(transactionService, never()).claim(secondId);
    }

    @Test
    void dueBacklogOverThresholdEmitsOperationalWarning() {
        when(outboxMapper.selectDueIds(50)).thenReturn(Collections.emptyList());
        when(outboxMapper.countBacklog()).thenReturn(101L);
        when(outboxMapper.selectOldestBacklogCreatedAt()).thenReturn(new Date(0L));

        assertEquals(0, service.retryDue());
        assertEquals(0, service.retryDue(), "十分钟冷却窗口内仍可继续恢复，但不能重复轰炸告警出口");

        verify(alarmService, times(1)).alarm(eq("俱乐部动态图片送审积压"), contains("backlogCount=101"));
    }

    @Test
    void disabledContentSecurityStillAlarmsForOneOldBacklogRowWithoutClaimingIt() {
        when(securityService.isEnabled()).thenReturn(false);
        when(outboxMapper.countBacklog()).thenReturn(1L);
        when(outboxMapper.selectOldestBacklogCreatedAt()).thenReturn(new Date(0L));

        assertEquals(0, service.retryDue());

        verify(alarmService).alarm(eq("俱乐部动态图片送审积压"), contains("backlogCount=1"));
        verify(outboxMapper, never()).selectDueIds(50);
        verify(transactionService, never()).claim(TASK_ID);
    }

    @Test
    void finalPreDispatchFailureTriggersBudgetAlarmWithoutCallingProvider() {
        ClubPostMediaReviewOutbox fifthAttempt = task(5);
        when(transactionService.claim(TASK_ID)).thenReturn(fifthAttempt);
        when(postMapper.selectClubPostById(POST_ID))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("local read unavailable"));

        assertFalse(service.dispatchOne(TASK_ID));

        verify(transactionService).retryTransient(fifthAttempt, ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE);
        verify(alarmService).alarm(eq("俱乐部动态图片送审持续失败"), contains("attempts=5"));
    }

    @Test
    void terminalWriteLossPreservesOriginalTraceAndNeverAuthorizesResubmission() {
        ClubPostMediaReviewOutbox firstLease = task(1);
        when(transactionService.claim(TASK_ID)).thenReturn(firstLease, (ClubPostMediaReviewOutbox) null);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(currentPost());
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member());
        when(securityService.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_CLUB_POST,
                POST_ID, MEDIA_URL, "wx-openid")).thenReturn("trace-before-crash");
        when(callbackInboxService.isRegistered("trace-before-crash")).thenReturn(true);
        when(transactionService.markSubmitted(firstLease, "trace-before-crash")).thenReturn(false);

        assertFalse(service.dispatchOne(TASK_ID));
        assertFalse(service.dispatchOne(TASK_ID), "原任务停止后不能再外呼");
        verify(transactionService).markUnknown(firstLease, "trace-before-crash");
        verify(securityService, times(1)).submitMediaCheckOne(
                IWxContentSecurityService.BIZ_TYPE_CLUB_POST, POST_ID, MEDIA_URL, "wx-openid");
    }

    @Test
    void fifthAttemptCanStillStoreItsSuccessfulOriginalReceipt() {
        ClubPostMediaReviewOutbox last = task(5);
        when(transactionService.claim(TASK_ID)).thenReturn(last);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(currentPost());
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member());
        when(securityService.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_CLUB_POST,
                POST_ID, MEDIA_URL, "wx-openid")).thenReturn("trace-final");
        when(callbackInboxService.isRegistered("trace-final")).thenReturn(true);
        when(transactionService.markSubmitted(last, "trace-final")).thenReturn(true);
        assertTrue(service.dispatchOne(TASK_ID));
        verify(transactionService).markSubmitted(last, "trace-final");
        verify(transactionService, never()).retryTransient(last, ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE);
    }

    @Test
    void providerExceptionAndMissingInboxReceiptBothStopWithUnknownOriginalIdentity() {
        ClubPostMediaReviewOutbox first = task(1);
        ClubPostMediaReviewOutbox second = task(1);
        second.setId(18L);
        when(transactionService.claim(TASK_ID)).thenReturn(first);
        when(transactionService.claim(18L)).thenReturn(second);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(currentPost());
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member());
        when(securityService.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_CLUB_POST,
                POST_ID, MEDIA_URL, "wx-openid"))
                .thenThrow(new IllegalStateException("reply lost")).thenReturn("original-trace");
        assertFalse(service.dispatchOne(TASK_ID));
        assertFalse(service.dispatchOne(18L));
        verify(transactionService).markUnknown(first, null);
        verify(transactionService).markUnknown(second, "original-trace");
        verify(transactionService, never()).retryTransient(eq(first), anyString());
        verify(transactionService, never()).retryTransient(eq(second), anyString());
        verify(postMapper, never()).updateClubPost(org.mockito.ArgumentMatchers.any());
    }

    private ClubPostMediaReviewOutbox task(int attemptCount) {
        ClubPostMediaReviewOutbox task = new ClubPostMediaReviewOutbox();
        task.setId(TASK_ID);
        task.setPostId(POST_ID);
        task.setPostVersion(POST_VERSION);
        task.setSubmitterMemberId(MEMBER_ID);
        task.setMediaHash(WxContentCallbackInboxServiceImpl.sha256Hex(MEDIA_URL));
        task.setOwnerToken("00112233445566778899aabbccddeeff");
        task.setFenceToken(2L);
        task.setAttemptCount(attemptCount);
        task.setMaxAttempts(5);
        return task;
    }

    private ClubPost currentPost() {
        ClubPost post = new ClubPost();
        post.setId(POST_ID);
        post.setVersion(POST_VERSION);
        post.setImages(MEDIA_URL);
        post.setDelFlag(0);
        return post;
    }

    private UmsMember member() {
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setWxOpenId("wx-openid");
        return member;
    }
}
