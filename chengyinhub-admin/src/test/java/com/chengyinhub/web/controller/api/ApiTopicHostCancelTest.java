package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [C8-05] 主题主办方取消主题:个人主办按本人、商家主办按店主(员工是操作人)进退款核心。
 * 金额、幂等、已核销口径在 HostWeatherCancelTest 里钉;这里只钉入口把「谁是主办、谁在操作」传对。
 */
class ApiTopicHostCancelTest
{
    private static final Long ME = 19L;
    private ApiTopicController controller;
    private ITeamGroupService refundCore;
    private IMerchantAccessService merchantAccess;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiTopicController());
        refundCore = mock(ITeamGroupService.class);
        merchantAccess = mock(IMerchantAccessService.class);
        doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "teamGroupService", refundCore);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccess);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
    }

    @Test
    void personalHostCancelsAsSelf()
    {
        when(refundCore.cancelTopicByHost(42L, ME, ME, "台风停运")).thenReturn(new ActivityCancelResult(3, 0, 0));

        AjaxResult result = controller.topicCancel("42", " 台风停运 ", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(refundCore).cancelTopicByHost(42L, ME, ME, "台风停运");
    }

    @Test
    void merchantStaffCancelsOnBehalfOfOwner()
    {
        when(merchantAccess.require(ME, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(ME, 5L, 77L, "MERCHANT_MANAGER"));
        when(refundCore.cancelTopicByHost(42L, 77L, ME, "场地停电")).thenReturn(new ActivityCancelResult(1, 0, 0));

        AjaxResult result = controller.topicCancel("42", "场地停电", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(refundCore).cancelTopicByHost(42L, 77L, ME, "场地停电");
    }

    @Test
    void blankReasonIsRejectedBeforeAnyMoneyMoves()
    {
        AjaxResult result = controller.topicCancel("42", "  ", null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(refundCore);
    }

    @SuppressWarnings("unchecked")
    @Test
    void previewReportsPaidPlayersForTheConfirmDialog()
    {
        when(refundCore.countPaidPlayersForTopicCancel(42L, ME)).thenReturn(6);
        when(refundCore.topicCancellationEpoch(42L,ME)).thenReturn(3L);

        AjaxResult result = controller.topicCancelPreview("42", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(6, ((Map<String, Object>) result.get(AjaxResult.DATA_TAG)).get("paidPlayers"));
        assertEquals(3L,((Map<String,Object>)result.get(AjaxResult.DATA_TAG)).get("cancellationEpoch"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitOriginalEpochIsPassedUnchangedAndReturnedWithOriginalFullCounts() {
        when(refundCore.cancelTopicByHost(42L,ME,ME,"原取消",2L)).thenReturn(new ActivityCancelResult(4,1,2));
        AjaxResult result=controller.topicCancel("42","原取消",null,2L);
        assertEquals(200,result.get(AjaxResult.CODE_TAG));
        verify(refundCore).cancelTopicByHost(42L,ME,ME,"原取消",2L);
        Map<String,Object> original=(Map<String,Object>)result.get(AjaxResult.DATA_TAG);
        assertEquals("DONE",original.get("cancellationState"));assertEquals(2L,original.get("cancellationEpoch"));
        assertEquals(4,original.get("refundedOrders"));assertEquals(1,original.get("manualOrders"));assertEquals(2,original.get("closedPendingOrders"));
    }
}
