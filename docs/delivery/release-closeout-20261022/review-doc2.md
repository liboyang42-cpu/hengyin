# RV3 独立评审 — Doc2 第二批 PR（#513–#527）

- 评审人：RV3（只读：未对任何 PR 推送、评论、批准、合并或关闭）
- 日期：2026-10-22；基线 master `38dc1bb7`
- 方法：逐 PR `git diff origin/master...prNNN` 读代码并追调用链；重点结论由主评审二次核对源码（标 ✔ 者）。本地 Maven 离线无法解析 Boot 4.1.1 BOM，**Java 单测未在本机跑**；#520 小程序新增单测 `node --test` 7/7 通过；F1 三条 lane 合并树上跑过 `migration_gates.py sql/dep-order`、`migration-safety-check.sh`、`dup_create_table_gate.py`、`role_permission_contract.py`，均通过。
- 评审头（PR head）：513 `5cd520be` · 514 `6e746b30` · 515 `458995f7` · 516 `525c4342` · 517 `57a5e55c` · 518 `b8c08c19` · 519 `1be14852` · 520 `0fe2c513` · 521 `92aa3703` · 522 `666f5b63` · 523 `852f3782` · 524 `f82e1c87` · 525 `c4f434f4` · 526 `d87be57f` · 527 `9982d242`

## 总览

| 严重度 | 数量 |
|---|---|
| blocker | 2 |
| should-fix | 25 |
| nit | 45 |

**Blocker**
1. **#519** city_code 回填按「地址任意位置含城市名」匹配，南京西路(上海)→南京、北京路(广州)→北京；只填 NULL 规则让错值永久固化，且仓内无预览 SQL。✔
2. **#527** F3 Phase 1 迁移改动资金表 `settlement_claim` 的唯一键（DROP+ADD `uk_settlement_claim_key` 加 `unit_code`），Phase 1 无任何消费者，属 Phase 2 资金闸放宽，越出 Phase 1 范围。✔

**按 PR 汇总**（B/S/N = blocker/should-fix/nit）

| PR | 主题 | B | S | N | 开关默认 OFF | 结论 |
|---|---|---|---|---|---|---|
| #513 | DL1 savepoint 死锁重试 | 0 | 1 | 0 | n/a | 1213 正确、恰好一次成立；1205 应收窄 |
| #514 | F1 会话生命周期 | 0 | 0 | 2 | ✔ | 可合 |
| #515 | F1 计分 | 0 | 2 | 1 | ✔（费率 0） | 费率 >0 前必须修 S |
| #516 | F1c 反作弊 / POI 身份 | 0 | 3 | 3 | ✔，无精度→放行保留 | shadow/enforce 前修 S |
| #517 | F1 地理/AR/队伍 | 0 | 2 | 3 | ✔ | 可合，回滚注释须改 |
| #518 | O15 revenue_share | 0 | 1 | 1 | n/a | 未交付 revenue_share 校验 |
| #519 | O14 搜索投影/推荐 | 1 | 2 | 4 | ✔（端点 503、job 暂停） | 回填迁移须改 |
| #520 | 局恢复服务端时钟 | 0 | 1 | 3 | 无开关 | 存量未来时间戳需兜底 |
| #521 | F5 剧情词表 | 0 | 3 | 2 | 配置驱动 | 可合，ADD_ITEM 发布闸须补 |
| #522 | CriticalPath 观测 | 0 | 1 | 3 | 无开关（失败安全） | 可合 |
| #523 | O8 私密位置读路径 | 0 | 1 | 3 | n/a | 附近地图 club 活动仍泄露坐标 |
| #524 | F2 角色卡/属性定义 | 0 | 2 | 3 | 配置驱动 | 先迁移后部署须硬闸 |
| #525 | F7 发票 + 未成年 | 0 | 3 | 7 | ✔ | 开 minorPaymentGate 前修 S |
| #526 | F4 商家台 | 0 | 1 | 5 | ✔ | 可合 |
| #527 | F3 自由探索 Phase 1 | 1 | 2 | 4 | ✔ | 拆出 settlement_claim 迁移；开 sale 前修 S |
| 跨 lane | — | 0 | 0 | 1 | — | — |

---

## 1. 资金

### #513 DL1（资金）

