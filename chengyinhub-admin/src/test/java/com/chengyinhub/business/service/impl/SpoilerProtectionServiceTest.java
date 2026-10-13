package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberSpoilerReveal;
import com.chengyinhub.business.domain.vo.SpoilerAccessSnapshot;
import com.chengyinhub.business.mapper.SpoilerProtectionMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpoilerProtectionServiceTest {
    private static final Long MEMBER_ID = 18L;
    private static final Long NODE_ID = 202L;
    private static final Long OTHER_NODE_ID = 203L;
    private static final String CONTENT_TYPE = "NODE";
    private static final String REQUEST_ID = "spoiler-reveal-node-202";
    private static final String ANSWER_FIELD = "answerReveal";
    private static final String SECRET = "真正答案";

    @Test
    void hiddenProjectionNeverContainsSecretUntilThisMemberReveals() {
        SpoilerProtectionMapper mapper = mock(SpoilerProtectionMapper.class);
        when(mapper.selectRevealAll(MEMBER_ID)).thenReturn(false);
        when(mapper.selectRevealedContentIds(MEMBER_ID, CONTENT_TYPE)).thenReturn(Collections.emptyList());
        SpoilerProtectionServiceImpl service = service(mapper);

        SpoilerAccessSnapshot snapshot = service.snapshot(MEMBER_ID, CONTENT_TYPE);
        Map<String, Object> projection = service.project(snapshot, NODE_ID, SECRET);

        assertTrue((Boolean) projection.get("spoilerProtected"));
        assertFalse((Boolean) projection.get("revealed"));
        assertFalse(projection.containsKey(ANSWER_FIELD));
    }

    @Test
    void replayingARequestIdForAnotherNodeFailsBeforeAnyWrite() {
        SpoilerProtectionMapper mapper = mock(SpoilerProtectionMapper.class);
        MemberSpoilerReveal existing = new MemberSpoilerReveal();
        existing.setMemberId(MEMBER_ID);
        existing.setContentType(CONTENT_TYPE);
        existing.setContentId(OTHER_NODE_ID);
        existing.setRequestId(REQUEST_ID);
        when(mapper.selectRevealByRequestId(MEMBER_ID, REQUEST_ID)).thenReturn(existing);
        SpoilerProtectionServiceImpl service = service(mapper);

        assertThrows(ServiceException.class,
                () -> service.reveal(MEMBER_ID, CONTENT_TYPE, NODE_ID, REQUEST_ID));
        verify(mapper, never()).insertReveal(org.mockito.ArgumentMatchers.any());
    }

    private SpoilerProtectionServiceImpl service(SpoilerProtectionMapper mapper) {
        SpoilerProtectionServiceImpl service = new SpoilerProtectionServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        return service;
    }
}
