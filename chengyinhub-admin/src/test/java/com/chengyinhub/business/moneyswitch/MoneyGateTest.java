package com.chengyinhub.business.moneyswitch;

import com.chengyinhub.business.begapmisc.MiscMoneySwitch;
import com.chengyinhub.business.moneyswitch.mapper.MoneySwitchMapper;
import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** W7 MERCHANT-3: every server money path is the legacy property AND its Q5 area; the gate fails closed. */
class MoneyGateTest {

    @Test
    void gateReadsTheEffectiveAreaAndFailsClosed() {
        MoneyGate gate = new MoneyGate(MoneyGateTestSupport.moneySwitch(true, MoneyArea.TICKETS));
        assertTrue(gate.isOpen(MoneyArea.TICKETS));
        assertFalse(gate.isOpen(MoneyArea.PAYOUT));
        assertFalse(gate.isOpen(null));
        assertDoesNotThrow(() -> gate.requireOpen(MoneyArea.TICKETS));
        BusinessRefusal refusal = assertThrows(BusinessRefusal.class, () -> gate.requireOpen(MoneyArea.PAYOUT));
        assertEquals(ErrorCode.FEATURE_OFF, refusal.getErrorCode());

        // Ceiling off: a stored row that is on is still closed.
        assertFalse(new MoneyGate(MoneyGateTestSupport.moneySwitch(false, MoneyArea.TICKETS)).isOpen(MoneyArea.TICKETS));
        // No service, or a read error: closed.
        assertFalse(new MoneyGate(null).isOpen(MoneyArea.TICKETS));
        MoneySwitchMapper broken = mock(MoneySwitchMapper.class);
        when(broken.selectAll()).thenThrow(new IllegalStateException("db down"));
        assertFalse(new MoneyGate(new MoneySwitchService(broken, true)).isOpen(MoneyArea.TICKETS));
    }

    @Test
    void miscPaymentsNeedTheTicketsAreaEvenWithTheLegacyPropertyOn() {
        MiscMoneySwitch closedArea = new MiscMoneySwitch(true, true, MoneyGateTestSupport.closedGate());
        assertFalse(closedArea.paymentsOpen(), "area closed → closed even with chengyin.release.money-payments=true");
        assertFalse(closedArea.payoutOpen());

        MiscMoneySwitch ticketsOnly = new MiscMoneySwitch(true, true,
                new MoneyGate(MoneyGateTestSupport.moneySwitch(true, MoneyArea.TICKETS)));
        assertTrue(ticketsOnly.paymentsOpen());
        assertFalse(ticketsOnly.payoutOpen(), "payout needs its own area");

        MiscMoneySwitch legacyOff = new MiscMoneySwitch(false, false, MoneyGateTestSupport.openGate());
        assertFalse(legacyOff.paymentsOpen(), "AND: the legacy property still has to be on");
        assertFalse(legacyOff.payoutOpen());

        assertFalse(new MiscMoneySwitch(true, true).paymentsOpen(), "no gate → closed");
        assertTrue(new MiscMoneySwitch(true, true, MoneyGateTestSupport.openGate()).payoutOpen());
    }

    @Test
    void cashPayoutNeedsThePayoutArea() {
        assertFalse(new CashPayoutSwitch(true, true, MoneyGateTestSupport.closedGate()).open());
        assertFalse(new CashPayoutSwitch(true, true,
                new MoneyGate(MoneyGateTestSupport.moneySwitch(true, MoneyArea.TICKETS))).open());
        assertFalse(new CashPayoutSwitch(true, true).open(), "no gate → closed");
        assertFalse(CashPayoutSwitch.off().open());
        assertFalse(new CashPayoutSwitch(true, false, MoneyGateTestSupport.openGate()).open());
        assertTrue(new CashPayoutSwitch(true, true,
                new MoneyGate(MoneyGateTestSupport.moneySwitch(true, MoneyArea.PAYOUT))).open());
    }

    @Test
    void closingTheAreaClosesTheServerPathWithoutARestart() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        java.util.Map<String, Object> on = new java.util.HashMap<>();
        on.put("area", "tickets");
        on.put("enabled", 1);
        java.util.Map<String, Object> off = new java.util.HashMap<>(on);
        off.put("enabled", 0);
        when(mapper.selectAll()).thenReturn(java.util.List.of(on), java.util.List.of(off));
        MiscMoneySwitch money = new MiscMoneySwitch(true, true, new MoneyGate(new MoneySwitchService(mapper, true)));
        assertTrue(money.paymentsOpen());
        assertFalse(money.paymentsOpen(), "the next request reads the switched-off row");
    }
}
