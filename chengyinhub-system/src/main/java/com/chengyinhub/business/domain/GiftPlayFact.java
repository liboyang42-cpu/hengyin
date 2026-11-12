package com.chengyinhub.business.domain;

/**
 * 礼品对"谁可玩"的只读事实（由 {@code gift_entitlement} + 原 {@code cms_registration} 得出）。
 *
 * <p>不是第二种准入真源：它只把"这张已付行是否已被礼品扣住/转给谁"投影出来，
 * 准入判定仍由 {@code CurrentPlayRegistration} 按原规则在 {@code cms_registration} 上做。
 */
public class GiftPlayFact {

    /** 0 待领取（无人可玩） 1 已领取（仅领取人可玩） */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CLAIMED = 1;

    private Long registrationId;
    private Integer status;
    private Long claimedMemberId;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Long getClaimedMemberId() { return claimedMemberId; }
    public void setClaimedMemberId(Long claimedMemberId) { this.claimedMemberId = claimedMemberId; }

    public boolean pending() { return Integer.valueOf(STATUS_PENDING).equals(status); }

    public boolean claimedBy(Long memberId) {
        return Integer.valueOf(STATUS_CLAIMED).equals(status) && memberId != null
                && memberId.equals(claimedMemberId);
    }

    /** 该行此人对礼品而言是否应被排除（待领取任何人都不行；已领取只排除非领取人）。 */
    public boolean withholds(Long memberId) {
        if (pending()) return true;
        return Integer.valueOf(STATUS_CLAIMED).equals(status) && !claimedBy(memberId);
    }
}
