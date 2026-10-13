package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台活动编辑的 CR-63 闸:已售出(有已支付报名)的活动,后台也不能改时间与地点。
 *
 * <p>为什么后台也要挡:退款钟按集合时间−24h 计算,已售改期会把已售订单的退款窗口
 * 一起追溯挪走 —— 后台不是这条规则的例外。文字/图片/音频仍可改。
 * P1-2(审查D):「已售出」含待支付/支付中(钱随时会进来)。
 *
 * <p>⚠️ 负控:删掉 countOpenSaleRegistrations 分支 ⇒ 两条 locks 用例红;
 * 把比较扩到全部字段(比如 dayNote)⇒ 图文放行用例红;
 * 经纬度改回字符串 equals ⇒ {@code soldActivity_allowsCoordinateFormattingDifference} 红。
 */
class BusinessActivityEditSoldLockTest {

    private static final long ACTIVITY = 500L;
    private static final Date START = new Date(1_800_000_000_000L);
    private static final Date END = new Date(1_800_003_600_000L);

    private CmsActivityController controller;
    private ICmsActivityService cmsActivityService;

    @BeforeEach
    void setUp() {
        controller = new CmsActivityController();
        cmsActivityService = mock(ICmsActivityService.class);
        ReflectionTestUtils.setField(controller, "cmsActivityService", cmsActivityService);
        when(cmsActivityService.selectCmsActivityById(ACTIVITY)).thenReturn(current());
        when(cmsActivityService.updateCmsActivity(any())).thenReturn(1);
    }

    private CmsActivity current() {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setStartDate(START);
        a.setEndDate(END);
        a.setAddressName("外滩");
        a.setAddress("中山东一路");
        a.setLongitude("121.49");
        a.setLatitude("31.24");
        return a;
    }

    private CmsActivity patch() {
        CmsActivity p = new CmsActivity();
        p.setId(ACTIVITY);
        p.setName("改过的活动名");
        p.setStartDate(START);
        p.setEndDate(END);
        p.setAddressName("外滩");
        p.setAddress("中山东一路");
        p.setLongitude("121.49");
        p.setLatitude("31.24");
        return p;
    }

    private void givenPaid() {
        when(cmsActivityService.countOpenSaleRegistrations(ACTIVITY)).thenReturn(1);
    }

    @Test
    void soldActivity_locksTimeChange() {
        givenPaid();
        CmsActivity p = patch();
        p.setStartDate(new Date(1_900_000_000_000L));

        AjaxResult res = controller.edit(p);

        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains("时间"), "实际:" + res.get("msg"));
        verify(cmsActivityService, never()).updateCmsActivity(any());
    }

    @Test
    void soldActivity_locksPlaceChange() {
        givenPaid();
        CmsActivity p = patch();
        p.setAddressName("新集合点");

        AjaxResult res = controller.edit(p);

        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains("地点"), "实际:" + res.get("msg"));
        verify(cmsActivityService, never()).updateCmsActivity(any());
    }

    @Test
    void soldActivity_allowsTextAndImageChange() {
        givenPaid();
        CmsActivity p = patch();
        p.setDescription("改过的简介");
        p.setImgUrl("new.jpg");
        p.setDayNote("雨天改室内");

        AjaxResult res = controller.edit(p);

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        ArgumentCaptor<CmsActivity> cap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(cmsActivityService).updateCmsActivity(cap.capture());
        assertEquals("改过的活动名", cap.getValue().getName());
        assertEquals("改过的简介", cap.getValue().getDescription());
        assertEquals("雨天改室内", cap.getValue().getDayNote());
    }

    @Test
    void unsoldActivity_allowsTimeChange() {
        CmsActivity p = patch();
        p.setStartDate(new Date(1_900_000_000_000L));

        AjaxResult res = controller.edit(p);

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        verify(cmsActivityService).updateCmsActivity(any());
    }

    /**
     * §12.3:坐标按数值比较 —— 121.49 与 121.490000 是同一个点;
     * 差 1e-6(小数点后 6 位)仍在容差内,不能把改文字误拒。
     */
    @Test
    void soldActivity_allowsCoordinateFormattingDifference() {
        givenPaid();
        CmsActivity p = patch();
        p.setLongitude("121.490000");
        p.setLatitude("31.240001");

        AjaxResult res = controller.edit(p);

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        verify(cmsActivityService).updateCmsActivity(any());
    }

    /** 真改坐标(超出 1e-6 容差)仍拒。 */
    @Test
    void soldActivity_locksRealCoordinateChange() {
        givenPaid();
        CmsActivity p = patch();
        p.setLatitude("31.240002");

        AjaxResult res = controller.edit(p);

        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains("地点"), "实际:" + res.get("msg"));
        verify(cmsActivityService, never()).updateCmsActivity(any());
    }
}
