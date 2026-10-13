package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MerchantOperator;

import java.util.Date;

/** 员工列表白名单，不下发 memberId、手机号、撤销操作者或内部审计字段。 */
public class MerchantOperatorVO {
    public static final String MUTATION_EXACT_RESULT = "EXACT_RESULT";
    public static final String MUTATION_LATER_AUTHORITATIVE = "LATER_AUTHORITATIVE";

    private Long id;
    private String nickname;
    private String avatar;
    private String roleCode;
    private String status;
    private Date acceptedAt;
    private Date revokedAt;
    private Integer version;
    private String mutationState;

    public static MerchantOperatorVO from(MerchantOperator source) {
        MerchantOperatorVO out = new MerchantOperatorVO();
        if (source != null) {
            out.id = source.getId();
            out.nickname = source.getOperatorNickname();
            out.avatar = source.getOperatorAvatar();
            out.roleCode = source.getRoleCode();
            out.status = source.getStatus();
            out.acceptedAt = source.getAcceptedAt();
            out.revokedAt = source.getRevokedAt();
            out.version = source.getVersion();
        }
        return out;
    }

    public static MerchantOperatorVO fromMutation(MerchantOperator source, String mutationState) {
        MerchantOperatorVO out = from(source);
        if (!MUTATION_EXACT_RESULT.equals(mutationState)
                && !MUTATION_LATER_AUTHORITATIVE.equals(mutationState)) {
            throw new IllegalArgumentException("invalid merchant operator mutation state");
        }
        out.mutationState = mutationState;
        return out;
    }

    public Long getId() { return id; }
    public String getNickname() { return nickname; }
    public String getAvatar() { return avatar; }
    public String getRoleCode() { return roleCode; }
    public String getStatus() { return status; }
    public Date getAcceptedAt() { return acceptedAt; }
    public Date getRevokedAt() { return revokedAt; }
    public Integer getVersion() { return version; }
    public String getMutationState() { return mutationState; }
}
