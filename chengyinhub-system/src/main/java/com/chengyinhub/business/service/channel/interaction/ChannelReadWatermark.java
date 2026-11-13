package com.chengyinhub.business.service.channel.interaction;

/**
 * Calculation for the sole im_read_state.last_read_msg_id source. This is not a store.
 * The caller must verify membership and that requestedVisibleMessageId is visible in this
 * exact conversation, then atomically persist GREATEST(current, requested) in im_read_state.
 * Do not persist this result into a second member-override unread source. Mentions are
 * counted separately; neither pins nor client-side arrival order advance this watermark.
 */
public final class ChannelReadWatermark {
    private ChannelReadWatermark() { }

    public static long merge(long current, long requestedVisibleMessageId) {
        if (current < 0 || requestedVisibleMessageId < 0)
            throw new IllegalArgumentException("Negative read watermark");
        return Math.max(current, requestedVisibleMessageId);
    }
}
