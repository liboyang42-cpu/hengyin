package com.chengyinhub.business.domain;

/** 不携带原始 trace 的 inbox 领取结果。 */
public final class WxContentCallbackClaim
{
    public enum State { ACQUIRED, DONE, BUSY, MISSING, REJECTED }

    private final State state;
    private final String traceHash;
    private final String ownerToken;
    private final long fenceToken;

    private WxContentCallbackClaim(State state, String traceHash, String ownerToken, long fenceToken)
    {
        this.state = state;
        this.traceHash = traceHash;
        this.ownerToken = ownerToken;
        this.fenceToken = fenceToken;
    }

    public static WxContentCallbackClaim acquired(String traceHash, String ownerToken, long fenceToken)
    {
        return new WxContentCallbackClaim(State.ACQUIRED, traceHash, ownerToken, fenceToken);
    }

    public static WxContentCallbackClaim done(String traceHash)
    {
        return new WxContentCallbackClaim(State.DONE, traceHash, null, 0L);
    }

    public static WxContentCallbackClaim of(State state, String traceHash)
    {
        return new WxContentCallbackClaim(state, traceHash, null, 0L);
    }

    public State getState() { return state; }
    public String getTraceHash() { return traceHash; }
    public String getOwnerToken() { return ownerToken; }
    public long getFenceToken() { return fenceToken; }
}
