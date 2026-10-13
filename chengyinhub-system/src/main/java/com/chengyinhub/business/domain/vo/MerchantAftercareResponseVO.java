package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MerchantAftercareResponse;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/** 售后意见白名单；不下发 actor memberId、owner memberId 或 requestId。 */
public class MerchantAftercareResponseVO {
    private Long id;
    private Long refundId;
    private String decision;
    private String content;
    private String evidenceUrl;
    /** NONE=无凭证;AVAILABLE=已签发短时地址;UNAVAILABLE=有凭证但核验/签名失败,前端显示「暂不可查看」。 */
    private String evidenceStatus = "NONE";
    private String actorRoleCode;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String processing;
    private String merchantOpinion;
    private boolean refunded;

    public MerchantAftercareResponseVO() {
    }

    public static MerchantAftercareResponseVO from(MerchantAftercareResponse source,
                                                   String projectedEvidenceUrl,
                                                   String processing,
                                                   String merchantOpinion,
                                                   boolean refunded) {
        MerchantAftercareResponseVO out = new MerchantAftercareResponseVO();
        if (source != null) {
            out.id = source.getId();
            out.refundId = source.getRefundApplicationId();
            out.decision = source.getDecision();
            out.content = source.getContent();
            out.evidenceUrl = projectedEvidenceUrl;
            boolean hasEvidence = source.getEvidenceUrl() != null && !source.getEvidenceUrl().trim().isEmpty();
            out.evidenceStatus = !hasEvidence ? "NONE" : projectedEvidenceUrl != null ? "AVAILABLE" : "UNAVAILABLE";
            out.actorRoleCode = source.getActorRoleCode();
            out.createTime = source.getCreateTime();
        }
        out.processing = processing;
        out.merchantOpinion = merchantOpinion;
        out.refunded = refunded;
        return out;
    }

    public Long getId() { return id; }
    public Long getRefundId() { return refundId; }
    public String getDecision() { return decision; }
    public String getContent() { return content; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public String getEvidenceStatus() { return evidenceStatus; }
    public String getActorRoleCode() { return actorRoleCode; }
    public Date getCreateTime() { return createTime; }
    public String getProcessing() { return processing; }
    public String getMerchantOpinion() { return merchantOpinion; }
    public boolean isRefunded() { return refunded; }
}
