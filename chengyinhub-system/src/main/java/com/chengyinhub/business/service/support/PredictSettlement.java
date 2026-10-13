package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayPredictBet;

import java.util.ArrayList;
import java.util.List;

/**
 * 竞猜结算的纯逻辑:给定一轮的所有下注与商家给出的答案,算出每一注的归宿。
 *
 * <p>抽成纯函数是为了能直接测。发奖、写库、事务都在调用方,
 * 这里不碰任何 IO —— 结算是会动钱的动作,它的判断部分必须能被一个不连库的测试盯住。
 */
public final class PredictSettlement {

    private PredictSettlement() {
    }

    /** 一注的归宿。 */
    public static final class Outcome {
        private final PlayPredictBet bet;
        private final int rewardStatus;

        Outcome(PlayPredictBet bet, int rewardStatus) {
            this.bet = bet;
            this.rewardStatus = rewardStatus;
        }

        public PlayPredictBet getBet() { return bet; }
        public int getRewardStatus() { return rewardStatus; }
        public boolean isWinner() { return rewardStatus == PlayPredictBet.REWARD_WON; }
    }

    /**
     * 商家给出答案时的结算。
     *
     * @param settledOption 商家选的正确项;空表示作废
     */
    public static List<Outcome> settle(List<PlayPredictBet> bets, String settledOption) {
        List<Outcome> outcomes = new ArrayList<>();
        if (bets == null) return outcomes;
        boolean voided = settledOption == null || settledOption.trim().isEmpty();
        for (PlayPredictBet bet : bets) {
            if (bet == null) continue;
            // 已经结过的不再动:重复结算不该把状态推回去,也不该再发一次奖
            if (bet.getRewardStatus() != null && bet.getRewardStatus() != PlayPredictBet.REWARD_PENDING) {
                continue;
            }
            int status;
            if (voided) {
                status = PlayPredictBet.REWARD_VOID;
            } else {
                status = settledOption.equals(bet.getOptionKey())
                        ? PlayPredictBet.REWARD_WON
                        : PlayPredictBet.REWARD_LOST;
            }
            outcomes.add(new Outcome(bet, status));
        }
        return outcomes;
    }

    /**
     * 商家超时未结算时的兜底:整轮作废,不发奖。
     *
     * <p>为什么不"全员按中奖处理":奖是商家配的券,平台不能替商家发。
     * 玩家的核心收益(节点通关、探索值)在下注那一刻就已经拿到了 ——
     * 竞猜的奖是额外的,拿不到不影响他继续走路线。
     */
    public static List<Outcome> voidRound(List<PlayPredictBet> bets) {
        return settle(bets, null);
    }
}
