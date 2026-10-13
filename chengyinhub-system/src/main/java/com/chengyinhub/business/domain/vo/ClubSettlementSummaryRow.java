package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CoopSettlement;
import java.math.BigDecimal;
import java.util.Date;

/** 俱乐部只读对账投影：核算调整与实际入账是两个独立事实。 */
public class ClubSettlementSummaryRow extends CoopSettlement {
    private BigDecimal executedAdjustmentAmount;
    private BigDecimal actualPaidAmount;
    private Date actualPayoutTime;
    /** CU-C-40:主题名随同一次查询带出(cms_topic 左连),不再由展示层拼「主题 #id」。 */
    private String topicName;

    public String getTopicName() { return topicName; }
    public void setTopicName(String value) { topicName = value; }

    public BigDecimal getExecutedAdjustmentAmount() { return executedAdjustmentAmount; }
    public void setExecutedAdjustmentAmount(BigDecimal value) { executedAdjustmentAmount = value; }
    public BigDecimal getActualPaidAmount() { return actualPaidAmount; }
    public void setActualPaidAmount(BigDecimal value) { actualPaidAmount = value; }
    public Date getActualPayoutTime() { return actualPayoutTime; }
    public void setActualPayoutTime(Date value) { actualPayoutTime = value; }
}
