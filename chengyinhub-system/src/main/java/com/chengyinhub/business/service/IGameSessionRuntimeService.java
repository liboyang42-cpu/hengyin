package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameMerchantEntryView;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.domain.dto.GameSessionView;

import java.util.List;
import java.util.Map;

/** 活动级三身份游戏深模块公开 seam。 */
public interface IGameSessionRuntimeService {
    boolean isGameModuleConfigEnabled(String configJson);
    boolean isGameSessionEnabled(com.chengyinhub.business.domain.CmsTopic topic);
    String routeSessionStatus(Long activityId);
    boolean isRouteLeaderboardHidden(Long activityId);
    void assertRoutePlayable(Long activityId, Long nodeId);
    void assertRouteReception(Long activityId, Long nodeId);
    List<GameMerchantEntryView> merchantEntries(GameActorContext actor);
    GameSessionView view(GameActorContext actor, Long activityId);
    Map<String, Object> exportRecap(GameActorContext actor, Long activityId);
    GameCommandReceiptView command(GameActorContext actor, GameSessionCommand command);
    void recordFailure(GameActorContext actor, GameSessionCommand command, GameSessionException failure);
    GameCommandReceiptView receipt(GameActorContext actor, Long activityId, String requestId);
}
