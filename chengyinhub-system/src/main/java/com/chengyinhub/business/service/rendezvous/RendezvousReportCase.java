package com.chengyinhub.business.service.rendezvous;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Reporter-visible receipt and the case row behind it.
 * The receipt has no counterpart, place, clue, or evidence body. */
public final class RendezvousReportCase {
    public enum State { LOCAL_INTAKE, UNKNOWN, SENT_TO_REVIEW, RESOLVED }
    public enum ReviewConnection { NOT_CONNECTED, AMBIGUOUS, UNKNOWN, ACCEPTED }
    public static final String EVIDENCE_TICKET_REF = "TICKET_REF";

    private RendezvousReportCase() {}

    /** Client projection. {@code reviewRef} and {@code dispositionCode} stay null until a real review result is stored. */
    public static final class Receipt {
        public final String caseId, requestId, ticketId, state, reviewConnection, reviewRef, dispositionCode;
        Receipt(String caseId, String requestId, String ticketId, String state, String reviewConnection,
                String reviewRef, String dispositionCode) {
            this.caseId = caseId; this.requestId = requestId; this.ticketId = ticketId; this.state = state;
            this.reviewConnection = reviewConnection; this.reviewRef = reviewRef; this.dispositionCode = dispositionCode;
        }
    }

    /** Internal row. Not a client response. Counterpart identity is not a column. */
    public static final class Row {
        public String caseId, requestId, ticketId, matchId, payloadHash, evidenceKind, state, reviewConnection, reviewRef, dispositionCode;
        public long reporterId, createdAt;
    }

    static Receipt receipt(Row row) {
        return new Receipt(row.caseId, row.requestId, row.ticketId, row.state, row.reviewConnection, row.reviewRef, row.dispositionCode);
    }

    /** Binds the ticket, the only allowed evidence kind, the match, and the derived counterpart. */
    static String payloadHash(String ticketId, String evidenceKind, String matchId, long counterpart) {
        String canonical = prefix(ticketId) + prefix(evidenceKind) + prefix(matchId) + counterpart;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failed) {
            throw new IllegalStateException("SHA-256", failed);
        }
    }

    private static String prefix(String value) { return value.length() + ":" + value; }
}
