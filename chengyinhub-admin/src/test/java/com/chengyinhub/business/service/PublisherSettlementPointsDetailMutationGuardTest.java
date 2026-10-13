package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.impl.UmsMemberPointsDetailServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublisherSettlementPointsDetailMutationGuardTest {

    private UmsMemberPointsDetailMapper mapper;
    private UmsMemberPointsDetailServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UmsMemberPointsDetailMapper.class);
        service = new UmsMemberPointsDetailServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberPointsDetailMapper", mapper);
    }

    @Test
    void genericInsertCannotForgeEvent15Ledger() {
        UmsMemberPointsDetail detail = event15(1L);

        assertThrows(ServiceException.class, () -> service.insertUmsMemberPointsDetail(detail));

        verify(mapper, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void genericInsertCannotForgePublisherTopicProjectionUnderAnotherEvent() {
        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setEventType(11L);
        detail.setPublisherSettlementTopicId(200L);

        assertThrows(ServiceException.class, () -> service.insertUmsMemberPointsDetail(detail));

        verify(mapper, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void genericUpdateCannotTargetOrTransformEvent15Ledger() {
        UmsMemberPointsDetail existing = event15(1L);
        when(mapper.selectUmsMemberPointsDetailById(1L)).thenReturn(existing);
        UmsMemberPointsDetail patch = new UmsMemberPointsDetail();
        patch.setId(1L);

        assertThrows(ServiceException.class, () -> service.updateUmsMemberPointsDetail(patch));

        UmsMemberPointsDetail forged = event15(2L);
        assertThrows(ServiceException.class, () -> service.updateUmsMemberPointsDetail(forged));
        verify(mapper, never()).updateUmsMemberPointsDetail(any());
    }

    @Test
    void genericSingleDeleteCannotRemoveEvent15IdempotencyEvidence() {
        when(mapper.selectUmsMemberPointsDetailById(1L)).thenReturn(event15(1L));

        assertThrows(ServiceException.class, () -> service.deleteUmsMemberPointsDetailById(1L));

        verify(mapper, never()).deleteUmsMemberPointsDetailById(anyLong());
    }

    @Test
    void genericBatchDeleteFailsClosedIfAnyRowIsEvent15() {
        UmsMemberPointsDetail ordinary = new UmsMemberPointsDetail();
        ordinary.setId(1L);
        ordinary.setEventType(11L);
        when(mapper.selectUmsMemberPointsDetailById(1L)).thenReturn(ordinary);
        when(mapper.selectUmsMemberPointsDetailById(2L)).thenReturn(event15(2L));

        assertThrows(ServiceException.class,
                () -> service.deleteUmsMemberPointsDetailByIds(new Long[]{1L, 2L}));

        verify(mapper, never()).deleteUmsMemberPointsDetailByIds(any());
    }

    private UmsMemberPointsDetail event15(Long id) {
        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setId(id);
        detail.setEventType(IPointsAwardService.EVENT_PUBLISHER_SETTLEMENT);
        detail.setEventId(200L);
        detail.setChangeType(1L);
        detail.setRewardBusinessKey("publisher-settlement:topic:200");
        return detail;
    }
}