- **S-513-1 should-fix：1205 锁等待超时也被抛出，但绝大多数调用方没有 `@DeadlockRetry`。** ✔
  - 位置：`SavepointLockFailures.java:23-29,63,76`（1205→`CannotAcquireLockException` 照抛）；调用点 `MerchantReferralBountyService.java:129/299/420`、`CommunityPostServiceImpl.java:1378`、`GameSessionRuntimeServiceImpl.java:3560`、`OfficialEventV2ServiceImpl.java:1319`、`WxContentSecurityServiceImpl.java:937`、`ThemeBroadcastService.java:33`。`@DeadlockRetry` 只在 `CmsRegistrationServiceImpl.java:3229`（redeemExploreChapterAndRecord）与若干 ApiPlayProgressController 入口。
  - 故障：1213 会整笔回滚事务，抛出是对的；但 1205 在 `innodb_rollback_on_timeout=OFF`（默认）下只回滚单条语句，savepoint 仍有效。仓库自己就依赖这一点（`ApiPlayProgressController.java:2866-2871` 注释、`NodeMedalClaimTest.lockWaitTimeoutMustBeSwallowed_soCheckinSurvives`）。合入后，赏金侧一次 1205（邀请人余额行被持锁 >50s）会让以下操作整单失败且不重试：商家整单核销 `verifyAndSettle*`（`CmsRegistrationServiceImpl.java:2833-2930`）、退款 `RefundServiceImpl.markRegistrationRefunded`（`onRefund`）、支付查询 REFUND 分支、内容下架；广播/局批处理一次 1205 会中断整批，而不是只标记单条 FAILED。
  - 最小修复：仅当 savepoint 确已失效时抛出（1213 / SQLState 40001 / 包着锁失败的 `TransactionSystemException`），1205 维持原吞掉；删去测试 `lockWaitTimeoutInsideSavepointAlsoGoesToOuterRetry` 的该期望。可选：给最外层 `verifyAndSettle*` 加 `@DeadlockRetry`。
- 已核对 OK：
  - 只匹配 `DeadlockLoserDataAccessException`、`CannotAcquireLockException`，以及 1213/1205/40001 的原始 `SQLException`；不匹配宽泛 `DataAccessException` 或 `DuplicateKey`。
  - cause 链遍历用 identity seen 集合，有界。
  - 7 处调用都把 helper 放在 catch 首句，非锁异常走原路径不变。
  - 恰好一次：只在最外层重试。另有 `markPaidIfPending` CAS、`uk_merchant` 唯一键、余额 `FOR UPDATE` 三道保护；真 MySQL IT 断言只入账一次。
  - CI 只新增 IT。

### #527 F3 自由探索 Phase 1（资金相邻）

- **B-527-1 blocker：`migration_doc2_f3_settlement_claim_unit_20261022.sql:33,43` 改资金表唯一键，超出 Phase 1 范围。** ✔
  - 现状：`ALTER TABLE settlement_claim DROP INDEX uk_settlement_claim_key, ADD UNIQUE KEY ... unit_code`。
  - 故障：当前写入方都写 `''`，语义暂时不变。但一旦任何代码写入非空 `unit_code`，同一 fulfillment/beneficiary 就能产生多条结算 claim，即放宽了重复计提的唯一闸。Phase 1 代码没有任何地方使用该列。按 AGENTS.md，这也属于线上 DDL，须经 chengyin-db-migrate 预检和用户确认。
  - 最小修复：从本 PR 移除该迁移及其依赖登记，留到 Phase 2 与计提代码一起评审；若坚持保留，须附预检报告并取得明确批准。
- **S-527-1 should-fix（开 `sale-enabled` 前必须修）：多票单冻结的 `paid_amount` / `unit_value` 错误，且不可更正。** ✔
  - 位置：`CmsRegistrationServiceImpl.java:622` 在 `createRegistrationInternal` 内冻结；多票循环 `:747-753` 对非首行传 `overridePayable=0`，首行要到 `:762` 才被改成整单金额，此时冻结已经发生；`FreeExploreBundleServiceImpl.java:137-146` 只插入不更新。
  - 故障：非首行冻结为 ¥0；首行冻结的是行价，而非积分抵扣后的整单额。`refundQuote`（`FreeExploreBundlePolicy.java:90-97`）因此展示错误的「原路全额」，Phase 2 也会以此为退款/结算基数。
  - 修复：改到 `:762` 之后按整单额分摊冻结（或对 `parentOrderNo != null` 的行延后冻结），并补多票单测试。
- **S-527-2 should-fix：`decideMerchantExit` 缺已支付校验，`REFUND_PENDING` 永不被清理。**
  - 位置：`FreeExploreBundleServiceImpl.java:542-546` 只做 `requireOwnOrder`（对比 `reserve` 的 `:437`）；`ExploreBundleMapper.xml:193-198` 的 `refundOpenUnits` 只清 AVAILABLE/RESERVED/HELD。
  - 故障：单位在下单时（尚未支付）即已发放，持有人可以对未支付订单发起退出，生成 `REFUND_UNFULFILLED` 退出单。订单超时关闭或整单退款后，单位永久停在 `REFUND_PENDING`，形成幽灵退款待办台账。
  - 修复：`decideMerchantExit` 加 `requirePaid(...)`；`refundOpenUnits` 纳入 `REFUND_PENDING`。
- Nit：
  - N-527-1 锁顺序相反：兑换/退出是先 unit 后 reservation（`:218→230`、`:542→550`），取消/爽约是先 reservation 后 unit（`:476→481`、`:508→520`），并发时会死锁。修复：统一先锁 unit。
  - N-527-2 同幂等键并发 `reserve` 在 `:433` 查键之后才在 `:439` 加锁，败者得到 409 而非重放结果。修复：加锁后再查一次键。
  - N-527-3 商家视角预约列表（`ExploreBundleMapper.xml:211-215,240-246`）回传持有人的 `idempotency_key` / `request_fingerprint` / `registration_id`。修复：商家视图收窄列。
  - N-527-4 设计问题：`SWAP_CONSENTED` 分支对 AVAILABLE 单位永远无法成立（`:556` 与 `:566` 条件互斥）；`insertWaiverIgnore` 用 `INSERT IGNORE` 会把非重复键错误也吞成警告；账本迁移的列指纹依赖 MySQL 版本（`int` vs `int(11)`），须在生产版本上确认。
