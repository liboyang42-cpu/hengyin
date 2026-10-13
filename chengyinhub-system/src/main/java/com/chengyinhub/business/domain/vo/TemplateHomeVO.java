package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.system.domain.SysCategory;

import java.util.List;

/**
 * 模板库首页聚合数据（App Store 风格）
 */
public class TemplateHomeVO {

    /** 公共模板总数（顶部角标） */
    private Integer total;

    /** 分类 Tab 列表 */
    private List<SysCategory> categoryList;

    /** 大 Banner 轮播（自动精选，useNum 倒序前 N） */
    private List<CmsTemplateLibrary> bannerList;

    /** 最好插件 列表（自动精选） */
    private List<CmsTemplateLibrary> recommendList;

    /** 必玩精选 三宫格（自动精选） */
    private List<CmsTemplateLibrary> mustPlayList;

    /** 热门模板榜（useNum 倒序排行） */
    private List<CmsTemplateLibrary> hotList;

    /** 世纪一刻（最新发布，createTime 倒序） */
    private List<CmsTemplateLibrary> latestList;

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public List<SysCategory> getCategoryList() {
        return categoryList;
    }

    public void setCategoryList(List<SysCategory> categoryList) {
        this.categoryList = categoryList;
    }

    public List<CmsTemplateLibrary> getBannerList() {
        return bannerList;
    }

    public void setBannerList(List<CmsTemplateLibrary> bannerList) {
        this.bannerList = bannerList;
    }

    public List<CmsTemplateLibrary> getRecommendList() {
        return recommendList;
    }

    public void setRecommendList(List<CmsTemplateLibrary> recommendList) {
        this.recommendList = recommendList;
    }

    public List<CmsTemplateLibrary> getMustPlayList() {
        return mustPlayList;
    }

    public void setMustPlayList(List<CmsTemplateLibrary> mustPlayList) {
        this.mustPlayList = mustPlayList;
    }

    public List<CmsTemplateLibrary> getHotList() {
        return hotList;
    }

    public void setHotList(List<CmsTemplateLibrary> hotList) {
        this.hotList = hotList;
    }

    public List<CmsTemplateLibrary> getLatestList() {
        return latestList;
    }

    public void setLatestList(List<CmsTemplateLibrary> latestList) {
        this.latestList = latestList;
    }
}
