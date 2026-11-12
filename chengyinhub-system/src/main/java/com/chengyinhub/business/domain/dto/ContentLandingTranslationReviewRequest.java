package com.chengyinhub.business.domain.dto;

/**
 * 后台审校「公开落地译文」的请求。只做状态翻转,**不改作者文字**。
 *
 * <p><b>为什么必须带 {@code observedContentHash}</b>:审校台先读、再提交;这中间作者可能改了译文
 * 或改了主题原文。若审校只按 rowVersion 通过,就会出现「看的是 A、批准的是 B」——
 * 版本只能证明"行没变过几次",不能证明"你看到的正文就是现在要批的正文"。
 * 因此提交方必须回传它**实际渲染给审校人看过的**内容摘要;服务端重算不一致即 409,
 * 要求重新读取后再审。摘要里同时含主题原文修订时刻(源内容修订身份),作者改了原文同样失效。
 */
public class ContentLandingTranslationReviewRequest {

    private Long topicId;
    private String locale;
    /** 审校人读到的行版本;必须与库内一致。 */
    private Long expectedVersion;
    /** 审校人读到的内容摘要(见 {@code ContentLandingMetadataService#reviewDigest});不一致即 409。 */
    private String observedContentHash;
    /** true=批准(APPROVED);false=撤回批准回到 PENDING。 */
    private Boolean approve;
    /** 审校理由,必填,写入审计。 */
    private String reason;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

    public String getObservedContentHash() { return observedContentHash; }
    public void setObservedContentHash(String observedContentHash) { this.observedContentHash = observedContentHash; }

    public Boolean getApprove() { return approve; }
    public void setApprove(Boolean approve) { this.approve = approve; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
