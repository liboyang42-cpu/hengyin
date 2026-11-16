package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.locationtrust.LocationTrustDecision;
import com.chengyinhub.business.locationtrust.LocationTrustKind;
import com.chengyinhub.business.locationtrust.LocationTrustPolicy;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;

/** GAME-4: the place completion both clients use is scored by location trust before the radius check. */
class ApiCityNodeLocationTrustTest {

    private static final long NODE = 77L;

    private RoamMapper roamMapper;
    private LocationTrustService trust;

    private ApiCityNodeController controller() {
        roamMapper = mock(RoamMapper.class);
        CityNodeVO node = new CityNodeVO();
        node.setPoiId(NODE);
        node.setStatus(1);
        node.setLat(BigDecimal.valueOf(31.2304));
        node.setLng(BigDecimal.valueOf(121.4737));
        node.setValidationMethod(5);
        when(roamMapper.selectPlayerMerchantNodeDetail(NODE, 0L)).thenReturn(node);
        trust = mock(LocationTrustService.class);
        ApiCityNodeController controller = new ApiCityNodeController() {
            @Override
            public Long getAppUserId() {
                return 1001L;
            }
        };
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "collectibleMapper", mock(MemberCollectibleMapper.class));
        ReflectionTestUtils.setField(controller, "locationTrust", trust);
        return controller;
    }

    private static LocationTrustService.Assessment assessed(LocationTrustDecision decision, String reason) {
        return new LocationTrustService.Assessment(
                new LocationTrustPolicy.Verdict(decision, List.of(reason), null), decision, 1L);
    }

    @Test
    void rejectedFixIsARetryableRefusalWithoutRewardLog() {
        ApiCityNodeController controller = controller();
        when(trust.assess(eq(1001L), any(), eq(LocationTrustKind.CITY_NODE), eq(String.valueOf(NODE)), any()))
                .thenReturn(assessed(LocationTrustDecision.REJECT, LocationTrustPolicy.ACCURACY_POOR));

        AjaxResult result = controller.complete(NODE, 31.2304, 121.4737, null, null, null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals(true, result.get("retryable"));
        verify(roamMapper, never()).insertRewardLogIgnore(anyLong(), anyLong(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void withheldFixIsRecordedAsNotCreditedWithoutRewardLog() {
        ApiCityNodeController controller = controller();
        when(trust.assess(eq(1001L), any(), eq(LocationTrustKind.CITY_NODE), eq(String.valueOf(NODE)), any()))
                .thenReturn(assessed(LocationTrustDecision.ACCEPT_NO_REWARD, LocationTrustPolicy.SPEED_IMPOSSIBLE));

        AjaxResult result = controller.complete(NODE, 31.2304, 121.4737, null, null, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(true, data.get("uncredited"));
        verify(roamMapper, never()).insertRewardLogIgnore(anyLong(), anyLong(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void routeIsRateLimitedPerMember() throws Exception {
        com.chengyinhub.common.annotation.RateLimiter limiter = ApiCityNodeController.class
                .getMethod("complete", Long.class, Double.class, Double.class, String.class, String.class, String.class)
                .getAnnotation(com.chengyinhub.common.annotation.RateLimiter.class);
        assertEquals(30, limiter.count());
        assertEquals(com.chengyinhub.common.enums.LimitType.MEMBER, limiter.limitType());
    }
}
