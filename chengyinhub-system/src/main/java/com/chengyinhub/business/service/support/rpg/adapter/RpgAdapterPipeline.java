package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.support.GameplayCheckEngine;
import com.chengyinhub.business.service.support.rpg.TopicEnvironmentService;

import java.time.Clock;

/**
 * C02 独占接线:先把世界/角色/团队事实写进现有 State,再调用
 * {@link GameplayCheckEngine#roll},最后把投影附到已有 breakdown。
 * 不是第二套骰子 / 钱包 / 角色账。
 */
public class RpgAdapterPipeline {
    private final EnvironmentExpiryAdapter environment;
    private final EquipmentStateDurationAdapter equipment;
    private final PartyFactAdapter party;
    private final TeamOppositionAdapter team;
    private final ResourceSourceAdapter resources;
    private final CharacterGrowthSourceAdapter growth;
    private final ResourceSourceAdapter.GameReceiptSource gameReceipts;

    public RpgAdapterPipeline(TopicEnvironmentService environmentService, Clock clock) {
        this(environmentService, clock, new ResourceSourceAdapter(), null);
    }

    /**
     * 注入已有路线动作表和游戏回执。持久原请求仍没有公开 writer，缺时保持 WAITING_SOURCE。
     */
    public RpgAdapterPipeline(TopicEnvironmentService environmentService, Clock clock,
            TopicRouteSessionMapper routeActions, IGameSessionRuntimeService games) {
        this(environmentService, clock,
                new ResourceSourceAdapter(null, routeActions == null ? null
                        : new ResourceSourceAdapter.MapperOriginalRouteActionSource(routeActions)),
                games == null ? null : ResourceSourceAdapter.fromGameRuntime(games));
    }

    private RpgAdapterPipeline(TopicEnvironmentService environmentService, Clock clock,
            ResourceSourceAdapter resources, ResourceSourceAdapter.GameReceiptSource gameReceipts) {
        Clock server = clock == null ? Clock.systemUTC() : clock;
        this.environment = new EnvironmentExpiryAdapter(environmentService, server);
        this.equipment = new EquipmentStateDurationAdapter(server);
        this.party = new PartyFactAdapter();
        this.team = new TeamOppositionAdapter();
        this.resources = resources;
        this.growth = new CharacterGrowthSourceAdapter();
        this.gameReceipts = gameReceipts;
    }

    public EnvironmentExpiryAdapter environment() { return environment; }
    public EquipmentStateDurationAdapter equipment() { return equipment; }
    public PartyFactAdapter party() { return party; }
    public TeamOppositionAdapter team() { return team; }
    public ResourceSourceAdapter resources() { return resources; }
    public CharacterGrowthSourceAdapter growth() { return growth; }

    public ResourceSourceAdapter.GameReceiptRead recoverOriginalGame(com.chengyinhub.business.domain.dto.GameActorContext actor,
            Long activityId, String requestId) {
        if (gameReceipts == null) {
            throw new ResourceSourceAdapter.WaitingSourceException(ResourceSourceAdapter.GAME_RECEIPT);
        }
        return resources.recoverFromGameReceipt(actor, activityId, requestId, gameReceipts);
    }

    public PlayCheckReceipt roll(RpgAdapterContext context) {
        prepare(context);
        PlayCheckReceipt receipt = GameplayCheckEngine.roll(context.getCheck(), context.getRules(),
                context.getState(), context.getDice(), context.isRerolled(), context.getBargainBonus());
        context.setReceipt(receipt);
        contribute(context, receipt);
        if (context.isWriteResources()) {
            resources.writeOriginal(context);
            resources.contribute(context, receipt);
        }
        if (context.getRecoveryKind() != null) {
            growth.resetIfRecovery(context);
        }
        return receipt;
    }

    public void prepare(RpgAdapterContext context) {
        environment.apply(context);
        equipment.apply(context);
        party.apply(context);
        team.apply(context);
        growth.prepare(context);
    }

    public void contribute(RpgAdapterContext context, PlayCheckReceipt receipt) {
        environment.contribute(context, receipt);
        equipment.contribute(context, receipt);
        party.contribute(context, receipt);
        team.contribute(context, receipt);
        growth.contribute(context, receipt);
    }
}
