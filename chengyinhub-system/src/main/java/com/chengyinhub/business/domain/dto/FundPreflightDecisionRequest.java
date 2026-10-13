package com.chengyinhub.business.domain.dto;

import javax.validation.constraints.NotBlank;

public class FundPreflightDecisionRequest {
    @NotBlank(message = "确认凭证不能为空")
    private String challengeToken;
    public String getChallengeToken() { return challengeToken; }
    public void setChallengeToken(String challengeToken) { this.challengeToken = challengeToken; }
}
