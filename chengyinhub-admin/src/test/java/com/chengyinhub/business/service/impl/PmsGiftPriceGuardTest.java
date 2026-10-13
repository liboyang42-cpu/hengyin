package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PmsGift;
import com.chengyinhub.business.domain.PmsGiftChangeAudit;
import com.chengyinhub.business.mapper.PmsGiftMapper;
import com.chengyinhub.business.mapper.PmsGiftChangeAuditMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 礼物是 C 端实际扣积分的目录，后台配置不能写入负数、零价格或未记录原因的调价。
 */
class PmsGiftPriceGuardTest {

    private PmsGiftServiceImpl service;
    private PmsGiftMapper mapper;
    private PmsGiftChangeAuditMapper auditMapper;

    @BeforeEach
    void setUp() {
        service = new PmsGiftServiceImpl();
        mapper = mock(PmsGiftMapper.class);
        auditMapper = mock(PmsGiftChangeAuditMapper.class);
        ReflectionTestUtils.setField(service, "pmsGiftMapper", mapper);
        ReflectionTestUtils.setField(service, "pmsGiftChangeAuditMapper", auditMapper);
    }

    @Test
    void insertDefaultsToDisabledAndRejectsNonPositivePrice() {
        PmsGift invalid = gift(null, 0L, null);
        invalid.setDelFlag(null);

        assertThrows(ServiceException.class, () -> service.insertPmsGift(invalid));
        verify(mapper, never()).insertPmsGift(invalid);

        PmsGift newGift = gift(null, 10L, null);
        newGift.setDelFlag(null);
        doAnswer(invocation -> {
            invocation.<PmsGift>getArgument(0).setId(41L);
            return 1;
        }).when(mapper).insertPmsGift(newGift);
        when(auditMapper.insertPmsGiftChangeAudit(any(PmsGiftChangeAudit.class))).thenReturn(1);

        assertEquals(1, service.insertPmsGift(newGift));
        assertEquals(Integer.valueOf(2), newGift.getStatus());
        assertEquals(Integer.valueOf(0), newGift.getDelFlag());
        verify(mapper).insertPmsGift(newGift);
        org.mockito.ArgumentCaptor<PmsGiftChangeAudit> audit = org.mockito.ArgumentCaptor.forClass(PmsGiftChangeAudit.class);
        verify(auditMapper).insertPmsGiftChangeAudit(audit.capture());
        assertEquals("CREATE", audit.getValue().getAction());
        assertEquals(Long.valueOf(41L), audit.getValue().getGiftId());
        assertEquals(Long.valueOf(10L), audit.getValue().getNewPoint());
    }

    @Test
    void priceChangeRequiresReasonAndRecordsServerDerivedBeforePrice() {
        PmsGift stored = gift(7L, 10L, 1);
        PmsGift change = gift(7L, 20L, 1);
        change.setAuditReason(null);
        when(mapper.selectPmsGiftByIdForUpdate(7L)).thenReturn(stored);

        assertThrows(ServiceException.class, () -> service.updatePmsGift(change));
        verify(mapper, never()).updatePmsGift(change);

        change.setAuditReason("调整活动礼物成本");
        when(mapper.updatePmsGift(change)).thenReturn(1);
        when(auditMapper.insertPmsGiftChangeAudit(any(PmsGiftChangeAudit.class))).thenReturn(1);

        assertEquals(1, service.updatePmsGift(change));
        assertEquals(Long.valueOf(10L), change.getAuditBeforePoint());
        assertEquals("调整活动礼物成本", change.getAuditReason());
        verify(mapper).updatePmsGift(change);
        org.mockito.ArgumentCaptor<PmsGiftChangeAudit> audit = org.mockito.ArgumentCaptor.forClass(PmsGiftChangeAudit.class);
        verify(auditMapper).insertPmsGiftChangeAudit(audit.capture());
        assertEquals("PRICE_CHANGE", audit.getValue().getAction());
        assertEquals(Long.valueOf(10L), audit.getValue().getOldPoint());
        assertEquals(Long.valueOf(20L), audit.getValue().getNewPoint());
    }

    @Test
    void updateRejectsNonPositivePriceBeforeMapperWrite() {
        PmsGift stored = gift(7L, 10L, 1);
        PmsGift invalid = gift(7L, -1L, 1);
        when(mapper.selectPmsGiftByIdForUpdate(7L)).thenReturn(stored);

        assertThrows(ServiceException.class, () -> service.updatePmsGift(invalid));

        verify(mapper, never()).updatePmsGift(invalid);
    }

