package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.IntSupplier;

/** C03 隔离夹具:作者选配 → 冻结 → 玩家显式选择。不连库。 */
final class MechanismFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String REQUEST = "check:1:alarm:tick";
    static final String RULES = "{\"schemaVersion\":1,\"checkEnabled\":true,\"rpg\":{"
            + "\"clocks\":[{\"var\":\"counter.alarm\",\"label\":\"警报\",\"segments\":4,\"kind\":\"danger\"}],"
            + "\"bargain\":{\"enabled\":true},\"momentum\":{\"enabled\":true},"
            + "\"aspects\":[{\"key\":\"night_owl\",\"label\":\"夜猫子\",\"kind\":\"facet\"},"
            + "{\"key\":\"noticed\",\"label\":\"被盯上\",\"kind\":\"aspect\"}]}}";
    static final String CHECK = "{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"medium\","
            + "\"bargain\":{\"label\":\"惊动了巡夜人\",\"bonus\":2,\"effects\":["
            + "{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":1,\"max\":4}]}}";
    static final String ORACLE = "{\"enabled\":true,\"oracles\":[{\"id\":\"street_lamp\",\"label\":\"路灯\","
            + "\"revelation\":\"巡夜人换班了\",\"effects\":[{\"op\":\"ADD_CONDITION\",\"value\":\"noticed\"}]}],"
            + "\"bounties\":[{\"id\":\"night_watch\",\"label\":\"巡夜赏\",\"var\":\"counter.hunt\",\"ticks\":1,\"segments\":4}]}";
    static final String OATH = "{\"enabled\":true,\"oaths\":[{\"key\":\"keep_silent\",\"label\":\"守口\"}],"
            + "\"flashbacks\":[{\"id\":\"alley\",\"label\":\"巷口\",\"restoreKeys\":[\"clue.page\"],"
            + "\"password\":\"author-set-cipher\"}]}";
    static final String ROUTE = "{\"enabled\":true,\"communityDay\":{\"enabled\":true,\"weekpart\":\"weekend\"},"
            + "\"segments\":[{\"id\":\"market\",\"label\":\"市集\",\"effects\":[{\"op\":\"SET\",\"var\":\"clue.page\",\"value\":1}]},"
            + "{\"id\":\"square\",\"label\":\"广场日\",\"communityDay\":true,\"effects\":["
            + "{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":1,\"max\":4}]}]}";
    static final String CLUE = "{\"enabled\":true,\"clues\":[{\"var\":\"clue.page\",\"label\":\"账页\"}],"
            + "\"secretGoals\":[{\"key\":\"find_ledger\",\"label\":\"找到账本\","
            + "\"when\":{\"var\":\"clue.page\",\"op\":\"EQ\",\"value\":1}}]}";

    private MechanismFixtures() {}

    static JourneyRules rules() {
        return JourneyRules.parse(RULES);
    }

    static JsonNode check() throws Exception {
        return JSON.readTree(CHECK);
    }

    static TopicRouteEngine.State state() {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JourneyRpg.seedRun(rules(), state);
        return state;
    }

    static MechanismRequestIdBinder.Play play(MechanismRequestIdBinder binder) throws Exception {
        JourneyRules journey = rules();
        TopicRouteEngine.State live = state();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        TradeBargainConsumer trade = new TradeBargainConsumer(binder);
        OracleBountyConsumer oracle = new OracleBountyConsumer(binder);
        OathFlashbackConsumer oath = new OathFlashbackConsumer(binder);
        RouteCommunityDayConsumer route = new RouteCommunityDayConsumer(binder);
        ClueSecretGoalConsumer clue = new ClueSecretGoalConsumer(binder);
        ObjectNode freeze = MechanismRequestIdBinder.newFreeze();
        freeze.set(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, clocks.select(RULES));
        freeze.set(MechanismRequestIdBinder.MECH_TRADE_BARGAIN, trade.select(journey, check()));
        freeze.set(MechanismRequestIdBinder.MECH_ORACLE_BOUNTY, oracle.select(JSON.readTree(ORACLE)));
        freeze.set(MechanismRequestIdBinder.MECH_OATH_FLASHBACK, oath.freezeWithSnapshot(oath.select(JSON.readTree(OATH)), live));
        freeze.set(MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY, route.select(JSON.readTree(ROUTE)));
        freeze.set(MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL, clue.select(JSON.readTree(CLUE)));
        MechanismRequestIdBinder.Play play = new MechanismRequestIdBinder.Play();
        play.setRules(journey);
        play.setState(live);
        play.setCheck(check());
        play.setFrozen(freeze);
        play.setOriginalRequestId(REQUEST);
        play.setDice(dice(11));
        return play;
    }

    static ObjectNode choice(String mechanism, String action) {
        return JSON.createObjectNode().put("mechanism", mechanism).put("action", action);
    }

    static IntSupplier dice(Integer... values) {
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>(Arrays.asList(values));
        return queue::removeFirst;
    }
}
