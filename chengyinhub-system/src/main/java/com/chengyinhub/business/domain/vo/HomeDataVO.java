package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public class HomeDataVO {

    @Schema(description = "用户总数")
    private Integer userNum=0;

    @Schema(description = "商家总数")
    private Integer merchantNum=0 ;

    @Schema(description = "商家待审数")
    private Integer merchantAuditNum=0 ;

    @Schema(description = "创意广场数量")
    private Integer creativeSquareNum=0 ;

    @Schema(description = "主题数量")
    private Integer topicNum=0;

    @Schema(description = "活动数量")
    private Integer  activityNum=0;

    @Schema(description = "用户模板数量")
    private Integer memberTemplateNum=0;

    public Integer getUserNum() {
        return userNum;
    }

    public void setUserNum(Integer userNum) {
        this.userNum = userNum;
    }

    public Integer getMerchantNum() {
        return merchantNum;
    }

    public void setMerchantNum(Integer merchantNum) {
        this.merchantNum = merchantNum;
    }

    public Integer getMerchantAuditNum() {
        return merchantAuditNum;
    }

    public void setMerchantAuditNum(Integer merchantAuditNum) {
        this.merchantAuditNum = merchantAuditNum;
    }

    public Integer getCreativeSquareNum() {
        return creativeSquareNum;
    }

    public void setCreativeSquareNum(Integer creativeSquareNum) {
        this.creativeSquareNum = creativeSquareNum;
    }

    public Integer getTopicNum() {
        return topicNum;
    }

    public void setTopicNum(Integer topicNum) {
        this.topicNum = topicNum;
    }

    public Integer getActivityNum() {
        return activityNum;
    }

    public void setActivityNum(Integer activityNum) {
        this.activityNum = activityNum;
    }

    public Integer getMemberTemplateNum() {
        return memberTemplateNum;
    }

    public void setMemberTemplateNum(Integer memberTemplateNum) {
        this.memberTemplateNum = memberTemplateNum;
    }

}
