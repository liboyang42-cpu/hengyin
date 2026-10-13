package com.chengyinhub.business.domain.vo;


import com.chengyinhub.business.domain.vo.TemplateLibraryVO;

import java.util.List;

public class CategoryTemplatesVO {

    private Long categoryId;
    private String categoryName;
    private Integer level;
    private String icon;
    private Long parentId;
    private Integer sortId;
    private List<TemplateLibraryVO> templates;

    // Getter和Setter方法
    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getSortId() {
        return sortId;
    }

    public void setSortId(Integer sortId) {
        this.sortId = sortId;
    }

    public List<TemplateLibraryVO> getTemplates() {
        return templates;
    }

    public void setTemplates(List<TemplateLibraryVO> templates) {
        this.templates = templates;
    }
}