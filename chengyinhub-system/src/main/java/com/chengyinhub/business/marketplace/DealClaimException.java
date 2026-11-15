package com.chengyinhub.business.marketplace;

/** A refused deal claim. Thrown inside the claim transaction so nothing it wrote survives. */
public class DealClaimException extends RuntimeException {
    public enum Reason { SIGN_IN, UNAVAILABLE, SOLD_OUT }

    private final Reason reason;

    public DealClaimException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