- 已核对 OK：
  - Phase 1 不动钱：mapper 只写 `explore_bundle_*`；爽约、没收、退出不写 redemption / payable / settlement；`refundQuote` 只读；PerkLedger 钩子位于 redemption 之后、`accrueOnRedemption` 之前，处于同一事务，计提逻辑本身未改。
  - 开关：`FreeExploreBundleSwitches.java:26-28` 全部默认 `:false`。开关关闭时各钩子首句即返回、零写入，老流程不变。
  - IDOR：订单、退款报价、预约、取消、退出都校验持有人；回执查询 WHERE 绑定 `holder_member_id`；商家端走 `merchantAccessService.require(VERIFY)`。
  - 恰好一次：订单、单位、预约（`active_unit_key`）、豁免、退出均有唯一键，状态迁移为 `FOR UPDATE` + version CAS。
  - 账本迁移幂等、非破坏。CI 只新增 IT。

### #518 O15 revenue_share（资金相邻）

- **S-518-1 should-fix（范围）：本 PR 并未做 revenue_share 校验。**
  - 提交 `d6207e9e` 自述「revenue_share 规则…本次不落校验」。实际改动是在 5 处 `insertProductRole` 前加 `ProductSlot.requireValid(slot)`（`ProductRoleServiceImpl` 约 65/141/180/257 行，`TopicEditionReopenServiceImpl` 约 276 行），入参都是常量，永不失败。
  - 风险：O-15 被误记为已交付。修复：PR 改名/改范围，O-15 revenue_share 保持 open。
- N-518-1：约 65 行的 guard 位于 try/catch 之外。由于不会抛出，无害。
- 已核对 OK：不改任何金额字段，mapper 未动。

### #522 CriticalPath 观测（触及 Refund/Settlement/Payout/支付回调）

- **S-522-1 should-fix：嵌套 `@CriticalPath` 方法会对随后被外层重试成功的死锁报 FAULT。**
  - 位置：`CriticalPathConfig` 的 order 是 `DeadlockRetryConfig.ORDER-2`，只对自身挂 `@DeadlockRetry` 的方法有效。`PerkLedgerServiceImpl.redeemChapter`（约 380 行）、`SettlementEntryServiceImpl.recordPaidRegistration` / `recordOnPayment` 作为内层参与者运行，第一次 1213 时就记 ERROR FAULT，与 javadoc「重试后成功不发事件」不符，会产生误报警。
  - 修复：在 `TransactionSynchronizationManager.isActualTransactionActive()` 且为锁失败时跳过上报（由外层边界负责上报）。
- Nit：
  - N-522-1 `CriticalPathInterceptor.invoke` 里的注解查找位于 `catch (Throwable)` 内且未加保护，查找一旦抛出会替换原业务异常。修复：查找与上报单独 try/catch，之后始终 `throw failure`。
  - N-522-2 没有关闭开关。当前失败安全：`CriticalPathEvents.failure` 吞掉 RuntimeException/LinkageError。
  - N-522-3 PR 夹带了无关的池恢复 CI 改动和 `CapacitySizingConsistencyTest`。这些改动加强而非削弱闸门：恰好一个 `pool-recovery.json`，且 result 断言为 `PASS_BOUNDED_...`。
- 已核对 OK：
  - 拦截器只做 proceed → catch → 上报 → 原样抛出：不吞异常，不改返回值，不改事务次序。
  - `markPayoutResult` 仍是 `REQUIRES_NEW`；`RefundPayoutServiceImpl` 新变成 CGLIB 代理，经核对安全。
  - 日志与指标只含枚举码、kind 和异常类名，无 PII/金额；标签基数固定为 7×2。

## 2. 新端点归属/范围（IDOR）

- #514/#515/#517、#525、#526、#527、#524、#520 的所有新端点均从 token 取 memberId，SQL WHERE 绑定 member/merchant/holder。admin 端点全部带 `@PreAuthorize`（`role_permission_contract.py` 通过）。**未发现 IDOR。**
- 关键证据：
  - #514 `OrientSessionMapper.xml` 全部为 `where id=? and member_id=?`。
  - #515 `lockOwned/readOwned` 对「不存在」和「非本人」返回同一应答。
  - #517 配置/审核走 `OrientOwnership`，且禁止自审。
  - #525 发票 SQL 全部带 `member_id`；监护撤销校验 `isParty`，UPDATE 带双方 id。
  - #526 每条 XML 都绑定 `merchant_id` 或 `owner_member_id`（`MerchantDeskMapperSqlTest` 钉住）。
  - #524 `PUT /api/topic/state-keys/{key}` 依次过 `editableDenial` → `editWindowDenial` → 内容安全，再按 `topic_id AND state_key` 更新。

## 3. 隐私（未成年 / 发票 / 搜索投影 / 私密位置）

### #525 F7 发票 + 未成年

