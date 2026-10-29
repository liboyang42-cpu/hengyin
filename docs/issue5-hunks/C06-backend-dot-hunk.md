# C06-backend · 商家 NPC AI 草稿到批准发布独占门禁

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C06**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓（不写旧 Java8 `chengyin`、不写 `chengyin-ios` / `chengyin-app`）  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c06-npc-draft-publish-e504`  
对照：iOS exclusive [chengyin-ios#65](https://github.com/liboyang42-cpu/chengyin-ios/pull/65) `MerchantNPCDraftApprovalGate`  
本组 **不是 100%**。缺源标 **WAITING_SOURCE**。DB / HTTP / CI = **NOT_RUN**。

## 1. 白名单（实际写入）

生产（仅新包 `chengyinhub-system/.../service/npc/`）：

- `MerchantNpcKnowledgeDraft.java`
- `MerchantNpcDraftApprovalGate.java`
- `MerchantNpcPublicPrivateIsolation.java`
- `MerchantNpcQualificationRevoke.java`

测试（`chengyinhub-admin/src/test/java/.../service/npc/`）：

- `MerchantNpcDraftApprovalGateTest.java`
- `MerchantNpcQualificationRevokeTest.java`
- `C06FirstJourneyIsolationTest.java`
- `C06AccountSwitchRestartIsolationTest.java`（账号切换 / 进程内重启恢复 / timeout UNKNOWN 不被后到 APPROVED 投影改写）
- `InMemoryNpcFixtures.java`（内存夹具，无 MySQL / HTTP）

未改（FORBIDDEN shared owners / D4）：任何 Controller / Mapper / migration、订单/发布原 writer、`AiGateway` public auth、`CmsTopicServiceImpl`、`TopicRouteRuntimeServiceImpl`、`RouteEffects`、`RpgAuthoringCaller`、`DynamicAttributeAuthoringService`、JWT / RBAC、生产 yml。无生产 DDL。本文件只列接线，不授权 Cursor 改那些文件。

## 2. 复用的既有真源（只读，不克隆第二套 NPC / AI）

| 既有真源 | 本适配器如何消费 |
|---|---|
| iOS#65 `MerchantNPCDraftApprovalGate` 合同 | Java 同路径：AI 只产 DRAFT → 授权人核实 → 原审核 → 发布 → 玩家消费 |
| `ApiAiNpcController#merchantChat` `/api/ai/npc/merchant-chat` | 已存在的玩家消费入口；发布后才可投影。本组不新造 knowledge/publish HTTP |
| `CmsMerchantNpcController` + `NpcProfileMapper` | 门店分身静态档案。知识草稿审核/发布原 writer **不在本树** |
| `MerchantNpcFactsBuilder` | 只读店铺事实包口径；本 DRAFT 不写 persona / 不进 prompt |
| `AiGatewayService` / `AiGatewayServiceImpl` | **不改**。`refuseAiGatewayPublicAuth()` 是 typed `WAITING_SOURCE`，不是新鉴权 |
| `NpcFeatureFlags.merchantChatOn()` | 主题主线不依赖 NPC AI；AI 不可用时用已批准 copy，不假成功 |

## 3. 首个可跑旅程（隔离测试）

`C06FirstJourneyIsolationTest`：

1. AI ingest 只能是 DRAFT；`encodesLiveCommerceMutation=false`。
2. `applyAiCommerce` 对 PRICE / DEDUCT_STOCK / AWARD / CERTIFY_PRESENCE **必须抛**。
3. 授权人核实后提交原审核票（带 `requestId`）→ 发布 → `requeryPublished` 同 hash。
4. 本 request 的驳回审核不得被当前已发布投影改写成 APPROVED。
5. 资格撤销清空隔离 store 的 public/private cache，随后 consume 拒绝。
6. 缺原 writer / 缺 requestId → `WAITING_SOURCE`；requestId 保持 Java `null`，不是 `""`。
7. 未签收原文 v12 / 232 / `e16c2c5` 保持 `WAITING_SOURCE`，不改写。

命令：

```bash
mvn -pl chengyinhub-admin -am \
  -Dtest=com.chengyinhub.business.service.npc.*Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

环境：OpenJDK 21.0.12.1 · Maven 3.8.7 · 隔离内存夹具 · 无 MySQL / HTTP / 生产库。  
真实结果（父复验 2026-10-23T20:54:05Z，HEAD `322b939d3`，`BUILD SUCCESS` 13 tests）：

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- MerchantNpcQualificationRevokeTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- MerchantNpcDraftApprovalGateTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- C06FirstJourneyIsolationTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

随后独占补测账号切换 / 进程内重启恢复 / timeout UNKNOWN（不改生产类）。2026-10-23T20:58:41Z：

```
Tests run: 3 -- C06AccountSwitchRestartIsolationTest
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

未见 requestId 回读不占账（不发明 recoverOriginal）。`reviewStatusForRequest` of null/blank/never-seen stays Java null without occupying; later unique `rememberReview(PENDING)` is the first write. WAITING_SOURCE first-write occupancy remains unique original. 2026-10-23T23:18:07Z HEAD `8f6c8d663`：

