package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiOfficialMerchantInviteTest {
    private static final Long OFFICIAL = 100092L;
    private ISysConfigService configService;
    private ICoopInviteService coopInviteService;
    private ApiOfficialEventController controller;

    @BeforeEach
    void setUp() {
        configService = mock(ISysConfigService.class);
        coopInviteService = mock(ICoopInviteService.class);
        controller = new TestableController(OFFICIAL);
        ReflectionTestUtils.setField(controller, "configService", configService);
        ReflectionTestUtils.setField(controller, "coopInviteService", coopInviteService);
    }

    @Test
    void inviteMerchants_nonWhitelistedAccount_isRejectedAtOfficialGate() {
        when(configService.selectConfigByKey("official_publish_whitelist")).thenReturn("999, 888");

        AjaxResult result = controller.inviteMerchants(request());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("官方发布权限"),
                "错误必须指认 official_publish_whitelist 权限闸");
        verify(coopInviteService, never()).createOfficialMerchantInvites(any(), any());
    }

    @Test
    void inviteMerchants_whitelistedAccount_dispatchesBatch() {
        when(configService.selectConfigByKey("official_publish_whitelist")).thenReturn("100092");
        when(coopInviteService.createOfficialMerchantInvites(any(), any()))
                .thenReturn(Arrays.asList(11L, 12L));

        AjaxResult result = controller.inviteMerchants(request());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(coopInviteService).createOfficialMerchantInvites(org.mockito.ArgumentMatchers.eq(OFFICIAL),
                org.mockito.ArgumentMatchers.argThat(request -> Long.valueOf(7L).equals(request.getTopicId())
                        && Long.valueOf(9L).equals(request.getNodeId())
                        && Integer.valueOf(1).equals(request.getShareMode())
                        && new BigDecimal("12.5").compareTo(request.getShareRate()) == 0));
    }

    @Test
    void merchantInvites_notActiveMerchant_returnsStableErrorCode() {
        when(coopInviteService.listOfficialMerchantInvites(OFFICIAL))
                .thenThrow(new com.chengyinhub.business.service.NotActiveMerchantException("任意文案都行"));

        AjaxResult result = controller.merchantInvites();

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertEquals("NOT_ACTIVE_MERCHANT", result.get("errorCode"), "前端只认机器码,不认文案");
    }

    @Test
    void merchantInvites_otherStateFailure_hasNoNotMerchantCode() {
        when(coopInviteService.listOfficialMerchantInvites(OFFICIAL))
                .thenThrow(new IllegalStateException("仅审核通过且启用的商家可参与合作邀约"));

        AjaxResult result = controller.merchantInvites();

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals(null, result.get("errorCode"), "负控:同样文案的普通故障不得被标成「不是商家」");
    }

    private OfficialMerchantInviteRequest request() {
        OfficialMerchantInviteRequest request = new OfficialMerchantInviteRequest();
        request.setTopicId(7L);
        request.setNodeId(9L);
        request.setMerchantIds(Arrays.asList(201L, 202L));
        request.setMessage("请承接");
        request.setShareMode(1);
        request.setShareRate(new BigDecimal("12.5"));
        return request;
    }

    private static class TestableController extends ApiOfficialEventController {
        private final Long memberId;
        private TestableController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }
}
