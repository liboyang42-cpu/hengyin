package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.service.IPlayTeamService;
import java.util.Objects;

/** Calls the existing authoritative create/join methods, never writes team tables.
 * NOT a Spring bean. Production is deliberately unsupported: legacy create lacks a
 * durable handoff idempotency key/result query and may perform non-transactional IM work.
 * ISOLATED_TEST_DOUBLE_ONLY is only for a synthetic IPlayTeamService in contract tests.
 * {@link #createAndJoin} is not the recovery state machine. {@link #recoveryPort()} does not
 * call this service and cannot prove that a team is absent.
 */
public final class ExistingPlayTeamMeetingHandoff implements RendezvousMeetingService.Handoff {
    public enum Mode { DISABLED, ISOLATED_TEST_DOUBLE_ONLY }
    private final Mode mode;
    private final IPlayTeamService teams;
    public ExistingPlayTeamMeetingHandoff(IPlayTeamService teams) { this(Mode.DISABLED, teams); }
    public ExistingPlayTeamMeetingHandoff(Mode mode, IPlayTeamService teams) {
        this.mode=Objects.requireNonNull(mode); this.teams=Objects.requireNonNull(teams);
    }
    public boolean available() { return mode == Mode.ISOLATED_TEST_DOUBLE_ONLY; }
    /** Fail-closed. Does not delegate to {@link #createAndJoin}. */
    public RendezvousHandoffTeamPort recoveryPort() { return new RendezvousHandoffLegacyTeamPort(); }
    public long createAndJoin(long first, long second, long activity) {
        if (!available()) throw new IllegalStateException("W16_LEGACY_HANDOFF_DISABLED");
        if (first <= 0 || second <= 0 || first == second || activity <= 0) throw new IllegalArgumentException("INVALID_HANDOFF");
        Long createdId=null;
        try {
            // Explicit invitation-only, two-member, activity-owned team. No public-map default.
            PlayTeam created=teams.create(first, 2, activity, null, 2, 1);
            if (created == null || created.getId() == null || created.getId() <= 0)
                throw new IllegalStateException("TEAM_CREATE_RESULT_UNKNOWN");
            createdId=created.getId();
            if (created.getInviteCode() == null || created.getInviteCode().trim().isEmpty())
                throw new IllegalStateException("TEAM_INVITE_RESULT_UNKNOWN");
            PlayTeam joined=teams.join(created.getInviteCode(), second);
            if (joined == null || !createdId.equals(joined.getId()))
                throw new IllegalStateException("TEAM_JOIN_RESULT_UNKNOWN");
            return createdId;
        } catch (RuntimeException uncertain) {
            // Even a thrown create may have committed/created an IM group. Never compensate,
            // retry create, synthesize membership, or report meeting success here.
            throw new RendezvousMeetingService.RecoveryRequired(createdId);
        }
    }
}
