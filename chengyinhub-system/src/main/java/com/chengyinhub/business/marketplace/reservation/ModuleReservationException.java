package com.chengyinhub.business.marketplace.reservation;

/** Internal failure; future HTTP adapters must use the existing application error envelope. */
public final class ModuleReservationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Reason { IDEMPOTENCY_CONFLICT, RESERVATION_TERMINAL, RESERVATION_NOT_FOUND,
        RESERVATION_EXPIRED, AUTHORITY_CHANGED, STORAGE_INVARIANT }
    private final Reason reason;
    public ModuleReservationException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason getReason() { return reason; }
}
