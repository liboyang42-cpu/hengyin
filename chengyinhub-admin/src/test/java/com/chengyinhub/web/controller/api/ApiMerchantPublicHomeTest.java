package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.PublicMerchantVO;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /api/merchant/public-home —— 商家公开主页新契约(白名单 + 失败关闭)。
 */
class ApiMerchantPublicHomeTest {

    private static final long MERCHANT_ID = 8L;
    private static final long MEMBER_ID = 201L;

    private IMmsMerchantService merchantService;
    private ISysCategoryService categoryService;
    private com.chengyinhub.business.service.ICmsActivityService cmsActivityService;
    private com.chengyinhub.business.service.ISmsCouponService smsCouponService;

    private ApiMerchantController controller(MmsMerchant merchant) {
        merchantService = mock(IMmsMerchantService.class);
        categoryService = mock(ISysCategoryService.class);
        cmsActivityService = mock(com.chengyinhub.business.service.ICmsActivityService.class);
        smsCouponService = mock(com.chengyinhub.business.service.ISmsCouponService.class);
        ApiMerchantController controller = new ApiMerchantController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "sysCategoryService", categoryService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", cmsActivityService);
        ReflectionTestUtils.setField(controller, "smsCouponService", smsCouponService);
        if (merchant != null) {
            when(merchantService.selectMmsMerchantById(MERCHANT_ID)).thenReturn(merchant);
            when(merchantService.selectMmsMerchantByMemberId(MEMBER_ID)).thenReturn(merchant);
        }
        return controller;
    }

    private com.chengyinhub.business.domain.CmsActivity ownedPublicActivity() {
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(16L);
        activity.setName("周末露营市集");
        activity.setDescription("本店招牌活动");
        activity.setMemberId(MEMBER_ID);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        return activity;
    }

    /** 一个完整的、公开可见的商家:私密字段全部填上,用来验证它们不会外泄。 */
    private MmsMerchant visibleMerchant() {
        MmsMerchant m = new MmsMerchant();
        m.setId(MERCHANT_ID);
        m.setMemberId(MEMBER_ID);
        m.setStatus(1L);
        m.setAccountStatus(1L);
        m.setDelFlag(0);
        m.setName("城瘾咖啡");
        m.setLogo("logo.png");
        m.setCoverImage("cover.png");
        m.setCityRole("城市补给站");
        m.setSlogan("来一杯");
        m.setStoryTitle("我们的故事");
        m.setDescription("十年老店");
        m.setGallery("a.png,b.png");
        m.setTags("咖啡,露营");
        m.setBusinessStatus(1);
        m.setBusinessTime("09:00-21:00");
        m.setAddress("上海市黄浦区某路 1 号");
        m.setLocationLat(31.2304);
        m.setLocationLng(121.4737);
        m.setCapacity(20);
        m.setAvailableTime("周末全天");
        m.setSuitActivityTypes("city-walk");
        m.setDemand("希望对接周末亲子主题");
        m.setCategoryId(3);
        // 私密 / 审核字段
        m.setPhone("13800000001");
        m.setWechat("wx-private");
        m.setBusinessLicense("license.png");
        m.setReson("审核备注:资质待补");
        return m;
    }

    private Map<String, Object> body(String key, Object value) {
        Map<String, Object> body = new HashMap<>();
        body.put(key, value);
        return body;
    }

    private static PublicMerchantVO data(AjaxResult result) {
        return (PublicMerchantVO) result.get(AjaxResult.DATA_TAG);
    }