- **S-525-1 should-fix（开 `minorPaymentGate` 前必须修）：没有任何会员端可写 `birthday` 的入口，年龄 UNKNOWN 一律拒绝。** ✔
  - 位置：`MinorProtectionServiceImpl.java:88-89,162-163`、`MinorGuardianServiceImpl.java:85-86`；`ApiUmsMemberController` 的资料更新不含 birthday；main 代码中没有 controller 调用 `setBirthday`。
  - 故障：开闸后几乎所有会员（包括成年人）在 `/api/registration` 下单/支付时都会收到 403 `MINOR_AGE_UNKNOWN`，也无人能成为监护人。补上入口后，生日又成了自报字段，未成年可以填成年日期绕过。
  - 修复：先提供受限的生日设置流程（只能设一次或变更留审计），或让 UNKNOWN 在结算时引导填写而非硬拒；在 PR/runbook 写明启用前置条件。
- **S-525-2 should-fix：支付闸只挂在报名结算一处。**
  - 位置：只有 `RegistrationCheckoutServiceImpl.java:65,85` 挂了闸；`ClubMembershipServiceImpl`、`MerchantCommerceService`、`CmsTopicServiceImpl`、`TeamGroupServiceImpl`、`CoopInviteServiceImpl` 等微信支付入口未挂。
  - 故障：开闸后未成年仍可购买俱乐部会员、商家商品、主题、团单。
  - 修复：所有会员端预下单都调用 `enforcePurchaseIfEnabled`，或在 PR 中明确「仅覆盖报名」。
- **S-525-3 should-fix：已申请/已开票的订单仍可退款。**
  - 位置：资格只在申请时校验（`UserInvoiceMapper.xml:109-125`）；退款代码不读 `user_invoice_application_order`；`markIssued`（`UserInvoiceServiceImpl.java:216-223`，`UserInvoiceMapper.xml:206-209`）也不复核退款状态。
  - 故障：用户先申请发票再退款，财务照常开票，结果是已退款的钱对应一张已开发票，需要红冲。
  - 修复：退款创建时，若存在 active 发票行则拒绝或标记；`markIssued` 前复核资格。
- Nit：
  - N-525-1 `markIssued/markRejected` 无 controller，申请会一直停在 SUBMITTED。若属有意，须在 PR 中写明。
  - N-525-2 `UserInvoiceServiceImpl.java:156-158` 把所有 `DuplicateKeyException` 都当作 REQUEST_IN_PROGRESS，包括申请单号碰撞。修复：单号碰撞时重新生成并重试。
  - N-525-3 `:121-122` 重放只比对订单集合，换了 `titleId` 的重放会静默返回旧申请。
  - N-525-4 `ApiUserInvoiceController.java:53` 在未传 `isDefault` 时置 0，编辑默认抬头会丢失默认标记。
  - N-525-5 `InvoiceIssuer.Request` 是 record，自动生成的 `toString` 含明文税号。修复：覆写 `toString` 打码。
  - N-525-6 `countActiveBetween`（`MinorGuardianAuthorizationMapper.xml:99-104`）接受任意 purpose 的授权。
  - N-525-7 `createInvite` 没有频率限制。
- 已核对 OK：
  - 税号与抬头经 `PiiCryptoTypeHandler` 做 AES-256-GCM 落库加密：读写 resultMap 全覆盖，密钥取自 `${chengyin.pii.key}`，无密钥拒写；没有明文列、明文索引，也没有基于税号的唯一键；IT 断言 `v1:` 前缀。
  - 响应只返回 `taxNoMasked`（前 2 位 + `******` + 后 4 位），新代码不打日志。
  - 一单不两开：`uk_user_invoice_order_active(order_source, order_id, active_key)` 保证；金额由服务端按 `wechat_payment_amount` 汇总。
  - 未成年数据最小化：不存身份证、照片、生物特征、手机号；年龄只取 `ums_member.birthday`；邀请 token 只存 SHA-256；授权截止到 18 岁。
  - 开关：缺 key、空值、读取异常均视为 OFF。关闭时 `ApiRegistrationController` 与 master 行为一致（只多两次带缓存的 `sys_config` 读取）。
  - 迁移只有 `CREATE TABLE IF NOT EXISTS` + 形状断言，测试未被削弱。

### #519 O14 搜索/推荐投影

- **B-519-1 blocker（数据迁移）：`migration_o14_content_city_code_backfill_20261022.sql:17-40` 的 city_code 回填启发式会写错，且错值固化。** ✔
  - 现状：`address_name LIKE CONCAT('%', keyword, '%')` 匹配 15 个城市名，`HAVING COUNT(DISTINCT city_code)=1`，只填 NULL。
  - 故障：「南京西路1266号」（上海）只命中「南京」，被写成 320100；北京路（广州）→110100，苏州河（上海）→320500，西安路、成都路、重庆路同理。之后「只填 NULL」使错值无法被重跑修正，并进一步影响搜索 city_code 过滤和推荐同城 +0.2。尾部断言只校验 6 位数字格式，且全量跳过时也恒为真。「只读预览」仅写在 PR 描述里，不在仓内。
  - 最小修复：改为前缀或显式城市 token 匹配（如 `address_name LIKE CONCAT(k.keyword,'%')` 或 `CONCAT('%', k.keyword, '市%')`），或排除后接 路/街/河/大道 的命中；把同一 JOIN 的只读预览 SELECT 作为 `_precheck_*.sql` 提交；上线前先在副本跑预览并人工抽检。
