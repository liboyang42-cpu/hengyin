package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.MerchantOperatorInviteMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MERCHANT-14:入驻审核结果(通过/驳回)给申请人发一条站内信;同状态重复保存不发;通知失败不影响审核。 */
class MerchantReviewNoticeTest {
    private static final long MERCHANT = 77L;
    private static final long OWNER = 900L;

    private MmsMerchantMapper merchants;
    private INotifyService notify;
    private MmsMerchantServiceImpl service;

    @BeforeEach
    void setUp() {
        merchants = mock(MmsMerchantMapper.class);
        notify = mock(INotifyService.class);
        service = new MmsMerchantServiceImpl();
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchants);
        ReflectionTestUtils.setField(service, "merchantOperatorMapper", mock(MerchantOperatorMapper.class));
        ReflectionTestUtils.setField(service, "merchantOperatorInviteMapper", mock(MerchantOperatorInviteMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberMapper", mock(UmsMemberMapper.class));
        ReflectionTestUtils.setField(service, "roleService", mock(IRoleService.class));
        ReflectionTestUtils.setField(service, "notifyService", notify);
    }

    private MmsMerchant pending() {
        MmsMerchant row = new MmsMerchant();
        row.setId(MERCHANT);
        row.setMemberId(OWNER);
        row.setStatus(0L);
        row.setAccountStatus(0L);
        row.setDelFlag(0);
        return row;
    }

    @Test
    void rejectionThroughTheEditPathNotifiesTheApplicantWithTheReason() {
        when(merchants.selectMmsMerchantById(MERCHANT)).thenReturn(pending());
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(pending());
        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT);
        patch.setStatus(2L);
        patch.setReson("营业执照不清晰");
        when(merchants.updateMmsMerchant(patch)).thenReturn(1);

        assertEquals(1, service.updateMmsMerchant(patch));

        verify(notify).notifyMerchantReview(OWNER, false, "营业执照不清晰");
    }

    @Test
    void approvalNotifiesWithoutAReason() {
        service.notifyReviewDecision(pending(), OWNER, 1L, "ignored");
        verify(notify).notifyMerchantReview(OWNER, true, null);
    }

    @Test
    void sameStatusOrNonDecisionStatusSendsNothing() {
        MmsMerchant approved = pending();
        approved.setStatus(1L);
        service.notifyReviewDecision(approved, OWNER, 1L, null);
        service.notifyReviewDecision(pending(), OWNER, 0L, null);
        service.notifyReviewDecision(pending(), null, 2L, "x");
        verify(notify, never()).notifyMerchantReview(any(), anyBoolean(), any());
    }

    @Test
    void failedWriteSendsNothing() {
        when(merchants.selectMmsMerchantById(MERCHANT)).thenReturn(pending());
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(pending());
        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT);
        patch.setStatus(2L);
        when(merchants.updateMmsMerchant(patch)).thenReturn(0);

        service.updateMmsMerchant(patch);

        verify(notify, never()).notifyMerchantReview(any(), anyBoolean(), any());
    }

    @Test
    void notifierFailureNeverBreaksTheReview() {
        when(notify.notifyMerchantReview(eq(OWNER), anyBoolean(), any())).thenThrow(new IllegalStateException("down"));
        assertDoesNotThrow(() -> service.notifyReviewDecision(pending(), OWNER, 2L, "x"));
    }

    @Test
    void missingNotifierIsSkipped() {
        ReflectionTestUtils.setField(service, "notifyService", null);
        assertDoesNotThrow(() -> service.notifyReviewDecision(pending(), OWNER, 2L, "x"));
    }
}
