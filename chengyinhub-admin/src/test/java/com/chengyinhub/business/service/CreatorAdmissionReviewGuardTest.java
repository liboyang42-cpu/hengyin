package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.dto.CreatorReviewDTO;
import com.chengyinhub.business.mapper.CreatorProfileMapper;
import com.chengyinhub.business.service.impl.CreatorCenterServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreatorAdmissionReviewGuardTest {

    private CreatorProfileMapper creatorProfileMapper;
    private CreatorCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        creatorProfileMapper = mock(CreatorProfileMapper.class);
        service = new CreatorCenterServiceImpl();
        ReflectionTestUtils.setField(service, "creatorProfileMapper", creatorProfileMapper);
    }

    @Test
    void rejectsUnsupportedDecisionBeforeAnyWrite() {
        CreatorReviewDTO request = reviewRequest(11L, 0, null);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.review(request, 71L));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verifyNoInteractions(creatorProfileMapper);
    }

    @Test
    void rejectionRequiresNonBlankReasonBeforeAnyWrite() {
        CreatorReviewDTO request = reviewRequest(11L, 2, "  ");

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.review(request, 71L));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(creatorProfileMapper, never()).updatePendingCreatorProfile(org.mockito.ArgumentMatchers.any(CreatorProfile.class));
    }

    @Test
    void reviewReasonCannotExceedPersistedColumnLength() {
        CreatorReviewDTO request = reviewRequest(11L, 2, repeat('a', 501));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.review(request, 71L));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verifyNoInteractions(creatorProfileMapper);
    }

    @Test
    void approvedDecisionUsesServerReviewerAndOnlyUpdatesPendingRow() {
        CreatorReviewDTO request = reviewRequest(11L, 1, null);
        when(creatorProfileMapper.updatePendingCreatorProfile(org.mockito.ArgumentMatchers.any(CreatorProfile.class))).thenReturn(1);

        int rows = service.review(request, 71L);

        assertEquals(1, rows);
        verify(creatorProfileMapper).updatePendingCreatorProfile(argThat(profile ->
                Long.valueOf(11L).equals(profile.getId())
                        && Integer.valueOf(1).equals(profile.getApplyStatus())
                        && Long.valueOf(71L).equals(profile.getReviewerId())
                        && profile.getReviewReason() == null
                        && profile.getReviewedAt() != null
                        && profile.getUpdateTime() != null));
    }

    @Test
    void staleOrAlreadyHandledDecisionFailsClosed() {
        CreatorReviewDTO request = reviewRequest(11L, 2, "资料信息不足");
        when(creatorProfileMapper.updatePendingCreatorProfile(org.mockito.ArgumentMatchers.any(CreatorProfile.class))).thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.review(request, 71L));

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
    }

    private static CreatorReviewDTO reviewRequest(Long id, Integer applyStatus, String reviewReason) {
        CreatorReviewDTO request = new CreatorReviewDTO();
        request.setId(id);
        request.setApplyStatus(applyStatus);
        request.setReviewReason(reviewReason);
        return request;
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
