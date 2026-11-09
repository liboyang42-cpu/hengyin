package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.support.ChapterRecruitmentRules;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M-16(2026-11-03 业主):商家行业不得出现在主题类别和玩家路线偏好里 —— 商家行业用自己的 type=6。
 * 主题类别 / 路线偏好照旧读 type=1;商家侧读 type=MERCHANT(存量 type 1 在前,type 6 在后),
 * 已有商家与章节引用的 type=1 记录继续可用。
 */
class MerchantIndustryCategoryTypeTest {

    private static SysCategory row(long id, long type, String name) {
        SysCategory c = new SysCategory();
        c.setId(id);
        c.setType(type);
        c.setParentId(0L);
        c.setCategoryName(name);
        c.setStatus(1);
        c.setDelFlag(0);
        return c;
    }

    private static ApiCategoryController controllerOver(List<SysCategory> table, List<Long> queriedTypes) {
        ISysCategoryService service = mock(ISysCategoryService.class);
        when(service.selectSysCategoryList(any())).thenAnswer(call -> {
            SysCategory filter = call.getArgument(0);
            queriedTypes.add(filter.getType());
            List<SysCategory> out = new ArrayList<>();
            for (SysCategory c : table) if (filter.getType() == null || filter.getType().equals(c.getType())) out.add(c);
            return out;
        });
        ApiCategoryController controller = new ApiCategoryController();
        ReflectionTestUtils.setField(controller, "sysCategoryService", service);
        return controller;
    }

    private static final List<SysCategory> TABLE = List.of(
            row(21, 1, "城市漫步"), row(4001, 1, "咖啡"), row(30, 6, "书店"), row(34, 6, "花店"));

    @SuppressWarnings("unchecked")
    private static List<String> names(AjaxResult r) {
        List<String> out = new ArrayList<>();
        for (SysCategory c : (List<SysCategory>) r.get("data")) out.add(c.getCategoryName());
        return out;
    }

    @Test
    void themeAndRoutePreferencePickersStillReadOnlyType1() {
        List<Long> types = new ArrayList<>();
        List<String> got = names(controllerOver(TABLE, types).categoryList("1", "0", null));
        assertEquals(List.of("城市漫步", "咖啡"), got);
        assertFalse(got.contains("书店"), "商家行业不能出现在主题类别 / 路线偏好里");
        assertEquals(List.of(1L), types);
    }

    @Test
    void merchantReadersGetLegacyType1FirstThenMerchantIndustries() {
        List<Long> types = new ArrayList<>();
        List<String> got = names(controllerOver(TABLE, types).categoryList("MERCHANT", "0", null));
        assertEquals(List.of("城市漫步", "咖啡", "书店", "花店"), got);
        assertEquals(List.of(1L, 6L), types);
    }

    @Test
    void chapterRecruitmentAcceptsBothMerchantTypesButNotOtherTypes() {
        assertTrue(ChapterRecruitmentRules.isMerchantCategory(30, row(30, 6, "书店")));
        assertTrue(ChapterRecruitmentRules.isMerchantCategory(4001, row(4001, 1, "咖啡")), "存量章节 / 商家的 type=1 继续解析");
        assertFalse(ChapterRecruitmentRules.isMerchantCategory(9, row(9, 2, "活动分类")));
        assertFalse(ChapterRecruitmentRules.isMerchantCategory(9, row(9, 4, "玩法类别")));
        assertEquals(Collections.emptyList(), Collections.emptyList());
    }
}
