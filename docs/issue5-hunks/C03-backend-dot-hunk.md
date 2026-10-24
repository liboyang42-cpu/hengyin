# C03-backend → dot 最小 hunk

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C03**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓（不写 `chengyin`）  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c03-rpg-mechanism-consumers-5266`

C03 未改下列禁止文件。Runtime 接线请由 dot 按本页粘贴；mechanism 源码在
`chengyinhub-system/.../rpg/mechanism/`，测试在
`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/rpg/mechanism/`。

本仓 **不是** 卷 39 的 100%。11/14/32 当前补丁、`RpgAuthoringCaller*`、
`DynamicAttributeAuthoringService*`、卷 39/40/31/32 原文包均
**WAITING_SOURCE**（unpushed `e16c2c5042f604c95b766ee7e35b73c78133a8c8` /
`5b928890e4969904c40029468fe7eace6b5e9b47` /
`94a5d4a6a07bdf389dec7db84f2dff4319133732` /
`a829a091f53394c8357ca7ae5ef58e0ae2117fd8`）。禁止猜代码重写那些提交。

C02 独占 `.../rpg/adapter/**`（PR #572），本组不写。

## 1. 白名单（实际写入）

生产（仅新目录 `chengyinhub-system/.../support/rpg/mechanism/`）：

- `MechanismRequestIdBinder.java` — 原 `TopicRouteAction.actionId` 一次效果账；UNKNOWN 不 mint
- `ProgressClockConsumer.java` — 进度钟 / 动量 / 侧面（复用 `JourneyRpg` + `TICK_CLOCK` / `ADD_CONDITION`）
- `TradeBargainConsumer.java` — 魔鬼交易（复用 `GameplayCheckEngine.roll(..., bargainBonus)`）
- `OracleBountyConsumer.java` — 神谕查表 + 赏金钟（不是第二套骰子，也不是 `MerchantReferralBounty` 金）
- `OathFlashbackConsumer.java` — 誓言条件 / 闪回恢复作者点名键；密码=作者冻结值
- `RouteCommunityDayConsumer.java` — 路段效果 + 社区日 `ENV_IN weekpart`（服务器环境快照）
- `ClueSecretGoalConsumer.java` — `clue.*` 写入 + 秘密目标 `when` 判定；拒绝未确认经济字段

测试：同包 `chengyinhub-admin/.../rpg/mechanism/*Test.java`（含 `C03FirstJourneyIsolationTest`）。

未改：`TopicRouteRuntimeServiceImpl`、`RouteEffects*`、`CmsTopicServiceImpl`、公共 Controller / Mapper / migration、`GameplayCheckEngine` 骰点公式、`JourneyRpg.normalize` 字段表。无生产 DDL。无 `adapter/**`。

## 2. 复用的既有真源（不克隆第二套骰子 / 钱包）

| 既有真源 | 本组如何消费 |
|---|---|
| `JourneyRpg` clocks / momentum / aspects | `ProgressClockConsumer` select/freeze/view/setMomentum |
| `StoryVocabulary.TICK_CLOCK` / `ADD_CONDITION` | 进度钟、誓言、神谕效果 |
| `TopicRouteEngine.applyEffects`（含 `clue.h_*` 只增不减） | 所有消费者的一次效果 |
| `GameplayCheckEngine.roll(..., bargainBonus)` | 交易加值，不另造骰子 |
| `AdvancedGameConfigValidator` check.bargain | 作者交易形状 |
| `JourneyRpg.ENV_IN` / `weekpart` | 社区日闸，不读客户端日历 |
| `TopicRouteAction.actionId` | 原 requestId；重放 / UNKNOWN 恢复 |

建议冻结 sidecar 键名 `rpgMechanisms`（**不能**塞进当前远端 `JourneyRpg.normalize`，多余字段会拒）。解析后按机制交给对应 Consumer.select，写入 `Play.frozen`。

## 3. `TopicRouteRuntimeServiceImpl` 玩家写入口（dot 接线）

文件：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java`

玩家显式选择已启用机制的命令（建议复用现有 play command / `actionId`，不要新账）：

```java
    private Map<String, Object> applyC03Mechanism(TopicRouteSession session, Long memberId, Long nodeId,
            String actionId, JsonNode choice) {
        MechanismRequestIdBinder binder = c03Binder(); // 会话级；跨请求用 action 表当原账
        MechanismRequestIdBinder.Play play = new MechanismRequestIdBinder.Play();
        play.setRules(rulesOf(session));
        play.setState(readEngineState(session.getStateJson()));
        play.setCheck(checkOf(session, nodeId));
        play.setFrozen(frozenMechanismsOf(session)); // 发布快照 sidecar
        play.setPlayerChoice(choice);
        play.setOriginalRequestId(actionId);         // 与 TopicRouteAction.actionId 同一条
        play.setOriginalAction(selectActionByAction(session.getId(), memberId, actionId));
        play.setDice(serverDice());                  // 现有服务端骰,禁止客户端骰
        String mechanism = choice.path("mechanism").asText();
        Map<String, Object> result;
        if ("progressClock".equals(mechanism)) result = new ProgressClockConsumer(binder).apply(play);
        else if ("tradeBargain".equals(mechanism)) result = new TradeBargainConsumer(binder).apply(play);
        else if ("oracleBounty".equals(mechanism)) result = new OracleBountyConsumer(binder).apply(play);
        else if ("oathFlashback".equals(mechanism)) result = new OathFlashbackConsumer(binder).apply(play);
        else if ("routeCommunityDay".equals(mechanism)) result = new RouteCommunityDayConsumer(binder).apply(play);
        else if ("clueSecretGoal".equals(mechanism)) result = new ClueSecretGoalConsumer(binder).apply(play);
        else throw new IllegalArgumentException("未知机制");
        // 与现有 commitCheck 同一 CAS 写 state_json + TopicRouteAction
        return result;
    }
```

同 `actionId` 走现有 action 回放；`status=UNKNOWN` 时只读原 `actionId`，**不要** `retry:`+id。

作者侧：保存时 `Consumer.select` → 模拟 `simulate`（只碰 state 副本）→ 发布把 `Play.frozen` 写进快照。闪回在冻结时调用 `OathFlashbackConsumer.freezeWithSnapshot`。

## 4. 首个可跑旅程（隔离测试）

`C03FirstJourneyIsolationTest`：

1. 作者选配六机制并发布冻结。
2. 模拟 TICK_CLOCK 不写原局。
3. 玩家显式 `progressClock` / `TICK_CLOCK` → 服务端按 `check:1:alarm:tick` 推进一口钟。
4. 同 id 回读；再提交不二次推进。
5. 内存丢失标 UNKNOWN；无原 action 仍是同一 id 的 UNKNOWN。
6. 带上原 `TopicRouteAction` 恢复为 APPLIED，仍是原 id。
7. `retry:`+原 id 拒绝。

命令：

```bash
mvn -pl chengyinhub-system,chengyinhub-admin -am test \
  -Dtest='**/rpg/mechanism/*Test.java' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

环境：OpenJDK 21.0.12.1 · Maven 3.8.7 · 隔离内存夹具 · 无 MySQL / HTTP / 生产库。  
真实结果（2026-10-23T23:01:37Z，`BUILD SUCCESS`）：

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- C03FirstJourneyIsolationTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- C03MechanismAccountSwitchRestartIsolationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- TradeBargainConsumerTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- ProgressClockConsumerTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- OracleBountyConsumerTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- OathFlashbackConsumerTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- RouteCommunityDayConsumerTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- ClueSecretGoalConsumerTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- MechanismRequestIdBinderTest
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

SHA256（生产 7 类，`b7dfe4d90`）：

- `MechanismRequestIdBinder.java` `c07031d65ccf6406157108436a1d741848b0eed0cda241a42366c224e5e0bc34`
- `ProgressClockConsumer.java` `ac44d4c1c98e3892068a7bf99db25d8333d9432b66359eee7877855abe0e24a8`
- `TradeBargainConsumer.java` `6985baf14d04a5652e701989b7d70d23073efbb98833417ace61dd9d3fd97673`
- `OracleBountyConsumer.java` `7838748b5363bf9b838c0396089c8a47695b5d2feaac323849260646226d1cbe`
- `OathFlashbackConsumer.java` `44a428db91f9e0a3318de4bf06ae070e581a8eeae45ab464edcfd24fb46bc04b`
- `RouteCommunityDayConsumer.java` `48a591d5885b4bb326b7286baa40ca42d328d22d510295add8e3329f14933968`
- `ClueSecretGoalConsumer.java` `623032de196be5690493786e7fbb47870849235cc073bcac1f808d54536fac04`

DB / HTTP / App / CI = **NOT_RUN**。Head：`b7dfe4d90`（本文件再提交会前进）。

## 5. 未确认决策（不编造标准答案或经济值）

- 阵营 / 领地具体规则：未确认。`ClueSecretGoalConsumer` 拒绝 `territory` / `factionReward` / `economy` 等字段。
- 秘密目标奖励金额 / XP / 积分 / 币：未确认。`CHECK_GOAL` 只回 `met`，不发奖。
- 赏金与商家拓展金 `MerchantReferralBounty` 的关系：未确认。本组赏金只推进作者 `counter.*` 钟。
- 神谕是否允许随机表 / 第二套骰：否。只查作者表。
- 密码：必须作者在玩法配置中设置，冻结与判定同一份。本组不造全局答案。卷 32 当前补丁 WAITING_SOURCE。
- 11/14 叙事补丁：WAITING_SOURCE，不改 `RouteEffects` / Runtime。

## 6. 不要做

- 不要改 `GameplayCheckEngine` 骰点公式、`natAuto`、modCap。
- 不要新表、新钱包、新 request 唯一键、新 Controller。
- 不要把未启用机制做成「按钮 / port / 默认拒绝冒充完成」；未启用是作者没选，启用后必须真写效果。
- 不要把 UNKNOWN 投影成 APPLIED，也不要为恢复 mint 新 requestId。
- 不要对未见过的 requestId 做 `recoverUnknown` / `recoverOriginal` 时 `bind`/`put` 进账本；那会让后续唯一写入变成对 mint 出的 UNKNOWN 的 recover。未见过的 id 只回 UNKNOWN 且 `existing==null`。`recoverOriginal` 对齐 iOS，委托 `recoverUnknown`。缺 requestId 仍抛（与 iOS `requireRequestID` 一致）。

## 7. Parent addendum 2026-10-23 17:45 America/Los_Angeles

Timer deliveryCount=31 (00:40Z). WAITING_SOURCE cat-file still ABSENT.

Class-level `recoverOriginal` of never-seen ids stays UNKNOWN and does not occupy the binder; later unique apply is the first APPLIED write:

- TradeBargain ACCEPT `c03-bargain-never` (`fe8477423`, TradeBargainConsumerTest 5/0)
- Oath SWEAR `c03-oath-never` (OathFlashbackConsumerTest 4/0)
- Oracle CONSULT `c03-oracle-never` (OracleBountyConsumerTest 4/0)
- Route TAKE_SEGMENT `c03-route-never` (RouteCommunityDayConsumerTest 4/0)
- Clue GAIN_CLUE `c03-clue-never` (ClueSecretGoalConsumerTest 4/0)
- Clock TICK `c03-clock-never` (ProgressClockConsumerTest 3/0)

HEAD `1a141870b`. Parent surefire `com.chengyinhub.business.service.support.rpg.mechanism.*Test` **34/0** 2026-10-24T00:45:38Z. Isolation 5/0. FirstJourney 2/0. Binder 3/0.

DB / HTTP / App / CI = NOT_RUN. Did not write D1–D6 shared files. Runtime still unwired. Not 100%.

## 8. Parent addendum 2026-10-23 18:28 America/Los_Angeles

Goal continuation after 01:20Z tick. WAITING_SOURCE cat-file still ABSENT.

Class-level `MechanismRequestIdBinderTest.recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueBindApplies`: recoverOriginal of `c03-bind-never` stays UNKNOWN / `existing==null` without occupying; unique bind is the first PENDING write of that id.

HEAD `d3e1e1c8d`. Parent surefire `com.chengyinhub.business.service.support.rpg.mechanism.*Test` **35/0** 2026-10-24T01:28:11Z. Isolation 5/0. FirstJourney 2/0. Binder 4/0.

DB / HTTP / App / CI = NOT_RUN. Did not write D1–D6 shared files. Runtime still unwired. Not 100%.

## 9. Parent addendum 2026-10-24 04:10Z

`MechanismOriginalActionAdapter.recoverFromRouteAction` 只读既有
`TopicRouteSessionMapper.selectActionByAction`。缺端口
WAITING_SOURCE `topicRouteSessionMapper.selectActionByAction`。
未见 actionId `c03-route-missing` 返回 null，不占 `MechanismRequestIdBinder`；
已有行 `check:1:alarm:tick` 回读原 `responseJson`，仍不写入内存账，不 `insertAction`。

HEAD `023afced7`. Surefire `MechanismRequestIdBinderTest` **5/0** 2026-10-24T04:10:02Z
（Failures 0, Errors 0, Skipped 0）.

DB / HTTP / App / CI = NOT_RUN. 未改 RouteEffects、Runtime、CmsTopicServiceImpl、
RpgAuthoringCaller、DynamicAttributeAuthoringService、Controller、Mapper、migration、AppSession。
Runtime 仍未接线。Not 100%。
