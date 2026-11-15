package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.dto.CircleExploreActionRequest;
import com.chengyinhub.business.domain.dto.CircleThemeInstanceConfigRequest;
import com.chengyinhub.business.service.ICircleExploreService;
import com.chengyinhub.business.service.ICircleThemeSupplyService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 ApiCircleThemeController 所有入口的 IllegalStateException 映射为语义化 4xx。 */
class ApiCircleThemeControllerStatusTest {

    private ICmsTopicService cmsTopicService;
    private ICircleThemeSupplyService supplyService;
    private ICircleExploreService exploreService;
    private IMerchantAccessService merchantAccessService;
    private TestableCircleThemeController controller;

    @BeforeEach
    void setUp() {
        cmsTopicService = mock(ICmsTopicService.class);
        supplyService = mock(ICircleThemeSupplyService.class);
        exploreService = mock(ICircleExploreService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        controller = new TestableCircleThemeController();
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "circleThemeSupplyService", supplyService);
        ReflectionTestUtils.setField(controller, "circleExploreService", exploreService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
    }

    @Test
    void offersMapsNotPurchasedConflictAndNotFoundTo4xx() {
        doThrow(new IllegalStateException("请先购买或领取后再探索")).when(exploreService).assertEligible(1L, 9001L);
        AjaxResult conflict = controller.offers(1L);
        assertEquals(HttpStatus.CONFLICT, conflict.get("code"));

        doThrow(new IllegalStateException("主题不存在")).when(exploreService).assertEligible(2L, 9001L);
        AjaxResult notFound = controller.offers(2L);
        assertEquals(HttpStatus.NOT_FOUND, notFound.get("code"));
    }

    @Test
    void sessionEndpointsMapIllegalStateExceptionToNon500() {
        when(exploreService.create(1L, 9001L)).thenThrow(new IllegalStateException("有效商户不在3—4家，暂不开放"));
        CircleExploreActionRequest createReq = new CircleExploreActionRequest();
        createReq.setTopicId(1L);
        assertEquals(HttpStatus.CONFLICT, controller.createSession(createReq).get("code"));

        when(exploreService.join("BAD", 9001L)).thenThrow(new IllegalStateException("邀请码参数无效"));
        CircleExploreActionRequest joinReq = new CircleExploreActionRequest();
        joinReq.setInviteCode("BAD");
        assertEquals(HttpStatus.BAD_REQUEST, controller.joinSession(joinReq).get("code"));

        doThrow(new IllegalStateException("无权访问该会话")).when(exploreService).record(10L, 20L, "n", 9001L);
        CircleExploreActionRequest recReq = new CircleExploreActionRequest();
        recReq.setSessionId(10L);
        recReq.setOfferId(20L);
        recReq.setNote("n");
        assertEquals(HttpStatus.FORBIDDEN, controller.record(recReq).get("code"));

        doThrow(new IllegalStateException("请先登录")).when(exploreService).answer(10L, "s", "v", 9001L);
        CircleExploreActionRequest ansReq = new CircleExploreActionRequest();
        ansReq.setSessionId(10L);
        ansReq.setStage("s");
        ansReq.setValue("v");
        assertEquals(HttpStatus.UNAUTHORIZED, controller.answer(ansReq).get("code"));

        when(exploreService.card(10L, 9001L)).thenThrow(new IllegalStateException("记录卡不存在"));
        assertEquals(HttpStatus.NOT_FOUND, controller.card(10L).get("code"));
    }

    @Test
    void configureAndReviewMapIllegalStateExceptionToNon500() {
        when(cmsTopicService.configureCircleCandidates(anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("有效商户必须为3至4家"));
        CircleThemeInstanceConfigRequest cfgReq = new CircleThemeInstanceConfigRequest();
        cfgReq.setTopicId(1L);
        cfgReq.setCandidateCodes(Arrays.asList("A", "B"));
        assertEquals(HttpStatus.BAD_REQUEST, controller.configure(cfgReq).get("code"));

        when(cmsTopicService.reviewCircleSupplies(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("仅限主理人复核"));
        CircleExploreActionRequest revReq = new CircleExploreActionRequest();
        revReq.setTopicId(1L);
        assertEquals(HttpStatus.FORBIDDEN, controller.review(revReq).get("code"));
    }

    private static class TestableCircleThemeController extends ApiCircleThemeController {
        @Override
        public Long getAppUserId() {
            return 9001L;
        }
    }
}
