package com.chengyinhub.business.domain;

import java.math.BigDecimal;

/**
 * 四账对账差异明细(P1-6 N块,只读产出,不落库)。
 *
 * <p>依据 P1-5《四账映射与守恒规则》(chengyinhub-admin/sql/_四账映射与守恒规则_20260725.sql)
 * 冻结的三条守恒式,每条违反守恒式的比对结果生成一行。本类只是携带差异结果的值对象,
 * 对账 Service 本身不做自动补偿/自动重放——"差异可重放"是后续阶段的事,本轮先把
 * "能发现差异"这个核心能力做出来。
 */
public class LedgerDiscrepancy {

    /** 对账维度:见 {@link com.chengyinhub.business.service.IFourLedgerReconcileService} 各方法注释 */
    private String dimension;

    /** 业务定位键,如 registrationNo / memberId / merchantMemberId+periodYm,供人工排查用 */
    private String bizKey;

    /** 该维度里"应该是多少"(以哪个账为准,见各检查方法注释) */
    private BigDecimal expectedAmount;

    /** 该维度里"实际是多少" */
    private BigDecimal actualAmount;

    /** actual - expected,正负号保留(★不能取绝对值,方向本身是排查线索:多了还是少了) */
    private BigDecimal deltaAmount;

    /** 差异原因,面向人工排查的可读描述 */
    private String reason;

    public LedgerDiscrepancy() {
    }

    public LedgerDiscrepancy(String dimension, String bizKey, BigDecimal expectedAmount,
                              BigDecimal actualAmount, String reason) {
        this.dimension = dimension;
        this.bizKey = bizKey;
        this.expectedAmount = expectedAmount;
        this.actualAmount = actualAmount;
        this.deltaAmount = actualAmount.subtract(expectedAmount);
        this.reason = reason;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public String getBizKey() {
        return bizKey;
    }

    public void setBizKey(String bizKey) {
        this.bizKey = bizKey;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public void setExpectedAmount(BigDecimal expectedAmount) {
        this.expectedAmount = expectedAmount;
    }

    public BigDecimal getActualAmount() {
        return actualAmount;
    }

    public void setActualAmount(BigDecimal actualAmount) {
        this.actualAmount = actualAmount;
    }

    public BigDecimal getDeltaAmount() {
        return deltaAmount;
    }

    public void setDeltaAmount(BigDecimal deltaAmount) {
        this.deltaAmount = deltaAmount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
