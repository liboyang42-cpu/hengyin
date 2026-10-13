package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * 主题模板详情聚合 VO —— 白名单组装，永不出答案/提示/打卡码/精确坐标/故事JSON。
 *
 * <p>与 {@link TopicInfoVO} 的区别：本 VO 是独立 POJO 而非继承 CmsTopic，
 * 字段由白名单显式声明 —— 新增敏感字段不会自动泄露。
 *
 * @author chengyinhub
 * @date 2026-07-19
 */
public class TopicTemplateDetailVO {

    // ====== topic 摘要 ======

    @Schema(description = "主题ID")
    private Long id;

    @Schema(description = "主题名称")
    private String name;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "主题描述（故事引子）")
    private String description;

    @Schema(description = "竖版封面图")
    private String imgUrl;

    @Schema(description = "横图轮播（分号分隔）")
    private String imgArr;

    @Schema(description = "产品类型：1经典定向 2自由定向")
    private Integer productType;

    @Schema(description = "圈层主题代码；仅历史圈层主题模板下发")
    private String circleThemeCode;

    @Schema(description = "总时长（秒）")
    private Long totalTime;

    @Schema(description = "总里程（KM）")
    private BigDecimal totalMileage;

    @Schema(description = "地点数")
    private Long locationCount;

    @Schema(description = "模板数")
    private Long templateCount;

    @Schema(description = "通关勋章名")
    private String finishMedalName;

    @Schema(description = "通关勋章图")
    private String finishMedalImg;

    @Schema(description = "探索值预算")
    private Integer xpBudget;

    @Schema(description = "主题音频URL")
    private String audioUrl;

    @Schema(description = "音频时长（秒）")
    private Integer audioDuration;

    @Schema(description = "地点名")
    private String addressName;

    // ====== 07-23 P0 字段字典(本轮收窄范围) ======
    // 数据来自 cms_topic_template_meta 附表；该表没有行 = 本模板尚未录入元数据，
    // 是合法状态，所有字段保持 null（@JsonInclude(NON_NULL) 使其整体不下发）。

    @Schema(description = "模板状态:EXPERIMENTAL实验/PILOT试点/VERIFIED已验证/RETIRED停用。"
            + "⚠️实验模板不得与已验证模板混排")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String templateStatus;

    @Schema(description = "模板版本号,如 v1.0")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String version;

    @Schema(description = "1=官方范本(平台官方账号出品)。未录入元数据时不下发,前端按非官方渲染")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer isOfficial;

    /**
     * ⚠️ 真源是 {@code cms_topic.product_type}，不是元数据表 —— 在两处存会立刻分叉，
     * 而「不允许空值或第三种发布模式」这条约束只有守住单一真源才成立。
     * 存量 product_type 为 NULL 时按经典定向兜底，所以本字段恒非空。
     */
    @Schema(description = "模式:ORIENTEERING城市定向 / FREE_EXPLORE自由探索。恒非空,不存在第三种")
    private String mode;

    @Schema(description = "适用身份:MERCHANT商家/CLUB俱乐部/AGENT代理人。至少一类;未录入时不下发")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<String> applicableRoles;

    @Schema(description = "核心人群与触发(人群/时刻/需求三者必须同时有)")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private TopicTemplateAudienceVO targetAudience;

    @Schema(description = "玩家承诺(40-80字),必须说明过程和结果")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String playerPromise;

    @Schema(description = "核心玩家循环六段,顺序即阶段顺序;必须包含反馈、结果和复玩/邀请")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<String> coreLoop;

    /**
     * 主玩法骨架。只给玩法 id —— 详情从同一份 {@link #games} 白名单里取，
     * <b>不为它另开一条数据通道</b>：另开出口正是历史上泄题的成因。
     */
    @Schema(description = "主玩法骨架玩法ID;详情在 games[] 里按此 id 查,不另开出口")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long mainSkeleton;

    @Schema(description = "证据等级:NONE无实测/ESTIMATED估算/PILOTED已试点/VERIFIED已验证")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String evidenceLevel;

    @Schema(description = "风险与资质(标签/资质/取消/责任/安全提示)")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private TopicTemplateRiskVO risk;

    @Schema(description = "玩家预览三段(开始前/进行中/完成后)。⚠️体验描述,不含谜底")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private TopicTemplatePlayerPreviewVO playerPreview;

    @Schema(description = "模板作者主体")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String author;

    @Schema(description = "模板元数据最后更新时间")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Date updatedAt;

    // ====== 聚合子树 ======

    @Schema(description = "章节列表（含节点 + 路线剪影）")
    private List<TopicTemplateChapterVO> chapters;

    /**
     * 内含玩法（去重）。同时充当 P0 字段字典里的「可选玩法」列表 ——
     * 「每项可点开预览，不公开谜底」由 {@link TopicTemplateGameVO} 的白名单本身保证：
     * 它只有 7 个展示字段，questionAnswer / correctAnswer / hint1 / hint2 / storyJson
     * 根本没有对应的 setter，泄题在结构上不可能发生。
     */
    @Schema(description = "内含玩法（去重）;同时是「可选玩法」列表,每项可点开预览但不含谜底")
    private List<TopicTemplateGameVO> games;

    @Schema(description = "俱乐部承接状态:OPEN开放承接/CLAIMED已被承接(读 club_id 是否为空,不下发俱乐部身份)")
    private String clubAdoptionStatus;

    @Schema(description = "访问者是否商家账号(决定前端招募区展示态,与 recruitStatus 同源下发)")
    private Boolean viewerIsMerchant;

    @Schema(description = "访问者是否本主题原发布者；仅此身份可查看申请人并审核")
    private Boolean viewerIsPublisher;

    // ====== getters/setters ======

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
    public String getFinishMedalName() { return finishMedalName; }
    public void setFinishMedalName(String finishMedalName) { this.finishMedalName = finishMedalName; }
    public String getFinishMedalImg() { return finishMedalImg; }
    public void setFinishMedalImg(String finishMedalImg) { this.finishMedalImg = finishMedalImg; }
    public Integer getXpBudget() { return xpBudget; }
    public void setXpBudget(Integer xpBudget) { this.xpBudget = xpBudget; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public Integer getAudioDuration() { return audioDuration; }
    public void setAudioDuration(Integer audioDuration) { this.audioDuration = audioDuration; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public List<TopicTemplateChapterVO> getChapters() { return chapters; }
    public void setChapters(List<TopicTemplateChapterVO> chapters) { this.chapters = chapters; }
    public List<TopicTemplateGameVO> getGames() { return games; }
    public void setGames(List<TopicTemplateGameVO> games) { this.games = games; }
    public String getClubAdoptionStatus() { return clubAdoptionStatus; }
    public void setClubAdoptionStatus(String clubAdoptionStatus) { this.clubAdoptionStatus = clubAdoptionStatus; }
    public Boolean getViewerIsMerchant() { return viewerIsMerchant; }
    public void setViewerIsMerchant(Boolean viewerIsMerchant) { this.viewerIsMerchant = viewerIsMerchant; }
    public Boolean getViewerIsPublisher() { return viewerIsPublisher; }
    public void setViewerIsPublisher(Boolean viewerIsPublisher) { this.viewerIsPublisher = viewerIsPublisher; }

    public String getTemplateStatus() { return templateStatus; }
    public void setTemplateStatus(String templateStatus) { this.templateStatus = templateStatus; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public Integer getIsOfficial() { return isOfficial; }
    public void setIsOfficial(Integer isOfficial) { this.isOfficial = isOfficial; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public List<String> getApplicableRoles() { return applicableRoles; }
    public void setApplicableRoles(List<String> applicableRoles) { this.applicableRoles = applicableRoles; }
    public TopicTemplateAudienceVO getTargetAudience() { return targetAudience; }
    public void setTargetAudience(TopicTemplateAudienceVO targetAudience) { this.targetAudience = targetAudience; }
    public String getPlayerPromise() { return playerPromise; }
    public void setPlayerPromise(String playerPromise) { this.playerPromise = playerPromise; }
    public List<String> getCoreLoop() { return coreLoop; }
    public void setCoreLoop(List<String> coreLoop) { this.coreLoop = coreLoop; }
    public Long getMainSkeleton() { return mainSkeleton; }
    public void setMainSkeleton(Long mainSkeleton) { this.mainSkeleton = mainSkeleton; }
    public String getEvidenceLevel() { return evidenceLevel; }
    public void setEvidenceLevel(String evidenceLevel) { this.evidenceLevel = evidenceLevel; }
    public TopicTemplateRiskVO getRisk() { return risk; }
    public void setRisk(TopicTemplateRiskVO risk) { this.risk = risk; }
    public TopicTemplatePlayerPreviewVO getPlayerPreview() { return playerPreview; }
    public void setPlayerPreview(TopicTemplatePlayerPreviewVO playerPreview) { this.playerPreview = playerPreview; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public java.util.Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.util.Date updatedAt) { this.updatedAt = updatedAt; }
}
