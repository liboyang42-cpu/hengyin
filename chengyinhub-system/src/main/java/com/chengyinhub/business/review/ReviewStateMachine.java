package com.chengyinhub.business.review;

import java.util.Set;

/**
 * The submission review state machine shared by external H5 games and workshop professional materializations.
 * <pre>
 *   PENDING ──approve / reject / cancel──► APPROVED / REJECTED / CANCELLED
 *      └──escalate (reviewer or timeout)──► ESCALATED ──approve / reject / cancel──► APPROVED / REJECTED / CANCELLED
 * </pre>
 * APPROVED, REJECTED and CANCELLED are terminal. Callers lock the row, check {@link #sources(String)} against the
 * current status, write the new status with a compare-and-set on the old one, and append one event row
 * (actor AUTHOR / ADMIN / SYSTEM) in the same transaction.
 */
public final class ReviewStateMachine {
    public static final String PENDING = "PENDING", APPROVED = "APPROVED", REJECTED = "REJECTED", ESCALATED = "ESCALATED",
            CANCELLED = "CANCELLED";
    public static final Set<String> STATUSES = Set.of(PENDING, APPROVED, REJECTED, ESCALATED, CANCELLED);
    private static final Set<String> OPEN = Set.of(PENDING, ESCALATED);

    private ReviewStateMachine() {}

    /** The statuses a submission may leave to reach {@code target}. */
    public static Set<String> sources(String target) {
        return switch (target) {
            case APPROVED, REJECTED, CANCELLED -> OPEN;
            case ESCALATED -> Set.of(PENDING);
            default -> throw new IllegalArgumentException(target);
        };
    }

    public static boolean allowed(String current, String target) {
        return current != null && sources(target).contains(current);
    }

    public static boolean open(String status) { return OPEN.contains(status); }

    public static boolean terminal(String status) {
        return APPROVED.equals(status) || REJECTED.equals(status) || CANCELLED.equals(status);
    }
}