- **S-519-1 should-fix：推荐读路径的活体复核弱于候选池构建时的闸。**
  - 位置：`RecommendationScoreDailyMapper.xml` `selectCandidateItems`（活动约 L74-99，主题 L101-122）、`RecommendationServiceImpl.java:86`。
  - 故障：读取时不复核活动 `cancel_time IS NULL`、俱乐部已删除、主题 `primaryCirclePublicGate`。行按 `max(stat_date)` 最多服务 7 天，于是已取消的活动或已删除俱乐部的内容仍会被推荐，而 master 所有公开列表都会隐藏它们。
  - 修复：改为 `exists (select 1 from cms_activity where id = s.candidate_id and <include activityStaticPublicGate/>)`，主题同理。
- **S-519-2 should-fix：搜索返回投影快照中的标题/封面，并按旧文本匹配。**
  - 位置：`ContentSearchMapper.xml:124,130`。
  - 故障：作者从标题中删掉手机号等个人信息后，按旧文本仍能搜到，且展示旧标题。读开关与刷新 job 是分别启用的，快照可以无限陈旧。
  - 修复：`title`/`img_url` 改为联表实时读取；或在 `max(indexed_at)` 超过 N 分钟时让端点拒绝服务，并把「启用读端点须同时运行刷新 job」写入 runbook。
- Nit：
  - N-519-1 `search_text` 上的前导通配 LIKE 加逐行 EXISTS，`pageNum` 无上限。修复：加上限或限流。
  - N-519-2 推荐不过滤 `im_block`（与 master 公开列表一致）。
  - N-519-3 搜索帖子分支未应用 NOT_INTERESTED / SEE_LESS_*。
  - N-519-4 `escape '\\'` 依赖 NO_BACKSLASH_ESCAPES 关闭（默认即关闭）。
- 已核对 OK：
  - `topicStaticPublicGate` / `activityStaticPublicGate` 与 master 的 `publicTopicListWhere`、`selectPublicCmsActivityList` 等价，`club_member_only=1` 直接排除（fail-closed）。
  - 帖子分支要求 PUBLISHED + PUBLIC + `search_eligible` + `community_search_document` VISIBLE。
  - 读路径对每行复核活体闸；`hideStale*` 会置 HIDDEN 并清空文本。
  - 不返回精确坐标或详细地址。
  - 无 `${}`，关键词经转义并限 50 字。
  - 端点默认 503；两个 `sys_job` 均为 `status='1'` 暂停。
  - 迁移幂等、可加列。
  - `O14MigrationContractTest` 是本 PR 新增文件，「取第一个命中块」是在修正配对错误，不构成削弱。

### #523 O8 私密位置读路径

- **S-523-1 should-fix：附近地图仍对非成员返回 club 活动坐标。**（问题早于本 PR，但正处在 O8 应收口的路径上）✔
  - 位置：`RoamHangoutMapper.xml` `selectNearbyActivities`（约 L101-126）不判断 `a.club_id`；`RoamHangoutServiceImpl.withoutMemberOnlyTopics` 只过滤 `club_member_only` 主题。
  - 故障：带 `club_id` 的活动在详情页（`ApiActivityController:1167`）对非成员显示「加入后查看」，但附近地图仍返回其 `latitude/longitude/addressName`。私密俱乐部（`public_visible=0`）下非 member-only 的主题同样可见。
  - 修复：在 SQL 中对 `a.club_id > 0` 套用同样的成员闸；或在 O8 台账中明确列为遗留项。
- Nit：
  - N-523-1 过滤发生在 LIMIT 之后，结果条数偏少。
  - N-523-2 discover 的 `canViewClub` 存在 N+1 查询。
  - N-523-3 夹带了两个无关的 O10 测试文件。
- 已核对 OK：
  - `clubMemberOnlyGate` 要求 `status=1 and del_flag=0`。
  - `/nodes` 对非报名者要求 `selectCmsTopicDetailById(tId, memberId)` 非空。
  - CreativeSquare 对非作者去除地址和坐标。
  - 三个既有测试只新增了 stub，未删改断言。

### #516 F1c 守卫日志隐私

- **S-516-2 should-fix：`play_arrive_guard_log` 无保留期。**
  - 每行含 member_id + 节点坐标 + 毫秒时间戳。玩家必在节点 50 m 内，因此在 shadow/enforce 模式下等同约 50 m 精度的个人轨迹。
  - 修复：PASS 行保留 30 天（对齐 `migration_20261012_roam_location_retention.sql`），REJECT 行只保留到申诉期结束。

## 4. 开关默认 OFF / 关闭时行为不变

