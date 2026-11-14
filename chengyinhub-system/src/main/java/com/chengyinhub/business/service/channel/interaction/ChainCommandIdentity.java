package com.chengyinhub.business.service.channel.interaction;

import java.util.Objects;

/**
 * Compare an immutable persisted command with a retry BEFORE calculating a transition.
 * The database must reserve (chain_id, request_id) atomically and bind it to authenticated
 * actor and exact payload. In particular, replay of an old JOIN after WITHDRAW must return
 * that JOIN's original receipt, not reactivate the current relation.
 */
public record ChainCommandIdentity(long chainId, long conversationId, long memberId,
        String requestId, ChainParticipationTransition.Action action, String content) {
    public ChainCommandIdentity {
        if (chainId <= 0 || conversationId <= 0 || memberId <= 0)
            throw new IllegalArgumentException("Invalid command scope");
        if (requestId == null || requestId.isBlank())
            throw new IllegalArgumentException("Missing original request identity");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(content, "content");
    }

    /** Null means no receipt observed, NOT proof the previous transaction did not commit. */
    public boolean matchesOriginal(ChainCommandIdentity persisted) {
        if (persisted == null) return false;
        if (chainId != persisted.chainId || !requestId.equals(persisted.requestId))
            throw new IllegalArgumentException("Wrong command receipt lookup");
        if (!equals(persisted)) throw new IllegalStateException("Request identity conflict");
        return true;
    }
}
