package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsTemplateLibrary;

public class TemplateLibraryVO extends CmsTemplateLibrary {

    private String categoryName;
    private String icon;

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }
}
