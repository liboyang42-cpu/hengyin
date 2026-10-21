package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.HandoffState;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.SoloResult;

/** Decides whether a solo continuation may be attempted. It never reports completion by itself. */
public final class RendezvousSoloContinuation {
    private RendezvousSoloContinuation() {}

    public static final class Plan {
        public final boolean start;
        public final String rejectionCode;
        public final String block;
        public final String goalRef;
        private Plan(boolean start, String rejectionCode, String block, String goalRef) {
            this.start = start;
            this.rejectionCode = rejectionCode;
            this.block = block;
            this.goalRef = goalRef;
        }
    }

    public static Plan plan(HandoffState handoff, String goalRef, boolean launcherPresent) {
        if (handoff != HandoffState.NOT_STARTED) {
            if (handoff == HandoffState.CREATED_JOIN_UNCONFIRMED) return blocked("HANDOFF_JOIN_UNCONFIRMED", "HANDOFF_JOIN_UNCONFIRMED");
            if (handoff == HandoffState.COMPLETE) return blocked("HANDOFF_COMPLETE", "HANDOFF_COMPLETE");
            return blocked("HANDOFF_UNKNOWN", "HANDOFF_UNKNOWN");
        }
        if (!ref(goalRef)) return blocked("SOLO_GOAL_UNAVAILABLE", "GOAL_NOT_PUBLISHED");
        if (!launcherPresent) return blocked("SOLO_LAUNCHER_UNAVAILABLE", "LAUNCHER_MISSING");
        return new Plan(true, null, null, goalRef);
    }

    public static boolean completed(SoloResult.Status status) {
        return status == SoloResult.Status.COMPLETED;
    }

    static boolean ref(String value) {
        return value != null && !value.isEmpty() && value.length() <= 128 && value.trim().equals(value);
    }

    private static Plan blocked(String rejectionCode, String block) {
        return new Plan(false, rejectionCode, block, null);
    }
}
