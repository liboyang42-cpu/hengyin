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
| `FixedTopicWeatherProvider.java` | sha256 `a11f51825c77a07a6623dbb3eae16e223e466ef4d6e14a05b335b555` | 已读（以 `sha256sum` 为准） |
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
       （themeRuntime.sources + storyGrants，不是第二钱包）
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
| 静态检查 | 见测试命令编译输出 |
| 编译 | `chengyinhub-system` + `chengyinhub-admin` 模块测试编译 |
| 单测 | `com.chengyinhub.business.service.support.rpg.adapter.**`（真实 surefire 输出另附） |
| DB | **NOT_RUN**（隔离单测，无 DDL） |
| HTTP | **NOT_RUN**（未改 Controller；Runtime hunk 未落地） |
| App | **NOT_RUN**（明确不碰小程序） |
| CI | **NOT_RUN**（Issue 禁止功能线自行触发 CI） |

## 迁移

无。不新增表，不跑生产 DDL。旧局无 sidecar = `inactive`，state_json 字段集合不变。