| PR | 代码默认 | 种子 | 关闭时 |
|---|---|---|---|
| #514 | `feature.flag.orientSession` 缺失或异常→false | 不种 | 一致 |
| #515 | `orient.scoring.enabled` 缺失→false；费率回落 0 | `'false'`，费率 0 | `decorateLeaderboard` 先返回，`scoreCenti` NON_NULL 不下发 |
| #516 | 模式 OFF（null/未知/异常） | `off`/`false`/`false`，WHERE NOT EXISTS | **无精度→放行保留** ✔；守卫只在原有坐标/精度/50 m 围栏检查之后执行；表异常时放行 |
| #517 | 4 个 flag 缺失→false（`flagOn` 接受 `"1"`、不 trim，与 #514/#515 不一致，记 nit） | `'false'` | 一致 |
| #519 | `chengyin.search.unified.enabled:false`→503 | job 暂停 | 一致 |
| #525 | 缺失、空、异常→OFF | — | 一致 |
| #526 | 仅 `"true"` 为开 | `'false'`，不覆盖 | 404 `F4_DISABLED`，在任何服务调用之前 |
| #527 | 三个开关 `:false` | 无 yml | 钩子首句返回 |
| #520/#522 | **无开关**：#520 服务端时钟无条件生效（老版本小程序兼容）；#522 失败安全 | — | 见各自条目 |

### #516 其余 should-fix

- **S-516-1 should-fix（`mode=enforce` 前必须修）：返回的申诉入口不可用。**
  - 位置：`ArriveLocationGuard` 的 `APPEAL_BIZ_TYPE="play_arrive_guard"`；`AuditTaskServiceImpl.submitAppeal`（约 1193 行）的 `isContentDecisionType` 不接受该类型，且要求存在 REJECTED 的 AuditTask。
  - 故障：玩家按提示申诉，得到 400「申诉参数不完整」。
  - 修复：注册该 bizType 的处理逻辑（owner 取守卫日志的 member_id，仅 REJECT 可申诉），或在此之前返回 `appealable=false`。
- **S-516-3 should-fix：POI 回填「可重跑」并不总成立。**
  - 位置：`migration_member_visited_poi_poi_key_backfill_20261022.sql` 的 rank/UPDATE。
  - 故障：首跑之后，开关为 false 的旧路径继续写入 `poi_key=NULL` 的新行；重跑时，某个落在地标 50 m 内的新行拿到 `rn=1` → `poi:{id}`，与该会员已有键冲突，触发 `uk_member_poi_key` 重复，迁移报错中断（单语句回滚，不坏数据）。
  - 修复：UPDATE 加 `NOT EXISTS (… x.member_id=v.member_id and x.poi_key=CONCAT('poi:',b.landmark_id))`，否则回落到 `pt:` 键；同时把去重预览 SELECT 提交入仓（当前写的是「见 PR 描述」）。
- Nit：
  - N-516-1 `stable_identity=true` 后，地标 50 m 内的不同店铺会合并成同一地点，需产品确认。
  - N-516-2 同一会员并发到达可同时通过节流（已有 `@RateLimiter` 兜底）。
  - N-516-3 `TopicRouteCandidatePolicy` 的地球半径从 6371008.8 改为 6371000，相对差约 1.4e-6。
- 已核对 OK：其余距离计算都是同一 haversine（R=6371000），参数顺序正确；POI 加列迁移 information_schema 守卫幂等，唯一键对存量全 NULL 安全；回填不删行、不改 `geo_key`；依赖 `roam` / `member_visited_poi` 存在。

### #520

- **S-520-1 should-fix：存量的未来 `saved_at`（旧客户端时钟）会屏蔽所有服务端时间戳写入。**
  - 位置：`PlayerRunSessionMapper.xml` 未改。`upsertPaused` 只在 `values(saved_at) > saved_at` 时覆盖，`markEnded` 只在 `saved_at <= #{savedAt}` 时生效。
  - 故障：手机时钟快一年时写下的行，之后的保存/结束全部静默 no-op（接口仍返回 200），「已结束的局又回来」的问题对这些存量行依旧存在。
  - 修复：条件加 `or saved_at > #{savedAt} + 300000`；或一次性迁移把 `saved_at > now` 的值钳到 now。
- Nit：
  - N-520-1 无开关。
  - N-520-2 离线暂停仍使用手机时钟。
  - N-520-3 `adoptServerSavedAt` 只按 `elapsedSeconds` 匹配本地记录。
- 已核对 OK：memberId 只取自 token；测试只把期望值改成服务端时钟，未删断言；小程序新增单测 7/7 通过。

## 5. 迁移（幂等 / 非破坏 / 跨 lane 冲突）

- 名称、表、config_key 冲突：15 个 PR 的迁移文件名、`CREATE TABLE` 表名、`sys_config` 键、mapper namespace 两两不重复（已逐一 grep）。
- 文本冲突（`git merge-tree` 两两检查）：只有 `ci/migration-dependencies.json`（514/515/516/517/519/524/525/526/527 都在同一位置追加）和 `ci/backend-test.sh`（513/524/527 都在 `MYBATIS_ITS` 尾部追加）。都是并集即可解决的追加冲突，Java 文件无冲突，#521×#524 合并树干净。
  - **N-X-1 nit**：按合并顺序逐个解冲突，解完重跑 `migration_gates.py dep-order`。
