package com.chengyinhub.business.service.support.rpg.mechanism;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.JSON;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.ORACLE;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.choice;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleBountyConsumerTest {
    @Test
    void consultLooksUpAuthorTableInsteadOfRollingASecondDie() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        OracleBountyConsumer oracle = new OracleBountyConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode consult = choice(MechanismRequestIdBinder.MECH_ORACLE_BOUNTY, OracleBountyConsumer.ACTION_CONSULT);
        consult.put("oracleId", "street_lamp");
        session.setPlayerChoice(consult);
        session.setOriginalRequestId("oracle-1");
        Map<String, Object> seen = oracle.apply(session);
        assertEquals("巡夜人换班了", seen.get("revelation"));
        assertTrue(session.getState().getStatusConditions().contains("noticed"));
    }

    @Test
    void claimTicksAuthorClockNotMerchantReferralMoney() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        OracleBountyConsumer oracle = new OracleBountyConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode claim = choice(MechanismRequestIdBinder.MECH_ORACLE_BOUNTY, OracleBountyConsumer.ACTION_CLAIM);
        claim.put("bountyId", "night_watch");
        session.setPlayerChoice(claim);
        session.setOriginalRequestId("bounty-1");
        Map<String, Object> claimed = oracle.apply(session);
        assertEquals(1, ((Number) claimed.get("value")).intValue());
        assertEquals("counter.hunt", claimed.get("var"));
        assertEquals(Boolean.FALSE, claimed.get("claimed"));
        assertEquals(1, session.getState().getVariables().get("counter.hunt").intValue());
    }

    @Test
    void economyFieldsAreRejectedAsUnconfirmed() throws Exception {
        OracleBountyConsumer oracle = new OracleBountyConsumer(new MechanismRequestIdBinder());
        assertThrows(IllegalArgumentException.class, () -> oracle.select(JSON.readTree(
                "{\"bounties\":[{\"id\":\"x\",\"label\":\"x\",\"var\":\"counter.hunt\",\"ticks\":1,\"segments\":4,\"amount\":50}]}")));
        oracle.select(JSON.readTree(ORACLE));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueConsultApplies() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        OracleBountyConsumer oracle = new OracleBountyConsumer(binder);
        MechanismRequestIdBinder.Play unseen = play(binder);
        unseen.setOriginalRequestId("c03-oracle-never");
        unseen.setOriginalAction(null);
        Map<String, Object> recovered = oracle.recoverOriginal(unseen);
        assertEquals("c03-oracle-never", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-oracle-never"));
        ObjectNode consult = choice(MechanismRequestIdBinder.MECH_ORACLE_BOUNTY, OracleBountyConsumer.ACTION_CONSULT);
        consult.put("oracleId", "street_lamp");
        unseen.setPlayerChoice(consult);
        Map<String, Object> first = oracle.apply(unseen);
        assertEquals("c03-oracle-never", first.get("requestId"));
        assertEquals("巡夜人换班了", first.get("revelation"));
        assertEquals(Boolean.FALSE, first.get("replay"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-oracle-never").getStatus());
        Map<String, Object> replay = oracle.apply(unseen);
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c03-oracle-never", replay.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-oracle-never").getStatus());
    }
}
