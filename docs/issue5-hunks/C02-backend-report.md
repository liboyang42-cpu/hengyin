# C02-backend 交付报告

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5  
仓库：`github.com/liboyang42-cpu/hengyin` feature 分支（不合 master）  
base：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`（Java 21 / Spring Boot 4.1.1）

未写 `liboyang42-cpu/chengyin`。未把公开 hengyin 当作未核实的私有生产目的地。

## 资料签收（本 agent 实际读到的）

| 项 | 字节 / hash | 结论 |
|---|---|---|
| Issue #5 正文 | gh 可读 | C02 范围真源（本任务） |
| vols 35–38 / 36 完整例子 | 本工作区与 remote-ops 仓均无对应原文文件 | **WAITING_SOURCE** |
| v12 DOCX / 232 目录实施包 | 未作为 Git 对象出现在本工作区 | **WAITING_SOURCE** |
| `TopicEnvironmentService.java` | sha256 `3d4b24d4dac4c3e3355bbeee814e5ab8858125928d8a81ecbb38151e984862c1` | 与任务给定 hash 一致 |
| `TopicWeatherProvider.java` | sha256 `3664057b113caea1b4fcc229cfa5f4a654f8f871b238853d9f37dbc77cb052b6` | 已读 |
| `FixedTopicWeatherProvider.java` | sha256 `a11f51825c77a07a6623dbb3eae16d21e0370a223e466ef4d6e14a05b335b555` | 已读 |
| `ApiPlayRpgController.java` / `TopicRouteRpgP1ServiceImplTest.java` / `JourneyRpgP1Test.java` | 当前 master | 已读 |
| `RpgAuthoringCaller*` / `DynamicAttributeAuthoringService*` | 远端不存在 | **WAITING_SOURCE**（e16c2c5 / 5b92889 / 94a5d4a / a829a09 未 push） |

FixedTopicWeatherProvider 精确 sha256 以签收命令输出为准：`a11f51825c77a07a6623dbb3eae16d21e0370a223e466ef4d6e14a05b335b555`。

## 调用链（正常入口 → 服务端事实 → 原 writer → 回读）

```
作者 sidecar RpgAdapterSpec（发布冻结后由 Runtime hunk 读）
  → RpgAdapterPipeline.prepare
       EnvironmentExpiryAdapter  → TopicEnvironmentService.refresh（服务器 Clock + 主题城市）
       EquipmentStateDurationAdapter → state.equipped / statusConditions
       PartyFactAdapter          → PlayerActivityRole → state.actorRoleCode
       TeamOppositionAdapter     → 白/红/被动投影 + 对抗状态
       CharacterGrowthSourceAdapter.prepare → 已有 counter.* / statusConditions
  → GameplayCheckEngine.roll     （现有骰点 / breakdown / natAuto）
  → ResourceSourceAdapter.writeOriginal → TopicRouteEngine.applyEffects
       （themeRuntime.sources + storyGrants，进程投影；persistOriginal 走 Runtime 原请求账，缺端口 WAITING_SOURCE）
  → CharacterGrowthSourceAdapter.readCard
       原 TopicRouteAction.responseJson.characterCard + requestId
  → TopicRouteRuntimeServiceImpl.commitCheck（dot hunk；现有 actionId 账）
