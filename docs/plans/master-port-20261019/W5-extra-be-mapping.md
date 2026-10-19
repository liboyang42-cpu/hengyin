# W5 移植映射：EXTRA 1e403b13..8822ab38 + B05 2ac55d20 + #231 → port/extra-be-20261019

全部 183 个 EXTRA 提交均按累计差异（排除 chengyinhub-xcx/）在 8294a0be 中 3-way 重放；下表逐条记录例外。

| EXTRA commit | subject | port |
|---|---|---|
| `24a36685` | test: 补齐E01排期时刻与保护事实真库回归（未验证） | **Deferred**: `B04ScheduleReadbackMySqlIT` (needs base symbols) |
| `ba910732` | test: 补齐E02社区详情与历史真库隐私回归（未验证） | → 8294a0be |
| `ae53f3d7` | test: 补齐E03结局正文冻结与旧快照真库回归（未验证） | → 8294a0be |
| `f9d3127d` | test: 补齐E04来源领券与退款冲正真库回归（未验证） | **Deferred**: `B03CouponLifecycleMySqlIT` (needs base `CouponSourceGrantMapper` / `grantCouponBySource`) |
| `c0c7f66d` | test: 交付E05退款账号晚回调失败回归（未验收） | → 8294a0be |
| `afe0f916` | test: 交付E06平台释放后晚回调失败回归（未验收） | → 8294a0be |
| `ddaa99ac` | docs: 发布EXTRA6独立50项源码池与六线领取恢复账 | → 8294a0be (docs) |
| `bdd53461` | fix: E03-01结局冻结传递本局快照并保留归档边界 | partial → 8294a0be (`includeArchive`, `freezeEnding(…, session)`). **Deferred**: 4-arg `endingChapter(…, session)` call + `E03_01Test` (base-only overload) |
| `f74c0280` | fix: E01-07候补到期回读保留并发认领终态 | → 8294a0be |
| `abd374d1` | fix: E02-01社区详情聚合读取使用一致快照 | → 8294a0be |
| `742dc963` | docs: E04-01库存转换假设反证并保留既有覆盖 | → 8294a0be (docs) |
| `5a417af3` | fix: E05-03订单历史退款隔离账号与组件代次 | → 8294a0be |
| `d7cc6d66` | fix: E06-08定位回调批次绑定可取消领取身份 | → 8294a0be |
| `aa2e6718` | docs: E01-01排期日期边界已有实现反证 | → 8294a0be (docs) |
| `99cefd34` | docs: E02-02信息流游标及可见性已有过滤反证 | → 8294a0be (docs) |
| `15912d5c` | fix: E03-02损坏结局快照拒绝伪成功且保留旧局兼容 | → 8294a0be |
| `ebf4bed4` | fix: E05-04参与退款确认绑定账号与原记录 | → 8294a0be |
| `096169d7` | fix: E06-03常亮失败可恢复并隔离晚回调 | → 8294a0be |
| `65f290d5` | docs: E04-03停发竞态方案与精确文件依赖 | → 8294a0be (docs) |
| `3832c3b8` | docs: 回填首轮源码交付并补足50项可执行池 | → 8294a0be (docs) |
| `3cb3a50d` | docs: E01-02既存排期时刻保留源码反证 | → 8294a0be (docs) |
| `2b6db01f` | docs: E02-03引用类型反证与历史卡片政策边界 | → 8294a0be (docs) |
| `72d53c64` | docs: E03-03结局选择顺序分属模型反证 | → 8294a0be (docs) |
| `70d95b5e` | docs: E04-02来源限领与不存在通用历史上限的范围裁决 | → 8294a0be (docs) |
| `db070a7e` | docs: E05-05收藏换号回调已有完整守卫反证 | → 8294a0be (docs) |
| `75e8d438` | fix: E06-01音频存活上下文重复注册幂等 | → 8294a0be |
| `d8b9cc64` | docs: 保存18项交付反证阻塞与50项后续源码游标 | → 8294a0be (docs) |
| `106f2e5a` | fix: E01-10排期聚合读保持同一快照 | partial → 8294a0be. **Deferred**: `E01_10Test` (needs base `ClubEventSeriesView.getStartTime`) |
| `2ff5c10d` | fix: E02-10编辑历史聚合读保持同一快照 | → 8294a0be |
| `f67845d4` | test: E03-09修正首批结局夹具合法条件 | → 8294a0be |
| `852feb00` | docs: E04-04来源回执身份冲突已有防护反证 | → 8294a0be (docs) |
| `4ff588ec` | fix: E05-09订单历史列表卸载晚回包隔离 | → 8294a0be |
| `0d8f17dc` | fix: E06-09定位启动同步异常沿原路径收口 | → 8294a0be |
| `89ad118a` | fix(schedule): reject normalized 24:00 collection time | → 8294a0be |
| `b8979dad` | fix(community): read feed aggregate in one visibility snapshot | → 8294a0be |
| `9c582b1f` | docs(story): record stage history policy boundary | → 8294a0be (docs) |
| `7b6f202f` | fix(participation): bind detail response to live account and instance | → 8294a0be |
| `0439629d` | fix(location): notify remaining consumers before rethrowing | → 8294a0be |
| `4902cc1c` | fix(coupon): reserve only currently issuable inventory | partial → 8294a0be. **Skipped**: `tryReserveIssuableCoupon` (master already has the stricter `tryIncrementIssuableReceiveCount`, which also checks the validity window); `CouponSourceGrantTest` edit (file is base-only) |
| `fa91ec98` | docs(schedule): record exact series replay identity | → 8294a0be (docs) |
| `8afc4472` | docs(community): record delete receipt and version guards | → 8294a0be (docs) |
| `f6420b6f` | docs(story): propose node remapping producer contract | → 8294a0be (docs) |
| `8066637f` | fix(subscribe): select three unique template identifiers | → 8294a0be |
| `d57d9ee5` | docs(poller): record inflight generation isolation | → 8294a0be (docs) |
| `ecb5f418` | docs(extra-six): persist 84-item history and replenished 50-task pool | → 8294a0be (docs) |
| `cf76e325` | fix(schedule): replay fixed-date requests after day rollover | → 8294a0be |
| `b91f900f` | docs(community): record location withdrawal projection behavior | → 8294a0be (docs) |
| `0d3ec05b` | docs(journey): record member and activity read scope | → 8294a0be (docs) |
| `15fe8055` | docs(coupon): record current-state redemption guards | → 8294a0be (docs) |
| `a0462199` | docs(subscribe): record partial authorization result semantics | → 8294a0be (docs) |
| `4c5f8748` | fix(poller): normalize invalid and overflowing timer delays | → 8294a0be |
| `55b34dda` | docs(schedule): record club-scoped series projections | → 8294a0be (docs) |
| `35a8536c` | fix(community): keep comment authorization and rows in one snapshot | → 8294a0be |
| `ed474734` | fix(story): remap existing node references without losing raw fields | → 8294a0be |
| `7903d2b9` | fix(favorites): retain committed page after failed loads | → 8294a0be |
| `d87f00c3` | fix(location): reject coercible missing coordinates | → 8294a0be |
| `343e44d6` | docs(schedule): record live waitlist structural protection | → 8294a0be (docs) |
| `7ef5d02a` | docs(community): record atomic comment deletion scope | → 8294a0be (docs) |
| `2daa1800` | docs(story): record chapter remapping compatibility | → 8294a0be (docs) |
| `a8f9cc4b` | fix(coupon): reject current invalidation during redemption | → 8294a0be (`incrementUseCount` gate on invalidated status 3) |
| `2e5775a4` | fix(favorites): retain confirmed removals across in-flight lists | → 8294a0be |
| `d07b766a` | docs(location): record cancellation and permission continuation contract | → 8294a0be (docs) |
| `6157997d` | docs(community): record interaction replay visibility | → 8294a0be (docs) |
| `07e65c2a` | fix(story): remap existing asked and result counters | → 8294a0be |
| `5afe0ae6` | docs(coupon): report reverse history identity race and required transfer | → 8294a0be (docs) |
| `21f2ed10` | docs(favorites): record lifecycle and modal settlement guards | → 8294a0be (docs) |
| `eb2f0e8e` | fix(location): propagate errors after stop completion | → 8294a0be |
| `e973d27c` | test(waitlist): add direct cancellation and promotion lock interleavings | → 8294a0be |
| `4d07c44b` | docs(community): record media receipt ownership barriers | → 8294a0be (docs) |
| `20e802fc` | docs(story): record existing condition validation scope | → 8294a0be (docs) |
| `0dcf2199` | docs(coupon): report missing original completion receipt | → 8294a0be (docs) |
| `67ba5ef4` | docs(orders): record history request ownership and disabled pagination | → 8294a0be (docs) |
| `aa27b350` | fix(location): reject nonnumeric picked-coordinate types | → 8294a0be |
| `21675733` | docs(waitlist): record signed offer scope protections | → 8294a0be (docs) |
| `5d9b8d74` | docs(community): record unresolved historical POI null semantics | → 8294a0be (docs) |
| `6b714bb2` | docs(snapshot): report invalid typed projections falling back to live data | → 8294a0be (docs) |
| `16ff2ce4` | docs(coupon): record clawback marker replay boundaries | → 8294a0be (docs) |
| `ac022d5e` | fix(favorites): queue return refresh behind active list request | → 8294a0be |
| `473fa6db` | fix(location): retire replaced failure notification identity | → 8294a0be |
| `07bbbdc7` | fix(coupon): lock reverse history association before reading | partial → 8294a0be (`SmsCouponHistoryServiceImpl` reverse lock). **Deferred**: B03 IT part |
| `57d77687` | test(waitlist): cover claimed registration lifecycle with real SQL | → 8294a0be |
| `8599167c` | docs(waitlist): record maintenance page identity contract | → 8294a0be (docs) |
| `5a7724d3` | docs(community): report stale review task version dependency | → 8294a0be (docs) |
| `3e8d621c` | docs(snapshot): hand scene ending freeze ordering to runtime owner | → 8294a0be (docs) |
| `568f617c` | fix(subscribe): ignore inherited template properties | → 8294a0be |
| `6aff9500` | docs(audio): record explicit pause precedence and scope exhaustion | → 8294a0be (docs) |
| `b6acc905` | docs(extra-six): reconcile public deliveries and six-worker support claims | → 8294a0be (docs) |
| `b09966ec` | docs(waitlist): record waiting admission policy boundary | → 8294a0be (docs) |
| `f47475e3` | test(events): cover cancellation failures after transaction writes | → 8294a0be |
| `f828c413` | fix(community): recheck membership before publishing retained drafts | → 8294a0be |
| `a8ac44b8` | test(community): cover deletion rollback after media and receipt writes | → 8294a0be |
| `33ed1687` | docs(story): record raw NPC mapping compatibility | → 8294a0be (docs) |
| `07ff820c` | fix(coupon): transact administrative history and statistics together | → 8294a0be |
| `e43477b7` | docs(events): record standalone cancellation safeguards | → 8294a0be (docs) |
| `93628d8e` | test(events): preserve cancellation audience after eligibility changes | → 8294a0be |
| `8d5a9380` | docs(community): record media erasure hold and sharing safeguards | → 8294a0be (docs) |
| `3f796be8` | test(community): cover notification ownership and withdrawn post filtering | → 8294a0be |
| `384dd408` | docs(casebook): record frozen optional clue progress compatibility | → 8294a0be (docs) |
| `f3e0198e` | fix(points): read current locked balance for clawback ledger | **Deferred** (clawback current read `selectPointsForUpdate`, base-only symbol) |
| `ef0b2317` | docs(waitlist): report new-offer activity availability mismatch | → 8294a0be (docs) |
| `75e1abc2` | fix(community): accept affected rows for governance projection updates | → 8294a0be |
| `6665b8a8` | fix(casebook): compute log deltas from frozen initial rules | → 8294a0be |
| `228a0281` | docs(coupon): report redemption association race and required fixtures | → 8294a0be (docs) |
| `8870e71f` | test(coupon): cover bulk expiry scope and excluded row invariants | → 8294a0be |
| `27b8e185` | docs(coupon): record existing history failure stock rollback coverage | → 8294a0be (docs) |
| `ead81af7` | chore: import authorized E05 backend baseline from CORE 7e5882 | → 8294a0be |
| `54fbc2b0` | docs(waitlist): inventory exact promotion preflight fixtures | → 8294a0be (docs) |
| `3f7bde5a` | docs(community): deliver replay and partial patch contract packets | → 8294a0be (docs) |
| `5764b9bc` | docs(journey): audit latest CORE remap source selection | → 8294a0be (docs) |
| `681745cb` | docs(coupon): trace actual clawback transaction callers | → 8294a0be (docs) |
| `ace11a90` | fix(encounter): reject malformed present frozen action contracts | → 8294a0be |
| `6dfff57d` | docs: record E03-16 source contract audits | → 8294a0be (docs) |
| `6b0dc2f8` | docs: record E06-14, E06-15, E06-16 source contract audits | → 8294a0be (docs) |
| `d2b2a8a5` | fix(share): preserve unicode pairs at existing text budgets | → 8294a0be |
| `a55dabc0` | docs: checkpoint 104 source-only items and precise active transfers | → 8294a0be (docs) |
| `acc03a06` | fix(community): apply nullable notification patches atomically | → 8294a0be |
| `0097e1c6` | fix(coupon): lock redemption identity before checking associations | → 8294a0be |
| `8b4ae47a` | fix(waitlist): recheck current activity before creating a new offer | → 8294a0be |
| `b5eb8866` | fix(journey): isolate archive content to the supplied play instance | → 8294a0be |
| `25ede658` | docs: preserve frozen scope patches and actual source-only queue | → 8294a0be (docs) |
| `767c37bb` | docs: resume authorized fixture and receipt work with original writers | → 8294a0be (docs) |
| `3e1349ff` | fix: 当前旅程读取采用既有当前局作用域 | → 8294a0be |
| `a37ce191` | fix: Encounter 当前局读取同步精确作用域夹具 | → 8294a0be |
| `49fd4905` | docs: 明确本地集中验证与已解除历史转交状态 | → 8294a0be (docs) |
| `ff7cddb9` | fix: 评论点赞撤销后保留不可变请求身份 | → 8294a0be |
| `474e54ae` | docs: 固化单修复五开发及三组本地复测交付 | → 8294a0be (docs) |
| `0c5422fd` | docs: 明确通关券阻塞为原发放身份合同 | → 8294a0be (docs) |
| `700e754b` | docs: 登记取消收藏真实交错与原奖励回执合同边界 | → 8294a0be (docs) |
| `aa36698e` | docs: 接收VPS三失败并仅派E02单修复线 | → 8294a0be (docs) |
| `4793f2b3` | fix(test): restore absent receipt fixtures for community regressions | → 8294a0be |
| `edcc2e4f` | docs: record published EXTRA fixture repair and pending local retest | → 8294a0be (docs) |
| `e72eff7e` | docs: reserve forward Cloud six-path boundary as EXTRA read-only | → 8294a0be (docs) |
| `8aebd7d7` | docs: claim two concrete forward fixes within EXTRA ownership | → 8294a0be (docs) |
| `31c66e77` | fix: align ending optional clue keys with accepted whitespace semantics | → 8294a0be |
| `ffba8a3e` | fix: keep transient share read failures retryable over HTTP | → 8294a0be |
| `129d08ea` | fix: align padded epilogue relation keys with publication contract | → 8294a0be |
| `93d7a653` | docs: clarify final clue and relation key delivery scope | → 8294a0be (docs) |
| `725743fa` | docs: record two delivered EXTRA fixes and actual forward-team ACK | → 8294a0be (docs) |
| `f3665655` | docs: preserve legacy node-id parsing boundary and resumable queue | → 8294a0be (docs) |
| `22eec0b2` | docs: acknowledge public EXTRA VPS evidence and isolated retest candidate | → 8294a0be (docs) |
| `86524e2a` | docs: close EXTRA E02 failures with local targeted e72 pass | → 8294a0be (docs) |
| `ed598258` | docs: link public evidence for e72 targeted VPS pass | → 8294a0be (docs) |
| `b45664de` | fix(test): use supported database diagnostic in share isolation control | → 8294a0be |
| `65db518b` | docs: record E06 fixture repair and preserve independent SQL concern | → 8294a0be (docs) |
| `b7f7a008` | docs: record b456 VPS admission refusal without test outcome | → 8294a0be (docs) |
| `2226ee5b` | docs: close E06 targeted repair with public b456 VPS pass | → 8294a0be (docs) |
| `fabc3522` | docs: claim concrete comment replay target fix and prepare owner handoff | → 8294a0be (docs) |
| `faefbaf8` | fix: bind comment creation replay to its parent target | → 8294a0be |
| `d0156ab1` | docs: deliver parent-bound comment replay fix and request narrow RR dependency | → 8294a0be (docs) |
| `ad0ffc86` | docs: finalize narrow RR handoff without changing delivered candidate | → 8294a0be (docs) |
| `dd211a8a` | docs: record faef ordinary pass and activate authorized RR continuation | → 8294a0be (docs) |
| `29fe9236` | fix: 评论重复键恢复按当前读查找已提交赢家 | → 8294a0be |
| `e9b9e191` | docs: 交付评论RR恢复补丁并签收独立普通验证证据 | → 8294a0be (docs) |
| `55c453d7` | docs: 登记本地冻结RR候选及普通定向准备状态 | → 8294a0be (docs) |
| `cdc94fa5` | docs: 签收29fe普通64项实测证据并保留真库未验 | → 8294a0be (docs) |
| `1ffca2d8` | docs: 记录仅保留现有验证入口并关闭新增真库方案 | → 8294a0be (docs) |
| `1b3905f9` | docs: 交付POI和候补定向事实及精确合同接续 | → 8294a0be (docs) |
| `62f5f4e5` | docs: 明确既授源码锁与当前合同阻塞 | → 8294a0be (docs) |
| `1bfa81a5` | fix: POI引用选项按既有上架状态对齐保存合同 | → 8294a0be |
| `02b99bea` | docs: 交付POI查询合同修复和两项定向回归 | → 8294a0be (docs) |
| `fb312a6b` | docs: 登记POI候选本地冻结及212串行排队 | → 8294a0be (docs) |
| `a76268d5` | docs: 签收POI单类两项H2回归与锁拒绝历史 | → 8294a0be (docs) |
| `dedb44d5` | fix: 原完成券身份未知仅审计并保留其他冲正 | **Deferred** (clawback unknown-identity audit, needs base; see 923170ab) |
| `13be9976` | fix: 旧广场收藏读写仅接受受保护迁移映射 | → 8294a0be |
| `923170ab` | fix: 完成券保存精确原回执并保留未知审计边界 | partial → 8294a0be (receipt `history_id`, migration, E04_07MySqlIT). **Deferred**: `RedemptionRewardClawbackService` + `RedemptionRewardClawbackMyBatisTest` + `E04_07Test` (need base `UmsMemberMapper.selectPointsForUpdate`) |
| `032090c7` | docs: 交付身份与原券回执三包及未验证边界 | → 8294a0be (docs) |
| `e63d8ee9` | docs: record combined candidate admission refusal and correct parameterized coun | → 8294a0be (docs) |
| `54fdc951` | docs: correct precheck running claim with second formal admission refusal | → 8294a0be (docs) |
| `b034a4aa` | fix: 修正旧广场回归夹具的H2别名标识符引用 | → 8294a0be |
| `40bb25f6` | docs: 签收35项异常并交原E02夹具修复候选 | → 8294a0be (docs) |
| `acbd5f54` | docs: 签收原E02两类8项实测通过并释放修复槽 | → 8294a0be (docs) |
| `0ee9a998` | fix: 阶段结算沿有效非空冻结节点检查完成门槛 | → 8294a0be |
| `530e7228` | fix: 新增候补登记复用活动资格及报名截止检查 | → 8294a0be |
| `541d36b2` | docs: 交付新增候补准入及阶段冻结门槛源码候选 | → 8294a0be (docs) |
| `ff6c1567` | docs: 签收候补与冻结门槛四类60项普通回归通过 | → 8294a0be (docs) |
| `6143c37f` | fix: 隐藏取消或下架活动的旧引用卡并保留正文 | → 8294a0be |
| `c35a4635` | docs: 交付活动引用卡过滤源码及四项普通回归候选 | → 8294a0be (docs) |
| `6c6f82d7` | test: 为评论重放集成夹具补齐活动引用空表前提 | → 8294a0be |
| `26d8245b` | docs: 签收活动引用卡四项通过并分记自有真库夹具补表 | → 8294a0be (docs) |
| `6c446369` | fix: 补齐三个社区真库夹具的活动空表前提 | → 8294a0be |
| `8fe437b1` | docs: 记录三个真库夹具源码交付与未验边界 | → 8294a0be (docs) |
| `a9ad53ae` | test: 补齐 E04-07 真库夹具撤销流水的商家归属 (#285) | → 8294a0be |
| `8822ab38` | fix: 主题运行时终态归档改读本局结局正文快照 (#287) | → 8294a0be |

| B05 / #231 commit | port |
|---|---|
| `2ac55d20` test: 补齐B05独占真库夹具的活动空表前提 | → da1f3b81 |
| `5ee9d50d` fix: 社区私有附件短签与稳定媒体身份重试 | → 849b0566 (xcx parts excluded) |
| `b0dc1019` fix: 社区附件短签固定使用HTTPS | → 849b0566 |
