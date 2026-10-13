package com.chengyinhub.business.util;

import com.chengyinhub.system.domain.SysCategory;

import java.util.List;

/** 公开分类出参投影：保留客户端分类字段，剥离后台操作人、时间与内部备注。 */
public final class CategoryPublicProjection {

    private CategoryPublicProjection() {
    }

    public static List<SysCategory> strip(List<SysCategory> categories) {
        if (categories == null) {
            return null;
        }
        categories.forEach(CategoryPublicProjection::strip);
        return categories;
    }

    public static SysCategory strip(SysCategory category) {
        if (category == null) {
            return null;
        }
        category.setCreateBy(null);
        category.setCreateTime(null);
        category.setUpdateBy(null);
        category.setUpdateTime(null);
        category.setRemark(null);
        category.setParams(null);
        strip(category.getSubCategoryList());
        return category;
    }
}