```

失败路径：

- 环境窗口过期：环境键写成哨兵 `expired`，ENV_IN（含 `unknown`）不得放行成功。
- 假天气 / 客户端时钟：intake 字段被记录为 ignored，不参与 refresh / 截止判定。
- 天气源失败且未过期：沿用现有 unknown fail-open。
- 角色源缺失 + requireRole：actorRoleCode 保持 null，ROLE_IN fail-closed。
- 同 originalRequestId 重放：sources / storyGrants / growth-reset 源键已在则跳过。
- 冻结旧局（`RpgAdapterSpec.inactive()`）：零行为，breakdown 仍是 F2 六键。

## 复用（未另造骰子 / 钱包 / 角色 / 请求账）

| 能力 | 复用 |
|---|---|
| 骰点 / breakdown / natAuto / modCap | `GameplayCheckEngine` |
| 环境快照 | `TopicEnvironmentService` + `TopicWeatherProvider` |
| ENV_IN / ITEM_EQUIPPED / HAS_CONDITION / ROLE_IN | `TopicRouteEngine.conditionsPass` |
| 物资 / XP / 徽章 | `TopicRouteEngine.applyEffects`（`themeRuntime.sources`、`storyGrants`） |
| 背包初始化 | `ThemeRuntimeEngine.initialize` |
| 角色卡属性 | 局状态 variables + 可选 `ThemeCharacterService.card` 投影 |
| RPG 视图 | `JourneyRpg.view` |
| 检定动作 id | `JourneyCheckActionId` / `TopicRouteAction.actionId` |
| 活动角色 | `PlayerActivityRole` |
| 归零地板 | `JourneyRules.Recovery` 已发布值 |
| 幸运重掷 | 现有 luck；红检定只投影 `rerollAllowed=false` |

## 未知产品决策（未编造标准答案或经济值）

1. sidecar 在已发布快照中的键名与作者 UI（等 RpgAuthoringCaller）。
2. 过期哨兵用 `expired` 而不是 `unknown`（否则作者写 `values:["unknown"]` 仍能成功）。vols 35 原文未到，列为待裁决。
3. 白/红检定是否额外扣不同 luck；当前白=现有 luck 重掷，红=禁止重掷。
4. 对抗是否改 DC；当前只挂作者已有的 disadvantageIf 状态。
5. 技能点是否计入 total；当前只投影，只有 `check.skill` 绑了同一 counter 时现有引擎才加值。
6. 专长经济与归零哪些键；只执行 sidecar 点名的 `resetKeys` + 已发布 hp/luck 地板。
7. `GET /api/play/character` 是否接受 requestId（公共 Controller，交 dot）。

## 未解决

- Runtime / 状态栏 / 红检定拒绝尚未接线（见 `C02-backend-dot-hunk.md`）。
- vols 35–38 全文与 36 例子未签收，机制按 Issue C02 条目 + 当前远端 API 回挂；原文到达后必须逐条对账，不得把本 checkpoint 宣称为卷级 100%。
- 未跑生产 DB、HTTP、App、CI。

## 验证栏

| 栏 | 结果 |
|---|---|
| 静态检查 | javac release 21 编译 C02 源码与测试通过（随 surefire 模块编译） |
| 编译 | `chengyinhub-system` + `chengyinhub-admin` SUCCESS |
| 单测 | **16 tests, Failures: 0, Errors: 0, Skipped: 0**（见下方真实输出） |
| DB | **NOT_RUN**（隔离单测，无 DDL） |
| HTTP | **NOT_RUN**（未改 Controller；Runtime hunk 未落地） |
| App | **NOT_RUN**（明确不碰小程序） |
| CI | **NOT_RUN**（Issue 禁止功能线自行触发 CI） |

命令：

```
mvn -pl chengyinhub-system,chengyinhub-admin -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='**/rpg/adapter/*Test.java'
```

真实 surefire 摘要（2026-10-23T19:57:48Z，`BUILD SUCCESS`，Total time 21.538 s）：

```
Tests run: 2  PartyFactAdapterTest
Tests run: 2  CharacterGrowthSourceAdapterTest
Tests run: 5  EnvironmentExpiryAdapterTest
Tests run: 2  ResourceSourceAdapterTest
Tests run: 3  TeamOppositionAdapterTest
Tests run: 2  EquipmentStateDurationAdapterTest
Results: Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
```

首个可跑旅程：`EnvironmentExpiryAdapterTest.authorExpiryUsesServerFactsThenBlocksSuccessAndReplaysOriginalRequestId`
（作者环境失效 → 服务器天气/日段 → 过期+假天气+客户端时钟不得成功 → 角色卡 requestId 仍是原 `check:1:ask_arong:roll`）。

## 交付文件 SHA256（提交后以 git 树为准，本段在提交前重算）

见同目录随后 commit 的 `git ls-files` + `sha256sum`。head 分支 `cursor/c02-rpg-world-adapters-1a98`。

## 迁移

无。不新增表，不跑生产 DDL。旧局无 sidecar = `inactive`，state_json 字段集合不变。

## Addendum 2026-10-23 16:25 America/Los_Angeles

HEAD `e3ffe4afa`。`readCard` of never-seen `c02-growth-never` does not occupy `growth-reset:<id>`; later unique `resetIfRecovery` is the first write. Did not invent `recoverOriginal`. Missing requestId still WAITING_SOURCE + Java null.

命令：`mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.support.rpg.adapter.*Test -Dsurefire.failIfNoSpecifiedTests=false test`

结果：`BUILD SUCCESS` **20/0** 2026-10-23T23:25:49Z。`C02AccountSwitchRestartIsolationTest` 4/0。Runtime still unwired. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。

## Addendum 2026-10-23 16:44 America/Los_Angeles

HEAD `09515c68b`。`writeOriginal` of present never-seen `c02-resource-never` with empty effects does not occupy `story:1:clue_page`; later unique ADD_ITEM `writeOriginal` is the first write (`sourcesDelta` 1 then 0). Did not invent `recoverOriginal`. Missing requestId still WAITING_SOURCE + Java null.

命令：`mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.support.rpg.adapter.*Test -Dsurefire.failIfNoSpecifiedTests=false test`

结果：`BUILD SUCCESS` **21/0** 2026-10-23T23:44:26Z。`C02AccountSwitchRestartIsolationTest` 5/0。Runtime still unwired. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不声称 100%。

## Addendum 2026-10-23 18:04 America/Los_Angeles

HEAD `0312e6445` tests. Class-level occupancy without inventing `recoverOriginal`:

- `CharacterGrowthSourceAdapterTest.readCardOfNeverSeenIdDoesNotOccupyGrowthResetThenUniqueResetApplies` — `readCard` of `c02-growth-class` does not occupy `growth-reset:<id>`; later unique `resetIfRecovery` is the first write
- `ResourceSourceAdapterTest.emptyEffectsOfPresentNeverSeenIdDoesNotOccupyStorySourceThenUniqueWriteApplies` — empty-effects `writeOriginal` of `c02-resource-class` does not occupy `story:1:clue_page`; later unique ADD_ITEM is the first write

Isolation occupancy remains. Parent surefire `com.chengyinhub.business.service.support.rpg.adapter.*Test` **23/0** 2026-10-24T01:04:20Z：

```
Tests run: 3 -- CharacterGrowthSourceAdapterTest
Tests run: 3 -- ResourceSourceAdapterTest
Tests run: 5 -- C02AccountSwitchRestartIsolationTest
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Runtime still unwired. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。

