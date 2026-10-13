package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 商家出示游戏打卡码：动态一次性串，不是海报静态 hex。 */
class ApiChapterMerchantNodeLiveCheckinCodeControllerTest {

    private static final long PRINCIPAL = 8801L;
    private static final long MERCHANT_OWNER = 8802L;
    private static final String LIVE = "v1.77.play_checkin.999.1.sig";

    private IPlayCheckinCodeService liveCodes;
    private IMerchantAccessService merchantAccessService;
    private TestableController controller;

    @BeforeEach
    void setUp() {
        liveCodes = mock(IPlayCheckinCodeService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 91L, MERCHANT_OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        controller = new TestableController(PRINCIPAL);
        ReflectionTestUtils.setField(controller, "playCheckinCodeService", liveCodes);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveCheckinCodeReturnsSignedTokenAndTtl() {
        when(liveCodes.issue(MERCHANT_OWNER, 77L)).thenReturn(
                new IPlayCheckinCodeService.Issued(77L, "毛孩子咖啡", 60_000L,
                        "https://oss/live-qr.png", LIVE));

        AjaxResult r = controller.liveCheckinCode(77L);

        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        assertEquals(LIVE, data.get("code"));
        assertEquals("https://oss/live-qr.png", data.get("qrcodeUrl"));
        assertEquals(77L, data.get("nodeId"));
        assertEquals("毛孩子咖啡", data.get("nodeName"));
        assertEquals(60_000L, data.get("ttlMs"));
        assertTrue(String.valueOf(data.get("code")).startsWith("v1."));
        assertFalse(String.valueOf(data.get("code")).matches("[0-9a-f]{32}"),
                "出示码不得是海报静态 hex");
    }

    @Test
    void anonymousRejectedBeforeTouchingService() {
        TestableController anon = new TestableController(0L);
        AjaxResult r = anon.liveCheckinCode(77L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void serviceGateBecomesReadableError() {
        when(liveCodes.issue(eq(MERCHANT_OWNER), eq(78L)))
                .thenThrow(new IllegalStateException("点位不存在或无权操作"));
        AjaxResult r = controller.liveCheckinCode(78L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("点位不存在或无权操作", r.get(AjaxResult.MSG_TAG));
    }

    private static class TestableController extends ApiChapterMerchantNodeController {
        private final Long appUserId;

        private TestableController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}
