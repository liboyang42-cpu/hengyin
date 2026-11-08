package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 顾客准备小费的请求。
 *
 * <p>小费是顾客<b>自愿</b>的额外支付:{@code orderRefId} 只用于关联既有订单的归属校验,
 * 不会改变该订单金额、支付状态、票券或退款规则。
 *
 * @author chengyinhub
 */
public class HostTipPrepareRequest
{
    private Long hostMemberId;
    /** 作用域俱乐部;不传按 0(不限定)。 */
    private Long clubId;
    private BigDecimal amount;
    /** 关联既有商城订单;不传 = 无关联(纯自愿小费)。 */
    private Long orderRefId;
    /** 客户端幂等键:同一顾客同一 requestId 只会有一笔意向单。 */
    private String requestId;
    private String note;

    public Long getHostMemberId() { return hostMemberId; }
    public void setHostMemberId(Long hostMemberId) { this.hostMemberId = hostMemberId; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Long getOrderRefId() { return orderRefId; }
    public void setOrderRefId(Long orderRefId) { this.orderRefId = orderRefId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
