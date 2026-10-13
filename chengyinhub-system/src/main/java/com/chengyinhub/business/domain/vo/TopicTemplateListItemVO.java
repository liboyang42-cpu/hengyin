package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 匿名主题模板货架白名单。独立 POJO，CmsTopic 新增内部字段时不会自动进入公开响应。
 *
 * @author chengyinhub
 * @date 2026-07-19
 */
public class TopicTemplateListItemVO {

    @Schema(description = "主题ID")
    private Long id;

    @Schema(description = "主题名称")
    private String name;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "主题简介")
    private String description;

    @Schema(description = "封面图")
    private String imgUrl;

    @Schema(description = "横图轮播（分号分隔）")
    private String imgArr;

    @Schema(description = "产品类型：1经典定向 2自由定向")
    private Integer productType;

    @Schema(description = "圈层主题代码")
    private String circleThemeCode;

    @Schema(description = "总时长（秒）")
    private Long totalTime;

    @Schema(description = "总里程（KM）")
    private BigDecimal totalMileage;

    @Schema(description = "地点数")
    private Long locationCount;

    @Schema(description = "玩法模板数")
    private Long templateCount;

    @Schema(description = "主题分类ID，逗号分隔")
    private String categoryIds;

    @Schema(description = "章节数")
    private Integer chapterCount;

    @Schema(description = "玩法数（去重）")
    private Integer gameCount;

    // 货架列表要能区分「实验模板」与「已验证模板」——指南明令二者不得混排，
    // 而不下发状态的话前端根本无从区分。这两个字段是列表页做这件事的最小集。

    @Schema(description = "模板状态:EXPERIMENTAL实验/PILOT试点/VERIFIED已验证/RETIRED停用。"
            + "未录入元数据时不下发,前端按「实验」保守渲染,不得当作已验证")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String templateStatus;

    @Schema(description = "证据等级:NONE/ESTIMATED/PILOTED/VERIFIED。未录入时不下发")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String evidenceLevel;

    @Schema(description = "1=官方范本(平台官方账号出品)。未录入元数据时不下发,前端按非官方渲染")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer isOfficial;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getImgArr() { return imgArr; }
    public void setImgArr(String imgArr) { this.imgArr = imgArr; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer productType) { this.productType = productType; }
    public String getCircleThemeCode() { return circleThemeCode; }
    public void setCircleThemeCode(String circleThemeCode) { this.circleThemeCode = circleThemeCode; }
    public Long getTotalTime() { return totalTime; }
    public void setTotalTime(Long totalTime) { this.totalTime = totalTime; }
    public BigDecimal getTotalMileage() { return totalMileage; }
    public void setTotalMileage(BigDecimal totalMileage) { this.totalMileage = totalMileage; }
    public Long getLocationCount() { return locationCount; }
    public void setLocationCount(Long locationCount) { this.locationCount = locationCount; }
    public Long getTemplateCount() { return templateCount; }
    public void setTemplateCount(Long templateCount) { this.templateCount = templateCount; }
    public String getCategoryIds() { return categoryIds; }
    public void setCategoryIds(String categoryIds) { this.categoryIds = categoryIds; }
    public Integer getChapterCount() { return chapterCount; }
    public void setChapterCount(Integer chapterCount) { this.chapterCount = chapterCount; }
    public Integer getGameCount() { return gameCount; }
    public void setGameCount(Integer gameCount) { this.gameCount = gameCount; }
    public String getTemplateStatus() { return templateStatus; }
    public void setTemplateStatus(String templateStatus) { this.templateStatus = templateStatus; }
    public String getEvidenceLevel() { return evidenceLevel; }
    public void setEvidenceLevel(String evidenceLevel) { this.evidenceLevel = evidenceLevel; }
    public Integer getIsOfficial() { return isOfficial; }
    public void setIsOfficial(Integer isOfficial) { this.isOfficial = isOfficial; }
}
