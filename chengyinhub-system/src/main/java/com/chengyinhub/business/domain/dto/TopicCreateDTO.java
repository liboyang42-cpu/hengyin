package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * 主题发布-请求参数
 *
 * <p>编辑复用本 DTO 时，未传 publishMode、teamMode/teamMaxMembers、xpBudget、selfPlay
 * 保留已有配置；显式 0 仍按关闭处理。productType 未传且票中也没有 mode 时保留已有有效类型。
 * 创建入口继续使用原有默认值，章节/票/合作者集合仍遵守全量替换合同。
 */
@Schema(description = "主题发布-请求参数")
public class TopicCreateDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 操作作用域：MERCHANT=以当前有效商家团队主体发布；空=操作者个人发布。 */
    @Schema(description = "操作作用域；MERCHANT 表示以商家主体发布，空表示个人发布")
    private String scope;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    @Schema(description = "主题名称", example = "测试主题")
    private String name;
    @Schema(description = "副标题", example = "副标题")
    private String subtitle;
    @Schema(description = "主题描述", example = "主题描述")
    private String description;
    @Schema(description = "主题开始日期", example = "2025-01-31 15:30:45")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startDate;
    @Schema(description = "主题结束日期", example = "2025-01-31 15:30:45")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endDate;
    /** 本期所有可售 SKU 的主题级硬售止（排他上界）。 */
    @Schema(description = "本期硬售止时间（所有票种和自玩通行证的排他上界）", example = "2026-08-15 23:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date saleEndAt;
    @Schema(description = "主题封面图", example = "")
    private String imgUrl;
    @Schema(description = "主题横图", example = "")
    private String imgArr;
    @Schema(description = "主题类别", example = "1,2")
    private String categoryIds;
    @Schema(description = "主题章节列表")
    private List<ChapterDTO> chapters;
    @Schema(description = "主题合作者列表")
    private List<Long> collaboratorIds; // 合作者用户ID列表
    @Schema(description = "主题票务列表")
    @Valid
    private List<ActivityTicketRequest> tickets; // 票务信息列表

    /** 主题地点路线模式；空值兼容为 LINEAR。 */
    private String routeMode;
    /** BRANCH_GRAPH 的主题级路线配置；首次创建可引用 clientNodeKey。 */
    private String routeGraphJson;
    /** 编辑时的路线配置 CAS 版本；首次创建为空。 */
    private Long configVersion;

    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String routeMode) { this.routeMode = routeMode; }
    public String getRouteGraphJson() { return routeGraphJson; }
    public void setRouteGraphJson(String routeGraphJson) { this.routeGraphJson = routeGraphJson; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public Date getSaleEndAt() {
        return saleEndAt;
    }

    public void setSaleEndAt(Date saleEndAt) {
        this.saleEndAt = saleEndAt;
    }

    public String getImgUrl() {
        return imgUrl;
    }

    public void setImgUrl(String imgUrl) {
        this.imgUrl = imgUrl;
    }

    public String getImgArr() {
        return imgArr;
    }

    public void setImgArr(String imgArr) {
        this.imgArr = imgArr;
    }

    public String getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(String categoryIds) {
        this.categoryIds = categoryIds;
    }

    public List<ChapterDTO> getChapters() {
        return chapters;
    }

    public void setChapters(List<ChapterDTO> chapters) {
        this.chapters = chapters;
    }

    public List<Long> getCollaboratorIds() {
        return collaboratorIds;
    }

    public void setCollaboratorIds(List<Long> collaboratorIds) {
        this.collaboratorIds = collaboratorIds;
    }

    public List<ActivityTicketRequest> getTickets() {
        return tickets;
    }

    public void setTickets(List<ActivityTicketRequest> tickets) {
        this.tickets = tickets;
    }

    /** 是否允许主题进入商家池：1=进池(开放商家报名) 0/null=自办不进池(默认) */
    private Integer openMerchantPool;

    /** 开放范围·俱乐部承接(P0-4):1=开放给俱乐部申请承接,其余按 0 处理 */
    private Integer openClubPool;

    public Integer getOpenClubPool() {
        return openClubPool;
    }

    public void setOpenClubPool(Integer openClubPool) {
        this.openClubPool = openClubPool;
    }

    public Integer getOpenMerchantPool() {
        return openMerchantPool;
    }

    public void setOpenMerchantPool(Integer openMerchantPool) {
        this.openMerchantPool = openMerchantPool;
    }

    /** 发布来源：simple=建议版 pro=专业版(默认) */
    private String publishMode;

    public String getPublishMode() {
        return publishMode;
    }

    public void setPublishMode(String publishMode) {
        this.publishMode = publishMode;
    }

    /** 保存为草稿：1=草稿(不提审、不上架，仅作者可见) 0/null=正常发布(默认) */
    private Integer saveDraft;

    public Integer getSaveDraft() {
        return saveDraft;
    }

    public void setSaveDraft(Integer saveDraft) {
        this.saveDraft = saveDraft;
    }

    /**
     * 首次存草稿的请求意图键(16-64 位字母数字/下划线/连字符,客户端按本机草稿稳定生成)。
     * 建草稿丢回包后,同一账号用同一键重试 → 回到已建的那份草稿,不重复建题。仅 saveDraft=1 的创建使用。
     */
    private String requestId;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** 是否同步发布到创意广场：1=发主题时同步发一条创意广场帖 0/null=不发(默认) */
    private Integer publishToCreative;

    public Integer getPublishToCreative() {
        return publishToCreative;
    }

    public void setPublishToCreative(Integer publishToCreative) {
        this.publishToCreative = publishToCreative;
    }

    /** 主题音频URL(OSS) */
    private String audioUrl;

    /** 主题音频时长(秒) */
    private Integer audioDuration;

    /** 归属俱乐部ID(C1多俱乐部:经典定向团发布时由前端选定;校验须为本人俱乐部) */
    private Long clubId;

    /**
     * P0-02 产品类型:1经典定向 2自由定向(= 前端的 entryMode)。取值见
     * {@link com.chengyinhub.common.constant.ProductType}。
     * 可空 = 旧版前端未传 ⇒ 后端从票的 mode 推断(混票则拒);新版前端应显式传,
     * 传了之后票的 mode 由它覆盖,前端票里传什么 mode 都不再有意义。
     */
    private Integer productType;

    public Integer getProductType() {
        return productType;
    }

    public void setProductType(Integer productType) {
        this.productType = productType;
    }

    public Long getClubId() {
        return clubId;
    }

    public void setClubId(Long clubId) {
        this.clubId = clubId;
    }

    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public Integer getAudioDuration() { return audioDuration; }
    public void setAudioDuration(Integer audioDuration) { this.audioDuration = audioDuration; }

    /** M2 自玩票(仅经典定向生效):是否开放 0否1是 */
    private Integer selfPlay;
    /** M2 自玩票价格(元) */
    private java.math.BigDecimal selfPlayPrice;
    /** M2 自玩票数/配额(NULL/<=0=不限) */
    private Integer selfPlayQuota;

    public Integer getSelfPlay() { return selfPlay; }
    public void setSelfPlay(Integer selfPlay) { this.selfPlay = selfPlay; }
    public java.math.BigDecimal getSelfPlayPrice() { return selfPlayPrice; }
    public void setSelfPlayPrice(java.math.BigDecimal selfPlayPrice) { this.selfPlayPrice = selfPlayPrice; }
    public Integer getSelfPlayQuota() { return selfPlayQuota; }
    public void setSelfPlayQuota(Integer selfPlayQuota) { this.selfPlayQuota = selfPlayQuota; }

    /** 主题级组队入口：0关闭 1报名时组队 2报名后邀请。 */
    private Integer teamMode;
    /** 玩家可选队伍人数上限；经典定向本期支持 2–4 人。 */
    private Integer teamMaxMembers;

    public Integer getTeamMode() { return teamMode; }
    public void setTeamMode(Integer teamMode) { this.teamMode = teamMode; }
    public Integer getTeamMaxMembers() { return teamMaxMembers; }
    public void setTeamMaxMembers(Integer teamMaxMembers) { this.teamMaxMembers = teamMaxMembers; }

    /** M7 主题探索值预算 */
    private Integer xpBudget;
    public Integer getXpBudget() { return xpBudget; }
    public void setXpBudget(Integer xpBudget) { this.xpBudget = xpBudget; }

    /** 主题节点通关规则 JSON：默认全部完成，AT_LEAST 表示完成 N 个即通关。 */
    private String completeRuleJson;
    public String getCompleteRuleJson() { return completeRuleJson; }
    public void setCompleteRuleJson(String completeRuleJson) { this.completeRuleJson = completeRuleJson; }

    /**
     * 主题数值规则 journeyRules JSON（HP/幸运/难度 DC/恢复，主题级一份）。
     *
     * <p>不传或空白 = 不改原值（老编辑端整包上送不会把已配的规则抹掉）；
     * 要关闭已配的规则就显式传 {"schemaVersion":1,"checkEnabled":false}。
     */
    private String themeRuntimeConfig;
    public String getThemeRuntimeConfig() { return themeRuntimeConfig; }
    public void setThemeRuntimeConfig(String v) { themeRuntimeConfig = v; }

    /** 主题画面风格（ThemeLook）。不传 = 不改原值；空串 = 恢复默认风格。 */
    private String look;
    public String getLook() { return look; }
    public void setLook(String v) { look = v; }

    private String journeyRules;
    public String getJourneyRules() { return journeyRules; }
    public void setJourneyRules(String journeyRules) { this.journeyRules = journeyRules; }

    /**
     * 主题级剧情配置 journeyStory JSON（开场通话/阶段结算/结局，主题级一份）。
     *
     * <p>不传或空白 = 不改原值（老编辑端整包上送不会把已配的剧情抹掉）。
     */
    private String journeyStory;
    public String getJourneyStory() { return journeyStory; }
    public void setJourneyStory(String journeyStory) { this.journeyStory = journeyStory; }

    /**
     * 招募截止时间(可选):传了即让主题进入招募态(lifecycle=1),触发双死线②;不传则不进招募,行为不变。
     *
     * <p>⚠️ @JsonFormat 必须与 startDate/endDate/saleEndAt 同口径:少这一个注解,本字段就只吃
     * ISO-8601,而小程序发布器把四个日期统一成 "yyyy-MM-dd HH:mm:ss" 上送 ⇒ Jackson 在进业务
     * 代码之前就抛 InvalidFormatException,用户只看到一句「主题发布失败,请稍后重试」。
     * 自由探索主题(productType=2)强制要求本字段 ⇒ 少它就是「小程序发不出自由探索主题」。
     */
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date recruitDeadline;
    public Date getRecruitDeadline() { return recruitDeadline; }
    public void setRecruitDeadline(Date recruitDeadline) { this.recruitDeadline = recruitDeadline; }

    /** P0.5 通关勋章名(空=后端默认"主题名·通关") */
    private String finishMedalName;
    /** P0.5 通关勋章图(可空) */
    private String finishMedalImg;
    /** 主题通关后发放的优惠券(可空) */
    private Long completeRewardCouponId;
    public String getFinishMedalName() { return finishMedalName; }
    public void setFinishMedalName(String finishMedalName) { this.finishMedalName = finishMedalName; }
    public String getFinishMedalImg() { return finishMedalImg; }
    public void setFinishMedalImg(String finishMedalImg) { this.finishMedalImg = finishMedalImg; }
    public Long getCompleteRewardCouponId() { return completeRewardCouponId; }
    public void setCompleteRewardCouponId(Long completeRewardCouponId) { this.completeRewardCouponId = completeRewardCouponId; }
}
