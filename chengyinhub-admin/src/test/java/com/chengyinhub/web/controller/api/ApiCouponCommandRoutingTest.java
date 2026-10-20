package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.CouponPublisDTO;
import com.chengyinhub.business.service.impl.CouponCommandService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;

class ApiCouponCommandRoutingTest {
    static class Controller extends ApiCouponController {
        int legacyPublish,legacyStop;
        @Override public Long getAppUserId() {return 7002L;}
        @Override public AjaxResult publish(CouponPublisDTO request) {legacyPublish++;return AjaxResult.success("legacy");}
        @Override public AjaxResult stopIssuing(Long couponId,String scope) {legacyStop++;return AjaxResult.success("legacy");}
    }
    Controller controller; MockMvc mvc;
    String body="{\"scope\":\"MERCHANT\",\"name\":\"test\",\"couponType\":0,\"publishCount\":10,\"startTime\":\"2026-10-05 00:00:00\",\"endTime\":\"2026-10-06 23:59:59\"}";
    @BeforeEach void setup() {
        controller=new Controller(); CouponCommandService service=new CouponCommandService();
        ReflectionTestUtils.setField(controller,"couponCommandService",service);
        mvc=MockMvcBuilders.standaloneSetup(controller).build();
    }
    @Test void absentHeadersPreserveBothLegacyRoutes() throws Exception {
        mvc.perform(post("/api/coupon/publish").contentType("application/json").content(body));
        mvc.perform(post("/api/coupon/stop").param("couponId","1").param("scope","MERCHANT"));
        assertEquals(1,controller.legacyPublish);assertEquals(1,controller.legacyStop);
    }
    @Test void loneEmptyMerchantHeaderCannotFallBack() throws Exception {
        String response=mvc.perform(post("/api/coupon/publish").header("X-Coupon-Merchant-Id","").contentType("application/json").content(body)).andReturn().getResponse().getContentAsString();
        assertTrue(response.contains("COMMANDS_UNAVAILABLE"));assertEquals(0,controller.legacyPublish);
    }
    @Test void loneEmptyCommandHeaderCannotFallBack() throws Exception {
        String response=mvc.perform(post("/api/coupon/stop").header("X-Coupon-Command-Id","").param("couponId","1").param("scope","MERCHANT")).andReturn().getResponse().getContentAsString();
        assertTrue(response.contains("COMMANDS_UNAVAILABLE"));assertEquals(0,controller.legacyStop);
    }
    @Test void malformedMerchantHeaderCannotFallBack() throws Exception {
        mvc.perform(post("/api/coupon/stop").header("X-Coupon-Merchant-Id","not-a-number").param("couponId","1").param("scope","MERCHANT"));assertEquals(0,controller.legacyStop);
    }
    @Test void validCommandHeadersNeverEnterDisabledLegacyBranch() throws Exception {
        mvc.perform(post("/api/coupon/publish").header("X-Coupon-Command-Id","11111111-1111-4111-8111-111111111111").header("X-Coupon-Merchant-Id","11").contentType("application/json").content(body));assertEquals(0,controller.legacyPublish);
    }
}
