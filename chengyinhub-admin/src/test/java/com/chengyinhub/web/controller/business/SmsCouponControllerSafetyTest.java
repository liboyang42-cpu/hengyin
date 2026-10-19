package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.dto.CouponRuleChangeDTO;
import com.chengyinhub.business.service.ICouponRuleGovernanceService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.impl.CouponRuleGovernanceServiceImpl;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.mapper.SmsCouponRuleVersionMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A3-4(P0) + F0572:优惠券运行时统计计数器与非规则字段封口。
 *
 * <p>发行/领取/使用量由领取与核销的原子自增维护。通用 PUT 现在只收白名单规则字段,
 * 请求体里出现 receiveCount/useCount/status/scopeType 等字段 → 整单拒绝,一行不写。
 */
class SmsCouponControllerSafetyTest {

    @BeforeEach
    void login() {
        SysUser user = new SysUser();
        user.setUserId(7L);
        LoginUser loginUser = new LoginUser(7L, null, user, new java.util.HashSet<>());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, java.util.Collections.emptyList()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void editRejectsRuntimeStatCountersAndNonWhitelistedFields() throws Exception {
        SmsCouponMapper coupons = mock(SmsCouponMapper.class);
        SmsCouponRuleVersionMapper versions = mock(SmsCouponRuleVersionMapper.class);
        CouponRuleGovernanceServiceImpl governance = new CouponRuleGovernanceServiceImpl();
        ReflectionTestUtils.setField(governance, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(governance, "smsCouponHistoryMapper", mock(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(governance, "ruleVersionMapper", versions);
        ISmsCouponService legacy = mock(ISmsCouponService.class);
        SmsCouponController controller = new SmsCouponController();
        ReflectionTestUtils.setField(controller, "smsCouponService", legacy);
        ReflectionTestUtils.setField(controller, "couponRuleGovernanceService", (ICouponRuleGovernanceService) governance);

        CouponRuleChangeDTO submitted = new ObjectMapper().readValue(
                "{\"id\":5,\"expectedVersion\":1,\"name\":\"新名\",\"receiveCount\":999,\"useCount\":999,\"status\":1}",
                CouponRuleChangeDTO.class);

        AjaxResult result = controller.edit(submitted);

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("receiveCount"));
        verify(coupons, never()).updateCouponRuleCas(any(), any());
        verify(coupons, never()).updateSmsCoupon(any());
        verifyNoInteractions(legacy);
    }

    @Test
    void editWithStaleVersionReturnsConflictWithCurrentVersion() {
        SmsCouponMapper coupons = mock(SmsCouponMapper.class);
        CouponRuleGovernanceServiceImpl governance = new CouponRuleGovernanceServiceImpl();
        ReflectionTestUtils.setField(governance, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(governance, "smsCouponHistoryMapper", mock(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(governance, "ruleVersionMapper", mock(SmsCouponRuleVersionMapper.class));
        SmsCouponController controller = new SmsCouponController();
        ReflectionTestUtils.setField(controller, "couponRuleGovernanceService", (ICouponRuleGovernanceService) governance);
        SmsCoupon current = new SmsCoupon();
        current.setId(5L);
        current.setStatus(1);
        current.setDelFlag(0);
        current.setRuleVersion(3);
        when(coupons.selectCouponRuleStateForUpdate(5L)).thenReturn(current);

        CouponRuleChangeDTO change = new CouponRuleChangeDTO();
        change.setId(5L);
        change.setExpectedVersion(2);
        change.setName("新名");
        AjaxResult result = controller.edit(change);

        assertEquals(409, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(3, data.get("currentVersion"));
        verify(coupons, never()).updateCouponRuleCas(any(), any());
    }
}
