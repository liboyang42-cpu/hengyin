package com.chengyinhub.business.domain;

/** 不携带原始 notifyId 的支付回调 inbox 领取结果。 */
public final class WxPaymentCallbackClaim
{
    public enum State { ACQUIRED, DONE, BUSY, MISSING, STALE }

    private final State state;
    private final String notifyHash;
    private final String ownerToken;
    private final long fenceToken;

    private WxPaymentCallbackClaim(State state, String notifyHash, String ownerToken, long fenceToken)
    {
        this.state = state;
        this.notifyHash = notifyHash;
        this.ownerToken = ownerToken;
        this.fenceToken = fenceToken;
    }

    public static WxPaymentCallbackClaim acquired(String notifyHash, String ownerToken, long fenceToken)
    {
        return new WxPaymentCallbackClaim(State.ACQUIRED, notifyHash, ownerToken, fenceToken);
    }

    public static WxPaymentCallbackClaim done(String notifyHash)
    {
        return new WxPaymentCallbackClaim(State.DONE, notifyHash, null, 0L);
    }

    public static WxPaymentCallbackClaim stale(String notifyHash)
    {
        return new WxPaymentCallbackClaim(State.STALE, notifyHash, null, 0L);
    }

    public static WxPaymentCallbackClaim of(State state, String notifyHash)
    {
        return new WxPaymentCallbackClaim(state, notifyHash, null, 0L);
    }

    public State getState() { return state; }
    public String getNotifyHash() { return notifyHash; }
    public String getOwnerToken() { return ownerToken; }
    public long getFenceToken() { return fenceToken; }
}
