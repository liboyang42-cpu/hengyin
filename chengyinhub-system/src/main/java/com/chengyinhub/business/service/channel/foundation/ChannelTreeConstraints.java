package com.chengyinhub.business.service.channel.foundation;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Validates a complete, authoritative ancestor snapshot; never fetches or invents parents. */
public final class ChannelTreeConstraints {
    private ChannelTreeConstraints() { }

    public static void requireAcyclicParentChain(ChannelDefinition channel,
            Map<Long, ChannelDefinition> ancestors) {
        if (channel == null || ancestors == null) throw new IllegalArgumentException("Snapshot is required");
        Set<Long> seen = new HashSet<>();
        seen.add(channel.id());
        ChannelDefinition current = channel;
        int depth = 0;
        while (current.parentId() != 0) {
            if (++depth > 32 || !seen.add(current.parentId())) {
                throw new IllegalArgumentException("Channel parent cycle or depth limit");
            }
            ChannelDefinition parent = ancestors.get(current.parentId());
            current.requireParent(parent);
            current = parent;
        }
    }
}
