package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.PmsGift;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IPmsGiftService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsCrowdfundingService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 礼物目录的“停用”必须在 C 端赠礼入口实际生效，不能只停在后台列表展示。
 */
class ApiCrowdfundingGiftAvailabilityTest {

    private TestableApiCrowdfundingController controller;
    private IPmsGiftService giftService;
    private IUmsCreativeSquareService squareService;
    private IUmsMemberService memberService;
    private IUmsCrowdfundingService crowdfundingService;
    private IUmsMemberPointsDetailService pointsDetailService;

    @BeforeEach
    void setUp() {
        controller = new TestableApiCrowdfundingController();
        giftService = mock(IPmsGiftService.class);
        squareService = mock(IUmsCreativeSquareService.class);
        memberService = mock(IUmsMemberService.class);
        crowdfundingService = mock(IUmsCrowdfundingService.class);
        pointsDetailService = mock(IUmsMemberPointsDetailService.class);
        ReflectionTestUtils.setField(controller, "pmsGiftService", giftService);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", squareService);
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
        ReflectionTestUtils.setField(controller, "umsCrowdfundingService", crowdfundingService);
        ReflectionTestUtils.setField(controller, "umsMemberPointsDetailService", pointsDetailService);
    }

    @Test
    void giftGiveUsesGiftIdInsteadOfActivityId() {
        PmsGift gift = gift(1, 0, 10L);
        when(giftService.selectPmsGiftById(23L)).thenReturn(gift);
        when(squareService.selectUmsCreativeSquareById(17L)).thenReturn(square(1, 0, 1));

        UmsMember member = new UmsMember();
        member.setId(1L);
        member.setPoint(100L);
        when(memberService.selectUmsMemberById(1L)).thenReturn(member);
        when(crowdfundingService.insertUmsCrowdfunding(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(pointsDetailService.insertUmsMemberPointsDetail(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        AjaxResult result = controller.giftGive("17", "23", "祝活动顺利");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(giftService).selectPmsGiftById(23L);
        verify(giftService, never()).selectPmsGiftById(17L);
        verify(memberService).deductPoints(1L, 10);
    }

    @Test
    void hiddenGiftFailsClosedBeforeAnyPointOperation() {
        when(giftService.selectPmsGiftById(23L)).thenReturn(gift(2, 0, 10L));

        AjaxResult result = controller.giftGive("17", "23", "隐藏礼物");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("该礼物已下架", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(memberService, crowdfundingService, pointsDetailService);
    }

    @Test
    void deletedOrInvalidPriceGiftFailsClosedBeforeAnyPointOperation() {
        when(giftService.selectPmsGiftById(23L)).thenReturn(gift(1, 2, 10L));

        AjaxResult deleted = controller.giftGive("17", "23", "已删除礼物");

        assertEquals(500, deleted.get(AjaxResult.CODE_TAG));
        assertEquals("该礼物已下架", deleted.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(memberService, crowdfundingService, pointsDetailService);

        when(giftService.selectPmsGiftById(24L)).thenReturn(gift(1, 0, 0L));
        AjaxResult invalidPrice = controller.giftGive("17", "24", "无效积分");

        assertEquals(500, invalidPrice.get(AjaxResult.CODE_TAG));
        assertEquals("礼物积分配置无效", invalidPrice.get(AjaxResult.MSG_TAG));
        verify(memberService, never()).selectUmsMemberById(anyLong());
    }

    @Test
    void missingHiddenDeletedOrNonCrowdfundingTargetFailsBeforeAnyPointOperation() {
        when(giftService.selectPmsGiftById(23L)).thenReturn(gift(1, 0, 10L));
        when(squareService.selectUmsCreativeSquareById(17L)).thenReturn(null);
        when(squareService.selectUmsCreativeSquareById(18L)).thenReturn(square(2, 0, 1));
        when(squareService.selectUmsCreativeSquareById(19L)).thenReturn(square(1, 2, 1));
        when(squareService.selectUmsCreativeSquareById(20L)).thenReturn(square(1, 0, 2));

        for (String activeId : new String[] { "17", "18", "19", "20" }) {
            AjaxResult result = controller.giftGive(activeId, "23", "无效众筹目标");
            assertEquals(500, result.get(AjaxResult.CODE_TAG));
            assertEquals("该众筹活动不可赠送", result.get(AjaxResult.MSG_TAG));
        }

        verifyNoInteractions(memberService, crowdfundingService, pointsDetailService);
    }

    private PmsGift gift(Integer status, Integer delFlag, Long point) {
        PmsGift gift = new PmsGift();
        gift.setId(23L);
        gift.setStatus(status);
        gift.setDelFlag(delFlag);
        gift.setPoint(point);
        return gift;
    }

    private UmsCreativeSquare square(Integer status, Integer delFlag, Integer isCrowdfunding) {
        UmsCreativeSquare square = new UmsCreativeSquare();
        square.setStatus(status);
        square.setDelFlag(delFlag);
        square.setIsCrowdfunding(isCrowdfunding);
        return square;
    }

    private static class TestableApiCrowdfundingController extends ApiCrowdfundingController {
        @Override
        public Long getAppUserId() {
            return 1L;
        }
    }
}
