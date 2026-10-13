package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [RV-p6line F3] 举报判「不成立」只恢复举报这件事,不能把随主题联动隐藏(status=3)的同步帖翻回公开:
 * 主题还在下架,帖子按 id 走 /info 就能看到。主题恢复公开时由 restoreTopicSyncPosts 统一放回。
 */
class AuditSquareReportDismissKeepsTopicHideTest {
    private AuditTaskServiceImpl service;
    private IUmsCreativeSquareService squareService;
    private final UmsCreativeSquare current = new UmsCreativeSquare();

    @BeforeEach
    void setUp() {
        AuditTaskMapper taskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        squareService = mock(IUmsCreativeSquareService.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "umsCreativeSquareService", squareService);
        AuditTask task = new AuditTask();
        task.setId(3L);
        task.setBizType(AuditTask.BIZ_SQUARE);
        task.setBizId(91L);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        when(taskMapper.selectAuditTaskById(3L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        current.setId(91L);
        when(squareService.selectUmsCreativeSquareById(91L)).thenReturn(current);
    }

    private UmsCreativeSquare dismissReport() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_APPROVED);
        service.review(3L, 8L, decision);
        ArgumentCaptor<UmsCreativeSquare> cap = ArgumentCaptor.forClass(UmsCreativeSquare.class);
        verify(squareService).updateUmsCreativeSquare(cap.capture());
        return cap.getValue();
    }

    @Test
    void dismissedReportLeavesTopicHiddenSyncPostHidden() {
        current.setStatus(3);
        UmsCreativeSquare written = dismissReport();
        assertNull(written.getStatus(), "status=3 是主题联动隐藏,举报不成立不能改它");
        assertEquals(Integer.valueOf(1), written.getCheckStatus());
    }

    @Test
    void dismissedReportStillRestoresAnOrdinaryPost() {
        current.setStatus(0);
        assertEquals(Integer.valueOf(1), dismissReport().getStatus());
    }
}
