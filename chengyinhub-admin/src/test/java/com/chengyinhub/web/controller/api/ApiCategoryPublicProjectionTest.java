package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 公开分类接口只返回客户端需要的分类数据，不暴露后台操作人和内部备注。 */
class ApiCategoryPublicProjectionTest {

    @SuppressWarnings("unchecked")
    @Test
    void publicListKeepsCategoryShapeButStripsAuditMetadataRecursively() {
        ISysCategoryService service = mock(ISysCategoryService.class);
        ApiCategoryController controller = new ApiCategoryController();
        ReflectionTestUtils.setField(controller, "sysCategoryService", service);

        SysCategory child = category(42L, "展览", "child-admin");
        SysCategory parent = category(4L, "城市探索", "root-admin");
        parent.setSubCategoryList(Collections.singletonList(child));
        when(service.selectSysCategoryList(any())).thenReturn(Collections.singletonList(parent));

        AjaxResult result = controller.categoryList("4", "0", null);

        assertEquals(200, result.get("code"));
        SysCategory visible = ((List<SysCategory>) result.get("data")).get(0);
        assertEquals("城市探索", visible.getCategoryName());
        assertAuditMetadataStripped(visible);
        assertAuditMetadataStripped(visible.getSubCategoryList().get(0));
    }

    private SysCategory category(Long id, String name, String operator) {
        SysCategory category = new SysCategory();
        category.setId(id);
        category.setType(4L);
        category.setCategoryName(name);
        category.setCreateBy(operator);
        category.setCreateTime(new Date());
        category.setUpdateBy(operator);
        category.setUpdateTime(new Date());
        category.setRemark("内部备注");
        return category;
    }

    private void assertAuditMetadataStripped(SysCategory category) {
        assertNull(category.getCreateBy());
        assertNull(category.getCreateTime());
        assertNull(category.getUpdateBy());
        assertNull(category.getUpdateTime());
        assertNull(category.getRemark());
    }
}
