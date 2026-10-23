# FXD — Doc 2 O-12（D-08）AI 额度退款规则 + OPT-080 配置安全复核

- 基线：master `f099c5be9eb3ef22214ce81e80303eb851f417dc`
- 分支：`feature/doc2-fxd-20261023`
- 决策：D-08（owner，2026-10-23）
- SQL：**无变更**（没有新增或修改 migration；`ai_quota_reservation.nonce` 唯一键已满足按 traceId 去重）

## 1. 现状核对（master 已有的预占 → 退款链路）

| 环节 | 位置 | 行为 |
|---|---|---|
| 预占 | `AiGatewayServiceImpl.consumeQuota` / `RESERVE_QUOTA_SCRIPT` | 当日 key `ai:gen:limit:<pool>:<member>:<yyyyMMdd>` INCR；写收据 `ai:gen:reservation:<key>:<nonce>` = `generation\|used`；台账 `ai_quota_reservation` 先 PREPARED 后 RESERVED |
| 退款 | `refundQuota` → `cancelQuota` → `AiQuotaRecoveryService.requireRefund` | 独立事务写 REFUND_DUE，再按台账 `counter_key`（原日）跑 `COMPENSATE` Lua；收据置 `'0'` 墓碑 |
| 保留 | `retainQuota` → `recordOutcome(RETAINED)` | 只写台账，不碰 Redis |
| 补偿任务 | `AiQuotaRecoveryTask` → `runDue` | fence+claim 单领取；MODEL_STARTED 等未知结果判 UNCERTAIN，不退 |

## 2. D-08 三条规则与落实

### (a) 只退到原日，绝不退到之后的日子
已在 master 成立，本次补测试证明：
- 退款 key 只从**预占凭证 / 台账 `counter_key`** 解析，退款时**不再读 `LocalDate.now()`**；跨零点发现的失败仍 DECR 原日 key。
- 原日 key 已过期 / 被重建（`:generation` 不匹配）→ 不退、不重建、不碰新 key（`RESOLVED_NO_ACTIVE_RESERVATION` 或 `UNCERTAIN`）。
- 伪造凭证（别的 member / 别的池 / 非 8 位日期 / 非法 nonce）一律忽略。

### (b) 每次调用至多退一次，按 traceId 去重 —— **本次代码改动**
master 上 `generate()` 的预占 nonce 是独立随机 UUID，和 traceId 无关，"按 traceId 去重"无法从台账/收据核对。
改动（`AiGatewayServiceImpl`）：
- `generate()` 先预占，再用 `traceIdOf(reservation)` 取**预占凭证里的 nonce 作为本次 traceId**（玩家 theme_draft 不经通用池，仍用随机 traceId）；`consumeQuota()` 公开签名与行为不变（拍照等调用方不受影响，覆写 `consumeQuota` 的既有回归用例仍走同一入口）。
- 结果：收据 key、台账 `nonce`（唯一键 `uk_ai_quota_nonce`）、`ai_generation_log.trace_id` 三处同一身份。同一 traceId 的重复退款请求：Redis 收据 `'0'` 墓碑使 Lua 只 DECR 一次；台账 `recordOutcome` 只从 PREPARED/RESERVED 迁移一次、`claim` 的 fence 只允许一个领取者。
- 同一 traceId 被再次预占时，`RESERVE_QUOTA_SCRIPT` 的 `prior` 分支返回原值/拒绝，不会二次扣。

### (c) 内容安全拦截不退
已在 master 成立，本次补测试：
- `INPUT_BLOCKED` → `retain(receipt,"INPUT_REJECTED")`；`OUTPUT_BLOCKED` → `retain(receipt,"OUTPUT_REJECTED")`；两条都不调用 `requireRefund`、不跑退款 Lua。
- 台账已 RETAINED 后，任何迟到的 `requireRefund` 都被拒（`recordOutcome` 0 行 + 状态不符 → 抛错，被 `cancelQuota` 吞掉记 warn），Redis 不动。

### 既有语义（不改，记录在案）
| 场景 | 处理 |
|---|---|
| 内容审核服务不可用（输入侧，MSG_BUSY/UNAVAILABLE 或异常） | 退（SERVICE_FAILURE），未调模型 |
| 选模型失败 / 配置缺失 / MODEL_STARTED 写台账失败 | 退（SERVICE_FAILURE），未发 HTTP |
| provider 调用异常/超时/空返回 | **不退、不保留**：抛 `AiProviderOutcomeUnknownException`，台账停在 MODEL_STARTED，补偿任务判 `UNCERTAIN/PROVIDER_OUTCOME_UNKNOWN`（可能已产生上游计费） |
| 输出审核不可用（拿到了模型结果） | 保留（OUTPUT_REVIEW_UNKNOWN） |
| cache-only / 成功 | 保留（CACHE_RESULT / SUCCESS） |
| 超限 | 保留（RATE_LIMITED） |
| 玩家 theme_draft | 不经通用池，由 Creator 三次独立池计费 |

