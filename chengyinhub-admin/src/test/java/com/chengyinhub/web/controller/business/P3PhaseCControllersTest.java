package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.MerchantCustomerProfile;
import com.chengyinhub.business.domain.SettlementBatch;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.dto.SettlementGenerateDTO;
import com.chengyinhub.business.domain.dto.WithdrawalReviewDTO;
import com.chengyinhub.business.domain.vo.MerchantCrmProfileReconcileVO;
import com.chengyinhub.business.service.IMerchantCrmService;
import com.chengyinhub.business.service.ISettlementService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class P3PhaseCControllersTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void crmControllerProvidesMerchantScopedListAndAggregate() {
        IMerchantCrmService service = mock(IMerchantCrmService.class);
        MerchantCrmController controller = new MerchantCrmController();
        ReflectionTestUtils.setField(controller, "merchantCrmService", service);
        when(service.selectCustomerList(any(MerchantCustomerProfile.class)))
                .thenReturn(Collections.singletonList(new MerchantCustomerProfile()));
        when(service.aggregateMerchantCustomers(9L)).thenReturn(new MerchantCrmProfileReconcileVO());
        bindPageRequest();

        TableDataInfo list = controller.list(new MerchantCustomerProfile());
        AjaxResult aggregate = controller.aggregate(9L);

        assertEquals(1, list.getRows().size());
        assertEquals(200, aggregate.get(AjaxResult.CODE_TAG));
        verify(service).selectCustomerList(any(MerchantCustomerProfile.class));
        verify(service).aggregateMerchantCustomers(9L);
    }

    @Test
    void settlementControllerProvidesBatchDetailAndSealsGenerate() {
        ISettlementService service = mock(ISettlementService.class);
        SettlementController controller = new SettlementController();
        ReflectionTestUtils.setField(controller, "settlementService", service);
        when(service.selectBatchList(any(SettlementBatch.class))).thenReturn(Collections.singletonList(new SettlementBatch()));
        when(service.selectDetailList(any(SettlementDetail.class))).thenReturn(Collections.singletonList(new SettlementDetail()));
        bindPageRequest();

        TableDataInfo batches = controller.list(new SettlementBatch());
        TableDataInfo details = controller.detailList(new SettlementDetail());
        AjaxResult generate = controller.generate(new SettlementGenerateDTO());

        assertEquals(1, batches.getRows().size());
        assertEquals(1, details.getRows().size());
        // A3-1(P0):generate 已封口(手工生成结算批次停用)⇒ 返回 403 且不触达 service。
        assertEquals(403, generate.get(AjaxResult.CODE_TAG));
        verify(service).selectBatchList(any(SettlementBatch.class));
        verify(service).selectDetailList(any(SettlementDetail.class));
        verify(service, never()).generateBatch(any(SettlementGenerateDTO.class));
    }

    @Test
    void withdrawalControllerUsesGuardedReviewEndpoint() {
        IUmsMemberWithdrawalService service = mock(IUmsMemberWithdrawalService.class);
        UmsMemberWithdrawalController controller = new UmsMemberWithdrawalController();
        ReflectionTestUtils.setField(controller, "umsMemberWithdrawalService", service);
        loginAs(1L);
        // A1-2 残余(问责):review 现以登录态 getUserId() 记审批人到 audit_by(不再传 null)。
        when(service.reviewWithdrawal(any(WithdrawalReviewDTO.class), eq(1L))).thenReturn(1);

        AjaxResult result = controller.review(new WithdrawalReviewDTO());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(service).reviewWithdrawal(any(WithdrawalReviewDTO.class), eq(1L));
    }

    /** 装一个带指定 userId 的登录态,供 review 的 getUserId() 读取。 */
    private void loginAs(Long userId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        LoginUser loginUser = new LoginUser(user, new HashSet<>());
        loginUser.setUserId(userId);   // 2 参构造不设 userId 字段,而 getUserId() 读它 → 必须显式设
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private void bindPageRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("pageNum", "1");
        request.setParameter("pageSize", "10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