```
Tests run: 4 -- C06AccountSwitchRestartIsolationTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`requeryPublished` of null/blank/never-seen does not occupy `publishedByRequestId`; later unique `publishApproved` of that original requestId is the first write. Did not invent `recoverOriginal`. 2026-10-23T23:44:26Z HEAD `708423c11`：

```
Tests run: 5 -- C06AccountSwitchRestartIsolationTest
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / 真库 / CI = NOT_RUN。

Class-level occupancy on exclusive class files (did not invent `recoverOriginal`). `MerchantNpcDraftApprovalGateTest`: `visibilityForRequest` of null/blank/never-seen stays null without occupying; later unique `rememberRejectedReview` is the first REJECTED write. Unpublished `requeryPublished` / `cachedPublic` stay null through ingest/verify/submit; unique `publishApproved` is the first write. `MerchantNpcQualificationRevokeTest`: `cachedPublic` of never-seen/mismatched scope stays null; unique `rememberPublished` is the first write. Parent surefire 2026-10-24T01:00:22Z HEAD `db4647446`：

```
Tests run: 4 -- MerchantNpcQualificationRevokeTest
Tests run: 5 -- C06AccountSwitchRestartIsolationTest
Tests run: 11 -- MerchantNpcDraftApprovalGateTest
Tests run: 1 -- C06FirstJourneyIsolationTest
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / 真库 / CI = NOT_RUN。不是 100%。

SHA256（生产 4 类）：

- `MerchantNpcKnowledgeDraft.java` `24cb23f71488ff7bbdd40c68dfe57e4392e073a2918c30ea3e40e76896cc659a`
- `MerchantNpcDraftApprovalGate.java` `723aea9952cba4b948f63dae73c39f428a7f0254b3d972b1bda1ebad352f1ab7`
- `MerchantNpcPublicPrivateIsolation.java` `3263e944a2f0de4e0ea270b80cfc6b4583ac88e1a38d3fe21195ad9ce44eb1f0`
- `MerchantNpcQualificationRevoke.java` `f19a2915b1dabc48dc4ef85819187ef86b620123dc70697171b191893b669419`

App / 真库 / CI = NOT_RUN。

## 4. WAITING_SOURCE / 未宣称完成

- 原知识审核/发布 writer 与 HTTP（`MerchantNpcKnowledgeDraftService.http`、`MerchantNpcKnowledgeReviewAuthority.route`）**本仓不存在**。默认 `WaitingSourcePublisher` 抛 WAITING_SOURCE，不是成功 stub。
- 未 push 的 D4 SHA `e16c2c5042f604c95b766ee7e35b73c78133a8c8`、Questify v12、232 文件包：`UNSIGNED_ORIGINALS_PRESENT=false`，不改写。
- `RpgAuthoringCaller` / `DynamicAttributeAuthoringService` 本树无此类；不发明。
- G01/G02、W04/W05、商家补卷、审核 12–15 原文包未作为 Git 对象签收到本仓。本组 **不是** 那些卷的 100%。
- 不存在的 API 不能写成已存在：无 `/api/merchant/npc/knowledge/publish`。

## 5. 门禁分栏

| 门禁 | 状态 | 说明 |
|---|---|---|
| 本组 JUnit 5 隔离单测 | 13 tests, BUILD SUCCESS | 仅 `service.npc.*Test` |
| 全模块回归 / 真库 MySQL / HTTP | NOT_RUN | 本组无授权跑生产式环境 |
| CI（dot 21/22 专线） | NOT_RUN | `[skip ci]`，不重复触发 |
| 真实支付 / 扣库存 / 发奖 / 认定到场 | NOT_RUN | AI 四类变异必须抛，未执行 |
| 生产 DDL | NOT_RUN | 未做 |
| `gh pr create` | 不做 | 只 push 分支，Parent 开 PR |

## 6. Needed hook（do not apply here / D4 接线）

共享 writer 仍归 dot。日后接线只把本包当 collaborator，不要从 Cursor 分支整文件覆盖：

1. **原审核/发布 writer** — 实现 `MerchantNpcDraftApprovalGate.OriginalReviewPublishing`，注入进 gate。默认 `WaitingSourcePublisher` 必须保持失败。不要让 C06 拥有订单/发布原 writer。
2. **`CmsMerchantNpcController` / `NpcProfileMapper`** — 批准发布后把 published revision 落到既有 `npc_profile`（knowledge 白名单字段）。本组不改 Controller/Mapper。
3. **`ApiAiNpcController#merchantChat`** — 玩家消费仍走 `/api/ai/npc/merchant-chat`；仅在 published revision 存在且资格未撤销时投影。公开门店/主题 run 不得读私有经营草稿。
4. **`TopicRouteRuntimeServiceImpl` / `CmsTopicServiceImpl` / `RouteEffects`** — 主题主线在 NPC AI 不可用时继续；用已批准 copy，禁止假成功。本组不改这些文件。
5. **`AiGatewayServiceImpl`** — 不要加 public auth。C06 的 `refuseAiGatewayPublicAuth()` 已是 WAITING_SOURCE。
6. **JWT / RBAC / 生产 yml** — 不改。授权人核实是商家 scope 匹配，不是新角色真源。
7. **资格撤销** — 撤权后调 `MerchantNpcQualificationRevoke.revoke`，使 cache / 后续 consume 失效。不要另造第二套 store。

