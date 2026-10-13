package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MerchantOperatorInvite;

import java.util.Date;

/** 邀请列表白名单；永不暴露 tokenHash。 */
public class MerchantOperatorInviteVO {
    public static final String MUTATION_EXACT_RESULT = "EXACT_RESULT";
    public static final String MUTATION_LATER_AUTHORITATIVE = "LATER_AUTHORITATIVE";

    private Long id;
    private String roleCode;
    private String status;
    private Date invitedAt;
    private Date expiresAt;
    private Date acceptedAt;
    private Date revokedAt;
    private Integer version;
    private String mutationState;

    public static MerchantOperatorInviteVO from(MerchantOperatorInvite source) {
        MerchantOperatorInviteVO out = new MerchantOperatorInviteVO();
        if (source != null) {
            out.id = source.getId();
            out.roleCode = source.getRoleCode();
            out.status = source.getStatus();
            out.invitedAt = source.getInvitedAt();
            out.expiresAt = source.getExpiresAt();
            out.acceptedAt = source.getAcceptedAt();
            out.revokedAt = source.getRevokedAt();
            out.version = source.getVersion();
        }
        return out;
    }

    public static MerchantOperatorInviteVO fromMutation(MerchantOperatorInvite source, String mutationState) {
        MerchantOperatorInviteVO out = from(source);
        if (!MUTATION_EXACT_RESULT.equals(mutationState)
                && !MUTATION_LATER_AUTHORITATIVE.equals(mutationState)) {
            throw new IllegalArgumentException("invalid merchant operator invite mutation state");
        }
        out.mutationState = mutationState;
        return out;
    }

    public Long getId() { return id; }
    public String getRoleCode() { return roleCode; }
    public String getStatus() { return status; }
    public Date getInvitedAt() { return invitedAt; }
    public Date getExpiresAt() { return expiresAt; }
    public Date getAcceptedAt() { return acceptedAt; }
    public Date getRevokedAt() { return revokedAt; }
    public Integer getVersion() { return version; }
    public String getMutationState() { return mutationState; }
}
