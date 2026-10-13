package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.CoopPerkTemplate;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCoopPerkTemplateValidationTest {
    private ICoopInviteService service;
    private ApiCoopController controller;

    @BeforeEach
    void setUp() {
        service = mock(ICoopInviteService.class);
        controller = new TestableController(201L);
        ReflectionTestUtils.setField(controller, "coopInviteService", service);
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(201L, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(
                        201L, 11L, 201L, MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
    }

    @Test
    void saveRejectsMissingOrNonPositiveRetailValue() {
        for (BigDecimal value : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-0.01")}) {
            CoopPerkTemplate body = validTemplate();
            body.setRetailValue(value);

            AjaxResult result = controller.perkTemplateSave(body);

            assertEquals(500, result.get(AjaxResult.CODE_TAG));
            assertEquals("权益零售价须为不超过99999999.99的正数，最多两位小数", result.get(AjaxResult.MSG_TAG));
        }
        verify(service, never()).savePerkTemplate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveRejectsMissingOrNonPositiveQuota() {
        for (Integer value : new Integer[]{null, 0, -1}) {
            CoopPerkTemplate body = validTemplate();
            body.setQuota(value);

            AjaxResult result = controller.perkTemplateSave(body);

            assertEquals(500, result.get(AjaxResult.CODE_TAG));
            assertEquals("请填写正整数可接待份数", result.get(AjaxResult.MSG_TAG));
        }
        verify(service, never()).savePerkTemplate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveRejectsNegativeUnitCost() {
        CoopPerkTemplate body = validTemplate();
        body.setUnitCost(new BigDecimal("-0.01"));

        AjaxResult result = controller.perkTemplateSave(body);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("成本价须为0至99999999.99，最多两位小数", result.get(AjaxResult.MSG_TAG));
        verify(service, never()).savePerkTemplate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveRejectsAmountOverflowAndExcessScale() {
        for (BigDecimal value : new BigDecimal[]{new BigDecimal("100000000"), new BigDecimal("1.001")}) {
            CoopPerkTemplate body = validTemplate();
            body.setRetailValue(value);
            AjaxResult result = controller.perkTemplateSave(body);
            assertEquals(500, result.get(AjaxResult.CODE_TAG));
        }
        for (BigDecimal value : new BigDecimal[]{new BigDecimal("100000000"), new BigDecimal("0.001")}) {
            CoopPerkTemplate body = validTemplate();
            body.setUnitCost(value);
            AjaxResult result = controller.perkTemplateSave(body);
            assertEquals(500, result.get(AjaxResult.CODE_TAG));
        }
        verify(service, never()).savePerkTemplate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveRejectsTodayOrEarlierButAcceptsTomorrow() {
        // CU-M-79:validEnd 存当日 00:00,下游 offer 按 validEnd.after(now) 判在售 ⇒ 今天到期 = 一建就过期
        CoopPerkTemplate today = validTemplate();
        today.setValidEnd(com.chengyinhub.common.utils.DateUtils.convertToStartOfDay(new java.util.Date()));

        AjaxResult rejected = controller.perkTemplateSave(today);

        assertEquals(500, rejected.get(AjaxResult.CODE_TAG));
        assertEquals("有效期至少要到明天", rejected.get(AjaxResult.MSG_TAG));
        verify(service, never()).savePerkTemplate(org.mockito.ArgumentMatchers.any());

        java.util.Calendar tomorrow = java.util.Calendar.getInstance();
        tomorrow.add(java.util.Calendar.DAY_OF_MONTH, 1);
        CoopPerkTemplate ok = validTemplate();
        ok.setValidEnd(com.chengyinhub.common.utils.DateUtils.convertToStartOfDay(tomorrow.getTime()));
        when(service.savePerkTemplate(ok)).thenReturn(72L);

        AjaxResult accepted = controller.perkTemplateSave(ok);

        assertEquals(200, accepted.get(AjaxResult.CODE_TAG));
        verify(service).savePerkTemplate(ok);
    }

    @Test
    void saveAcceptsValidRecruitableTemplate() {
        CoopPerkTemplate body = validTemplate();
        when(service.savePerkTemplate(body)).thenReturn(71L);

        AjaxResult result = controller.perkTemplateSave(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(201L, body.getMerchantId());
        verify(service).savePerkTemplate(body);
    }

    @Test
    void perksListHidesUnitCostFromOtherParty() {
        CoopInvite invite = new CoopInvite();
        invite.setId(31L);
        invite.setFromId(100L);
        invite.setToId(201L);
        invite.setToType("merchant");
        CoopPerk perk = new CoopPerk();
        perk.setName("咖啡兑换券");
        perk.setUnitCost(new BigDecimal("20.50"));
        when(service.selectCoopInviteById(31L)).thenReturn(invite);
        when(service.listPerks(31L)).thenReturn(Collections.singletonList(perk));

        ApiCoopController otherParty = new TestableController(100L);
        ReflectionTestUtils.setField(otherParty, "coopInviteService", service);
        AjaxResult result = otherParty.perksList(Collections.<String, Object>singletonMap("inviteId", 31L));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        List<?> rows = (List<?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(null, ((CoopPerk) rows.get(0)).getUnitCost());
    }

    @Test
    void perksListKeepsUnitCostForTemplateOwner() {
        CoopInvite invite = new CoopInvite();
        invite.setId(31L);
        invite.setFromId(100L);
        invite.setToId(201L);
        invite.setToType("merchant");
        CoopPerk perk = new CoopPerk();
        perk.setUnitCost(new BigDecimal("20.50"));
        when(service.selectCoopInviteById(31L)).thenReturn(invite);
        when(service.listPerks(31L)).thenReturn(Collections.singletonList(perk));

        AjaxResult result = controller.perksList(Collections.<String, Object>singletonMap("inviteId", 31L));

        assertEquals(new BigDecimal("20.50"), ((CoopPerk) ((List<?>) result.get(AjaxResult.DATA_TAG)).get(0)).getUnitCost());
    }

    private CoopPerkTemplate validTemplate() {
        CoopPerkTemplate body = new CoopPerkTemplate();
        body.setPerkType(0);
        body.setName("咖啡兑换券");
        body.setRetailValue(new BigDecimal("88.00"));
        body.setQuota(20);
        return body;
    }

    private static class TestableController extends ApiCoopController {
        private final Long memberId;
        private TestableController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }
}