    @Test
    void byIdAndByMemberIdResolveTheSamePublicMerchant() {
        ApiMerchantController controller = controller(visibleMerchant());
        SysCategory category = new SysCategory();
        category.setId(3L);
        category.setCategoryName("咖啡馆");
        when(categoryService.selectSysCategoryListByIds(new String[]{"3"}))
                .thenReturn(Collections.singletonList(category));

        AjaxResult byId = controller.publicHome(body("id", MERCHANT_ID));
        AjaxResult byMemberId = controller.publicHome(body("memberId", MEMBER_ID));

        assertEquals(200, byId.get(AjaxResult.CODE_TAG));
        assertEquals(200, byMemberId.get(AjaxResult.CODE_TAG));
        assertEquals(MERCHANT_ID, data(byId).getId());
        assertEquals(MERCHANT_ID, data(byMemberId).getId());
        assertEquals(MEMBER_ID, data(byId).getMemberId());
        assertEquals(MEMBER_ID, data(byMemberId).getMemberId());
        assertEquals("城瘾咖啡", data(byId).getName());
        assertEquals("咖啡馆", data(byId).getSysCategoryList().get(0).getCategoryName());
        // 按 memberId 查走 memberId 路径,不能偷偷退化成按 id 查
        verify(merchantService).selectMmsMerchantByMemberId(MEMBER_ID);
    }

    @Test
    void pendingMerchantIsNotPublic() {
        MmsMerchant m = visibleMerchant();
        m.setStatus(0L);   // 未审核通过
        ApiMerchantController controller = controller(m);

        assertNotEquals(200, controller.publicHome(body("id", MERCHANT_ID)).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.publicHome(body("memberId", MEMBER_ID)).get(AjaxResult.CODE_TAG));
    }

    @Test
    void disabledAccountIsNotPublic() {
        MmsMerchant m = visibleMerchant();
        m.setAccountStatus(2L);   // 账号禁用
        ApiMerchantController controller = controller(m);

        assertNotEquals(200, controller.publicHome(body("id", MERCHANT_ID)).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.publicHome(body("memberId", MEMBER_ID)).get(AjaxResult.CODE_TAG));
    }

    @Test
    void unknownOrPendingAccountStatusIsNotPublic() {
        MmsMerchant unknown = visibleMerchant();
        unknown.setAccountStatus(null);
        ApiMerchantController unknownController = controller(unknown);
        assertNotEquals(200,
                unknownController.publicHome(body("id", MERCHANT_ID)).get(AjaxResult.CODE_TAG));

        MmsMerchant pending = visibleMerchant();
        pending.setAccountStatus(0L);
        ApiMerchantController pendingController = controller(pending);
        assertNotEquals(200,
                pendingController.publicHome(body("id", MERCHANT_ID)).get(AjaxResult.CODE_TAG));
    }

    @Test
    void deletedMerchantIsNotPublic() {
        MmsMerchant m = visibleMerchant();
        m.setDelFlag(2);   // 已删除
        ApiMerchantController controller = controller(m);

        assertNotEquals(200, controller.publicHome(body("id", MERCHANT_ID)).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.publicHome(body("memberId", MEMBER_ID)).get(AjaxResult.CODE_TAG));
    }

    @Test
    void bothIdsOrNeitherIdFailsClosedWithoutQuerying() {
        ApiMerchantController controller = controller(visibleMerchant());

        Map<String, Object> both = new HashMap<>();
        both.put("id", MERCHANT_ID);
        both.put("memberId", MEMBER_ID);

        assertNotEquals(200, controller.publicHome(both).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.publicHome(new HashMap<String, Object>()).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.publicHome(null).get(AjaxResult.CODE_TAG));

        verify(merchantService, never()).selectMmsMerchantById(anyLong());
        verify(merchantService, never()).selectMmsMerchantByMemberId(anyLong());
    }

