package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantRegistrationDTO;
import com.chengyinhub.business.service.IMapGeocodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入驻/改资料的坐标落库(ApiMerchantController#applyLocation)。
 *
 * 背景:wx.chooseLocation 本来就跟 address 一起返回经纬度,apply 页以前把它丢了,
 * 所以生产 10 家商家全有地址、零家有坐标。补上写入之后,真正危险的不是"写不进去",
 * 而是"把已有的好坐标写坏"—— 见 doesNotOverwrite* 两条。
 */
class MerchantRegistrationLocationTest {

    private ApiMerchantController controller;
    private IMapGeocodeService geocode;

    @BeforeEach
    void setUp() {
        controller = new ApiMerchantController();
        geocode = mock(IMapGeocodeService.class);
        ReflectionTestUtils.setField(controller, "mapGeocodeService", geocode);
    }

    private static MerchantRegistrationDTO request(String address, Double lat, Double lng) {
        MerchantRegistrationDTO dto = new MerchantRegistrationDTO();
        dto.setAddress(address);
        dto.setLocationLat(lat);
        dto.setLocationLng(lng);
        return dto;
    }

    private static MmsMerchant merchantAt(Double lat, Double lng) {
        MmsMerchant m = new MmsMerchant();
        m.setLocationLat(lat);
        m.setLocationLng(lng);
        return m;
    }

    /** 商家自己在地图上点的最准,直接落库并标记为已确认;不该再去反查地址。 */
    @Test
    void usesPickedCoordsAndMarksThemVerified() {
        MmsMerchant target = new MmsMerchant();
        controller.applyLocation(target, request("杭州市上城区延安路1号", 30.27411D, 120.15514D), null);

        assertEquals(30.27411D, target.getLocationLat(), 1e-6);
        assertEquals(120.15514D, target.getLocationLng(), 1e-6);
        assertEquals(Integer.valueOf(1), target.getLocationVerified(), "商家亲自点的应标记为已确认");
        verify(geocode, never()).suggestCoords(anyString(), any());
    }

    /**
     * 核心闸:更新分支、请求没带坐标、库里已有坐标 —— 一个字都不能动。
     *
     * 留 null 才能让 mapper 的动态 SET 跳过这两列。断言里同时钉死"没去反查地址":
     * 只断 target 坐标为 null 是不够的 —— 真正的坏法不是抹成空,而是拿反查出来的粗坐标
     * 盖掉商家在装修页点过的精确坐标,那种情况下 target 坐标恰恰是非空的。
     */
    @Test
    void doesNotOverwriteExistingCoordsWhenRequestOmitsThem() {
        MmsMerchant target = new MmsMerchant();
        MmsMerchant existing = merchantAt(30.27411D, 120.15514D);

        controller.applyLocation(target, request("杭州市上城区延安路1号", null, null), existing);

        assertNull(target.getLocationLat(), "已有坐标时必须留空,让动态 SET 跳过 location_lat");
        assertNull(target.getLocationLng(), "已有坐标时必须留空,让动态 SET 跳过 location_lng");
        verify(geocode, never()).suggestCoords(anyString(), any());
    }

    /** 同上,但坐标来自装修页(verified=1)。改一次资料就把它降级成推断值,是同一个坑。 */
    @Test
    void doesNotDowngradeDecorPickedCoordsToGeocodedGuess() {
        MmsMerchant target = new MmsMerchant();
        MmsMerchant existing = merchantAt(30.27411D, 120.15514D);
        existing.setLocationVerified(1);

        controller.applyLocation(target, request("杭州市上城区延安路1号", null, null), existing);

        assertNull(target.getLocationVerified(), "不该把已确认坐标改写成推断态");
        verify(geocode, never()).suggestCoords(anyString(), any());
    }

    /** 库里也没坐标时才轮到地址反查,且标记为推断而非商家确认。 */
    @Test
    void fallsBackToGeocodingOnlyWhenNoCoordsAnywhere() {
        when(geocode.suggestCoords(anyString(), any())).thenReturn(new double[]{120.15514D, 30.27411D});
        MmsMerchant target = new MmsMerchant();

        controller.applyLocation(target, request("杭州市上城区延安路1号", null, null), merchantAt(null, null));

        assertEquals(30.27411D, target.getLocationLat(), 1e-6, "服务返回 {经度,纬度},纬度是第 1 位");
        assertEquals(120.15514D, target.getLocationLng(), 1e-6, "经度是第 0 位");
        assertEquals(Integer.valueOf(2), target.getLocationVerified(), "反查出来的是推断值(2),不能冒充商家确认(1),也不能记成没坐标(0)");
    }

    /** 反查不可信(可信度不足会返回 null)时坐标留空 —— 绝不塞城市中心。 */
    @Test
    void leavesCoordsEmptyWhenGeocodingIsNotTrustworthy() {
        when(geocode.suggestCoords(anyString(), any())).thenReturn(null);
        MmsMerchant target = new MmsMerchant();

        controller.applyLocation(target, request("说不清在哪的地址", null, null), null);

        assertNull(target.getLocationLat(), "反查不可信时宁可没坐标,也不能给个看着正常的错坐标");
        assertNull(target.getLocationLng());
        assertNull(target.getLocationVerified());
    }

    /** 半个坐标视同没给,回落到地址反查,而不是把 null 那半边写成 0。 */
    @Test
    void treatsHalfACoordinateAsMissing() {
        when(geocode.suggestCoords(anyString(), any())).thenReturn(new double[]{120.1D, 30.2D});
        MmsMerchant target = new MmsMerchant();

        controller.applyLocation(target, request("杭州市上城区延安路1号", 30.27411D, null), null);

        assertEquals(Integer.valueOf(2), target.getLocationVerified(), "半个坐标不算商家点过,回落到推断态");
        assertEquals(30.2D, target.getLocationLat(), 1e-6);
    }

    /**
     * 客户端把库里的推断坐标原样传回来时,不能因此升级成"商家确认"。
     *
     * 这是驳回后重新提交那条路上真会发生的事:表单回填了上次反查出来的坐标,一提交
     * 推断值就冒充成了商家亲自点的。apply 页现在不回填了,但线上还跑着旧版本小程序,
     * 所以判据必须在服务端。
     */
    @Test
    void echoingStoredCoordsDoesNotPromoteInferredToPicked() {
        MmsMerchant target = new MmsMerchant();
        MmsMerchant existing = merchantAt(30.27411D, 120.15514D);
        existing.setLocationVerified(2);

        controller.applyLocation(target, request("杭州市上城区延安路1号", 30.27411D, 120.15514D), existing);

        assertEquals(Integer.valueOf(2), target.getLocationVerified(),
                "原样传回的坐标不算重新点过,来源仍是推断");
    }

    /** 但真的换了个点,就是商家重新点过,来源升级为已确认。 */
    @Test
    void pickingADifferentSpotDoesCountAsPicked() {
        MmsMerchant target = new MmsMerchant();
        MmsMerchant existing = merchantAt(30.27411D, 120.15514D);
        existing.setLocationVerified(2);

        controller.applyLocation(target, request("杭州市上城区延安路2号", 30.28000D, 120.16000D), existing);

        assertEquals(Integer.valueOf(1), target.getLocationVerified(), "换了坐标就是重新点过");
        assertEquals(30.28000D, target.getLocationLat(), 1e-6);
    }

    /** 没地址就没得反查,不该白打一次三方接口。 */
    @Test
    void skipsGeocodingWhenAddressIsBlank() {
        MmsMerchant target = new MmsMerchant();

        controller.applyLocation(target, request("   ", null, null), null);

        verify(geocode, never()).suggestCoords(anyString(), any());
        assertNull(target.getLocationLat());
    }
}
