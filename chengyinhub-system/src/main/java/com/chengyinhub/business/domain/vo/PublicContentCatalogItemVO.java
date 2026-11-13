package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * M7 公开目录「完整卡」投影。只放售前可公开事实，字段来源全部可追到既有真源：
 *
 * <ul>
 *   <li>title/subtitle/summary：{@code cms_topic} + 公开落地译文(缺译回退并标 {@code translationState})；</li>
 *   <li>totalTime/totalMileage/locationCount：{@code cms_topic} 自身列；</li>
 *   <li>chapterCount：{@code cms_topic_chapter} 批量计数；</li>
 *   <li>priceFrom/priceState：{@code cms_topic.self_play_price} 与 live {@code oms_ticket.price} 的只读聚合；</li>
 *   <li>deliveryModes：{@code self_play} 与 live 票种 {@code sub_type/mode} 与 {@code club_id} 推导；</li>
 *   <li>group/school/年龄/无障碍：{@code content_landing_metadata} 作者显式配置。</li>
 * </ul>
 *
 * <p><b>不含</b>：答案、提示、blocks 正文、角色私密线索、未解锁章节、商家邮箱/手机号、
 * 核销码、审核与分账字段、内部 lifecycle 数值。展示内容对应可售修订时另由 {@link PublishRevisionVO} 标注。
 */
public class PublicContentCatalogItemVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 稳定 SEO 路由；由原 topic id 拼出，不建任何路由映射表。 */
    private String canonicalPath;

    private String cityCode;

    /** 城市名只在真实数据(俱乐部所在城市)对同一 city_code 唯一一致时给出；否则 null。 */
    private String cityName;

    private String name;

    private String subtitle;

    private String summary;

    private String imgUrl;

    private Integer productType;

    private Long totalTime;

    private BigDecimal totalMileage;

    private Long locationCount;

    private Integer chapterCount;

    private List<String> deliveryModes;

    /** SELF_PLAY_PASS / TICKET / null(不可读价)；UI 不能把票种价当通行证价。 */
    private String priceUnit;

    private BigDecimal priceFrom;

    /** KNOWN / UNKNOWN；UNKNOWN 时 UI 必须显示「价格待确认」，不得用旧价完成购买。 */
    private String priceState;

    /**
     * ON_SALE / SALE_ENDED / NOT_ON_SALE：只读投影，真正下单仍走既有售卖闸
     * (SaleAvailabilityPolicy / 免费主题在架判定)。免费可得性由 {@link #freeSelfPlay} 单列表达，
     * 不塞进本枚举，避免把两种购买路径混成一个状态。
     */
    private String saleState;

    /**
     * 事实：开放自玩票且自玩价为 0、未过主题截止。
     * 只表示「有免费自玩入口」，不表示已通过商业审核；付费自玩的可得性看 {@link #saleState}。
     */
    private Boolean freeSelfPlay;

    /** null = 未声明(不是「不接待」)。 */
    private Boolean groupEligible;

    private Boolean schoolEligible;

    private Integer groupMinPeople;

    private Integer groupMaxPeople;

    private Integer ageMin;

    private Integer ageMax;

    private String accessibilityNote;

    /** 请求语言，原样回显归一化后的值。 */
    private String requestedLocale;

    /** 实际返回文案的语言。 */
    private String actualLocale;

    /** TRANSLATED / FALLBACK / SOURCE；FALLBACK 表示缺译回退，不得展示成已翻译。 */
    private String translationState;
    /**
     * 逐字段完成度：title / subtitle / summary 各自为 TRANSLATED / FALLBACK。
     * 简介翻译完而标题没译时，不能对外说「已支持该语言」。
     */
    private java.util.Map<String, String> translationFields;

    /** 该作品真实存在的可用译文语言(只列 APPROVED)。 */
    private List<String> availableLocales;

    private PublishRevisionVO revision;

    /** 内容事实的最后更新时间，用于页面「更新时间」；不等于实地核验时间。 */
    private Date updatedAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getCanonicalPath() { return canonicalPath; }

    public void setCanonicalPath(String canonicalPath) { this.canonicalPath = canonicalPath; }

    public String getCityCode() { return cityCode; }

    public void setCityCode(String cityCode) { this.cityCode = cityCode; }

    public String getCityName() { return cityName; }

    public void setCityName(String cityName) { this.cityName = cityName; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getSubtitle() { return subtitle; }

    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public String getSummary() { return summary; }

    public void setSummary(String summary) { this.summary = summary; }

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

    public Integer getChapterCount() { return chapterCount; }

    public void setChapterCount(Integer chapterCount) { this.chapterCount = chapterCount; }

    public List<String> getDeliveryModes() { return deliveryModes; }

    public void setDeliveryModes(List<String> deliveryModes) { this.deliveryModes = deliveryModes; }

    public String getPriceUnit() { return priceUnit; }

    public void setPriceUnit(String priceUnit) { this.priceUnit = priceUnit; }

    public BigDecimal getPriceFrom() { return priceFrom; }

    public void setPriceFrom(BigDecimal priceFrom) { this.priceFrom = priceFrom; }

    public String getPriceState() { return priceState; }

    public void setPriceState(String priceState) { this.priceState = priceState; }

    public String getSaleState() { return saleState; }

    public void setSaleState(String saleState) { this.saleState = saleState; }
    public Boolean getFreeSelfPlay() { return freeSelfPlay; }

    public void setFreeSelfPlay(Boolean freeSelfPlay) { this.freeSelfPlay = freeSelfPlay; }

    /** 当前登录玩家对此主题有有效通行证覆盖；匿名或未覆盖为 false。 */
    private boolean passCovered;

    public boolean isPassCovered() { return passCovered; }

    public void setPassCovered(boolean passCovered) { this.passCovered = passCovered; }

    public Boolean getGroupEligible() { return groupEligible; }

    public void setGroupEligible(Boolean groupEligible) { this.groupEligible = groupEligible; }

    public Boolean getSchoolEligible() { return schoolEligible; }

    public void setSchoolEligible(Boolean schoolEligible) { this.schoolEligible = schoolEligible; }

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

    public String getRequestedLocale() { return requestedLocale; }

    public void setRequestedLocale(String requestedLocale) { this.requestedLocale = requestedLocale; }

    public String getActualLocale() { return actualLocale; }

    public void setActualLocale(String actualLocale) { this.actualLocale = actualLocale; }

    public String getTranslationState() { return translationState; }

    public void setTranslationState(String translationState) { this.translationState = translationState; }
    public java.util.Map<String, String> getTranslationFields() { return translationFields; }

    public void setTranslationFields(java.util.Map<String, String> translationFields) {
        this.translationFields = translationFields;
    }

    public List<String> getAvailableLocales() { return availableLocales; }

    public void setAvailableLocales(List<String> availableLocales) { this.availableLocales = availableLocales; }

    public PublishRevisionVO getRevision() { return revision; }

    public void setRevision(PublishRevisionVO revision) { this.revision = revision; }

    public Date getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
