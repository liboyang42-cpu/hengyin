package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class ExploreTicketUsePolicyTest {
    @Test void beforeEndAllowsNewUse() { assertNull(ExploreTicketUsePolicy.rejection(3, new Date(100), new Date(99))); }
    @Test void exactEndRejectsNewUse() { assertNotNull(ExploreTicketUsePolicy.rejection(3, new Date(100), new Date(100))); }
    @Test void afterEndRejectsNewUse() { assertNotNull(ExploreTicketUsePolicy.rejection(3, new Date(100), new Date(101))); }
    @Test void missingEndTemporarilyRejectsAndRepairRestores() {
        assertNotNull(ExploreTicketUsePolicy.rejection(3, null, new Date(99)));
        assertNull(ExploreTicketUsePolicy.rejection(3, new Date(100), new Date(99)));
    }
    @Test void otherTicketsKeepDateBehavior() {
        for (Integer kind : new Integer[]{null, 1, 2}) {
            assertNull(ExploreTicketUsePolicy.rejection(kind, null, new Date(100)));
            assertNull(ExploreTicketUsePolicy.rejection(kind, new Date(1), new Date(100)));
        }
    }
}
