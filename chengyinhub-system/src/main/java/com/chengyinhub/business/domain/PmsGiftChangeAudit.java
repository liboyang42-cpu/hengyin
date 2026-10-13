package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 礼物目录的不可缺失变更审计记录。
 *
 * <p>该表与 pms_gift 的写入处于同一事务，不能用异步 sys_oper_log 替代。</p>
 */
public class PmsGiftChangeAudit extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long giftId;
    private String action;
    private Long oldPoint;
    private Long newPoint;
    private Integer oldStatus;
    private Integer newStatus;
    private String reason;
    private String operatorUsername;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getGiftId()
    {
        return giftId;
    }

    public void setGiftId(Long giftId)
    {
        this.giftId = giftId;
    }

    public String getAction()
    {
        return action;
    }

    public void setAction(String action)
    {
        this.action = action;
    }

    public Long getOldPoint()
    {
        return oldPoint;
    }

    public void setOldPoint(Long oldPoint)
    {
        this.oldPoint = oldPoint;
    }

    public Long getNewPoint()
    {
        return newPoint;
    }

    public void setNewPoint(Long newPoint)
    {
        this.newPoint = newPoint;
    }

    public Integer getOldStatus()
    {
        return oldStatus;
    }

    public void setOldStatus(Integer oldStatus)
    {
        this.oldStatus = oldStatus;
    }

    public Integer getNewStatus()
    {
        return newStatus;
    }

    public void setNewStatus(Integer newStatus)
    {
        this.newStatus = newStatus;
    }

    public String getReason()
    {
        return reason;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    public String getOperatorUsername()
    {
        return operatorUsername;
    }

    public void setOperatorUsername(String operatorUsername)
    {
        this.operatorUsername = operatorUsername;
    }
}
