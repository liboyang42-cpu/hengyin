package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.dto.MerchantRegistrationDTO;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantRegistrationStateGateTest {
    private static final long MEMBER = 901L;

    @Test
    void newApplicantSubmitsPendingStateWithoutReceivingMerchantActivation() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.lockMemberById(MEMBER)).thenReturn(MEMBER);
        when(merchants.insertMmsMerchant(any())).thenReturn(1);
        ApiMerchantController controller = controller(merchants);
        ReflectionTestUtils.setField(controller, "umsMemberMapper", members);
        ReflectionTestUtils.setField(controller, "merchantOperatorMapper", mock(MerchantOperatorMapper.class));
        MerchantRegistrationDTO request = request();
        request.setId(null);

        assertEquals(200, controller.merchantRegistration(request).get(AjaxResult.CODE_TAG));

        ArgumentCaptor<MmsMerchant> saved = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(merchants).insertMmsMerchant(saved.capture());
        assertEquals(0L, saved.getValue().getStatus());
        assertEquals(0L, saved.getValue().getAccountStatus());
        assertEquals(MEMBER, saved.getValue().getMemberId());
    }

    @Test
    void disabledMerchantCannotRewriteSuspensionAsPendingApplication() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant disabled = existing(1L, 2L);
        when(merchants.selectMmsMerchantById(11L)).thenReturn(disabled);
        ApiMerchantController controller = controller(merchants);

        AjaxResult result = controller.merchantRegistration(request());

        verify(merchants, never()).updateMmsMerchant(any());
        verify(merchants, never()).updateOwnerRegistrationProfile(any(), any());
        org.junit.jupiter.api.Assertions.assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void approvedMerchantMustUseProfileFlowInsteadOfReopeningApplication() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        when(merchants.selectMmsMerchantById(11L)).thenReturn(existing(1L, 1L));
        ApiMerchantController controller = controller(merchants);

        AjaxResult result = controller.merchantRegistration(request());

        verify(merchants, never()).updateMmsMerchant(any());
        verify(merchants, never()).updateOwnerRegistrationProfile(any(), any());
        org.junit.jupiter.api.Assertions.assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void rejectedApplicationCanResubmitWithoutChangingAccountStatus() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant rejected = existing(2L, 0L);
        when(merchants.selectMmsMerchantById(11L)).thenReturn(rejected);
        when(merchants.updateOwnerRegistrationProfile(any(), eq(MEMBER))).thenReturn(1);
        ApiMerchantController controller = controller(merchants);

        AjaxResult result = controller.merchantRegistration(request());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<MmsMerchant> update = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(merchants).updateOwnerRegistrationProfile(update.capture(), eq(MEMBER));
        verify(merchants, never()).updateMmsMerchant(any());
        assertEquals(Long.valueOf(0L), update.getValue().getStatus());
        assertEquals("", update.getValue().getReson());
        assertNull(update.getValue().getAccountStatus(), "用户写链不得改后台账号状态");
        assertNull(update.getValue().getMemberId(), "自助资料更新不得携带 owner 字段，避免并发转让后夺回归属");
    }

    @Test
    void activeMerchantOperatorCannotStartOwnMerchantApplication() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operators = mock(MerchantOperatorMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.lockMemberById(MEMBER)).thenReturn(MEMBER);
        MerchantOperator active = new MerchantOperator();
        active.setId(41L);
        active.setOperatorMemberId(MEMBER);
        active.setStatus(MerchantOperator.STATUS_ACTIVE);
        when(operators.selectActiveByMemberId(MEMBER)).thenReturn(active);
        ApiMerchantController controller = controller(merchants);
        ReflectionTestUtils.setField(controller, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(controller, "umsMemberMapper", members);
        MerchantRegistrationDTO request = request();
        request.setId(null);

        AjaxResult result = controller.merchantRegistration(request);

        org.junit.jupiter.api.Assertions.assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchants, never()).insertMmsMerchant(any());
    }

    private ApiMerchantController controller(IMmsMerchantService merchants) {
        ApiMerchantController controller = new ApiMerchantController() {
            @Override
            public Long getAppUserId() {
                return MEMBER;
            }
        };
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        // RUN-52 发布者实名:本用例测的是首次入驻的状态机(待审/不激活),实名闸在这里放行
        // (mock 的 checkRegistered 返回 null = 已登记)。
        ReflectionTestUtils.setField(controller, "memberIdentityService",
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        return controller;
    }

    private MmsMerchant existing(Long status, Long accountStatus) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(11L);
        merchant.setMemberId(MEMBER);
        merchant.setStatus(status);
        merchant.setAccountStatus(accountStatus);
        merchant.setDelFlag(0);
        merchant.setLocationLat(31.2D);
        merchant.setLocationLng(121.4D);
        merchant.setLocationVerified(1);
        return merchant;
    }

    private MerchantRegistrationDTO request() {
        MerchantRegistrationDTO request = new MerchantRegistrationDTO();
        request.setId(11L);
        request.setName("城瘾咖啡");
        request.setAddress("黄浦路 1 号");
        request.setLocationLat(31.2D);
        request.setLocationLng(121.4D);
        return request;
    }
}
