package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/**
 * M7 公开目录的 SQL 直读行(内部，不直接对外序列化)。
 *
 * <p>只有显式列名、只取目录需要的窄列；<b>不</b>选 blocks_json / route_graph_json /
 * theme_runtime_config_json 等正文与运行配置 —— 分页查询不得拉全量正文。
 *
 * <p>这里允许出现 {@code lifecycle}、{@code clubId} 等内部事实：它们只在服务端用于
 * 推导 {@code saleState} / {@code deliveryModes}，投影到对外 VO 时不会出现。
 */
public class PublicContentCatalogRow {

    private Long id;
    private String cityCode;
    private String name;
    private String subtitle;
    private String description;
    private String imgUrl;
    private Integer productType;
    private Long totalTime;
    private BigDecimal totalMileage;
    private Long locationCount;
    private Integer lifecycle;
    private Integer selfPlay;
    private BigDecimal selfPlayPrice;
    private Integer teamMode;
    private Date saleEndAt;
    private Date endDate;
    private Long clubId;
    private Long publishedConfigVersion;
    private String publishedThemeRuntimeConfigJson;
    private Date updateTime;

    /** 落地元信息(无行为 null)，只在该行 status=1 时由 SQL 的 LEFT JOIN 条件带出。 */
    private String landingSummary;
    private String sourceLocale;
    private Integer groupEligible;
    private Integer schoolEligible;
    private Integer groupMinPeople;
    private Integer groupMaxPeople;
    private Integer ageMin;
    private Integer ageMax;
    private String accessibilityNote;

    /** 请求语言的可用译文(APPROVED 才有内容)；缺译时全为 null。 */
    private String translatedTitle;
    private String translatedSubtitle;
    private String translatedSummary;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getCityCode() { return cityCode; }

    public void setCityCode(String cityCode) { this.cityCode = cityCode; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getSubtitle() { return subtitle; }

    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public String getImgUrl() { return imgUrl; }

    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }

    public Integer getProductType() { return productType; }

    public void setProductType(Integer productType) { this.productType = productType; }

    public Long getTotalTime() { return totalTime; }

    public void setTotalTime(Long totalTime) { this.totalTime = totalTime; }

    public BigDecimal getTotalMileage() { return totalMileage; }

    public void setTotalMileage(BigDecimal totalMileage) { this.totalMileage = totalMileage; }

    public Long getLocationCount() { return locationCount; }

    public void setLocationCount(Long locationCount) { this.locationCount = locationCount; }

    public Integer getLifecycle() { return lifecycle; }

    public void setLifecycle(Integer lifecycle) { this.lifecycle = lifecycle; }

    public Integer getSelfPlay() { return selfPlay; }

    public void setSelfPlay(Integer selfPlay) { this.selfPlay = selfPlay; }

    public BigDecimal getSelfPlayPrice() { return selfPlayPrice; }

    public void setSelfPlayPrice(BigDecimal selfPlayPrice) { this.selfPlayPrice = selfPlayPrice; }

    public Integer getTeamMode() { return teamMode; }

    public void setTeamMode(Integer teamMode) { this.teamMode = teamMode; }

    public Date getSaleEndAt() { return saleEndAt; }

    public void setSaleEndAt(Date saleEndAt) { this.saleEndAt = saleEndAt; }

    public Date getEndDate() { return endDate; }

    public void setEndDate(Date endDate) { this.endDate = endDate; }

    public Long getClubId() { return clubId; }

    public void setClubId(Long clubId) { this.clubId = clubId; }

    public Long getPublishedConfigVersion() { return publishedConfigVersion; }

    public void setPublishedConfigVersion(Long publishedConfigVersion) {
        this.publishedConfigVersion = publishedConfigVersion;
    }

    public String getPublishedThemeRuntimeConfigJson() { return publishedThemeRuntimeConfigJson; }

    public void setPublishedThemeRuntimeConfigJson(String publishedThemeRuntimeConfigJson) {
        this.publishedThemeRuntimeConfigJson = publishedThemeRuntimeConfigJson;
    }

    public Date getUpdateTime() { return updateTime; }

    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    public String getLandingSummary() { return landingSummary; }

    public void setLandingSummary(String landingSummary) { this.landingSummary = landingSummary; }

    public String getSourceLocale() { return sourceLocale; }

    public void setSourceLocale(String sourceLocale) { this.sourceLocale = sourceLocale; }

    public Integer getGroupEligible() { return groupEligible; }

    public void setGroupEligible(Integer groupEligible) { this.groupEligible = groupEligible; }

    public Integer getSchoolEligible() { return schoolEligible; }

    public void setSchoolEligible(Integer schoolEligible) { this.schoolEligible = schoolEligible; }

    public Integer getGroupMinPeople() { return groupMinPeople; }

    public void setGroupMinPeople(Integer groupMinPeople) { this.groupMinPeople = groupMinPeople; }

    public Integer getGroupMaxPeople() { return groupMaxPeople; }

    public void setGroupMaxPeople(Integer groupMaxPeople) { this.groupMaxPeople = groupMaxPeople; }

    public Integer getAgeMin() { return ageMin; }

    public void setAgeMin(Integer ageMin) { this.ageMin = ageMin; }

    public Integer getAgeMax() { return ageMax; }

    public void setAgeMax(Integer ageMax) { this.ageMax = ageMax; }

    public String getAccessibilityNote() { return accessibilityNote; }

    public void setAccessibilityNote(String accessibilityNote) { this.accessibilityNote = accessibilityNote; }

    public String getTranslatedTitle() { return translatedTitle; }

    public void setTranslatedTitle(String translatedTitle) { this.translatedTitle = translatedTitle; }

    public String getTranslatedSubtitle() { return translatedSubtitle; }

    public void setTranslatedSubtitle(String translatedSubtitle) { this.translatedSubtitle = translatedSubtitle; }

    public String getTranslatedSummary() { return translatedSummary; }

    public void setTranslatedSummary(String translatedSummary) { this.translatedSummary = translatedSummary; }
}