## 7. 未解决

- 生产接线：原审核票 / 发布 writer 尚未挂 Mapper（禁止本组改 Mapper）。
- 知识草稿 HTTP 路由不存在，保持 WAITING_SOURCE。
- 真实 App / MySQL / 并发 / CI 属 C12 与 D6，本组未跑。
- 不是 100%。

## 8. Parent addendum 2026-10-23 18:37 America/Los_Angeles

Timer deliveryCount=36 (01:30Z). WAITING_SOURCE cat-file still ABSENT.

Class-level `MerchantNpcDraftApprovalGateTest.unpublishedProjectionDoesNotOccupyPrivateScopesThenUniqueRetainPrivateApplies`：unpublished `playerProjection` 不得占 `privateScopes`；后续唯一 `retainPrivate` 才是第一次隔离占用，`publish` 清掉后投影恢复。Did not invent recoverOriginal on iOS/Java C06 isolation.

HEAD `ce76c9644`. Parent surefire `com.chengyinhub.business.service.npc.*Test` **22/0** 2026-10-24T01:37:16Z. Isolation 5/0. Gate 12/0. Revoke 4/0.

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 9. Parent addendum 2026-10-23 19:23 America/Los_Angeles

HEAD `e830db56a` tests. Did not invent recoverOriginal.

`MerchantNpcDraftApprovalGateTest.unusedVerifyBeforeIngestDoesNotOccupyThenUniqueIngestThenVerifyApplies`：未见草稿的 `verifyByAuthorizer` 抛 UNPUBLISHED 且 authorizer 仍空、phase 仍 IDLE；后续唯一 `ingestAiDraft` + `verifyByAuthorizer` 才是 AUTHORIZER_VERIFIED。

父进程独立 surefire（`/tmp/hy-c06`，2026-10-24T02:23:27Z）：

```
Tests run: 13 -- MerchantNpcDraftApprovalGateTest
Tests run: 5 -- C06AccountSwitchRestartIsolationTest
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 10. Parent addendum 2026-10-23 19:26 America/Los_Angeles

HEAD `df7fe0de7` tests. Did not invent recoverOriginal.

`MerchantNpcDraftApprovalGateTest.unusedSubmitBeforeAuthorizerDoesNotOccupyThenUniqueSubmitApplies`：未见授权的 `submitOriginalReview` 抛 AUTHORIZER_REQUIRED 且审核票仍空、phase 仍 IDLE；后续唯一 ingest+verify+submit 才是 ORIGINAL_REVIEW_PENDING。

父进程独立 surefire（`/tmp/hy-c06`，2026-10-24T02:26:07Z）：

```
Tests run: 14 -- MerchantNpcDraftApprovalGateTest
Tests run: 5 -- C06AccountSwitchRestartIsolationTest
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 11. Parent addendum 2026-10-23 19:32 America/Los_Angeles

HEAD `a894acd27` tests. Did not invent recoverOriginal.

`MerchantNpcDraftApprovalGateTest.unusedInvalidIngestDoesNotOccupyThenUniqueValidIngestApplies`：无效草稿 ingest 抛 INVALID_DRAFT 且 phase 仍 IDLE、draft 仍空；后续唯一合法 ingest 才是 AI_DRAFT。

父进程独立 surefire（`/tmp/hy-c06`，2026-10-24T02:32:04Z）：

```
Tests run: 15 -- MerchantNpcDraftApprovalGateTest
Tests run: 5 -- C06AccountSwitchRestartIsolationTest
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。

## 12. Parent addendum 2026-10-23 21:13 America/Los_Angeles

HEAD `d94ff575e`. Did not invent recoverOriginal. Did not publish, price, deduct stock, or issue rewards.

`MerchantNpcDraftApprovalGate.projectFromDraftQuery` 只读既有 `AiThemeDraftRequestService.read`（capability `AiThemeDraftRequestService.read`）。缺端口抛 `WAITING_SOURCE`。未见草稿 id 回读为 Java null，不占 `privateScopes`、不占 publish 账。已读到的草稿同样不占这两处。

父进程独立 surefire（`/tmp/hy-c06`，2026-10-24T04:13:01Z）：

```
Tests run: 16 -- MerchantNpcDraftApprovalGateTest
Tests run: 5 -- C06AccountSwitchRestartIsolationTest
Tests run: 4 -- MerchantNpcQualificationRevokeTest
Tests run: 1 -- C06FirstJourneyIsolationTest
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

App / HTTP / MySQL / CI = NOT_RUN。不是 100%。
