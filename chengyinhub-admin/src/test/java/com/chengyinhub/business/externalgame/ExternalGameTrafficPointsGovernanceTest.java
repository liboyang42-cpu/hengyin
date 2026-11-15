package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.pointsgov.PointsGovernanceService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** G3: creator traffic points (event_type 99) are credited only when points governance admits them. */
class ExternalGameTrafficPointsGovernanceTest {
    private final UmsMemberMapper mapper = mock(UmsMemberMapper.class);
    private final IUmsMemberService members = mock(IUmsMemberService.class);
    private final IUmsMemberPointsDetailService details = mock(IUmsMemberPointsDetailService.class);
    private final PointsGovernanceService governance = mock(PointsGovernanceService.class);
    private final SpringExternalGameWalletBridge bridge = new SpringExternalGameWalletBridge(
            mapper, members, details, mock(IUmsMemberBalanceDetailService.class));

    @Test
    void refusedByGovernanceWritesNothing() {
        ReflectionTestUtils.setField(bridge, "governance", governance);
        when(mapper.selectPointsForUpdate(7L)).thenReturn(10L);
        when(governance.admit(7L, 99L, 600)).thenReturn(new PointsGovernanceService.Admission(false, "DAILY_CAP", "PLAY"));
        ExternalGameWalletBridge.PointsCredit credit = bridge.creditPointsGoverned(7L, 600, "k", "r");
        assertEquals(0, credit.credited());
        assertEquals("DAILY_CAP", credit.outcome());
        verify(members, never()).addPoints(anyLong(), anyInt());
        verify(details, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void admittedCreditsOnce() {
        ReflectionTestUtils.setField(bridge, "governance", governance);
        when(mapper.selectPointsForUpdate(7L)).thenReturn(10L);
        when(governance.admit(7L, 99L, 50)).thenReturn(new PointsGovernanceService.Admission(true, "ADMITTED", "PLAY"));
        when(members.addPoints(7L, 50)).thenReturn(true);
        when(details.insertUmsMemberPointsDetail(any())).thenReturn(1);
        assertEquals(50, bridge.creditPointsGoverned(7L, 50, "k", "r").credited());
        verify(members, times(1)).addPoints(7L, 50);
    }

    @Test
    void withoutGovernanceBeanBehavesAsBefore() {
        when(mapper.selectPointsForUpdate(7L)).thenReturn(0L);
        when(members.addPoints(7L, 5)).thenReturn(true);
        when(details.insertUmsMemberPointsDetail(any())).thenReturn(1);
        ExternalGameWalletBridge.PointsCredit credit = bridge.creditPointsGoverned(7L, 5, "k", "r");
        assertEquals(5, credit.credited());
        assertEquals("UNGOVERNED", credit.outcome());
    }
}
