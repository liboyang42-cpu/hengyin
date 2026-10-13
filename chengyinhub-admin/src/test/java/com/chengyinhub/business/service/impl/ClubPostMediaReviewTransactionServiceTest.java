package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
import com.chengyinhub.business.mapper.ClubPostMediaReviewOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubPostMediaReviewTransactionServiceTest {
    private static final Long POST_ID = 81L;
    private static final Long MEMBER_ID = 42L;
    private static final int POST_VERSION = 3;
    private static final String MEDIA_URL = "https://cdn.example.test/club/post.jpg";

    private ClubPostMediaReviewOutboxMapper mapper;
    private ClubPostMediaReviewTransactionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubPostMediaReviewOutboxMapper.class);
        service = new ClubPostMediaReviewTransactionService(mapper);
    }

    @Test
    void samePostVersionAndMediaHashUsesOneDatabaseIdentity() {
        when(mapper.insertIgnore(any())).thenReturn(1, 0);
        ClubPostMediaReviewOutbox existing = new ClubPostMediaReviewOutbox();
        existing.setPostId(POST_ID);
        existing.setPostVersion(POST_VERSION);
        existing.setSubmitterMemberId(MEMBER_ID);
        existing.setMediaHash(WxContentCallbackInboxServiceImpl.sha256Hex(MEDIA_URL));
        when(mapper.selectByKey(POST_ID, POST_VERSION, existing.getMediaHash())).thenReturn(existing);

        assertEquals(1, service.enqueueUpdatedMedia(POST_ID, POST_VERSION, MEMBER_ID,
                MEDIA_URL + "," + MEDIA_URL));
        assertEquals(0, service.enqueueUpdatedMedia(POST_ID, POST_VERSION, MEMBER_ID, MEDIA_URL));

        ArgumentCaptor<ClubPostMediaReviewOutbox> inserted =
                ArgumentCaptor.forClass(ClubPostMediaReviewOutbox.class);
        verify(mapper, times(2)).insertIgnore(inserted.capture());
        assertEquals(inserted.getAllValues().get(0).getMediaHash(),
                inserted.getAllValues().get(1).getMediaHash());
        assertEquals(POST_VERSION, inserted.getValue().getPostVersion());
    }

    @Test
    void enqueueMustJoinTheClubPostUpdateTransaction() throws Exception {
        Method enqueue = ClubPostMediaReviewTransactionService.class.getMethod(
                "enqueueUpdatedMedia", Long.class, Integer.class, Long.class, String.class);
        Transactional transactional = enqueue.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.MANDATORY, transactional.propagation(),
                "没有 ClubPost update 事务时禁止单独制造 orphan outbox");
    }

    @Test
    void transientExternalFailureAtTheAlertThresholdRemainsRetryable() {
        ClubPostMediaReviewOutbox task = new ClubPostMediaReviewOutbox();
        task.setId(17L);
        task.setOwnerToken("00112233445566778899aabbccddeeff");
        task.setFenceToken(2L);
        task.setAttemptCount(5);
        task.setMaxAttempts(5);
        when(mapper.markRetryable(org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq(task.getOwnerToken()),
                org.mockito.ArgumentMatchers.eq(2L), any(),
                org.mockito.ArgumentMatchers.eq("WX_NO_RECEIPT"))).thenReturn(1);

        assertTrue(service.retryTransient(task, "WX_NO_RECEIPT"));

        verify(mapper).markRetryable(org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq(task.getOwnerToken()),
                org.mockito.ArgumentMatchers.eq(2L), any(),
                org.mockito.ArgumentMatchers.eq("WX_NO_RECEIPT"));
        verify(mapper, never()).markPoisoned(any(), any(), any(), any());
    }
}