- 破坏性：除 B-527-1（资金表唯一键 DROP+ADD）外，其余迁移都只加表/加列/加索引，且有 information_schema 或形状守卫。
- **S-517-1 should-fix：#517 迁移头的回滚注释 `DELETE FROM sys_config WHERE config_key LIKE 'orient.%'`（第 8 行）会连带删掉 #515 的 5 个 `orient.scoring.*` 键。** ✔ 修复：改为按名逐个删除 F1d 的 4 个键。
- **S-524-1 should-fix：#524 新 jar 依赖迁移中新增的列（`ThemeStateKeyMapper.xml` 的 `selectThemeStateKeyVo` / `upsertThemeStateKey`）。**
  - 故障：迁移滞后时，凡带运行时状态的主题，开局（`seedCharacter` → `selectByTopicId`，3 条创建路径）一律报 BadSql；发布同步、state-key 列表、角色卡也会失败。
  - 修复：在 PR 正文和部署清单中把「先迁移、验收，再部署 jar」设为硬闸；可选把 `seedRun` 包成失败降级。
- N-514-1 #514 的 `ADD COLUMN ... AFTER status` 在 MySQL < 8.0.29 上会重建表，预检时须确认版本。

## 6. 测试/门禁是否被削弱

- 未发现被删除、跳过或放松的断言，也未发现新增的 `@Disabled`/assume。
- 既有测试的改动都是补 stub、补迁移或改服务端时钟期望：#523 三个测试、#524 三个 IT 与两个单测、#520。
- CI 改动均为加强：#513、#524、#527 向 `MYBATIS_ITS` 新增 IT；#522 新增池恢复回执断言；#524 的 HTTP e2e 计数 +1 与新增的 1 个 `@Test` 对应。
- #517 的 `.gitleaksignore` 三条指纹指向旧键名 `orient.geo.wgs84.enabled`，不是密钥。

## 7. 其余 should-fix / nit（玩法 lane）

### #515 计分

- **S-515-1 should-fix（费率 >0 前必须修）：每局只结算一次，但重开局可反复领积分/XP。** ✔
  - 位置：`OrientScoringServiceImpl.java:126-128` 的 `ended()` 同时接受 COMPLETED 与 ENDED（放弃）；`:381-388` 的奖励键 `orient-settlement:{sessionId}` 按局区分。`TopicRouteRuntimeServiceImpl:4364-4389` 每次重玩都会新建 session。
  - 故障：完成少量任务 → 放弃 → 结算 → 重玩，可无限循环。
  - 修复：只结算 FINISHED；奖励按 member+topic+activity 只发首次（键或唯一约束）；或明确决策并写明「重玩照发」。
- **S-515-2 should-fix：结算不等申诉窗口关闭。**
  - 位置：`:381-427,587-627`。
  - 故障：局一结束即可 `/settle`，之后的申诉或撤销只追加台账行，已发的积分/XP 不再调整；`resolveDispute` 不改任何分数。
  - 修复：窗口关闭且无未决申诉后再结算，或在结算后写补偿流水。
- N-515-1：`reverseEntry` 未调用 `requireActive`；`breakdown.startedAt` 取的是 `create_time`，而非 #514 的服务端时钟；玩家侧申诉列表会回传管理员的 `handled_by`。

### #514 / #517 联动

- **S-517-2 should-fix：三条 lane 的任务完成数据互不相通。**
  - #517 的替代完成只写 `orient_task_completion`，而 #514 的 `NodeProgressOrientTaskEvidenceVerifier` 与 #515 的 `accrue` 只读 `player_node_progress`。结果是 SCAN/PHOTO/QUIZ 完成既不满足 `/task/{id}/result`，也不计分，与迁移注释「替代与主路径同分」矛盾。
  - #517 的 `submit` 也不检查 #514 的阶段，开局前或放弃/DQ 后的完成仍被接受。
  - 修复：统一完成事实来源；session flag 开启时，`submit` 要求阶段为 RUNNING。
- Nit：
  - N-517-1 `playInstanceId` 由客户端自由传入；队伍看板的 `selectCompletionFacts` 不限定局/时间窗（`OrientTaskServiceImpl.java:317-322`、`OrientMapper.xml:246-263`）。
  - N-517-2 QUIZ/SCAN 被拒次数不设上限，只有 30 次/分钟限流，短答案可被暴力尝试。
  - N-517-3 `OrientSwitches.flagOn` 接受 `"1"`、不 trim、不捕获异常，与另两条 lane 不一致。
  - N-514-2 旧路线 actionId 可以合法写成 `orient:...`，会与 #514 回执共用 `uk_topic_route_action`，并在日志中被隐藏。修复：改用 `orient#` 前缀，或在旧校验器中拒绝 `orient:`。
- 已核对 OK：结算有三道保护（session 行 `FOR UPDATE` → `uk_orient_settlement_session` → `uk_member_reward_business`），同一局不会重复发；队伍看板与排行不返回坐标；3 个 PR 的 CI 均为 32 通过 / 3 跳过。

### #524 F2

- **S-524-2 should-fix：`GameplayCheckEngine.java:82-85` 让匹配 `^counter\.…` 的自由文本 `check.skill` 隐式变成属性加值。**
  - 故障：该行为无开关；`StateKeyRefs` 还会把这类 skill 当作必须注册的引用，旧主题重新发布可能被拦。
  - 修复：改用显式字段（如 `check.skillKey`），或先确认线上已发布配置中没有匹配项。
