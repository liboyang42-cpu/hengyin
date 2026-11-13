package com.chengyinhub.business.service.channel.interaction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChannelReadWatermarkTest {
    @Test void staleCursorNeverMovesBackwards() { assertEquals(80, ChannelReadWatermark.merge(80, 40)); }
    @Test void repeatedCursorIsIdempotent() { assertEquals(80, ChannelReadWatermark.merge(80, 80)); }
    @Test void newerVisibleCursorAdvances() { assertEquals(81, ChannelReadWatermark.merge(80, 81)); }
    @Test void zeroDoesNotEraseExistingState() { assertEquals(80, ChannelReadWatermark.merge(80, 0)); }
    @Test void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> ChannelReadWatermark.merge(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> ChannelReadWatermark.merge(1, -1));
    }
    @Test void longIdsAreNotNarrowedOrIncremented() {
        assertEquals(Long.MAX_VALUE, ChannelReadWatermark.merge(Long.MAX_VALUE, 0));
        assertEquals(Long.MAX_VALUE, ChannelReadWatermark.merge(0, Long.MAX_VALUE));
    }
}