## Addendum 2026-10-23 18:13 America/Los_Angeles

HEAD `2027d1c8f` tests. Remaining adapter class-level occupancy without inventing `recoverOriginal`:

- `PartyFactAdapterTest.missingActorDoesNotOccupyRoleThenUniqueNavigatorApplies` — empty party + requireRole leaves `actorRoleCode` null; unique NAVIGATOR is the first write
- `TeamOppositionAdapterTest.unusedOppositionDoesNotOccupyThenUniqueHunterApplies` — NAVIGATOR-only party leaves `opposed` false; unique HUNTER is the first opposed write
- `EnvironmentExpiryAdapterTest.inactiveExpiryDoesNotOccupyWeatherThenUniqueApplyWritesServerFact` — inactive spec leaves environment empty; unique expiry apply writes server weather `rain`
- `EquipmentStateDurationAdapterTest.unusedDurationDoesNotUnequipThenUniqueExpiryClears` — BEFORE clock apply leaves lantern/soaked; unique AFTER clock apply unequips and clears soaked

Isolation occupancy remains. Parent surefire `com.chengyinhub.business.service.support.rpg.adapter.*Test` **27/0** 2026-10-24T01:13:33Z：

```
Tests run: 3 -- PartyFactAdapterTest
Tests run: 4 -- TeamOppositionAdapterTest
Tests run: 6 -- EnvironmentExpiryAdapterTest
Tests run: 3 -- EquipmentStateDurationAdapterTest
Tests run: 5 -- C02AccountSwitchRestartIsolationTest
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Runtime still unwired. App / XCTest / MySQL / HTTP / CI = NOT_RUN。不是 100%。