- Nit：
  - N-524-1 隐藏属性的值可以由 `total − die − applied` 反推出来。
  - N-524-2 发布同步不做 min/max 校验，并且每次都覆盖 `label` / `initial_value`。
  - N-524-3 结算回执丢失 `breakdown`（`TopicRouteRuntimeServiceImpl.java:984-996`）。
- 已核对 OK：
  - 角色卡端点只取 token 的 memberId；非作者访问未发布主题得到 404。
  - `natAuto` 不显式设置时，冻结快照逐字节一致。
  - 无 `persist_scope=topic` 键时 `seedRun` 直接返回，存量主题不受影响。
  - 9 列均为可空、`ALGORITHM=INSTANT`、带守卫。

### #521 F5

- **S-521-1 should-fix：`ADD_ITEM` 能通过发布，运行时却抛异常。** ✔
  - 位置：`TopicRouteEngine.java:315` 在未启用背包时抛出 `IllegalStateException`；三个发布校验器都不检查背包是否启用。
  - 故障：该节点或边上的所有玩家都会被卡住。
  - 修复：发布时拒绝，或运行时改为 no-op。
- **S-521-2 should-fix：`bindStoryContext` 只在 outcome 流程中绑定（`TopicRouteRuntimeServiceImpl.java:674`）。**
  - 故障：check 路径（约 896、955-964 行）、`conditionsPass(memberId…)`（约 1049 行）、`PlayEncounterServiceImpl`、`PlayJourneyStoryServiceImpl`、`GameplayCheckEngine` 中 `ROLE_IN` 恒为 false，check 效果中的 `ADD_ITEM` 也不受奖励上限约束。
  - 修复：统一一个 helper 绑定，或在未绑定的上下文中拒绝这些 op。
- **S-521-3 should-fix：`GRANT_BADGE` / `GRANT_XP` 只写入 `state.storyGrants`，没有任何消费者。**
  - 故障：校验器接受这类配置，但永远不会真正发放。
  - 修复：写明它只记账，或在有消费者之前不放行这两个 op。
- Nit：
  - N-521-1 `TopicRouteEngine.State` 缺少 `@JsonIgnoreProperties(ignoreUnknown=true)`：回滚到旧 jar 后，含新字段的局无法读取。修复：先单独发一版加上该注解。
  - N-521-2 `GameSessionRuntimeServiceImpl.applyRouteEffects`（约 5152-5163 行）会静默丢弃新增的效果。
- 已核对 OK：`TopicRouteEngineGoldenTest` 与 master 引擎的逐字副本做差分（>2000 次比对，加全路线 `state_json` 比对）；新 op 都排在既有分支之后，此前合法的配置不会被新拒。

### #526 F4 商家台

- **S-526-1 should-fix：SKU 编辑不回到待审核状态。** ✔
  - 位置：`MerchantDeskMapper.xml` 中只有 `updateProduct` 置 `publish_status=0`，`insertSku` / `updateSku` 只要求 `<>1`；与 `f4-backend.md:41` 的描述不符。
  - 故障：商家下架商品（状态 2）后修改 SKU 的积分价/库存，管理员把 2→1 重新上架时并不知道 SKU 已变；审核中改 SKU 同理。
  - 修复：SKU 写入后，在同一事务中执行 `update pms_product set publish_status=0 where id=? and merchant_id=? and publish_status<>1`，并补测试。
- Nit：
  - N-526-1 所有 `DuplicateKeyException` 都映射为 `F4_IN_FLIGHT`，包括业务唯一键冲突。
  - N-526-2 BSSID 用无盐 SHA-256，可被暴力还原。修复：改用 HMAC，或弱化「不存明文」的说法。
  - N-526-3 门店无坐标时跳过锚点 1 km 校验；当前未强制，接入核销前必须补上。
  - N-526-4 `(page-1)*size` 存在 int 溢出（`MerchantDeskServiceImpl.java:511,517`）。
  - N-526-5 `openEntitlements` 按整个章节跨商家计数（只是聚合数，无 PII）。
- 已核对 OK：
  - 不写任何资金表（`noWriteTouchesMoneyOrFulfillmentTables` 钉住）。
  - `/redeem/resolve` 只做纯分类，无 DB 查询，不构成码枚举 oracle。
  - 每个端点依次经过登录 → 开关 → `merchantAccessService.require`。
  - 收据和到店记录都不含手机号或姓名。
  - 迁移只建表并带指纹守卫。

---

## 发布建议

1. **#519、#527 修掉 blocker 前不合入**：#519 收紧回填匹配并提交预览；#527 拆出 `settlement_claim` 迁移。
2. 其余 PR 在开关全部 OFF 的前提下可合入；以下 should-fix 是**打开对应开关前的硬前置**，请登记到 disposition：
   - S-513-1：资金路径，建议合入前修。
   - S-515-1/2：费率 >0 前。
   - S-516-1/2/3：shadow/enforce 前。
   - S-525-1/2/3：minorPaymentGate / 开票上线前。
   - S-527-1/2：sale-enabled 前。
3. 部署顺序：#524 必须先迁移、验收，再部署 jar；`migration-dependencies.json` 按合并顺序做并集，解完后重跑 dep-order。
4. 所有线上 DDL 仍需走 chengyin-db-migrate 只读预检并由用户确认。本评审未连接任何数据库。
