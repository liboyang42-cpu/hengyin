package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CoopInvite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CoopAcceptanceStatus} 承接状态派生单测(从 coop_invite 派生,无 schema 变更)。
 */
class CoopAcceptanceStatusTest {

    private CoopInvite inv(int status) {
        CoopInvite c = new CoopInvite();
        c.setStatus(status);
        return c;
    }

    @Test
    void empty_selfRun() {
        assertEquals(CoopAcceptanceStatus.SELF_RUN, CoopAcceptanceStatus.derive(null));
        assertEquals(CoopAcceptanceStatus.SELF_RUN, CoopAcceptanceStatus.derive(new ArrayList<>()));
    }

    @Test
    void hasAccepted_accepted() {
        assertEquals(CoopAcceptanceStatus.ACCEPTED, CoopAcceptanceStatus.derive(Arrays.asList(inv(1))));
    }

    @Test
    void onlyPending_pending() {
        assertEquals(CoopAcceptanceStatus.PENDING, CoopAcceptanceStatus.derive(Arrays.asList(inv(0))));
    }

    @Test
    void onlyRejected_selfRun() {
        assertEquals(CoopAcceptanceStatus.SELF_RUN, CoopAcceptanceStatus.derive(Arrays.asList(inv(2), inv(3))));
    }

    @Test
    void acceptedWinsOverPending() {
        assertEquals(CoopAcceptanceStatus.ACCEPTED, CoopAcceptanceStatus.derive(Arrays.asList(inv(0), inv(1))));
    }

    @Test
    void pendingAmongRejected_pending() {
        List<CoopInvite> list = Arrays.asList(inv(2), inv(0), inv(3));
        assertEquals(CoopAcceptanceStatus.PENDING, CoopAcceptanceStatus.derive(list));
    }
}
