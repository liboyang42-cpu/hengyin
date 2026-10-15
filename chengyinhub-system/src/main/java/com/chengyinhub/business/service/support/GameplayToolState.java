package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.node.ObjectNode;

public final class GameplayToolState {
    public enum Reason {
        LIMIT_REACHED, INSUFFICIENT_RESOURCES, NOT_ARRIVED, PROOF_PENDING, NOT_CAPTAIN,
        SESSION_ENDED, CHALLENGE_LIMIT, TEAM_COMPLETED, RULE_BLOCKED, TASK_ACTIVE,
        SESSION_PAUSED, WORLD_LOCKED, COUNTERPART_COMPLETED
    }

    private GameplayToolState() { }

    public static ObjectNode usage(ObjectNode view, int limit, int count, String scope) {
        return view.put("limit", limit).put("count", count).put("remainingCount", Math.max(0, limit-count)).put("countScope", scope);
    }

    public static ObjectNode availability(ObjectNode view, Reason reason, boolean trustedPass) {
        view.put("enabled", reason == null).put("trustedPass", trustedPass);
        if (reason == null) view.putNull("disabledReason");
        else view.put("disabledReason", reason.name());
        return view;
    }
}
