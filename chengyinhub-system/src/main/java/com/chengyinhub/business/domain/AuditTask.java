package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * P3 统一审核任务对象 audit_task。
 */
public class AuditTask extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final Integer STATUS_PENDING = 0;
    public static final Integer STATUS_APPROVED = 1;
    public static final Integer STATUS_REJECTED = 2;
    public static final Integer STATUS_ESCALATED = 3;
    public static final Integer STATUS_CANCELLED = 4;

    /** 内容举报 bizType(对齐既有 submit 约定·大写);软封口径:SQUARE/COMMENT 用 status(下2/复1),CLUB_POST(_COMMENT) 用 del_flag(下2/复0),IM 用 status(BLOCKED/NORMAL) */
    public static final String BIZ_MEDIA_ASSET = "MEDIA_ASSET";
    public static final String BIZ_SQUARE = "SQUARE";
    /** 新版专业广场帖文；审核结论由帖文生命周期服务以 CAS 方式回写。 */
    public static final String BIZ_COMMUNITY_POST = "COMMUNITY_POST";
    /** 新版专业广场回复；与旧 COMMENT 分表，禁止误回写 ums_comment。 */
    public static final String BIZ_COMMUNITY_POST_COMMENT = "COMMUNITY_POST_COMMENT";
    public static final String BIZ_CLUB_POST = "CLUB_POST";
    public static final String BIZ_CLUB_POST_COMMENT = "CLUB_POST_COMMENT";
    public static final String BIZ_COMMENT = "COMMENT";
    public static final String BIZ_IM = "IM_MESSAGE";
    /** 漫游·附近的局(玩家自组局) */
    public static final String BIZ_HANGOUT = "HANGOUT";
    /** 地图附近的公开队伍(play_team.join_mode=2);REJECT=确认违规改回邀请制并使待审申请失效,APPROVE=不动。 */
    public static final String BIZ_TEAM = "TEAM";
    /** M0-6 先审后发 bizType;审核通过(APPROVE)→status=1(上架),驳回(REJECT)→status=2 */
    public static final String BIZ_TOPIC = "TOPIC";
    /** H3 商业主题两阶段审核；bizType 分相保证同一主题可同时拥有独立阶段历史。 */
    public static final String BIZ_TOPIC_PROJECT_REVIEW = "TOPIC_PROJECT_REVIEW";
    public static final String BIZ_TOPIC_SALE_REVIEW = "TOPIC_SALE_REVIEW";
    public static final String BIZ_ACTIVITY = "ACTIVITY";
    /** 玩法模板先审后发；bizId 指向 cms_member_template.id，模板库记录是其同步投影。 */
    public static final String BIZ_TEMPLATE = "TEMPLATE";
    /** 模板广场独立审核；bizId 指向 cms_template_library.id，不回写个人玩法。 */
    public static final String BIZ_TEMPLATE_LIBRARY = "TEMPLATE_LIBRARY";
    /** 商家城市节点与节点模板审核，值对齐既有产品文档/API 约定。 */
    public static final String BIZ_MERCHANT_NODE = "merchant_node";
    public static final String BIZ_MERCHANT_NODE_TEMPLATE = "merchant_node_template";
    /** 已核销商家评价举报；REJECT=确认违规下架，APPROVE=举报无效恢复。 */
    public static final String BIZ_MERCHANT_REVIEW = "MERCHANT_REVIEW";

    private Long id;
    private String bizType;
    private Long bizId;
    private Long submitterId;
    private String sourceType;
    private String riskLevel;
    private Integer status;
    private Long currentAssignee;
    private Date submittedAt;
    private Date deadlineAt;
    private String snapshotJson;
    private Integer version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public Long getSubmitterId() { return submitterId; }
    public void setSubmitterId(Long submitterId) { this.submitterId = submitterId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getCurrentAssignee() { return currentAssignee; }
    public void setCurrentAssignee(Long currentAssignee) { this.currentAssignee = currentAssignee; }
    public Date getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Date submittedAt) { this.submittedAt = submittedAt; }
    public Date getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Date deadlineAt) { this.deadlineAt = deadlineAt; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
