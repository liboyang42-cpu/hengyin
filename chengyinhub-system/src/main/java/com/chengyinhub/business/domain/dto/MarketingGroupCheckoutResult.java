package com.chengyinhub.business.domain.dto;

import java.util.List;
import java.util.Map;

/**
 * 团体/学校商品建单结果。
 *
 * <p>{@link #registration} 是既有报名结算返回的原始 data（registrationId/registrationNo/
 * payableAmount/registrationIds/payParams 等）；本对象只额外标注商品绑定，不复制金额真源。
 */
public class MarketingGroupCheckoutResult {

    private Long productId;
    private Integer purchaseRoute;
    private Integer ownerType;
    private Long activityId;
    private Long topicId;
    private Long ticketId;
    private Integer quantity;

    private String message;
    private Long registrationId;
    private List<Long> registrationIds;
    /** 原报名结算 data 的只读副本，供原 `/api/registration/info` 读取同一单。 */
    private Map<String, Object> registration;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getPurchaseRoute() { return purchaseRoute; }
    public void setPurchaseRoute(Integer purchaseRoute) { this.purchaseRoute = purchaseRoute; }

    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }

    public List<Long> getRegistrationIds() { return registrationIds; }
    public void setRegistrationIds(List<Long> registrationIds) { this.registrationIds = registrationIds; }

    public Map<String, Object> getRegistration() { return registration; }
    public void setRegistration(Map<String, Object> registration) { this.registration = registration; }
}
