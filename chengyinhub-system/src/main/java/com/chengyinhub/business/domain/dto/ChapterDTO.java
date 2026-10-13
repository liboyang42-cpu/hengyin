package com.chengyinhub.business.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 主题发布-章节请求参数
 */
@Schema(description = "主题发布-章节请求参数")
public class ChapterDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 编辑已有主题时的旧章节 ID；重建后仅用于剧情 chapterId 映射，不直接插入新行。 */
    private Long id;

    @Schema(description = "是否为第一站之前的开局流程；仅 v2 故事流使用")
    private Boolean opening;

    public Boolean getOpening() { return opening; }
    public void setOpening(Boolean opening) { this.opening = opening; }

    /**
     * 结局章(2026-09-24 用户:「结局 = 添加一章,打开结局开关」):{@code {fallback, when[]}}。
     * 保存时按它重建 journeyStory.ending.endings(见 JourneyStory#withEndingChapters),不落章节表。
     */
    @Schema(description = "结局章设置 {fallback, when[]};仅 v2 故事流")
    private com.fasterxml.jackson.databind.JsonNode ending;

    public com.fasterxml.jackson.databind.JsonNode getEnding() { return ending; }
    public void setEnding(com.fasterxml.jackson.databind.JsonNode ending) { this.ending = ending; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Schema(description = "章节名称", example = "chapter1")
    private String name;
    @Schema(description = "章节描述", example = "chapter desc")
    private String description;
    @Schema(description = "章节图片", example = "")
    private String imgArr;
    /**
     * 本章背景旁白;玩家进入本章自动播放,一章最多一段(2026-09-04 起音频不再是 blocks 里的块)。
     * ★必须在 DTO 上声明:两条写路径都走 BeanUtils.copyProperties,DTO 少一个字段这一列就永远是 NULL。
     *   同一张表的 atmospherePreset 当初就是这么漏的(2026-09-06 已补,见下一个字段)。
     */
    @Schema(description = "章节背景旁白音频地址", example = "")
    private String audioUrl;
    /**
     * 章节配色(黑/蓝/红/黄/白 五档纯色,取值见 ChapterAtmospherePreset)。
     * ★2026-09-06 补上:在此之前小程序有配色选择器、这里没有字段,于是选了什么都落不进库 ——
     *   上一行 audioUrl 的注释里点名的就是这个洞。补字段的同时,两条写路径都显式
     *   requireValid 一次,不靠 BeanUtils 隐式透传(同一个方法里 templateId / mode 已经栽过两次)。
     */
    @Schema(description = "章节配色预设", example = "DEFAULT")
    private String atmospherePreset;
    @Schema(description = "章节下的节点", example = "")
    private List<NodeDTO> nodes;
    @Schema(description = "章节故事流块；仅 v2 写接口使用")
    private List<ChapterBlockDTO> blocks;
    @Schema(description = "章节故事流结构版本；当前固定为 1", example = "1")
    private Integer schemaVersion;
    @Schema(description = "前端计算的章节距离（可选）", example = "")
    private BigDecimal calculatedDistance;
    // 以下四项是章节的商家承接配置，靠 BeanUtils.copyProperties(chapterDTO, chapter) 按同名字段拷进实体，
    // 所以 DTO 少一个字段前端就传不进来（发布接口静默丢配置，不报错）
    @Schema(description = "章节品类，用于匹配能承接本章的商家", example = "咖啡")
    private String category;
    @Schema(description = "适合商家品类ID；开放商家承接时必填，与商家档案 categoryId 同口径", example = "18")
    private Integer categoryId;
    @Schema(description = "是否开放本章节的商家承接：0否 1是；不传按库默认0", example = "1")
    private Integer recruitEnabled;
    @Schema(description = "入驻门槛：商家权益零售标价须≥此值。⚠️比的是retail_value零售价，不是unit_cost成本价", example = "50.00")
    private BigDecimal perkMinValue;
    @Schema(description = "本章最多可承接商家数；0不限，1到127为上限", example = "4")
    private Integer maxMerchant;
    @Schema(description = "1必选章（计入通关判定与容量） 0可选章；不传则用库默认1，维持既有「每章都算」语义", example = "1")
    private Integer required;

    /** 权益/引流；普通俱乐部自由探索可声明由本俱乐部直付的计酬档。 */
    private String termsMode;
    @Schema(description = "俱乐部直付计酬每人次上限；仅REVSHARE填写，须大于0")
    private BigDecimal maxPerHeadFee;
    public BigDecimal getMaxPerHeadFee() { return maxPerHeadFee; }
    public void setMaxPerHeadFee(BigDecimal v) { maxPerHeadFee = v; }

    /**
     * 本章节允许商家使用的验证方式,逗号分隔如 "1,3,4"。空 = 不限。
     * ★必须在 DTO 上声明:发布器走 BeanUtils.copyProperties(CmsTopicServiceImpl#saveChaptersAndNodes),
     * DTO 少一个字段,这一列就永远是 NULL —— 边界校验静默失效且永不报错。
     */
    private String allowedValidationMethods;

    /** 单节点探索值上限,商家点位不得超过。空 = 不限。 */
    private Integer maxNodeXp;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }

    public String getAtmospherePreset()
    {
        return atmospherePreset;
    }

    public void setAtmospherePreset(String atmospherePreset)
    {
        this.atmospherePreset = atmospherePreset;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImgArr() {
        return imgArr;
    }

    public void setImgArr(String imgArr) {
        this.imgArr = imgArr;
    }

    public List<NodeDTO> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeDTO> nodes) {
        this.nodes = nodes;
    }

    public List<ChapterBlockDTO> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<ChapterBlockDTO> blocks) {
        this.blocks = blocks;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public BigDecimal getCalculatedDistance() {
        return calculatedDistance;
    }

    public void setCalculatedDistance(BigDecimal calculatedDistance) {
        this.calculatedDistance = calculatedDistance;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getRecruitEnabled() {
        return recruitEnabled;
    }

    public void setRecruitEnabled(Integer recruitEnabled) {
        this.recruitEnabled = recruitEnabled;
    }

    public BigDecimal getPerkMinValue() {
        return perkMinValue;
    }

    public void setPerkMinValue(BigDecimal perkMinValue) {
        this.perkMinValue = perkMinValue;
    }

    public Integer getMaxMerchant() {
        return maxMerchant;
    }

    public void setMaxMerchant(Integer maxMerchant) {
        this.maxMerchant = maxMerchant;
    }

    public Integer getRequired() {
        return required;
    }

    public void setRequired(Integer required) {
        this.required = required;
    }

    public String getTermsMode() {
        return termsMode;
    }

    public void setTermsMode(String termsMode) {
        this.termsMode = termsMode;
    }

    public String getAllowedValidationMethods() {
        return allowedValidationMethods;
    }

    public void setAllowedValidationMethods(String allowedValidationMethods) {
        this.allowedValidationMethods = allowedValidationMethods;
    }

    public Integer getMaxNodeXp() {
        return maxNodeXp;
    }

    public void setMaxNodeXp(Integer maxNodeXp) {
        this.maxNodeXp = maxNodeXp;
    }
}