    @Test
    void serializedResponseCarriesNoPrivateOrAuditFields() throws Exception {
        ApiMerchantController controller = controller(visibleMerchant());

        String json = new ObjectMapper().writeValueAsString(controller.publicHome(body("id", MERCHANT_ID)));

        // 字段名负控
        for (String forbidden : new String[]{"phone", "wechat", "businessLicense", "reson",
                "status", "accountStatus", "delFlag", "featuredType", "featuredId",
                "locationVerified", "chargeType", "coopOpen"}) {
            assertFalse(json.contains("\"" + forbidden + "\""), "公开响应泄露字段 " + forbidden + ": " + json);
        }
        // 值负控:换个字段名也不能把内容带出去
        for (String secret : new String[]{"13800000001", "wx-private", "license.png", "审核备注:资质待补"}) {
            assertFalse(json.contains(secret), "公开响应泄露内容 " + secret + ": " + json);
        }
        // 正向:白名单字段确实在
        assertTrue(json.contains("\"storyTitle\""));
        assertTrue(json.contains("\"gallery\""));
        assertTrue(json.contains("\"capacity\""));
        assertTrue(json.contains("\"demand\""));
    }

    /** 门店坐标是公开地址的一部分:不透出前端「点击地址导航」永远点不动。 */
    @Test
    void publicHomeCarriesStoreCoordinates() throws Exception {
        MmsMerchant merchant = visibleMerchant();
        ApiMerchantController controller = controller(merchant);

        AjaxResult result = controller.publicHome(body("id", MERCHANT_ID));

        assertEquals(merchant.getLocationLat(), data(result).getLocationLat());
        assertEquals(merchant.getLocationLng(), data(result).getLocationLng());
        String json = new ObjectMapper().writeValueAsString(result);
        assertTrue(json.contains("\"locationLat\":31.2304"), json);
        assertTrue(json.contains("\"locationLng\":121.4737"), json);
    }

    /** 未打点的商家要显式回 null,前端据此隐藏导航;抽掉键会变成 undefined 分不清。 */
    @Test
    void missingCoordinatesKeepKeysWithNullValue() throws Exception {
        MmsMerchant merchant = visibleMerchant();
        merchant.setLocationLat(null);
        merchant.setLocationLng(null);
        ApiMerchantController controller = controller(merchant);

        AjaxResult result = controller.publicHome(body("id", MERCHANT_ID));

        assertNull(data(result).getLocationLat());
        assertNull(data(result).getLocationLng());
        String json = new ObjectMapper().writeValueAsString(result);
        assertTrue(json.contains("\"locationLat\":null"), json);
        assertTrue(json.contains("\"locationLng\":null"), json);
    }

    @Test
    void ordinaryMemberIdYieldsBusinessUnavailableInsteadOfError() {
        ApiMerchantController controller = controller(null);   // 该会员没有商家档案
        when(merchantService.selectMmsMerchantByMemberId(999L)).thenReturn(null);

        AjaxResult result = controller.publicHome(body("memberId", 999L));

        // 业务结果:HTTP 层正常返回,带可读消息,前端可据此显示「非商家」而不是网络错误
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("商家不存在或未开放", result.get(AjaxResult.MSG_TAG));
        assertNull(result.get(AjaxResult.DATA_TAG));
    }

    /** R9-33:装修里选中的主推活动,统一主页必须消费 —— 与旧 public-detail 同口径。 */
    @Test
    void publicHomeCarriesOwnedFeaturedActivity() throws Exception {
        MmsMerchant merchant = visibleMerchant();
        merchant.setFeaturedType(1);
        merchant.setFeaturedId(16L);
        ApiMerchantController controller = controller(merchant);
        when(cmsActivityService.selectPublicCmsActivityById(16L)).thenReturn(ownedPublicActivity());

        AjaxResult result = controller.publicHome(body("id", MERCHANT_ID));

        Object featured = data(result).getFeatured();
        assertTrue(featured instanceof com.chengyinhub.business.domain.vo.PublicFeaturedActivityVO);
        assertEquals(16L, ((com.chengyinhub.business.domain.vo.PublicFeaturedActivityVO) featured).getId());
        String json = new ObjectMapper().writeValueAsString(result);
        assertTrue(json.contains("\"featured\""), json);
        assertTrue(json.contains("周末露营市集"), json);
        // 4-12:卡片 VO 自带 featuredType/featuredId(前端据此判断可点与跳转目标);见
        // PublicFeaturedCardFieldsContractTest。
        assertTrue(json.contains("\"featuredType\":1"), json);
        assertTrue(json.contains("\"featuredId\":16"), json);
        // 但「实体上的装修输入字段」仍不进商家白名单:public-home 的商家 VO 不得长出这两个属性
        assertFalse(java.util.Arrays.stream(PublicMerchantVO.class.getDeclaredFields())
                        .anyMatch(f -> "featuredType".equals(f.getName()) || "featuredId".equals(f.getName())),
                "PublicMerchantVO 不得直接透出实体装修输入字段");
    }

