package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RendezvousSafetyAdapterTest {
    @Test void usesExistingBidirectionalBlockAuthorityWithoutWritingBlocks() {
        BlockMapper blocks = new BlockMapper();
        RendezvousSafetyAdapter adapter = new RendezvousSafetyAdapter(blocks, new Policy());
        blocks.result = 1;
        assertTrue(adapter.blocked(7, 8)); assertEquals(Long.valueOf(7), blocks.a); assertEquals(Long.valueOf(8), blocks.b);
        blocks.result = 0; assertFalse(adapter.blocked(8, 7));
    }
    @Test void policyIsRequiredAndNeverDefaultsToEligibilityOrPublicPlace() {
        assertThrows(NullPointerException.class, () -> new RendezvousSafetyAdapter(new BlockMapper(), null));
        RendezvousSafetyAdapter adapter = new RendezvousSafetyAdapter(new BlockMapper(), new Policy());
        assertFalse(adapter.eligible(7, "pool", "unreviewed", 0));
        assertFalse(adapter.publicMeetingAllowed("pool", "private", 0));
    }
    @Test void blockAuthorityFailurePropagatesInsteadOfAllowingContact() {
        BlockMapper blocks = new BlockMapper(); blocks.fail = true;
        RendezvousSafetyAdapter adapter = new RendezvousSafetyAdapter(blocks, new Policy());
        assertThrows(IllegalStateException.class, () -> adapter.blocked(7, 8));
    }
    static final class Policy implements RendezvousSafetyAdapter.ReviewedPolicy {
        public boolean eligible(long actor, String pool, String version, long now) { return false; }
        public boolean compatible(long a, long b, String pool, long now) { return false; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return false; }
    }
    static final class BlockMapper implements ImBlockMapper {
        Long a, b; int result; boolean fail;
        public int existsBetween(Long a, Long b) { this.a = a; this.b = b; if (fail) throw new IllegalStateException("unavailable"); return result; }
        public int insertImBlock(ImBlock block) { throw new AssertionError("No block mutation"); }
        public int deleteImBlock(Long memberId, Long blockedMemberId) { throw new AssertionError("No block mutation"); }
        public ImBlock selectOne(Long memberId, Long blockedMemberId) { throw new AssertionError("Must use bidirectional authority"); }
    }
}
