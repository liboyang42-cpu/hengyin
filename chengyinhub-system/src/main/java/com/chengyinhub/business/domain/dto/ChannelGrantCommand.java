package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 渠道兑换交给权威注册/权益 grant 端口的不可变事实。
 *
 * <p>这是 M8 与 M2-F 的边界：M8 只证明“外部订单已验证、码未被用过”，
 * 端口负责在同一事务内产出 {@code cms_registration} + 既有权益事实。
 * 本对象不携带任何“直接置 PAID”的指令。
 *
 * <p><b>typed 来源</b>：{@code sourceType=CHANNEL}，并带 provider/external order/
 * 不可变 fact id+hash/原金额币种。适配器必须把这些字段与微信/积分事实分开落账。
 *
 * <p><b>席位</b>：{@code seatCount} 已按“商品席位 × 购买数量”算好；
 * 适配器必须按原商品数量授权，不能硬编码一席位。
 */
public class ChannelGrantCommand {

    /** 外部来源类型：渠道验证事实，与微信/积分事实分开。 */
    public static final String SOURCE_CHANNEL = "CHANNEL";

    public static final int VALIDITY_NONE = 0;
    public static final int VALIDITY_RELATIVE_DAYS = 1;
    public static final int VALIDITY_FIXED_WINDOW = 2;

    private String sourceType;
    private Long externalFactId;
    private String externalFactHash;
    private String channelKey;
    private String providerKey;
    private String channelOrderNo;
    private BigDecimal externalAmount;
    private String externalCurrency;

    private Long memberId;
    private Long productId;
    private Long productRevision;
    private Integer quantity;
    private Integer seatCount;
    private Integer subjectMode;
    private Long boundMemberId;
    private Integer validityKind;
    private Integer validDays;
    private Date fixedValidFrom;
    private Date fixedValidTo;
    private Date expiresAt;
    private Date grantedAt;
    private String requestId;
    private String requestFingerprint;
    private String termsVersion;
    private List<ChannelGrantTopic> topics;

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public Long getExternalFactId() { return externalFactId; }
    public void setExternalFactId(Long externalFactId) { this.externalFactId = externalFactId; }

    public String getExternalFactHash() { return externalFactHash; }
    public void setExternalFactHash(String externalFactHash) { this.externalFactHash = externalFactHash; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public BigDecimal getExternalAmount() { return externalAmount; }
    public void setExternalAmount(BigDecimal externalAmount) { this.externalAmount = externalAmount; }

    public String getExternalCurrency() { return externalCurrency; }
    public void setExternalCurrency(String externalCurrency) { this.externalCurrency = externalCurrency; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getProductRevision() { return productRevision; }
    public void setProductRevision(Long productRevision) { this.productRevision = productRevision; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }

    public Integer getSubjectMode() { return subjectMode; }
    public void setSubjectMode(Integer subjectMode) { this.subjectMode = subjectMode; }

    public Long getBoundMemberId() { return boundMemberId; }
    public void setBoundMemberId(Long boundMemberId) { this.boundMemberId = boundMemberId; }

    public Integer getValidityKind() { return validityKind; }
    public void setValidityKind(Integer validityKind) { this.validityKind = validityKind; }

    public Integer getValidDays() { return validDays; }
    public void setValidDays(Integer validDays) { this.validDays = validDays; }

    public Date getFixedValidFrom() { return fixedValidFrom; }
    public void setFixedValidFrom(Date fixedValidFrom) { this.fixedValidFrom = fixedValidFrom; }

    public Date getFixedValidTo() { return fixedValidTo; }
    public void setFixedValidTo(Date fixedValidTo) { this.fixedValidTo = fixedValidTo; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }

    public Date getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Date grantedAt) { this.grantedAt = grantedAt; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    public List<ChannelGrantTopic> getTopics() { return topics; }
    public void setTopics(List<ChannelGrantTopic> topics) { this.topics = topics; }
}
