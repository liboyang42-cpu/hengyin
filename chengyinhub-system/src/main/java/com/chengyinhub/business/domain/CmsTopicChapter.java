package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 主题章节对象 cms_topic_chapter
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
public class CmsTopicChapter extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 主题的ID */
    @Excel(name = "主题的ID")
    private Long topicId;

    /** 章节名称 */
    @Excel(name = "章节名称")
    private String name;

    /** 描述 */
    @Excel(name = "描述")
    private String description;

    /** blocks 列原始 JSON 文本，仅供持久化读取。 */
    private String blocksJson;

    /** 解析后的章节内容块，供接口返回。 */
    private transient List<Object> blocks;

    /** 章节剧情/谜题层氛围预设；不是全 App 皮肤。 */
    private String atmospherePreset;

    /** 本章背景旁白；玩家进入本章自动播放。一章最多一段（2026-09-04 起音频不再是 blocks 里的块）。 */
    private String audioUrl;

    /** 图片（多张）分号隔开 */
    @Excel(name = "图片", readConverterExp = "多=张")
    private String imgArr;

    /** 总时长，单位秒 */
    @Excel(name = "总时长，单位秒")
    private Long totalTime;

    /** 总里程KM */
    @Excel(name = "总里程KM")
    private BigDecimal totalMileage;

    /** 地点数 */
    @Excel(name = "地点数")
    private Long locationCount;

    /** 模版数 */
    @Excel(name = "模版数")
    private Long templateCount;

    @Excel(name = "模版数")
    private Long sortId;

    /** 章节品类，用于把章节和能承接它的商家匹配起来（商家按品类找章节，不是靠人工指派） */
    @Excel(name = "章节品类")
    private String category;

    /** 受控商家品类ID。保留 category 自由文案兼容存量章节，招商匹配只认本字段。 */
    @Excel(name = "适合商家品类ID")
    private Integer categoryId;

    /** V1 招商总闸：0未开放 1开放。绝不能再从 maxMerchant 推导。 */
    @Excel(name = "是否开放商家承接")
    private Integer recruitEnabled;

    /** 本章结束的时刻。稿 468:1126「结束本章」写的就是它。
     *  ⚠️ 不可逆:一旦有值,这一章不再接受商家承接申请,也不能重新开放。
     *     用时刻而不是布尔,是因为「什么时候结束的」本身要能回答(结算与复盘都要问)。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date finishTime;

    /** 入驻门槛：商家权益的零售标价须≥此值。
     *  ⚠️ 比的是 CoopPerk.retailValue（玩家眼里这份权益值多少），不是 unitCost——
     *  unitCost 是「单人成本价·商家自报」，那是给定价引擎算成本用的，拿它当门槛会让高毛利商家轻松过线。
     *  空=本章不设门槛。 */
    @Excel(name = "权益零售价门槛")
    private BigDecimal perkMinValue;

    /** 本章节最多可承接的商家数。超过就不再放商家进来，避免一章挤满商家稀释每家的曝光 */
    @Excel(name = "最多承接商家数")
    private Integer maxMerchant;

    /** 1必选章（计入通关判定与容量预占） 0可选章。
     *  库默认 1 = 保持存量「每章都算」的既有语义，加这列不改变任何已有主题的行为 */
    @Excel(name = "是否必选章")
    private Integer required;

    /** D21 供给条款档（{@link com.chengyinhub.common.constant.TermsMode}）：PERK/REVSHARE/TRAFFIC。
     *  ★ 章节才是档位口径真源，不是 offer —— D5承诺按章展示、感知价值只加总权益档章节、
     *  quota容量链只对权益档成立，这三句若只挂 offer，混档章节里全都答不出来。
     *  offer.termsMode 是入驻校验的对象（须与本列一致）且是快照，二者可能不一致。
     *  库默认 PERK = 从严：它是唯一有容量约束的档 */
    @Excel(name = "供给条款档")
    private String termsMode;

    /** 圈层模板中的供给角色候选码，如 A1/O1/K1/H1/N1。 */
    private String circleCandidateCode;

    /** EXPERIENCE/REST/EXPRESSION；城市实例发布时三类必须齐全。 */
    private String circleRole;

    /** COMMON/WORKDAY_EVENING/SOLO/PARENT_CHILD/PHOTO/NIGHT。 */
    private String circlePolicy;

    /** 1=城市实例启用该候选，0=候选仍保留但不进入发布阵容。 */
    private Integer circleEnabled;

    /** 计酬档人头费上限（元/人次）：商家 enroll 时自报的 offer.perHeadFee 不得超过本值。
     *  ★ 为什么必须有：计酬档是**平台按核销真金白银付**，而 perHeadFee 由商家在 enroll 自报；
     *  P0-50 必亏闸挂在 confirmPrice（定价时），对定价后新增/改价的 offer **不复查**
     *  ⇒ 没有本列，商家可自挂天价人头费，平台照付且零告警。
     *  ⚠️ 空 = **拒绝计酬档入驻**（fail-closed），不是「不设上限」——
     *  与同章的 perkMinValue「空=不设门槛」相反,因为那是质量门槛(商家承诺)、这是平台成本上界。
     *  口径同 REVSHARE 既有的 isPlatformOwned fail-closed:宁可当场拒,不静默放过掏钱的口子。 */
    @Excel(name = "计酬档人头费上限")
    private BigDecimal maxPerHeadFee;

    /**
     * 本章节允许商家使用的验证方式,逗号分隔如 "1,3,4"。NULL = 不限(存量语义)。
     * ★ 边界在**招商时公示**:商家申请前就看得到,不是做完了才被告知超标。
     */
    private String allowedValidationMethods;

    /** 单节点探索值上限,商家节点不得超过。NULL = 不限。 */
    private Integer maxNodeXp;

    /** 新生活装机计划需求组：1生存效率 2自我安顿 3城市归属；NULL 不参与分组完成规则。 */
    private Integer needGroup;


    @Schema(description = "节点列表")
    private List<CmsTopicNode> cmsTopicNodeList;

    public List<CmsTopicNode> getCmsTopicNodeList() {
        return cmsTopicNodeList;
    }

    public void setCmsTopicNodeList(List<CmsTopicNode> cmsTopicNodeList) {
        this.cmsTopicNodeList = cmsTopicNodeList;
    }

    public Long getSortId() {
        return sortId;
    }

    public void setSortId(Long sortId) {
        this.sortId = sortId;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setTopicId(Long topicId) 
    {
        this.topicId = topicId;
    }

    public Long getTopicId() 
    {
        return topicId;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }

    @JsonIgnore
    public String getBlocksJson()
    {
        return blocksJson;
    }

    public void setBlocksJson(String blocksJson)
    {
        this.blocksJson = blocksJson;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<Object> getBlocks()
    {
        return blocks;
    }

    public void setBlocks(List<Object> blocks)
    {
        this.blocks = blocks;
    }

    public String getAtmospherePreset()
    {
        return atmospherePreset;
    }

    public void setAtmospherePreset(String atmospherePreset)
    {
        this.atmospherePreset = atmospherePreset;
    }

    public String getAudioUrl()
    {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl)
    {
        this.audioUrl = audioUrl;
    }

    public void setImgArr(String imgArr) 
    {
        this.imgArr = imgArr;
    }

    public String getImgArr() 
    {
        return imgArr;
    }

    public void setTotalTime(Long totalTime) 
    {
        this.totalTime = totalTime;
    }

    public Long getTotalTime() 
    {
        return totalTime;
    }

    public void setTotalMileage(BigDecimal totalMileage) 
    {
        this.totalMileage = totalMileage;
    }

    public BigDecimal getTotalMileage() 
    {
        return totalMileage;
    }

    public void setLocationCount(Long locationCount) 
    {
        this.locationCount = locationCount;
    }

    public Long getLocationCount() 
    {
        return locationCount;
    }

    public void setTemplateCount(Long templateCount) 
    {
        this.templateCount = templateCount;
    }

    public Long getTemplateCount() 
    {
        return templateCount;
    }

    public void setCategory(String category)
    {
        this.category = category;
    }

    public String getCategory()
    {
        return category;
    }

    public void setCategoryId(Integer categoryId)
    {
        this.categoryId = categoryId;
    }

    public Integer getCategoryId()
    {
        return categoryId;
    }

    public void setRecruitEnabled(Integer recruitEnabled)
    {
        this.recruitEnabled = recruitEnabled;
    }

    public Integer getRecruitEnabled()
    {
        return recruitEnabled;
    }

    public void setMaxPerHeadFee(BigDecimal maxPerHeadFee)
    {
        this.maxPerHeadFee = maxPerHeadFee;
    }

    public String getAllowedValidationMethods()
    {
        return allowedValidationMethods;
    }

    public void setAllowedValidationMethods(String allowedValidationMethods)
    {
        this.allowedValidationMethods = allowedValidationMethods;
    }

    public Integer getMaxNodeXp()
    {
        return maxNodeXp;
    }

    public void setMaxNodeXp(Integer maxNodeXp)
    {
        this.maxNodeXp = maxNodeXp;
    }

    public Integer getNeedGroup()
    {
        return needGroup;
    }

    public void setNeedGroup(Integer needGroup)
    {
        this.needGroup = needGroup;
    }

    public BigDecimal getMaxPerHeadFee()
    {
        return maxPerHeadFee;
    }

    public void setPerkMinValue(BigDecimal perkMinValue)
    {
        this.perkMinValue = perkMinValue;
    }

    public BigDecimal getPerkMinValue()
    {
        return perkMinValue;
    }

    public void setMaxMerchant(Integer maxMerchant)
    {
        this.maxMerchant = maxMerchant;
    }

    public Integer getMaxMerchant()
    {
        return maxMerchant;
    }

    public void setRequired(Integer required)
    {
        this.required = required;
    }

    public Integer getRequired()
    {
        return required;
    }

    public void setTermsMode(String termsMode)
    {
        this.termsMode = termsMode;
    }

    public String getTermsMode()
    {
        return termsMode;
    }

    public String getCircleCandidateCode() { return circleCandidateCode; }
    public void setCircleCandidateCode(String circleCandidateCode) { this.circleCandidateCode = circleCandidateCode; }
    public String getCircleRole() { return circleRole; }
    public void setCircleRole(String circleRole) { this.circleRole = circleRole; }
    public String getCirclePolicy() { return circlePolicy; }
    public void setCirclePolicy(String circlePolicy) { this.circlePolicy = circlePolicy; }
    public Integer getCircleEnabled() { return circleEnabled; }
    public void setCircleEnabled(Integer circleEnabled) { this.circleEnabled = circleEnabled; }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("topicId", getTopicId())
            .append("name", getName())
            .append("description", getDescription())
            .append("imgArr", getImgArr())
            .append("totalTime", getTotalTime())
            .append("totalMileage", getTotalMileage())
            .append("locationCount", getLocationCount())
            .append("templateCount", getTemplateCount())
            .append("category", getCategory())
            .append("categoryId", getCategoryId())
            .append("recruitEnabled", getRecruitEnabled())
            .append("perkMinValue", getPerkMinValue())
            .append("maxMerchant", getMaxMerchant())
            .append("required", getRequired())
            .append("circleCandidateCode", getCircleCandidateCode())
            .append("circleRole", getCircleRole())
            .append("circlePolicy", getCirclePolicy())
            .append("circleEnabled", getCircleEnabled())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }

    public Date getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(Date finishTime) {
        this.finishTime = finishTime;
    }
}