## 3. 测试证据

新增 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiQuotaRefundRulesD08Test.java`（11 例）：
traceId=nonce、重复退款同一收据、INPUT/OUTPUT_BLOCKED 不退、provider 未知不退、跨零点只退原日（台账路径与 Redis-only 路径）、伪造凭证拒绝、补偿只用原日 key、同 traceId 至多补偿一次、已保留收据不能再退。

本地（`LANG=C.UTF-8`）：
```
mvn -pl chengyinhub-admin test -Dtest='AiQuotaRefundRulesD08Test,AiGateway*Test,AiQuotaRecoveryServiceTest,PhotoCheck*Test,
  AiQuotaReservationRedisIT,AiCreatorServiceImplTest,NpcChat*Test,AiTopicPlanServiceImplTest,AiTemplateFillTest,
  AppMediaRejectPathAcceptanceTest,AiSceneRouterContractTest,OWN_C0*Test'
Tests run: 422, Failures: 0, Errors: 0, Skipped: 12 — BUILD SUCCESS
```
全量 `mvn -pl chengyinhub-admin test`（13717 例）本地仅剩 `business.it.*MyBatisTest` 因本机无 MySQL（`Communications link failure`）报错，与本改动无关，CI 有库。
其中 `AiQuotaReservationRedisIT`（15 例，真 Redis Lua，含 `concurrentRefundsDebitOnlyOnce`、`actualRefundUsesOriginalDayAndRejectsOtherMemberOrPool`、`missingOldDayDoesNotRecreateAndReplayDoesNotTouchLaterCounter`）用本机一次性 redis-server（127.0.0.1，非生产）跑通。
MySQL IT：本次无 SQL 变更，未新增；`AiQuotaRecoveryMySqlRedisIT` 未改动（需专用环境门控）。

## 4. OPT-080 AI 配置安全复核（只报告，未改）

| # | 发现 | 结论 |
|---|---|---|
| 1 | `ai.model.*` / `ai.vision.*` / `ai.providers.*`（provider、baseUrl、model、apiKey、dailyLimit、npcDailyLimit、photoDailyLimit、timeoutMs）全部来自 `application.yml` + 环境变量，`@Value`/`@ConfigurationProperties` 启动期绑定；**没有任何运行期写入口**（不读 `sys_config`，无后台编辑接口） | 非管理员无法改模型/端点/额度。✅ |
| 2 | 小程序 `/api/ai/*` 请求 DTO 不含 model/provider/baseUrl/systemPrompt/bizType/limit 字段；bizType 与 systemPrompt 全在服务端硬编码（`AiCreatorServiceImpl`、`NpcChatService`、`AiTopicPlanServiceImpl`），角色经 `resolveRoleFor` 服务端解析 | 客户端不能选模型或切池。✅ |
| 3 | apiKey 只在 LLM/语音客户端 Authorization 头使用，未在任何 controller 返回 | ✅ |
| 4 | `feature.flag.roamNpcEvent/roamNpcChat` 在 `sys_config`，走若依参数管理（`system:config:edit` 权限，管理员） | 仅管理员可改；合规风险已在 migration 注释说明。✅ |
| 5 | `AiGenerationLogController`（含 `/snapshot/{traceId}`）有 `business:ailog:*` 权限注解 | ✅ |
| 6 | `npc_shop_chat` / `merchant_npc_chat` 在 `rateGroupOf` 中落入 **gen（创作）池**，而 `npc_chat` 走 npc 池 | 待 owner 确认：是否有意让店铺 NPC 对话吃创作额度（玩家聊店铺 NPC 20 次会耗光创作额度）。属产品决策，不是明确 bug，**未改** |
| 7 | 额度上限无下界校验（配置成 0 = 全拒；负数会让预占 Lua 报 `invalid quota arguments`，调用失败） | 仅部署配置可触达，建议启动期校验，未改 |
| 8 | traceId 现在同时是预占 nonce，会出现在返回给前端的 traceId / 错误文案中 | 无公开接口可按 traceId 退款（refundQuota 仅服务内部调用，且校验 member/pool/day），不构成可利用面 |

## 5. 未做 / 边界
- 未改鉴权/RBAC/JWT/Redis 配置，未引入依赖，未动既有 migration，未接触生产。
- 部署需用户操作（push ≠ 部署）。
