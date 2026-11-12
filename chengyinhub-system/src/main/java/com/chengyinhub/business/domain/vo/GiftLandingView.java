package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 礼品领取码落地页只读投影（匿名可读）。
 *
 * <p><b>不领取、不发放、不透露买家</b>：展示的是这份礼品"现在是什么状态、覆盖哪些作品"，
 * 真正领取仍走 {@code POST /api/marketing/product/gifts/claim}（需登录、支持 requestId 幂等）。
 * 礼物受益人 ≠ 付费买家：买家信息一律不下发，领取成功前也不把任何人标记为受益人。
 */
public class GiftLandingView {

    public static final String KIND_GIFT = "GIFT";

    public static final String STATE_VALID = "VALID";
    public static final String STATE_INVALID_TOKEN = "INVALID_TOKEN";
    public static final String STATE_TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String STATE_CLAIMED = "CLAIMED";
    public static final String STATE_GIFT_EXPIRED = "GIFT_EXPIRED";
    public static final String STATE_GIFT_REVOKED = "GIFT_REVOKED";
    public static final String STATE_PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE";

    public static final String ACTION_NONE = "NONE";
    public static final String ACTION_CLAIM_GIFT = "CLAIM_GIFT";

    private String kind = KIND_GIFT;
    private String state;
    private String reasonCode;
    /** 商品公开摘要；不包含买家、价格、订单号与来源注册单。 */
    private Product product;
    /** 码级领取截止；null 表示由礼品行自身有效期约束。 */
    private Date claimExpiresAt;
    /** 是否已绑定指定领取人（只报事实，不回报对方会员 id）。 */
    private boolean recipientBound;
    private Date claimedAt;
    private String nextAction = ACTION_NONE;
    private Endpoints endpoints;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public Date getClaimExpiresAt() { return claimExpiresAt; }
    public void setClaimExpiresAt(Date claimExpiresAt) { this.claimExpiresAt = claimExpiresAt; }

    public boolean isRecipientBound() { return recipientBound; }
    public void setRecipientBound(boolean recipientBound) { this.recipientBound = recipientBound; }

    public Date getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Date claimedAt) { this.claimedAt = claimedAt; }

    public String getNextAction() { return nextAction; }
    public void setNextAction(String nextAction) { this.nextAction = nextAction; }

    public Endpoints getEndpoints() { return endpoints; }
    public void setEndpoints(Endpoints endpoints) { this.endpoints = endpoints; }

    /** 礼品商品公开字段：名称/种类/有效期/覆盖作品（标题与冻结修订），不含价格与订单。 */
    public static class Product {
        private Long productId;
        private String productCode;
        private String productName;
        private Integer productKind;
        private Integer validityKind;
        private Integer validDays;
        private Date fixedValidFrom;
        private Date fixedValidTo;
        private Long revision;
        private List<Item> items = new ArrayList<>();

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public String getProductCode() { return productCode; }
        public void setProductCode(String productCode) { this.productCode = productCode; }

        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }

        public Integer getProductKind() { return productKind; }
        public void setProductKind(Integer productKind) { this.productKind = productKind; }

        public Integer getValidityKind() { return validityKind; }
        public void setValidityKind(Integer validityKind) { this.validityKind = validityKind; }

        public Integer getValidDays() { return validDays; }
        public void setValidDays(Integer validDays) { this.validDays = validDays; }

        public Date getFixedValidFrom() { return fixedValidFrom; }
        public void setFixedValidFrom(Date fixedValidFrom) { this.fixedValidFrom = fixedValidFrom; }

        public Date getFixedValidTo() { return fixedValidTo; }
        public void setFixedValidTo(Date fixedValidTo) { this.fixedValidTo = fixedValidTo; }

        public Long getRevision() { return revision; }
        public void setRevision(Long revision) { this.revision = revision; }

        public List<Item> getItems() { return items; }
        public void setItems(List<Item> items) { this.items = items; }
    }

    /** 覆盖作品：只给主题 id、标题与商品绑定的内容修订。 */
    public static class Item {
        private Long topicId;
        private String topicTitle;
        private Long contentRevision;

        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }

        public String getTopicTitle() { return topicTitle; }
        public void setTopicTitle(String topicTitle) { this.topicTitle = topicTitle; }

        public Long getContentRevision() { return contentRevision; }
        public void setContentRevision(Long contentRevision) { this.contentRevision = contentRevision; }
    }

    /** 真实领取接口；落地页不提供"直接生效"入口。 */
    public static class Endpoints {
        private String claimGift = "/api/marketing/product/gifts/claim";
        private boolean requiresLogin = true;
        private boolean requestIdIdempotent = true;

        public String getClaimGift() { return claimGift; }
        public void setClaimGift(String claimGift) { this.claimGift = claimGift; }

        public boolean isRequiresLogin() { return requiresLogin; }
        public void setRequiresLogin(boolean requiresLogin) { this.requiresLogin = requiresLogin; }

        public boolean isRequestIdIdempotent() { return requestIdIdempotent; }
        public void setRequestIdIdempotent(boolean requestIdIdempotent) { this.requestIdIdempotent = requestIdIdempotent; }
    }
}
