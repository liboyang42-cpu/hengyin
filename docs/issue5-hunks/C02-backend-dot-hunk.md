# C02-backend → dot 最小 hunk

前置 hash（hengyin `origin/master`）：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`

C02 未改下列禁止文件。Runtime 接线请由 dot 按本页粘贴；adapter 源码在
`chengyinhub-system/.../rpg/adapter/`，测试在
`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/rpg/adapter/`。

Sidecar 作者配置目前**不能**放进 `JourneyRpg.normalize`（只认 `enabled`/`keys`）。
在已发布快照里给 C02 一块独立 JSON（建议键名 `rpgAdapter`），解析为 `RpgAdapterSpec`。
`RpgAuthoringCaller` / `DynamicAttributeAuthoringService` 本远端不存在（WAITING_SOURCE e16c2c5 / 5b92889 / 94a5d4a / a829a09）。

## 1. `TopicRouteRuntimeServiceImpl.checkContext` 环境刷新之后

文件：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java`

当前（约 L3280）：

```java
        context.state = readEngineState(session.getStateJson());
        refreshEnvironment(scope.topic, rules, context.state);
        return context;
```

改为：

```java
        context.state = readEngineState(session.getStateJson());
        refreshEnvironment(scope.topic, rules, context.state);
        applyC02Adapters(session, scope.topic, rules, context.state, context.check, nodeId, memberId);
        return context;
```

`applyC02Adapters` 建议实现（同文件私有方法，或 dot 自定等价物）：

```java
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.rpg.TopicEnvironmentService topicEnvironmentService;

    private void applyC02Adapters(TopicRouteSession session, CmsTopic topic, JourneyRules rules,
            TopicRouteEngine.State state, JsonNode check, Long nodeId, Long memberId) {
        RpgAdapterSpec spec = RpgAdapterSpec.parse(rpgAdapterJson(session)); // 快照 sidecar；缺省 = inactive
        if (!spec.active()) return;
        RpgAdapterContext intake = new RpgAdapterContext();
        intake.setTopic(topic);
        intake.setRules(rules);
        intake.setState(state);
        intake.setCheck(check);
        intake.setSpec(spec);
        intake.setNodeId(nodeId);
        intake.setMemberId(memberId);
        intake.setActorRole(/* 现有 bindStoryContext / PlayerActivityRoleMapper 已读的角色 */);
        intake.setPartyRoles(/* 同场 PlayerActivityRole 列表；没有则空，fail-closed */);
        // 禁止把客户端 weather / now 填进 intake
        new RpgAdapterPipeline(topicEnvironmentService, clock).prepare(intake);
    }
```

`clock` 用本类已有服务器时钟字段，不要用请求里的时间。

## 2. 掷骰后把投影写进现有 breakdown

当前 `rollDice`（约 L3308）只调 `GameplayCheckEngine.roll`。prepare 已改 State 后，现有 roll 会吃 ENV_IN / ITEM_EQUIPPED / HAS_CONDITION / ROLE_IN。

结算成功后若 sidecar 带了资源效果，再调：

```java
        intake.setReceipt(receipt);
        intake.setOriginalRequestId(actionId); // JourneyCheckActionId，不要新造 request
        intake.setWriteResources(true);
        pipeline.resources().writeOriginal(intake);
        pipeline.contribute(intake, receipt);
```

`commitCheck` 仍是唯一 action 写入。同 `actionId` 走现有 `replayCheck`，不要新账。

## 3. 红检定拒绝幸运重掷

`reroll` 方法在已有 luck 校验旁增加：sidecar `teamOpposition.lane=RED` 且 adapter 投影 `rerollAllowed=false` 时拒绝，errorCode 建议 `RPG_RED_REROLL_DISABLED`。白检定保持现有 luck 扣减。不要改 `natAuto`。

## 4. 角色卡按原 requestId 回读

`ThemeCharacterService.card` / `ApiPlayCharacterController` 仍是只读现卡。检定回执上的 `breakdown.requestId` + `breakdown.characterCard` 由 adapter 写入。

重试读卡：`CharacterGrowthSourceAdapter.readCard`，`originalAction` = `selectActionByAction(sessionId, memberId, originalActionId)`。不要为重试 mint 新 requestId。

若要把 `requestId` 查询挂到 `GET /api/play/character`，那是公共 Controller，由 dot 加可选参数；C02 未改 Controller。

## 5. 状态栏「此刻」也要过期

`rpgStatus`（约 L3463）在 `refreshEnvironment` 之后对 preview 再 `pipeline.environment().apply`，避免过期窗口仍显示 rain 并被客户端当成可成功条件。preview 仍不落库。

## 不要做

- 不要改 `GameplayCheckEngine` 骰点公式、`natAuto`、modCap。
- 不要新表、新钱包、新角色台账、新 request 唯一键。
- 不要把 `FixedTopicWeatherProvider` 配成假雨来让过期检定成功。
- 不要读客户端 clock / GPS / weather。
