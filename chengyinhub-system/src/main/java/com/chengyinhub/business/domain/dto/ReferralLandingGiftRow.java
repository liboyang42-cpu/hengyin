package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 礼品领取码的落地页只读行。
 *
 * <p>按 {@code claim_token_hash} 精确命中；不返回买家、订单、来源注册单或领取人 id，
 * 只保留"这份礼品现在能否被领取"所需的状态事实。
 */
public class ReferralLandingGiftRow {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CLAIMED = 1;
    public static final int STATUS_EXPIRED = 2;
    public static final int STATUS_REVOKED = 3;

    private String publicId;
    private Long productId;
    private Integer status;
    /** 1=已预绑定指定领取人；只给事实，不给对方会员 id。 */
    private boolean recipientBound;
    private Date claimExpiresAt;
    private Date claimedAt;

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public boolean isRecipientBound() { return recipientBound; }
    public void setRecipientBound(boolean recipientBound) { this.recipientBound = recipientBound; }

    public Date getClaimExpiresAt() { return claimExpiresAt; }
    public void setClaimExpiresAt(Date claimExpiresAt) { this.claimExpiresAt = claimExpiresAt; }

    public Date getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Date claimedAt) { this.claimedAt = claimedAt; }
}
