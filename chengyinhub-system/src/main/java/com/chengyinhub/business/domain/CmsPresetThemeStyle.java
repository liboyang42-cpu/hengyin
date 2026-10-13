package com.chengyinhub.business.domain;

import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 建议版主题风格预设对象 preset_theme_style
 *
 * @author chengyin
 */
public class CmsPresetThemeStyle extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 风格ID */
    private Long id;

    /** 风格名称 */
    @Excel(name = "风格名称")
    private String name;

    /** 封面图URL */
    private String coverImg;

    /** 一组 --cy- token 色值(JSON) */
    private String toneTokens;

    /** 默认主题标题文案 */
    private String defaultTitle;

    /** 默认副标题文案 */
    private String defaultSubtitle;

    /** 默认简介/规则文案 */
    private String defaultDesc;

    /** 默认分类ID(逗号分隔) */
    private String defaultCategoryIds;

    /** 排序(小在前) */
    private Integer sort;

    /** 上下架 1上架 0下架 */
    @Excel(name = "状态", readConverterExp = "1=上架,0=下架")
    private String status;

    /** 删除标志 0存在 2删除 */
    private Integer delFlag;

    public void setId(Long id) { this.id = id; }
    public Long getId() { return id; }
    public void setName(String name) { this.name = name; }
    public String getName() { return name; }
    public void setCoverImg(String coverImg) { this.coverImg = coverImg; }
    public String getCoverImg() { return coverImg; }
    public void setToneTokens(String toneTokens) { this.toneTokens = toneTokens; }
    public String getToneTokens() { return toneTokens; }
    public void setDefaultTitle(String defaultTitle) { this.defaultTitle = defaultTitle; }
    public String getDefaultTitle() { return defaultTitle; }
    public void setDefaultSubtitle(String defaultSubtitle) { this.defaultSubtitle = defaultSubtitle; }
    public String getDefaultSubtitle() { return defaultSubtitle; }
    public void setDefaultDesc(String defaultDesc) { this.defaultDesc = defaultDesc; }
    public String getDefaultDesc() { return defaultDesc; }
    public void setDefaultCategoryIds(String defaultCategoryIds) { this.defaultCategoryIds = defaultCategoryIds; }
    public String getDefaultCategoryIds() { return defaultCategoryIds; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getSort() { return sort; }
    public void setStatus(String status) { this.status = status; }
    public String getStatus() { return status; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Integer getDelFlag() { return delFlag; }
}
