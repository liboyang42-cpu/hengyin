package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * relation-home 的 discovery.merchants 公开投影:键集逐个冻结,多一个少一个都判红。
 */
class MerchantDiscoveryMemberIdProjectionTest {

    /** 冻结的公开投影键集。改这里之前先确认新字段确实允许公开。
     *  2026-08-20 卡片重设计新增 coverImage/businessStatus/locationVerified:
     *  三者都在 /api/merchant/list 公开脱敏响应里,允许公开。
     *  2026-09-25 CU-M-61 新增 sysCategoryList(品类 id + categoryName):与 /api/merchant/list
     *  公开响应里同一份 PublicCategoryVO 形状 —— 商家卡无图时按品类出图标要靠它。
     *  CU-M-61 后续卡片字段与 PublicMerchantDiscoveryVO 的公开营业/承接信息一致，
     *  精确键集继续排除 phone/wechat 等联系人与审核字段。 */
    private static final List<String> EXPECTED_KEYS =
            Arrays.asList("id", "memberId", "name", "logo", "categoryId", "address", "cityRole",
                    "coverImage", "businessStatus", "locationVerified", "businessTime", "availableTime",
                    "tags", "capacity", "chargeType", "suitActivityTypes", "coopOpen", "sysCategoryList");

    @Test
    @SuppressWarnings("unchecked")
    void discoveryMerchantsAddOnlyMemberIdToPublicProjection() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        IClubService clubs = mock(IClubService.class);
        ISysCategoryService categories = mock(ISysCategoryService.class);

        MmsMerchant viewer = new MmsMerchant();
        viewer.setId(1010L);
        viewer.setMemberId(10L);
        viewer.setStatus(1L);
        viewer.setDelFlag(0);
        when(merchants.selectMmsMerchantByMemberId(10L)).thenReturn(viewer);

        MmsMerchant peer = new MmsMerchant();
        peer.setId(1020L);
        peer.setMemberId(20L);
        peer.setName("邻店");
        peer.setLogo("logo.png");
        peer.setCategoryId(3);
        peer.setAddress("某路 1 号");
        peer.setCityRole("城市补给站");
        peer.setStatus(1L);
        peer.setDelFlag(0);
        peer.setPhone("13800000001");
        peer.setWechat("wx-private");
        when(merchants.selectMmsMerchantList(any(MmsMerchant.class)))
                .thenReturn((List) Collections.singletonList(peer));
        when(clubs.selectClubList(any(Club.class))).thenReturn(Collections.<Club>emptyList());
        SysCategory bookstore = new SysCategory();
        bookstore.setId(3L);
        bookstore.setCategoryName("书店");
        when(categories.selectSysCategoryListByIds(any(String[].class)))
                .thenReturn(Collections.singletonList(bookstore));

        MerchantAggregateReadServiceImpl service = new MerchantAggregateReadServiceImpl(
                merchants, clubs, mock(IClubMemberService.class), mock(CoopInviteMapper.class),
                mock(ICmsTopicService.class),
                mock(ISmsCouponService.class), mock(ICmsActivityService.class),
                mock(MerchantMarketingAnalyticsMapper.class), mock(CmsTopicChapterMapper.class),
                categories);

        Map<String, Object> result = service.relationHome(10L, null, null, null, 10);
        Map<String, Object> discovery = (Map<String, Object>) result.get("discovery");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) discovery.get("merchants");

        assertEquals(1, rows.size());
        assertEquals(20L, rows.get(0).get("memberId"));
        assertEquals(EXPECTED_KEYS, new java.util.ArrayList<String>(rows.get(0).keySet()));
        @SuppressWarnings("unchecked")
        List<PublicMemberVO.PublicCategoryVO> own =
                (List<PublicMemberVO.PublicCategoryVO>) rows.get(0).get("sysCategoryList");
        assertEquals(1, own.size(), "品类名不下发,前端就只能全员同一只咖啡杯(书店与花店长得一样)");
        assertEquals(3L, own.get(0).getId());
        assertEquals("书店", own.get(0).getCategoryName());
    }
}
