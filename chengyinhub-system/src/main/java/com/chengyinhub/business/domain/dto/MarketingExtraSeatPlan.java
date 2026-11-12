package com.chengyinhub.business.domain.dto;

import java.util.List;

/**
 * 一次加座的业务计划：由 {@code IMarketingExtraSeatService#requirePurchasePlan} 在原单行锁内产出，
 * 交给 M3 的下单路径写入 {@code marketing_product_order}（fulfillment_kind=3、original_order_id）。
 *
 * <p><b>为什么必须显式携带 partyBinding</b>：自玩通行证（{@code cms_registration.owner_type=1}）
 * 在本仓没有队伍/席位名册——{@code PlayTeam} 只服务活动场次（owner_type=2，入队要求该场次已付报名），
 * {@code cms_registration_seat} 又要求 {@code seatNo <= orderNum}。因此这一位是
 * **独立付款的独立准入**，不冒充"加入原队"。
 *
 * <p><b>capacity 字段恒为 null</b>：自玩增购没有"场次座位"这种资源，加座件数不受任何既有配置限制；
 * 把 {@code cms_topic.team_max_members}（活动组队人数）当上限会误拒正常增购。
 * 这两个键保留只为响应形状稳定，值永远为 null，客户端不得据此置灰按钮。
 */
public class MarketingExtraSeatPlan {

    /** 原营销订单主键（写入 marketing_product_order.original_order_id）。 */
    private Long originalOrderId;
    /** 原营销订单号（回显与签名用）。 */
    private String originalOrderNo;
    /** 本单受赠人；必需且不能是买家本人。 */
    private Long recipientMemberId;
    /** 原单这份通行证实际覆盖的主题（来自成交时冻结的 order_line，不是当前商品配置）。 */
    private List<Long> topicIds;
    /** 原 registration 已付席位数（{@code cms_registration.order_num}）。 */
    private Integer originalPaidSeats;
    /** 恒为 null：自玩增购没有可读的容量资源。 */
    private Integer partyCapacity;
    /** 恒为 null：同上，不按任何配置推算剩余位。 */
    private Integer seatsRemaining;
    /** 固定为 NOT_APPLICABLE：自玩域没有可加入的队伍/席位名册。 */
    private String partyBinding;
    /** 机器可读原因，客户端不解析中文文案。 */
    private String partyBindingReason;
    /** 准入取得方式：复用原 grant + 礼品单源单准入，领取一次生效。 */
    private String admissionMode;

    public Long getOriginalOrderId() { return originalOrderId; }
    public void setOriginalOrderId(Long originalOrderId) { this.originalOrderId = originalOrderId; }

    public String getOriginalOrderNo() { return originalOrderNo; }
    public void setOriginalOrderNo(String originalOrderNo) { this.originalOrderNo = originalOrderNo; }

    public Long getRecipientMemberId() { return recipientMemberId; }
    public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }

    public List<Long> getTopicIds() { return topicIds; }
    public void setTopicIds(List<Long> topicIds) { this.topicIds = topicIds; }

    public Integer getOriginalPaidSeats() { return originalPaidSeats; }
    public void setOriginalPaidSeats(Integer originalPaidSeats) { this.originalPaidSeats = originalPaidSeats; }

    public Integer getPartyCapacity() { return partyCapacity; }
    public void setPartyCapacity(Integer partyCapacity) { this.partyCapacity = partyCapacity; }

    public Integer getSeatsRemaining() { return seatsRemaining; }
    public void setSeatsRemaining(Integer seatsRemaining) { this.seatsRemaining = seatsRemaining; }

    public String getPartyBinding() { return partyBinding; }
    public void setPartyBinding(String partyBinding) { this.partyBinding = partyBinding; }

    public String getPartyBindingReason() { return partyBindingReason; }
    public void setPartyBindingReason(String partyBindingReason) { this.partyBindingReason = partyBindingReason; }

    public String getAdmissionMode() { return admissionMode; }
    public void setAdmissionMode(String admissionMode) { this.admissionMode = admissionMode; }
}
