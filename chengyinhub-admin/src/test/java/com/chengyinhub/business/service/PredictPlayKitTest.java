package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayPredictBet;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.PredictSettlement;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 竞猜(advanced.predict):玩家押一个,次日商家给出答案再发奖。
 *
 * <p>它与另外八个玩法的结构差异:答案在提交时还不存在。所以下注即算通关
 * (不然玩家会卡在一个当天无法判定的节点上),奖励延迟发。
 */
class PredictPlayKitTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    private String config() {
        return "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,"
                + "\"question\":\"猜猜今天哪款卖得最多?\",\"hint\":\"明天此时揭晓\","
                + "\"closeAtHour\":21,\"xp\":30,\"options\":["
                + "{\"key\":\"a\",\"label\":\"冰美式\"},"
                + "{\"key\":\"b\",\"label\":\"燕麦拿铁\"},"
                + "{\"key\":\"c\",\"label\":\"单一产地手冲\"}]}}";
    }

    @Test
    void acceptsAWellFormedRound() {
        String normalized = validator.validateAndNormalize(config());
        assertTrue(normalized.contains("closeAtHour"));
    }

    /**
     * 揭晓时间(revealDays / revealHour)是 2026-09-16 才加的,**存量模板里没有**。
     * 所以「缺字段」必须合法(按默认值走);做成必填的话,所有老模板一提交就报错 ——
     * 这条断言就是照着那次实炸写的:上面那份 config() 正是不带这两个字段的老形状。
     */
    @Test
    void oldRoundsWithoutRevealFieldsStillPass() {
        String normalized = validator.validateAndNormalize(config());
        assertTrue(normalized.contains("predict"));
    }

    /** 写了值就要校验:当天揭晓不能早于收注截止 —— 先公布答案再收注,那就不是猜了 */
    @Test
    void rejectsSameDayRevealEarlierThanClose() {
        String bad = config().replace("\"closeAtHour\":21",
                "\"closeAtHour\":21,\"revealDays\":0,\"revealHour\":9");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(bad));
    }

    /**
     * 竞猜没有需要藏的答案 —— 提交时它还不存在。所以选项整个下发是对的,
     * 这与其他玩法「答案必须剥」的口径不冲突。
     */
    @Test
    void optionsAreFullyPublicBecauseTheAnswerDoesNotExistYet() {
        String publicJson = AdvancedGamePublicProjection.safeConfigJson(config());

        // 投影里没有 copyPredict 就整段不下发,那样玩家看不到题
        assertTrue(publicJson.contains("predict"), "至少要有这一段:" + publicJson);
    }

    @Test
    void rejectsTooFewOrTooManyOptions() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"q\","
                        + "\"closeAtHour\":21,\"options\":[{\"key\":\"a\",\"label\":\"x\"}]}}"));

        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"q\","
                        + "\"closeAtHour\":21,\"options\":["
                        + "{\"key\":\"a\",\"label\":\"1\"},{\"key\":\"b\",\"label\":\"2\"},"
                        + "{\"key\":\"c\",\"label\":\"3\"},{\"key\":\"d\",\"label\":\"4\"},"
                        + "{\"key\":\"e\",\"label\":\"5\"}]}}"));
    }

    @Test
    void rejectsCloseHourOutsideTheDay() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"q\","
                        + "\"closeAtHour\":25,\"options\":["
                        + "{\"key\":\"a\",\"label\":\"1\"},{\"key\":\"b\",\"label\":\"2\"}]}}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"q\","
                        + "\"options\":[{\"key\":\"a\",\"label\":\"1\"},{\"key\":\"b\",\"label\":\"2\"}]}}"));
    }

    @Test
    void countsAsAnEnabledMechanic() {
        assertTrue(validator.hasEnabledMechanic(config()));
    }

    // ===== 结算 =====

    private PlayPredictBet bet(long memberId, String optionKey, int rewardStatus) {
        PlayPredictBet b = new PlayPredictBet();
        b.setMemberId(memberId);
        b.setOptionKey(optionKey);
        b.setRewardStatus(rewardStatus);
        return b;
    }

    @Test
    void settleSplitsWinnersFromLosers() {
        List<PlayPredictBet> bets = Arrays.asList(
                bet(1L, "a", PlayPredictBet.REWARD_PENDING),
                bet(2L, "b", PlayPredictBet.REWARD_PENDING),
                bet(3L, "a", PlayPredictBet.REWARD_PENDING));

        List<PredictSettlement.Outcome> outcomes = PredictSettlement.settle(bets, "a");

        assertEquals(3, outcomes.size());
        assertEquals(2, outcomes.stream().filter(PredictSettlement.Outcome::isWinner).count());
        assertEquals(PlayPredictBet.REWARD_LOST, outcomes.get(1).getRewardStatus());
    }

    /** 重复结算不该把状态推回去,更不该再发一次奖。 */
    @Test
    void alreadySettledBetsAreLeftAlone() {
        List<PlayPredictBet> bets = Arrays.asList(
                bet(1L, "a", PlayPredictBet.REWARD_WON),
                bet(2L, "a", PlayPredictBet.REWARD_PENDING));

        List<PredictSettlement.Outcome> outcomes = PredictSettlement.settle(bets, "a");

        assertEquals(1, outcomes.size(), "已结过的那一注不该再出现在结算清单里");
        assertEquals(2L, outcomes.get(0).getBet().getMemberId());
    }

    /**
     * 商家超时不结算:整轮作废,不发奖。
     *
     * <p>奖是商家配的券,平台不能替商家发。玩家的核心收益(通关、探索值)
     * 在下注那一刻已经拿到,竞猜的奖是额外的。
     */
    @Test
    void voidRoundGrantsNothingToAnyone() {
        List<PlayPredictBet> bets = Arrays.asList(
                bet(1L, "a", PlayPredictBet.REWARD_PENDING),
                bet(2L, "b", PlayPredictBet.REWARD_PENDING));

        List<PredictSettlement.Outcome> outcomes = PredictSettlement.voidRound(bets);

        assertEquals(2, outcomes.size());
        assertTrue(outcomes.stream().noneMatch(PredictSettlement.Outcome::isWinner),
                "作废的轮次一个赢家都不该有");
        outcomes.forEach(o -> assertEquals(PlayPredictBet.REWARD_VOID, o.getRewardStatus()));
    }

    @Test
    void blankAnswerIsTreatedAsVoidNotAsAnOptionKey() {
        List<PlayPredictBet> bets = Collections.singletonList(bet(1L, "a", PlayPredictBet.REWARD_PENDING));

        assertEquals(PlayPredictBet.REWARD_VOID,
                PredictSettlement.settle(bets, "   ").get(0).getRewardStatus());
        assertEquals(PlayPredictBet.REWARD_VOID,
                PredictSettlement.settle(bets, null).get(0).getRewardStatus());
    }

    @Test
    void emptyRoundSettlesCleanly() {
        assertTrue(PredictSettlement.settle(new ArrayList<>(), "a").isEmpty());
        assertTrue(PredictSettlement.settle(null, "a").isEmpty());
    }
}
