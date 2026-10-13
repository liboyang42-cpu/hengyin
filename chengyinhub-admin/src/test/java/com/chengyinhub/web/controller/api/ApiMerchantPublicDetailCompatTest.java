package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.vo.PublicFeaturedActivityVO;
import com.chengyinhub.business.domain.vo.PublicFeaturedCouponVO;
import com.chengyinhub.business.domain.vo.PublicMerchantVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * POST /api/merchant/public-detail 旧客户端合同锁。
 *
 * <p>线上仍有旧小程序读这条接口,前端回滚后也必须继续可用:顶层结构
 * ({@code data} 是 MmsMerchant 实体 + 兄弟键 {@code officialActivities} / {@code featured})
 * 与旧字段一个都不许少。本批只允许新增 {@code delFlag=0} 可见性闸。</p>
 */
class ApiMerchantPublicDetailCompatTest {

    private ICoopInviteService inviteService;
    private ICmsActivityService activityService;
    private ISmsCouponService couponService;

    private ApiMerchantController controller(MmsMerchant merchant) {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        ISysCategoryService categoryService = mock(ISysCategoryService.class);
        inviteService = mock(ICoopInviteService.class);
        activityService = mock(ICmsActivityService.class);
        couponService = mock(ISmsCouponService.class);
        ApiMerchantController controller = new ApiMerchantController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "sysCategoryService", categoryService);
        ReflectionTestUtils.setField(controller, "coopInviteService", inviteService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "smsCouponService", couponService);
        when(merchantService.selectMmsMerchantById(8L)).thenReturn(merchant);
        SysCategory category = new SysCategory();
        category.setId(3L);
        category.setCategoryName("咖啡馆");
        when(categoryService.selectSysCategoryListByIds(new String[]{"3"}))
                .thenReturn(Collections.singletonList(category));
        return controller;
    }

    private MmsMerchant visibleMerchant() {
        MmsMerchant m = new MmsMerchant();
        m.setId(8L);
        m.setMemberId(201L);
        m.setStatus(1L);
        m.setAccountStatus(1L);
        m.setDelFlag(0);
        m.setName("城瘾咖啡");
        m.setCategoryId(3);
        m.setPhone("13800000001");
        m.setWechat("wx-private");
        m.setBusinessLicense("license.png");
        m.setReson("审核备注");
        return m;
    }

    private static MmsMerchant request(long id) {
        MmsMerchant request = new MmsMerchant();
        request.setId(id);
        return request;
    }

    @Test
    void legacyRouteKeepsFieldNamesButProjectsToPublicWhitelist() {
        MmsMerchant merchant = visibleMerchant();
        ApiMerchantController controller = controller(merchant);
        List<Map<String, Object>> awarded =
                Collections.<Map<String, Object>>singletonList(Collections.<String, Object>singletonMap("topicId", 7L));
        when(inviteService.listAwardedOfficialActivities(201L)).thenReturn(awarded);

        AjaxResult result = controller.publicDetail(request(8L));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Object data = result.get(AjaxResult.DATA_TAG);
        assertTrue(data instanceof PublicMerchantVO, "匿名路由必须返回显式公开白名单: " + data);
        assertEquals("城瘾咖啡", ((PublicMerchantVO) data).getName());
        assertEquals("咖啡馆", ((PublicMerchantVO) data).getSysCategoryList().get(0).getCategoryName());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data).toString();
        assertFalse(json.contains("phone"));
        assertFalse(json.contains("wechat"));
        assertFalse(json.contains("businessLicense"));
        assertFalse(json.contains("accountStatus"));
        assertFalse(json.contains("delFlag"));
        assertFalse(json.contains("createBy"));
        // officialActivities 仍是 data 的兄弟键
        assertEquals(awarded, result.get("officialActivities"));
    }

    @Test
    void featuredSiblingStillReturnedForActivityAndCoupon() {
        MmsMerchant activityFeatured = visibleMerchant();
        activityFeatured.setFeaturedType(1);
        activityFeatured.setFeaturedId(66L);
        ApiMerchantController controller = controller(activityFeatured);
        CmsActivity activity = new CmsActivity();
        activity.setId(66L);
        activity.setMemberId(201L);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(activityService.selectPublicCmsActivityById(66L)).thenReturn(activity);

        assertTrue(controller.publicDetail(request(8L)).get("featured")
                instanceof PublicFeaturedActivityVO);

        MmsMerchant couponFeatured = visibleMerchant();
        couponFeatured.setFeaturedType(2);
        couponFeatured.setFeaturedId(77L);
        ApiMerchantController couponController = controller(couponFeatured);
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(77L);
        coupon.setMemberId(201L);
        coupon.setScopeType(1);
        coupon.setMerchantId(8L);
        coupon.setStatus(1);
        coupon.setDelFlag(0);
        coupon.setStartTime(new java.util.Date(System.currentTimeMillis() - 60_000L));
        coupon.setEndTime(new java.util.Date(System.currentTimeMillis() + 60_000L));
        when(couponService.selectSmsCouponById(77L)).thenReturn(coupon);

        assertTrue(couponController.publicDetail(request(8L)).get("featured")
                instanceof PublicFeaturedCouponVO);

        // featuredType=0 时不带 featured 键,行为同旧版
        ApiMerchantController plain = controller(visibleMerchant());
        assertFalse(plain.publicDetail(request(8L)).containsKey("featured"));
    }

    @Test
    void deletedMerchantIsNowInvisibleThroughLegacyEndpoint() {
        MmsMerchant deleted = visibleMerchant();
        deleted.setDelFlag(2);

        AjaxResult result = controller(deleted).publicDetail(request(8L));

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("商家不存在或未开放", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void existingStatusAndAccountGatesStillClosed() {
        MmsMerchant pending = visibleMerchant();
        pending.setStatus(0L);
        assertNotEquals(200, controller(pending).publicDetail(request(8L)).get(AjaxResult.CODE_TAG));

        MmsMerchant disabled = visibleMerchant();
        disabled.setAccountStatus(2L);
        assertNotEquals(200, controller(disabled).publicDetail(request(8L)).get(AjaxResult.CODE_TAG));

        MmsMerchant unknown = visibleMerchant();
        unknown.setAccountStatus(null);
        assertNotEquals(200, controller(unknown).publicDetail(request(8L)).get(AjaxResult.CODE_TAG));

        MmsMerchant notEnabled = visibleMerchant();
        notEnabled.setAccountStatus(0L);
        assertNotEquals(200, controller(notEnabled).publicDetail(request(8L)).get(AjaxResult.CODE_TAG));

        assertNotEquals(200, controller(null).publicDetail(request(8L)).get(AjaxResult.CODE_TAG));
    }
}
