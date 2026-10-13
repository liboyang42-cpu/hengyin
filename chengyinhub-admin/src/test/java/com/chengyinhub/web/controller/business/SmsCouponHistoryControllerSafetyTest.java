package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.CouponReverseRequest;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 券撤销核销后台入口:操作者必须来自登录态、原因必填、CAS 空转要可读、审计流水原样透出。
 */
class SmsCouponHistoryControllerSafetyTest {

    private ISmsCouponHistoryService couponHistoryService;
    private SmsCouponHistoryController controller;

    @BeforeEach
    void setUp() {
        couponHistoryService = mock(ISmsCouponHistoryService.class);
        controller = new SmsCouponHistoryController();
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", couponHistoryService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 装一个带指定平台账号 id 的登录态 —— 撤销入口要把操作者写进审计,必须有真 id。 */
    private void loginAs(Long userId, String... permissions) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        // ⚠️ 必须走 4 参构造:LoginUser.getUserId() 读的是自己的 userId 字段,
        // 2 参构造不填它(SecurityUtils.getUserId() 会拿到 null,撤销审计当场缺操作者)。
        LoginUser loginUser = new LoginUser(userId, null, user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private CouponReverseRequest request(String reason) {
        CouponReverseRequest request = new CouponReverseRequest();
        request.setReason(reason);
        return request;
    }

    /**
     * ★ RED:撤销入口必须把 **平台账号 id** 一起送进服务 —— 少了它,审计答不出「谁撤的」。
     * 负控:把 controller 里 couponHistoryService.reverseRedemption(...) 那一行摘掉/换掉
     * ⇒ 本条当场红(Mockito verify 落空)。
     */
    @Test
    void reverseWiresServiceWithLoggedInOperatorAndReason() {
        loginAs(77L);
        when(couponHistoryService.reverseRedemption(301L, "店员扫错券", 77L)).thenReturn(true);

        AjaxResult result = controller.reverse(301L, request("店员扫错券"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), "正常撤销应报成功,实际:" + result.get(AjaxResult.MSG_TAG));
        verify(couponHistoryService).reverseRedemption(301L, "店员扫错券", 77L);
    }

    /** 原因必填(留痕本体);空白原因不得碰服务。 */
    @Test
    void reverseBlankReasonRejectedBeforeTouchingService() {
        loginAs(77L);

        AjaxResult result = controller.reverse(301L, request("   "));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("撤销原因不能为空", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(couponHistoryService);
    }

    /** 超长原因在入口拦下,别等审计 reason 列截断/抛错。 */
    @Test
    void reverseOverlongReasonRejected() {
        loginAs(77L);

        AjaxResult result = controller.reverse(301L, request(String.join("", Collections.nCopies(256, "误"))));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(couponHistoryService, never()).reverseRedemption(anyLong(), anyString(), anyLong());
    }

    /** 流水 id 缺失/非法 → 拒,且不查库。 */
    @Test
    void reverseBadIdRejected() {
        loginAs(77L);

        assertEquals(500, controller.reverse(null, request("店员扫错券")).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.reverse(0L, request("店员扫错券")).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(couponHistoryService);
    }

    /**
     * CAS 落空(券当前不是已核销态)必须变成可读错误 —— 幂等,但**不报成功**:
     * 运维需要知道这次点击没有产生新动作。
     */
    @Test
    void reverseAlreadyReversedReportsConflict() {
        loginAs(77L);
        when(couponHistoryService.reverseRedemption(eq(301L), anyString(), eq(77L))).thenReturn(false);

        AjaxResult result = controller.reverse(301L, request("重复点击"));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("不是已核销状态"),
                "应提示当前非已核销态,实际:" + result.get(AjaxResult.MSG_TAG));
    }

    /** 撤销入口的读回面:不可变审计流水(核销/撤销)原样透出。 */
    @Test
    void auditTrailDelegatesToService() {
        BusinessAuditEvent reverseEvent = new BusinessAuditEvent();
        reverseEvent.setAction("COUPON_REDEEM_REVERSE");
        reverseEvent.setReason("店员扫错券");
        reverseEvent.setCreateTime(new Date());
        when(couponHistoryService.listAuditTrail(301L))
                .thenReturn(Collections.singletonList(reverseEvent));

        AjaxResult result = controller.auditTrail(301L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        List<?> data = (List<?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, data.size());
        assertEquals("COUPON_REDEEM_REVERSE", ((BusinessAuditEvent) data.get(0)).getAction());
    }
}
