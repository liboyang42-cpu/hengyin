package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.service.support.GameplayCheckEngine;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.choice;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.dice;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeBargainConsumerTest {
    @Test
    void explicitAcceptAppliesCostAndReusesGameplayCheckEngineBargainBonus() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        TradeBargainConsumer trade = new TradeBargainConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        session.setDice(dice(11));
        ObjectNode accept = choice(MechanismRequestIdBinder.MECH_TRADE_BARGAIN, TradeBargainConsumer.ACTION_ACCEPT);
        session.setPlayerChoice(accept);
        session.setOriginalRequestId("bargain-1");
        Map<String, Object> dealt = trade.apply(session);
        assertEquals(Boolean.TRUE, dealt.get("accepted"));
        assertEquals(2, ((Number) dealt.get("bonus")).intValue());
        assertEquals(13, ((Number) dealt.get("total")).intValue(), "11 + 交易 2,不受第二套骰子");
        assertEquals(Boolean.TRUE, dealt.get("success"));
        assertEquals(1, session.getState().getVariables().get("counter.alarm").intValue(), "代价 TICK_CLOCK 真写入");
        Map<?, ?> breakdown = (Map<?, ?>) dealt.get("breakdown");
        assertEquals(2, ((Number) breakdown.get("bargain")).intValue());
        assertTrue(JourneyRpg.bargainEnabled(session.getRules()));
    }

    @Test
    void declineDoesNotRollOrWriteCosts() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        TradeBargainConsumer trade = new TradeBargainConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        session.setPlayerChoice(choice(MechanismRequestIdBinder.MECH_TRADE_BARGAIN, TradeBargainConsumer.ACTION_DECLINE));
        session.setOriginalRequestId("bargain-2");
        Map<String, Object> declined = trade.apply(session);
        assertEquals(Boolean.FALSE, declined.get("accepted"));
        assertFalse(session.getState().getVariables().containsKey("counter.alarm"));
    }

    @Test
    void simulateLeavesOriginalStateAndDicePathUnchanged() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        TradeBargainConsumer trade = new TradeBargainConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        session.setDice(dice(11, 11));
        session.setPlayerChoice(choice(MechanismRequestIdBinder.MECH_TRADE_BARGAIN, TradeBargainConsumer.ACTION_ACCEPT));
        Map<String, Object> simulated = trade.simulate(session);
        assertEquals(Boolean.TRUE, simulated.get("simulated"));
        assertFalse(session.getState().getVariables().containsKey("counter.alarm"));
        assertEquals(13, GameplayCheckEngine.roll(session.getCheck(), session.getRules(), session.getState(),
                dice(11), false, 2).getTotal().intValue());
    }

    @Test
    void nonAcceptActionIsRejected() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        TradeBargainConsumer trade = new TradeBargainConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        session.setPlayerChoice(choice(MechanismRequestIdBinder.MECH_TRADE_BARGAIN, "AUTO"));
        assertThrows(IllegalArgumentException.class, () -> trade.apply(session));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueAcceptApplies() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        TradeBargainConsumer trade = new TradeBargainConsumer(binder);
        MechanismRequestIdBinder.Play unseen = play(binder);
        unseen.setOriginalRequestId("c03-bargain-never");
        unseen.setOriginalAction(null);
        Map<String, Object> recovered = trade.recoverOriginal(unseen);
        assertEquals("c03-bargain-never", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-bargain-never"));
        unseen.setPlayerChoice(choice(MechanismRequestIdBinder.MECH_TRADE_BARGAIN, TradeBargainConsumer.ACTION_ACCEPT));
        unseen.setDice(dice(11));
        Map<String, Object> first = trade.apply(unseen);
        assertEquals("c03-bargain-never", first.get("requestId"));
        assertEquals(Boolean.TRUE, first.get("accepted"));
        assertEquals(Boolean.FALSE, first.get("replay"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-bargain-never").getStatus());
        Map<String, Object> replay = trade.apply(unseen);
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c03-bargain-never", replay.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-bargain-never").getStatus());
    }
}