    @Test
    void cannotEnableInvalidStoredPriceOrRestoreDeletedGiftThroughGenericEdit() {
        PmsGift disabledInvalid = gift(7L, 0L, 2);
        PmsGift enable = gift(7L, null, 1);
        when(mapper.selectPmsGiftByIdForUpdate(7L)).thenReturn(disabledInvalid);

        assertThrows(ServiceException.class, () -> service.updatePmsGift(enable));
        verify(mapper, never()).updatePmsGift(enable);

        PmsGift deleted = gift(8L, 10L, 2);
        deleted.setDelFlag(2);
        PmsGift restore = gift(8L, null, 2);
        restore.setDelFlag(0);
        when(mapper.selectPmsGiftByIdForUpdate(8L)).thenReturn(deleted);

        assertThrows(ServiceException.class, () -> service.updatePmsGift(restore));
        verify(mapper, never()).updatePmsGift(restore);
    }

    @Test
    void genericEditCannotWriteServerOwnedAuditAndDeletionFields() {
        PmsGift stored = gift(7L, 10L, 1);
        stored.setDelFlag(0);
        PmsGift update = gift(7L, null, 2);
        update.setDelFlag(0);
        update.setCreateBy("forged-creator");
        update.setCreateTime(new Date(0));
        update.setUpdateBy("forged-updater");
        update.setUpdateTime(new Date(0));
        when(mapper.selectPmsGiftByIdForUpdate(7L)).thenReturn(stored);
        when(mapper.updatePmsGift(update)).thenReturn(1);
        when(auditMapper.insertPmsGiftChangeAudit(any(PmsGiftChangeAudit.class))).thenReturn(1);

        assertEquals(1, service.updatePmsGift(update));

        assertNull(update.getDelFlag());
        assertNull(update.getCreateBy());
        assertNull(update.getCreateTime());
        assertNull(update.getUpdateBy());
        verify(mapper).updatePmsGift(update);
    }

    @Test
    void insertAndStatusChangeRequireNonblankReason() {
        PmsGift newGift = gift(null, 10L, 2);
        newGift.setAuditReason(" ");

        assertThrows(ServiceException.class, () -> service.insertPmsGift(newGift));
        verify(mapper, never()).insertPmsGift(newGift);

        PmsGift stored = gift(7L, 10L, 2);
        PmsGift enable = gift(7L, null, 1);
        enable.setAuditReason(" ");
        when(mapper.selectPmsGiftByIdForUpdate(7L)).thenReturn(stored);

        assertThrows(ServiceException.class, () -> service.updatePmsGift(enable));
        verify(mapper, never()).updatePmsGift(enable);
    }

    @Test
    void auditWriteFailureFailsTheTransactionalPriceChange() throws Exception {
        PmsGift stored = gift(7L, 10L, 1);
        PmsGift change = gift(7L, 20L, 1);
        when(mapper.selectPmsGiftByIdForUpdate(7L)).thenReturn(stored);
        when(mapper.updatePmsGift(change)).thenReturn(1);
        when(auditMapper.insertPmsGiftChangeAudit(any(PmsGiftChangeAudit.class))).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.updatePmsGift(change));
        assertTrue(PmsGiftServiceImpl.class.getMethod("updatePmsGift", PmsGift.class)
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
        verify(mapper).updatePmsGift(change);
        verify(auditMapper).insertPmsGiftChangeAudit(any(PmsGiftChangeAudit.class));
    }

    @Test
    void physicalDeleteIsDisabledUntilTheFourWayRetirementCheckIsComplete() {
        assertThrows(ServiceException.class, () -> service.deletePmsGiftById(7L));
        assertThrows(ServiceException.class, () -> service.deletePmsGiftByIds(new Long[] { 7L, 8L }));

        verify(mapper, never()).deletePmsGiftById(any(Long.class));
        verify(mapper, never()).deletePmsGiftByIds(any(Long[].class));
    }

    private PmsGift gift(Long id, Long point, Integer status) {
        PmsGift gift = new PmsGift();
        gift.setId(id);
        gift.setGiftName("测试礼物");
        gift.setPoint(point);
        gift.setStatus(status);
        gift.setDelFlag(0);
        gift.setCreateBy("admin");
        gift.setUpdateBy("admin");
        gift.setAuditReason("目录例行调整");
        return gift;
    }
}
