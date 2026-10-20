package com.chengyinhub.business.marketplace.reservation;

import java.time.Instant;

/** Durable quota hold only. Never an execution/publication token or a license grant. */
public final class ModuleReservationReceipt {
    private final String reservationKey;
    private final String state;
    private final Instant expiresAt;
    ModuleReservationReceipt(String reservationKey, String state, Instant expiresAt) {
        this.reservationKey = reservationKey; this.state = state; this.expiresAt = expiresAt;
    }
    public String getReservationKey() { return reservationKey; }
    public String getState() { return state; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isExecutable() { return false; }
    public boolean isPublished() { return false; }
    public boolean grantsPlayerAdmission() { return false; }
}