    /** 负控:主推指向别家活动/已下架活动时,公开主页不得把它挂上去。 */
    @Test
    void publicHomeDropsFeaturedActivityThatIsNotOwnedOrNotPublic() {
        MmsMerchant merchant = visibleMerchant();
        merchant.setFeaturedType(1);
        merchant.setFeaturedId(16L);
        ApiMerchantController controller = controller(merchant);

        com.chengyinhub.business.domain.CmsActivity otherMerchant =
                ownedPublicActivity();
        otherMerchant.setMemberId(MEMBER_ID + 1);
        when(cmsActivityService.selectPublicCmsActivityById(16L)).thenReturn(otherMerchant);
        assertNull(data(controller.publicHome(body("id", MERCHANT_ID))).getFeatured());

        com.chengyinhub.business.domain.CmsActivity offline = ownedPublicActivity();
        offline.setPublishStatus(0L);
        when(cmsActivityService.selectPublicCmsActivityById(16L)).thenReturn(offline);
        assertNull(data(controller.publicHome(body("id", MERCHANT_ID))).getFeatured());

        when(cmsActivityService.selectPublicCmsActivityById(16L)).thenReturn(null);
        assertNull(data(controller.publicHome(body("id", MERCHANT_ID))).getFeatured());
    }

    /** 主推券同理:别家券/已撤回券不进主页,本店进行中的券才进。 */
    @Test
    void publicHomeCarriesOnlyOwnedActiveFeaturedCoupon() {
        MmsMerchant merchant = visibleMerchant();
        merchant.setFeaturedType(2);
        merchant.setFeaturedId(15L);
        ApiMerchantController controller = controller(merchant);

        com.chengyinhub.business.domain.SmsCoupon coupon = new com.chengyinhub.business.domain.SmsCoupon();
        coupon.setId(15L);
        coupon.setName("满减券");
        coupon.setMemberId(MEMBER_ID);
        coupon.setMerchantId(MERCHANT_ID);
        coupon.setScopeType(1);
        coupon.setStatus(1);
        coupon.setDelFlag(0);
        coupon.setStartTime(new java.util.Date(System.currentTimeMillis() - 60_000));
        coupon.setEndTime(new java.util.Date(System.currentTimeMillis() + 86_400_000));
        when(smsCouponService.selectSmsCouponById(15L)).thenReturn(coupon);

        Object featured = data(controller.publicHome(body("id", MERCHANT_ID))).getFeatured();
        assertTrue(featured instanceof com.chengyinhub.business.domain.vo.PublicFeaturedCouponVO);
        assertEquals(15L, ((com.chengyinhub.business.domain.vo.PublicFeaturedCouponVO) featured).getId());

        coupon.setMerchantId(MERCHANT_ID + 1);
        assertNull(data(controller.publicHome(body("id", MERCHANT_ID))).getFeatured());
    }

    /** 没设主推时 featured 为 null,前端据此整块不渲染(不给空壳卡片)。 */
    @Test
    void publicHomeKeepsFeaturedNullWhenNothingSelected() {
        MmsMerchant merchant = visibleMerchant();
        assertNull(data(controller(merchant).publicHome(body("id", MERCHANT_ID))).getFeatured());
    }
}
