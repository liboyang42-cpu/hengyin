package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集成缺陷 AUDIT-INT-1(#379 × #381):官方活动先审后发(#379)期间,封面机审违规回调(#381 AUDIT-G1-4)
 * 把 status 置 ST_OFFLINE 而 control_state 仍是 PENDING_REVIEW。此前 applyAuditDecision 只认
 * 「status=0 + PENDING_REVIEW」,通过/驳回都抛「审核状态已变化」⇒ 审核单永远关不掉。
 * 现在:驳回照常结案(保持下线);通过被拒并说明封面已被机审下线,违规封面不会因人工通过而公开。
 */
class OfficialEventCoverTakedownDuringReviewTest {

    private OfficialEventMapper eventMapper;
    private OfficialEventServiceImpl v1;

    @BeforeEach
    void setUp() {
        eventMapper = mock(OfficialEventMapper.class);
        v1 = new OfficialEventServiceImpl();
        ReflectionTestUtils.setField(v1, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(v1, "signupMapper", mock(EventSignupMapper.class));
        ReflectionTestUtils.setField(v1, "officialEventV2Service", mock(IOfficialEventV2Service.class));
        ReflectionTestUtils.setField(v1, "auditTaskService", mock(IAuditTaskService.class));
    }

    private OfficialEvent coverTakenDownWhilePending(Long id) {
        OfficialEvent e = new OfficialEvent();
        e.setId(id);
        e.setContractVersion(1);
        e.setTitle("官方城市漫游");
        e.setStatus(OfficialEvent.ST_OFFLINE);
        e.setControlState(OfficialEvent.CONTROL_PENDING_REVIEW);
        return e;
    }

    @Test
    void rejectClosesReviewOfEventWhoseCoverWasTakenDownDuringReview() {
        when(eventMapper.selectOfficialEventByIdForUpdate(51L)).thenReturn(coverTakenDownWhilePending(51L));

        v1.applyAuditDecision(51L, false, "封面违规", 1L);

        ArgumentCaptor<OfficialEvent> upd = ArgumentCaptor.forClass(OfficialEvent.class);
        verify(eventMapper).updateOfficialEvent(upd.capture());
        assertEquals(OfficialEvent.ST_OFFLINE, upd.getValue().getStatus());
        assertEquals("NORMAL", upd.getValue().getControlState());
    }

    @Test
    void approveIsRefusedWithCoverReasonAndNeverPublishes() {
        when(eventMapper.selectOfficialEventByIdForUpdate(52L)).thenReturn(coverTakenDownWhilePending(52L));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> v1.applyAuditDecision(52L, true, null, 1L));

        assertTrue(ex.getMessage().contains("封面"), ex.getMessage());
        verify(eventMapper, never()).updateOfficialEvent(any());
    }
}
