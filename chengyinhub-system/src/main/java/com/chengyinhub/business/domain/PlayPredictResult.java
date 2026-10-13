package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 竞猜结算。一轮只能结一次 —— uk_round 加条件更新兜住,不靠应用层记状态。
 */
public class PlayPredictResult {

    /** 待商家给出答案 */
    public static final int SETTLE_PENDING = 0;
    /** 已结算 */
    public static final int SETTLE_DONE = 1;
    /** 商家没结,整轮作废 */
    public static final int SETTLE_VOID = 2;

    private Long id;
    private Long nodeId;
    private Date playDay;
    private String settledOption;
    private Integer settleStatus;
    private Long settledBy;
    private Date settledAt;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }

    public Date getPlayDay() { return playDay; }
    public void setPlayDay(Date playDay) { this.playDay = playDay; }

    public String getSettledOption() { return settledOption; }
    public void setSettledOption(String settledOption) { this.settledOption = settledOption; }

    public Integer getSettleStatus() { return settleStatus; }
    public void setSettleStatus(Integer settleStatus) { this.settleStatus = settleStatus; }

    public Long getSettledBy() { return settledBy; }
    public void setSettledBy(Long settledBy) { this.settledBy = settledBy; }

    public Date getSettledAt() { return settledAt; }
    public void setSettledAt(Date settledAt) { this.settledAt = settledAt; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
