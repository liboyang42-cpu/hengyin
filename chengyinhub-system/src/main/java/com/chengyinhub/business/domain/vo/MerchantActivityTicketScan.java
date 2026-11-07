package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 商家自有活动的已核销票(cms_registration.verification_status=1)只读投影,供资金域「核销与退款 / 核销记录」
 * 与 redemption(主题章节核销)同列。settlementAmount / settlementStatus 取该报名商家方的
 * settlement_detail(支付时落账的待结算/已结算事实,作废行不取)。姓名是密文,解密只在 Service 做。
 */
public class MerchantActivityTicketScan {
    private Long registrationId;
    private Long activityId;
    private Long topicId;
    private String activityTitle;
    private String ticketName;
    private String realName;
    private Date verifiedAt;
    private BigDecimal settlementAmount;
    private Integer settlementStatus;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long value) { activityId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public String getActivityTitle() { return activityTitle; }
    public void setActivityTitle(String value) { activityTitle = value; }
    public String getTicketName() { return ticketName; }
    public void setTicketName(String value) { ticketName = value; }
    public String getRealName() { return realName; }
    public void setRealName(String value) { realName = value; }
    public Date getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Date value) { verifiedAt = value; }
    public BigDecimal getSettlementAmount() { return settlementAmount; }
    public void setSettlementAmount(BigDecimal value) { settlementAmount = value; }
    public Integer getSettlementStatus() { return settlementStatus; }
    public void setSettlementStatus(Integer value) { settlementStatus = value; }

    @Override
    public String toString() {
        // 姓名不进 toString,避免被日志或异常带出。
        return "MerchantActivityTicketScan{registrationId=" + registrationId + "}";
    }
}
