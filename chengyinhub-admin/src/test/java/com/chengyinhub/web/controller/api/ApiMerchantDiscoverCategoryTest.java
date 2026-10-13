package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;
import com.chengyinhub.business.domain.vo.PublicMerchantDiscoveryVO;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CU-M-61(2026-09-24 走查)· 合作商家卡片缺图时统一显示同一只咖啡杯。
 *
 * 根因一半在后端:发现列表(/api/merchant/list)不回品类名,前端即便想按品类出图也无依据,
 * 只能全员退化到写死的兜底图标。这里钉两件事:
 *   ① 行里带上 sysCategoryList(只出 id/categoryName,与 public-detail 同一份白名单);
 *   ② 整页只查一次分类表,不按行重复查库。
 */
class ApiMerchantDiscoverCategoryTest {

    /** startPage() 依赖绑定的 Servlet 请求,单测里置空;发现列表接口无需登录。 */
    private static class TestController extends ApiMerchantController {
        @Override
        protected void startPage() { /* no-op */ }
    }

    private static MmsMerchant merchant(long id, Integer categoryId) {
        MmsMerchant m = new MmsMerchant();
        m.setId(id);
        m.setMemberId(id + 100L);
        m.setName("商家" + id);
        m.setCategoryId(categoryId);
        m.setStatus(1L);
        m.setAccountStatus(1L);
        m.setDelFlag(0);
        return m;
    }

    private static SysCategory category(long id, String name) {
        SysCategory c = new SysCategory();
        c.setId(id);
        c.setCategoryName(name);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<PublicMerchantDiscoveryVO> rowsOf(AjaxResult result) {
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        return (List<PublicMerchantDiscoveryVO>) result.get(AjaxResult.DATA_TAG);
    }

    @Test
    void discoveryRowsCarryCategoryNamesAndResolveThemInOneBatchQuery() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        ISysCategoryService categories = mock(ISysCategoryService.class);
        ApiMerchantController controller = new TestController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "sysCategoryService", categories);

        when(merchants.selectMmsMerchantList(any(MmsMerchant.class))).thenReturn(Arrays.asList(
                merchant(9002L, 3),          // 书店:单品类
                merchant(9003L, 1),          // 咖啡店:单品类
                merchant(9004L, null)        // 资料没填品类
        ));
        when(categories.selectSysCategoryListByIds(any())).thenReturn(Arrays.asList(
                category(1L, "美食咖啡"), category(3L, "文化叙事")));

        List<PublicMerchantDiscoveryVO> rows = rowsOf(controller.merchantList(new MerchantSearchQuery()));

        assertEquals(3, rows.size());
        assertEquals("文化叙事", rows.get(0).getSysCategoryList().get(0).getCategoryName(),
                "书店的卡片必须拿得到自己的品类名(前端据此选兜底图标)");
        assertEquals("美食咖啡", rows.get(1).getSysCategoryList().get(0).getCategoryName());
        assertNull(rows.get(2).getSysCategoryList(), "没填品类就不给空壳列表");

        ArgumentCaptor<String[]> idsArg = ArgumentCaptor.forClass(String[].class);
        verify(categories, times(1)).selectSysCategoryListByIds(idsArg.capture());
        Set<String> asked = new LinkedHashSet<>(Arrays.asList(idsArg.getValue()));
        assertEquals(new LinkedHashSet<>(Arrays.asList("1", "3")), asked,
                "整页一次查完去重后的分类 id,不按行重复查库");
    }

    @Test
    void rowsWithoutAnyCategoryIdDoNotQueryTheCategoryTable() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        ISysCategoryService categories = mock(ISysCategoryService.class);
        ApiMerchantController controller = new TestController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "sysCategoryService", categories);

        when(merchants.selectMmsMerchantList(any(MmsMerchant.class)))
                .thenReturn(Arrays.asList(merchant(9004L, null)));

        List<PublicMerchantDiscoveryVO> rows = rowsOf(controller.merchantList(new MerchantSearchQuery()));
        assertNull(rows.get(0).getSysCategoryList());
        verify(categories, times(0)).selectSysCategoryListByIds(any());
    }
}
