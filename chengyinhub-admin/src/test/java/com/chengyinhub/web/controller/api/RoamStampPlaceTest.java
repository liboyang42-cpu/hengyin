package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.RoamStamp;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.locationtrust.LocationTrustDecision;
import com.chengyinhub.business.locationtrust.LocationTrustKind;
import com.chengyinhub.business.locationtrust.LocationTrustPolicy;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.RoamStampMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;

/** GAME-12: a place stamp is earned at the place, once per place; free photos stay postcards. */
class RoamStampPlaceTest {
    private static final long POI = 77L;
    private static final double LAT = 31.2304, LNG = 121.4737;

    private static class StubController extends ApiRoamStampController {
        @Override Long uid() { return 9L; }
    }

    private final RoamStampMapper stamps = mock(RoamStampMapper.class);
    private final RoamMapper roam = mock(RoamMapper.class);
    private final LocationTrustService trust = mock(LocationTrustService.class);

    private ApiRoamStampController controller(boolean enabled, boolean withTrust) {
        StubController c = new StubController();
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(members.selectUmsMemberById(9L)).thenReturn(new UmsMember());
        ReflectionTestUtils.setField(c, "roamStampMapper", stamps);
        ReflectionTestUtils.setField(c, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(c, "umsMemberService", members);
        ReflectionTestUtils.setField(c, "roamMapper", roam);
        if (withTrust) ReflectionTestUtils.setField(c, "locationTrust", trust);
        ReflectionTestUtils.setField(c, "placeStampEnabled", enabled);
        CityNodeVO place = new CityNodeVO();
        place.setStatus(1);
        place.setLat(BigDecimal.valueOf(LAT));
        place.setLng(BigDecimal.valueOf(LNG));
        place.setRadiusM(60);
        place.setName("老书店");
        when(roam.selectPlayerMerchantNodeDetail(POI, 0L)).thenReturn(place);
        doAnswer(inv -> { ((RoamStamp) inv.getArgument(0)).setId(500L); return 1; }).when(stamps).insertStamp(any());
        when(stamps.countPlacesByMember(9L)).thenReturn(3);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(AjaxResult r) { return (Map<String, Object>) r.get(AjaxResult.DATA_TAG); }

    @Test
    void atThePlaceIsAPlaceStampWithThePlaceCoordinatesOnly() {
        AjaxResult r = controller(true, false).create("https://x/p.jpg", null, "k1", POI, LAT + 0.0003, LNG);
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertEquals(1, data(r).get("kind"));
        assertEquals("老书店", data(r).get("placeName"));
        assertEquals(3, data(r).get("placesStamped"));
        ArgumentCaptor<RoamStamp> saved = ArgumentCaptor.forClass(RoamStamp.class);
        verify(stamps).insertStamp(saved.capture());
        assertEquals(1, saved.getValue().getKind());
        assertEquals(POI, saved.getValue().getPoiId());
        assertEquals(BigDecimal.valueOf(LAT), saved.getValue().getLat(), "stores the place, not the player's fix");
        assertNull(saved.getValue().getGeohash7());
    }

    @Test
    void samePlaceTwiceIsIdempotent() {
        RoamStamp first = new RoamStamp();
        first.setId(321L);
        when(stamps.selectByMemberAndPoi(9L, POI)).thenReturn(first);
        AjaxResult r = controller(true, false).create("https://x/p2.jpg", null, "k2", POI, LAT, LNG);
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertEquals(321L, data(r).get("id"));
        assertEquals(true, data(r).get("alreadyStamped"));
        verify(stamps, never()).insertStamp(any());
    }

    @Test
    void farFixIsRefusedAndRetryable() {
        AjaxResult r = controller(true, false).create("https://x/p.jpg", null, "k3", POI, LAT + 0.01, LNG);
        assertEquals("STAMP_TOO_FAR", r.get("reasonCode"));
        assertEquals(true, r.get("retryable"));
        verify(stamps, never()).insertStamp(any());
    }

    @Test
    void untrustedFixIsRefusedAndDoubtfulFixKeepsAPostcard() {
        when(trust.assess(eq(9L), any(), eq(LocationTrustKind.CITY_NODE), eq(String.valueOf(POI)), any()))
                .thenReturn(new LocationTrustService.Assessment(new LocationTrustPolicy.Verdict(LocationTrustDecision.REJECT,
                        List.of(LocationTrustPolicy.ACCURACY_POOR), null), LocationTrustDecision.REJECT, 1L));
        AjaxResult refused = controller(true, true).create("https://x/p.jpg", null, "k4", POI, LAT, LNG);
        assertEquals(true, refused.get("retryable"));
        verify(stamps, never()).insertStamp(any());

        when(trust.assess(eq(9L), any(), eq(LocationTrustKind.CITY_NODE), eq(String.valueOf(POI)), any()))
                .thenReturn(new LocationTrustService.Assessment(new LocationTrustPolicy.Verdict(LocationTrustDecision.ACCEPT_NO_REWARD,
                        List.of(LocationTrustPolicy.SPEED_IMPOSSIBLE), null), LocationTrustDecision.ACCEPT_NO_REWARD, 1L));
        AjaxResult held = controller(true, true).create("https://x/p.jpg", null, "k5", POI, LAT, LNG);
        assertEquals(0, data(held).get("kind"));
        assertEquals(false, data(held).get("placeStamped"));
        ArgumentCaptor<RoamStamp> saved = ArgumentCaptor.forClass(RoamStamp.class);
        verify(stamps).insertStamp(saved.capture());
        assertEquals(0, saved.getValue().getKind());
        assertNull(saved.getValue().getPoiId());
    }

    @Test
    void switchOffIgnoresThePlaceAndSavesAFreePhoto() {
        AjaxResult r = controller(false, true).create("https://x/p.jpg", null, "k6", POI, LAT + 0.01, LNG);
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertNull(data(r).get("kind"), "same reply shape as before");
        ArgumentCaptor<RoamStamp> saved = ArgumentCaptor.forClass(RoamStamp.class);
        verify(stamps).insertStamp(saved.capture());
        assertEquals(0, saved.getValue().getKind());
        assertNull(saved.getValue().getPoiId());
        verify(roam, never()).selectPlayerMerchantNodeDetail(anyLong(), anyLong());
    }
}
