package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.GameRandomAudit;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * 统一、可设种子、可审计的随机服务。
 */
public interface IGameRandomService {

    String DOMAIN_REWARD_DROP = "REWARD_DROP";
    String DOMAIN_PLAYKIT_COIN = "PLAYKIT_COIN";
    String DOMAIN_PLAYKIT_DICE = "PLAYKIT_DICE";
    String DOMAIN_PLAYKIT_D20 = "PLAYKIT_D20";
    String DOMAIN_PLAYKIT_RANDOM = "PLAYKIT_RANDOM";
    String DOMAIN_JOURNEY_CHECK = "JOURNEY_CHECK";
    String DOMAIN_CITY_DICE = "CITY_DICE";
    String DOMAIN_TASK_DISPATCH = "TASK_DISPATCH";

    record DrawResult(int ticket, int total, String receiptId, String seedDigest, boolean replayed) {}

    record PickResult<T>(T item, DrawResult draw) {}

    DrawResult draw(String domain, int total, String receiptId,
                    Long memberId, String refType, String refId,
                    IntFunction<String> outcomeFormatter);

    boolean hitBp(int probBp, String receiptId, Long memberId, String refType, String refId);

    <T> PickResult<T> weightedPick(String domain, List<T> candidates, ToIntFunction<T> weightFn,
                                   String receiptId, Long memberId, String refType, String refId,
                                   Function<T, String> labelFn);

    int d20(String receiptId, Long memberId, String refType, String refId);

    int d6(String domain, String receiptId, Long memberId, String refType, String refId);

    int flipCoin(String receiptId, Long memberId, String refType, String refId);

    List<GameRandomAudit> listAudits(GameRandomAudit filter);
}
