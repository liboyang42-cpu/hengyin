package com.chengyinhub.business.domain;

/**
 * 主题模板详情页元数据(主题级)—— 07-23 指南 P0 字段字典本轮收窄范围。
 *
 * <p>与 {@link CmsTopic} 1:1(库里 uk_meta_topic 保证)。之所以单独一张附表而不是给
 * cms_topic 加列:cms_topic 的 selectCmsTopicVo 被 9 处 select 复用(含事务内
 * SELECT ... FOR UPDATE),而这批字段只服务模板货架详情页一个消费面。
 *
 * <p>⚠️ <b>「模式」不在本类</b>。真源是 {@code CmsTopic.productType}
 * (1 经典定向 / 2 自由探索)。在这里再存一份会立刻和主表分叉,而
 * 「不允许空值或第三种发布模式」这条约束只有守住单一真源才成立。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public class CmsTopicTemplateMeta {

    private Long id;

    /** 主题 id;仅对 is_template=1 的主题有意义。 */
    private Long topicId;

    /** 模板状态:EXPERIMENTAL / PILOT / VERIFIED / RETIRED,见 {@link com.chengyinhub.common.constant.TemplateStatus}。 */
    private String templateStatus;

    /** 模板版本号,如 v1.0。实例默认锁定采用时的版本。 */
    private String version;

    /** 适用身份,逗号分隔:MERCHANT / CLUB / AGENT。必填口径 = 至少一类。 */
    private String applicableRoles;

    /** 核心人群:是谁。 */
    private String audienceWho;

    /** 核心触发:什么时刻。 */
    private String audienceMoment;

    /** 核心需求:要解决什么。 */
    private String audienceNeed;

    /** 玩家承诺,40–80 字;必须说明过程和结果。 */
    private String playerPromise;

    /** 核心玩家循环六段,换行分隔,顺序即阶段顺序。 */
    private String coreLoop;

    /** 主玩法骨架 cms_member_template.id。⚠️ 只存 id,玩法详情走既有白名单 VO,不由此另开出口。 */
    private Long mainSkeletonTemplateId;

    /** 证据等级:NONE / ESTIMATED / PILOTED / VERIFIED,见 {@link com.chengyinhub.common.constant.EvidenceLevel}。 */
    private String evidenceLevel;

    /** 风险标签,逗号分隔。 */
    private String riskTags;

    /** 所需资质材料说明。 */
    private String riskQualifications;

    /** 取消规则。 */
    private String riskCancellation;

    /** 责任划分。 */
    private String riskLiability;

    /** 安全提示(对玩家公开)。 */
    private String riskSafetyNotice;

    /** 玩家预览:开始前。 */
    private String previewBefore;

    /** 玩家预览:进行中。 */
    private String previewDuring;

    /** 玩家预览:完成后。 */
    private String previewAfter;

    /** 模板作者主体。 */
    private String author;

    /** 1=官方范本(平台官方账号出品,运营用SQL标记);0=非官方。 */
    private Integer isOfficial;

    /** 最后更新时间;「模板作者与更新」的时间轴读这一列。 */
    private java.util.Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getTemplateStatus() { return templateStatus; }
    public void setTemplateStatus(String templateStatus) { this.templateStatus = templateStatus; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getApplicableRoles() { return applicableRoles; }
    public void setApplicableRoles(String applicableRoles) { this.applicableRoles = applicableRoles; }

    public String getAudienceWho() { return audienceWho; }
    public void setAudienceWho(String audienceWho) { this.audienceWho = audienceWho; }

    public String getAudienceMoment() { return audienceMoment; }
    public void setAudienceMoment(String audienceMoment) { this.audienceMoment = audienceMoment; }

    public String getAudienceNeed() { return audienceNeed; }
    public void setAudienceNeed(String audienceNeed) { this.audienceNeed = audienceNeed; }

    public String getPlayerPromise() { return playerPromise; }
    public void setPlayerPromise(String playerPromise) { this.playerPromise = playerPromise; }

    public String getCoreLoop() { return coreLoop; }
    public void setCoreLoop(String coreLoop) { this.coreLoop = coreLoop; }

    public Long getMainSkeletonTemplateId() { return mainSkeletonTemplateId; }
    public void setMainSkeletonTemplateId(Long mainSkeletonTemplateId) {
        this.mainSkeletonTemplateId = mainSkeletonTemplateId;
    }
    public String getEvidenceLevel() { return evidenceLevel; }
    public void setEvidenceLevel(String evidenceLevel) { this.evidenceLevel = evidenceLevel; }

    public String getRiskTags() { return riskTags; }
    public void setRiskTags(String riskTags) { this.riskTags = riskTags; }

    public String getRiskQualifications() { return riskQualifications; }
    public void setRiskQualifications(String riskQualifications) { this.riskQualifications = riskQualifications; }

    public String getRiskCancellation() { return riskCancellation; }
    public void setRiskCancellation(String riskCancellation) { this.riskCancellation = riskCancellation; }

    public String getRiskLiability() { return riskLiability; }
    public void setRiskLiability(String riskLiability) { this.riskLiability = riskLiability; }

    public String getRiskSafetyNotice() { return riskSafetyNotice; }
    public void setRiskSafetyNotice(String riskSafetyNotice) { this.riskSafetyNotice = riskSafetyNotice; }

    public String getPreviewBefore() { return previewBefore; }
    public void setPreviewBefore(String previewBefore) { this.previewBefore = previewBefore; }

    public String getPreviewDuring() { return previewDuring; }
    public void setPreviewDuring(String previewDuring) { this.previewDuring = previewDuring; }

    public String getPreviewAfter() { return previewAfter; }
    public void setPreviewAfter(String previewAfter) { this.previewAfter = previewAfter; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public Integer getIsOfficial() { return isOfficial; }
    public void setIsOfficial(Integer isOfficial) { this.isOfficial = isOfficial; }

    public java.util.Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.util.Date updateTime) { this.updateTime = updateTime; }
}
