package com.chengyinhub.business.domain.vo;

/** 原始 token 只在这一次创建响应中返回；列表接口永不返回。 */
public class MerchantInviteCreationVO {
    private final MerchantOperatorInviteVO invite;
    private final String token;
    public MerchantInviteCreationVO(MerchantOperatorInviteVO invite, String token) {
        this.invite = invite;
        this.token = token;
    }
    public MerchantOperatorInviteVO getInvite() { return invite; }
    public String getToken() { return token; }
}
