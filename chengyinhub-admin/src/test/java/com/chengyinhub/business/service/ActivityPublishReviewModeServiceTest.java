package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 2026-10-20 分级审核(推翻 M0-6):publishActivity 的 preReview 决定落库状态与审核单。 */
class ActivityPublishReviewModeServiceTest {
    private CmsActivityServiceImpl service;
    private CmsActivityMapper activities;
    private IAuditTaskService auditTaskService;
    private SettlementDebtLedger debtLedger;

    @BeforeEach
    void setUp() {
        service = new CmsActivityServiceImpl();
        activities = mock(CmsActivityMapper.class);
        auditTaskService = mock(IAuditTaskService.class);
        debtLedger = mock(SettlementDebtLedger.class);
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(MemberIdentityService.class));
        ReflectionTestUtils.setField(service, "settlementDebtLedger", debtLedger);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mock(OmsTicketMapper.class));
        ReflectionTestUtils.setField(service, "auditTaskService", auditTaskService);
        when(activities.insertCmsActivity(any())).thenAnswer(call -> {
            call.getArgument(0, CmsActivity.class).setId(9L); return 1;
        });
    }

    private static CmsActivityPublishRequest freeActivity() {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setName("免费夜跑");
        r.setStartDate(new Date(1800000000000L));
        r.setEndDate(new Date(1800086400000L));
        return r;
    }

    @Test
    void postReviewPublishesImmediatelyWithoutAuditTask() {
        assertEquals(Long.valueOf(9L), service.publishActivity(freeActivity(), 88L, "test", false));

        ArgumentCaptor<CmsActivity> saved = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activities).insertCmsActivity(saved.capture());
        assertEquals(Long.valueOf(1L), saved.getValue().getStatus());
        assertEquals(Long.valueOf(1L), saved.getValue().getPublishStatus());
        verify(auditTaskService, never()).submit(anyString(), any(), any(), anyString(), any());
        verify(debtLedger).requireNoOverdueDebt(88L);
    }

    @Test
    void preReviewStaysPendingWithActivityAuditTask() {
        assertEquals(Long.valueOf(9L), service.publishActivity(freeActivity(), 88L, "test", true));

        ArgumentCaptor<CmsActivity> saved = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activities).insertCmsActivity(saved.capture());
        assertEquals(Long.valueOf(0L), saved.getValue().getStatus());
        assertEquals(Long.valueOf(0L), saved.getValue().getPublishStatus());
        verify(auditTaskService).submit(eq(AuditTask.BIZ_ACTIVITY), eq(9L), eq(88L), eq("user_publish"), anyString());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), eq("post_review_flag"), any());
    }
}
