package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 视频悬赏投稿(video_bounty_submission)。
 *
 * <p><b>归属与去重</b>:一条投稿绑定一个真实已上架主题(topic)作为作品归属;
 * {@code canonicalVideoKey} 由平台域名 + 外部视频 id 规范化生成,唯一键拒绝同一视频重复投稿。
 * {@code originGroupKey} 表示"同一原视频",跨平台搬运(同一作品剪成多个平台贴)
 * 必须同组,组内合计封顶,不能多次搬运领多次。
 *
 * <p><b>证据边界</b>:本表不存我方设备/账号自证的播放;播放只认
 * {@link VideoBountyEvidence} 里的外部平台证据。
 *
 * @author chengyinhub
 */
public class VideoBountySubmission
{
    /** 已投稿,待审核。 */
    public static final int STATUS_SUBMITTED = 0;
    /** 审核中。 */
    public static final int STATUS_UNDER_REVIEW = 1;
    /** 已通过:可录入外部播放证据。 */
    public static final int STATUS_APPROVED = 2;
    /** 已拒绝。 */
    public static final int STATUS_REJECTED = 3;
    /** 已撤销:通过后被运营撤回,应计作废。 */
    public static final int STATUS_REVOKED = 4;
    /** 申诉中:作者对拒绝提出申诉。 */
    public static final int STATUS_APPEALING = 5;
    /** 作者主动取消。 */
    public static final int STATUS_CANCELLED = 6;

    private Long id;
    private Long campaignId;
    private String campaignKey;
    /** 投稿作者会员 id;组内投稿必须同一作者,防多账号按组重复领取。 */
    private Long memberId;
    /** 作品归属:真实已上架主题 id。 */
    private Long topicId;
    /** tiktok / reels / youtube。 */
    private String platform;
    /** 作者提交的原始视频链接(保留原始,规范化结果另存)。 */
    private String sourceUrl;
    /** 平台 + 外部视频 id 的规范化唯一键。 */
    private String canonicalVideoKey;
    /**
     * 同一原视频的分组键,由<b>作者</b>提交,只作建议值。
     *
     * <p>⚠️ 它不再是封顶/应计依据:同一视频在两个平台各填一个不同组键就能拆开封顶。
     * 真正的分组见 {@link #verifiedGroupKey},只能由运营核验写入。
     */
    private String originGroupKey;
    /**
     * 运营核验后的原视频组键。{@code null} = 未核验 ⇒ 不生成确定应计。
     *
     * <p>跨平台同一条原视频由核验人指定同一个组键,封顶按该组合计;
     * 一旦该投稿存在有效应计即不可再改(防"先按小组计满、再并组绕上限")。
     */
    private String verifiedGroupKey;
    /** 核验操作者(后台 sys user id)。 */
    private Long verifiedBy;
    private Date verifiedAt;
    /** 核验证据引用(工单/素材/外链编号),审计追溯用。 */
    private String verifiedSourceRef;
    /** 核验版本:CAS 用;重投同一结论时不变。 */
    private Integer verifiedGroupVersion;
    private Integer status;
    private Date submittedAt;
    /** 审核通过时确定的发布时间(发布后窗口起点)。 */
    private Date publishedAt;
    /** 应计窗口终点 = publishedAt + windowDays。 */
    private Date windowEndsAt;
    /**
     * 审核人标识,<b>带 realm 前缀</b>(如 {@code sys:1})。
     *
     * <p>为什么不是 Long:后台运营是 sys_user,会员是 ums_member,两套 id 空间不同。
     * 只存数字会让"1 号审核人"同时指两个人,审计就无法回溯到底谁审的。
     */
    private String reviewedBy;
    private Date reviewedAt;
    private String reviewNote;
    private String delFlag;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getCanonicalVideoKey() { return canonicalVideoKey; }
    public void setCanonicalVideoKey(String canonicalVideoKey) { this.canonicalVideoKey = canonicalVideoKey; }
    public String getOriginGroupKey() { return originGroupKey; }
    public void setOriginGroupKey(String originGroupKey) { this.originGroupKey = originGroupKey; }
    public String getVerifiedGroupKey() { return verifiedGroupKey; }
    public void setVerifiedGroupKey(String verifiedGroupKey) { this.verifiedGroupKey = verifiedGroupKey; }
    public Long getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(Long verifiedBy) { this.verifiedBy = verifiedBy; }
    public Date getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Date verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getVerifiedSourceRef() { return verifiedSourceRef; }
    public void setVerifiedSourceRef(String verifiedSourceRef) { this.verifiedSourceRef = verifiedSourceRef; }
    public Integer getVerifiedGroupVersion() { return verifiedGroupVersion; }
    public void setVerifiedGroupVersion(Integer verifiedGroupVersion) { this.verifiedGroupVersion = verifiedGroupVersion; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Date submittedAt) { this.submittedAt = submittedAt; }
    public Date getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Date publishedAt) { this.publishedAt = publishedAt; }
    public Date getWindowEndsAt() { return windowEndsAt; }
    public void setWindowEndsAt(Date windowEndsAt) { this.windowEndsAt = windowEndsAt; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public Date getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Date reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
