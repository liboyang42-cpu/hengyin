package com.chengyinhub.business.domain.vo;

import java.util.List;

public class MerchantTeamVO {
    private final List<MerchantOperatorVO> operators;
    private final List<MerchantOperatorInviteVO> invites;
    public MerchantTeamVO(List<MerchantOperatorVO> operators, List<MerchantOperatorInviteVO> invites) {
        this.operators = operators;
        this.invites = invites;
    }
    public List<MerchantOperatorVO> getOperators() { return operators; }
    public List<MerchantOperatorInviteVO> getInvites() { return invites; }
}
