package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.CoopSettlementPlan;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 第十二轮 k5:按核销付费的单主题级没有可证履约时,主题有在世俱乐部场次 ⇒ 交给场次结算(SETTLED_PER_SESSION),
 * 不落 claim、不按零元终态消耗;没有俱乐部场次照旧 NO_PROVEN_FULFILLMENT。
 */
class ClubSessionPrepareRouteTest {
    @Test
    void emptyThemeEvidenceOnAClubSessionThemeIsSettledPerSession() {
        Wp04CoopSettlementContractTest.Fixture f = new Wp04CoopSettlementContractTest.Fixture();
        when(f.evidence.lockCandidates(any(), any())).thenReturn(List.of());
        when(f.evidence.liveClubSessionIds(11L)).thenReturn(Set.of(201L));

        CoopSettlementPlan plan = f.service.prepare(f.order, new Date(11_000));

        assertEquals(ICoopSettlementClaimService.ROUTE_SETTLED_PER_SESSION, plan.getRoute());
        assertTrue(plan.getOriginLines().isEmpty());
        verifyNoInteractions(f.claims, f.heads, f.details);
        IllegalStateException persist = assertThrows(IllegalStateException.class, () -> f.service.persist(plan, null));
        assertTrue(persist.getMessage().contains("按场次结算"));
    }

    @Test
    void withoutClubSessionsEmptyEvidenceStillRefuses() {
        Wp04CoopSettlementContractTest.Fixture f = new Wp04CoopSettlementContractTest.Fixture();
        when(f.evidence.lockCandidates(any(), any())).thenReturn(List.of());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.service.prepare(f.order, new Date(11_000)));
        assertTrue(failure.getMessage().contains("NO_PROVEN_FULFILLMENT"));
    }
}
