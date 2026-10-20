# W5 移植映射：EXTRA 1e403b13..8822ab38 + B05 2ac55d20 + #231 → port/extra-be-20261019

全部 183 个 EXTRA 提交均按累计差异（排除 chengyinhub-xcx/）在 3b7cafa5 中 3-way 重放；下表逐条记录例外。提交号为变基到 port/base-be-20261019 (074fdfe9) 之后的哈希。

| EXTRA commit | subject | port |
|---|---|---|
| `24a36685` | test: 补齐E01排期时刻与保护事实真库回归（未验证） | → f5d2cff0 (`B04ScheduleReadbackMySqlIT`) |
| `ba910732` | test: 补齐E02社区详情与历史真库隐私回归（未验证） | → 3b7cafa5 |
| `ae53f3d7` | test: 补齐E03结局正文冻结与旧快照真库回归（未验证） | → 3b7cafa5 |
| `f9d3127d` | test: 补齐E04来源领券与退款冲正真库回归（未验证） | → 3261e7c5 (`B03CouponLifecycleMySqlIT`, after base ported `CouponSourceGrantMapper`) |
| `c0c7f66d` | test: 交付E05退款账号晚回调失败回归（未验收） | → 3b7cafa5 |
| `afe0f916` | test: 交付E06平台释放后晚回调失败回归（未验收） | → 3b7cafa5 |
| `ddaa99ac` | docs: 发布EXTRA6独立50项源码池与六线领取恢复账 | → 3b7cafa5 (docs) |
| `bdd53461` | fix: E03-01结局冻结传递本局快照并保留归档边界 | → 3b7cafa5 (`includeArchive`; 4-arg `endingChapter` from base cf5200d3) + f5d2cff0 (`E03_01Test`) |
| `f74c0280` | fix: E01-07候补到期回读保留并发认领终态 | → 3b7cafa5 |
| `abd374d1` | fix: E02-01社区详情聚合读取使用一致快照 | → 3b7cafa5 |
| `742dc963` | docs: E04-01库存转换假设反证并保留既有覆盖 | → 3b7cafa5 (docs) |
| `5a417af3` | fix: E05-03订单历史退款隔离账号与组件代次 | → 3b7cafa5 |
| `d7cc6d66` | fix: E06-08定位回调批次绑定可取消领取身份 | → 3b7cafa5 |
| `aa2e6718` | docs: E01-01排期日期边界已有实现反证 | → 3b7cafa5 (docs) |
| `99cefd34` | docs: E02-02信息流游标及可见性已有过滤反证 | → 3b7cafa5 (docs) |
| `15912d5c` | fix: E03-02损坏结局快照拒绝伪成功且保留旧局兼容 | → 3b7cafa5 |
| `ebf4bed4` | fix: E05-04参与退款确认绑定账号与原记录 | → 3b7cafa5 |
| `096169d7` | fix: E06-03常亮失败可恢复并隔离晚回调 | → 3b7cafa5 |
| `65f290d5` | docs: E04-03停发竞态方案与精确文件依赖 | → 3b7cafa5 (docs) |
| `3832c3b8` | docs: 回填首轮源码交付并补足50项可执行池 | → 3b7cafa5 (docs) |
| `3cb3a50d` | docs: E01-02既存排期时刻保留源码反证 | → 3b7cafa5 (docs) |
| `2b6db01f` | docs: E02-03引用类型反证与历史卡片政策边界 | → 3b7cafa5 (docs) |
| `72d53c64` | docs: E03-03结局选择顺序分属模型反证 | → 3b7cafa5 (docs) |
| `70d95b5e` | docs: E04-02来源限领与不存在通用历史上限的范围裁决 | → 3b7cafa5 (docs) |
| `db070a7e` | docs: E05-05收藏换号回调已有完整守卫反证 | → 3b7cafa5 (docs) |
| `75e8d438` | fix: E06-01音频存活上下文重复注册幂等 | → 3b7cafa5 |
| `d8b9cc64` | docs: 保存18项交付反证阻塞与50项后续源码游标 | → 3b7cafa5 (docs) |
| `106f2e5a` | fix: E01-10排期聚合读保持同一快照 | → 3b7cafa5 + f5d2cff0 (`E01_10Test`) |
| `2ff5c10d` | fix: E02-10编辑历史聚合读保持同一快照 | → 3b7cafa5 |
| `f67845d4` | test: E03-09修正首批结局夹具合法条件 | → 3b7cafa5 |
| `852feb00` | docs: E04-04来源回执身份冲突已有防护反证 | → 3b7cafa5 (docs) |
| `4ff588ec` | fix: E05-09订单历史列表卸载晚回包隔离 | → 3b7cafa5 |
| `0d8f17dc` | fix: E06-09定位启动同步异常沿原路径收口 | → 3b7cafa5 |
| `89ad118a` | fix(schedule): reject normalized 24:00 collection time | → 3b7cafa5 |
| `b8979dad` | fix(community): read feed aggregate in one visibility snapshot | → 3b7cafa5 |
| `9c582b1f` | docs(story): record stage history policy boundary | → 3b7cafa5 (docs) |
| `7b6f202f` | fix(participation): bind detail response to live account and instance | → 3b7cafa5 |
| `0439629d` | fix(location): notify remaining consumers before rethrowing | → 3b7cafa5 |
| `4902cc1c` | fix(coupon): reserve only currently issuable inventory | partial → 3b7cafa5. **Skipped** (owner decision 1): `tryReserveIssuableCoupon`, master keeps the stricter `tryIncrementIssuableReceiveCount`; `CouponSourceGrantTest` edit skipped, base already targets master's method |
| `fa91ec98` | docs(schedule): record exact series replay identity | → 3b7cafa5 (docs) |
| `8afc4472` | docs(community): record delete receipt and version guards | → 3b7cafa5 (docs) |
| `f6420b6f` | docs(story): propose node remapping producer contract | → 3b7cafa5 (docs) |
| `8066637f` | fix(subscribe): select three unique template identifiers | → 3b7cafa5 |
| `d57d9ee5` | docs(poller): record inflight generation isolation | → 3b7cafa5 (docs) |
| `ecb5f418` | docs(extra-six): persist 84-item history and replenished 50-task pool | → 3b7cafa5 (docs) |
| `cf76e325` | fix(schedule): replay fixed-date requests after day rollover | → 3b7cafa5 |
| `b91f900f` | docs(community): record location withdrawal projection behavior | → 3b7cafa5 (docs) |
| `0d3ec05b` | docs(journey): record member and activity read scope | → 3b7cafa5 (docs) |
| `15fe8055` | docs(coupon): record current-state redemption guards | → 3b7cafa5 (docs) |
| `a0462199` | docs(subscribe): record partial authorization result semantics | → 3b7cafa5 (docs) |
| `4c5f8748` | fix(poller): normalize invalid and overflowing timer delays | → 3b7cafa5 |
| `55b34dda` | docs(schedule): record club-scoped series projections | → 3b7cafa5 (docs) |
| `35a8536c` | fix(community): keep comment authorization and rows in one snapshot | → 3b7cafa5 |
| `ed474734` | fix(story): remap existing node references without losing raw fields | → 3b7cafa5 |
| `7903d2b9` | fix(favorites): retain committed page after failed loads | → 3b7cafa5 |
| `d87f00c3` | fix(location): reject coercible missing coordinates | → 3b7cafa5 |
| `343e44d6` | docs(schedule): record live waitlist structural protection | → 3b7cafa5 (docs) |
| `7ef5d02a` | docs(community): record atomic comment deletion scope | → 3b7cafa5 (docs) |
| `2daa1800` | docs(story): record chapter remapping compatibility | → 3b7cafa5 (docs) |
| `a8f9cc4b` | fix(coupon): reject current invalidation during redemption | → 3b7cafa5 (`incrementUseCount` gate on invalidated status 3) |
| `2e5775a4` | fix(favorites): retain confirmed removals across in-flight lists | → 3b7cafa5 |
| `d07b766a` | docs(location): record cancellation and permission continuation contract | → 3b7cafa5 (docs) |
| `6157997d` | docs(community): record interaction replay visibility | → 3b7cafa5 (docs) |
| `07e65c2a` | fix(story): remap existing asked and result counters | → 3b7cafa5 |
| `5afe0ae6` | docs(coupon): report reverse history identity race and required transfer | → 3b7cafa5 (docs) |
| `21f2ed10` | docs(favorites): record lifecycle and modal settlement guards | → 3b7cafa5 (docs) |
| `eb2f0e8e` | fix(location): propagate errors after stop completion | → 3b7cafa5 |
| `e973d27c` | test(waitlist): add direct cancellation and promotion lock interleavings | → 3b7cafa5 |
| `4d07c44b` | docs(community): record media receipt ownership barriers | → 3b7cafa5 (docs) |
| `20e802fc` | docs(story): record existing condition validation scope | → 3b7cafa5 (docs) |
| `0dcf2199` | docs(coupon): report missing original completion receipt | → 3b7cafa5 (docs) |
| `67ba5ef4` | docs(orders): record history request ownership and disabled pagination | → 3b7cafa5 (docs) |
| `aa27b350` | fix(location): reject nonnumeric picked-coordinate types | → 3b7cafa5 |
| `21675733` | docs(waitlist): record signed offer scope protections | → 3b7cafa5 (docs) |
| `5d9b8d74` | docs(community): record unresolved historical POI null semantics | → 3b7cafa5 (docs) |
| `6b714bb2` | docs(snapshot): report invalid typed projections falling back to live data | → 3b7cafa5 (docs) |
| `16ff2ce4` | docs(coupon): record clawback marker replay boundaries | → 3b7cafa5 (docs) |
| `ac022d5e` | fix(favorites): queue return refresh behind active list request | → 3b7cafa5 |
| `473fa6db` | fix(location): retire replaced failure notification identity | → 3b7cafa5 |
| `07bbbdc7` | fix(coupon): lock reverse history association before reading | → 3b7cafa5 (service reverse lock) + 3261e7c5 (B03 IT) |
| `57d77687` | test(waitlist): cover claimed registration lifecycle with real SQL | → 3b7cafa5 |
| `8599167c` | docs(waitlist): record maintenance page identity contract | → 3b7cafa5 (docs) |
| `5a7724d3` | docs(community): report stale review task version dependency | → 3b7cafa5 (docs) |
| `3e8d621c` | docs(snapshot): hand scene ending freeze ordering to runtime owner | → 3b7cafa5 (docs) |
| `568f617c` | fix(subscribe): ignore inherited template properties | → 3b7cafa5 |
| `6aff9500` | docs(audio): record explicit pause precedence and scope exhaustion | → 3b7cafa5 (docs) |
| `b6acc905` | docs(extra-six): reconcile public deliveries and six-worker support claims | → 3b7cafa5 (docs) |
| `b09966ec` | docs(waitlist): record waiting admission policy boundary | → 3b7cafa5 (docs) |
| `f47475e3` | test(events): cover cancellation failures after transaction writes | → 3b7cafa5 |
| `f828c413` | fix(community): recheck membership before publishing retained drafts | → 3b7cafa5 |
| `a8ac44b8` | test(community): cover deletion rollback after media and receipt writes | → 3b7cafa5 |
| `33ed1687` | docs(story): record raw NPC mapping compatibility | → 3b7cafa5 (docs) |
| `07ff820c` | fix(coupon): transact administrative history and statistics together | → 3b7cafa5 |
| `e43477b7` | docs(events): record standalone cancellation safeguards | → 3b7cafa5 (docs) |
| `93628d8e` | test(events): preserve cancellation audience after eligibility changes | → 3b7cafa5 |
| `8d5a9380` | docs(community): record media erasure hold and sharing safeguards | → 3b7cafa5 (docs) |
| `3f796be8` | test(community): cover notification ownership and withdrawn post filtering | → 3b7cafa5 |
| `384dd408` | docs(casebook): record frozen optional clue progress compatibility | → 3b7cafa5 (docs) |
| `f3e0198e` | fix(points): read current locked balance for clawback ledger | → 4e2d3d6f (owner decision 2: locked current read via base `selectPointsForUpdate`) + f5d2cff0 (`E04_13Test`) |
| `ef0b2317` | docs(waitlist): report new-offer activity availability mismatch | → 3b7cafa5 (docs) |
| `75e1abc2` | fix(community): accept affected rows for governance projection updates | → 3b7cafa5 |
| `6665b8a8` | fix(casebook): compute log deltas from frozen initial rules | → 3b7cafa5 |
| `228a0281` | docs(coupon): report redemption association race and required fixtures | → 3b7cafa5 (docs) |
| `8870e71f` | test(coupon): cover bulk expiry scope and excluded row invariants | → 3b7cafa5 |
| `27b8e185` | docs(coupon): record existing history failure stock rollback coverage | → 3b7cafa5 (docs) |
| `ead81af7` | chore: import authorized E05 backend baseline from CORE 7e5882 | → 3b7cafa5 |
| `54fbc2b0` | docs(waitlist): inventory exact promotion preflight fixtures | → 3b7cafa5 (docs) |
| `3f7bde5a` | docs(community): deliver replay and partial patch contract packets | → 3b7cafa5 (docs) |
| `5764b9bc` | docs(journey): audit latest CORE remap source selection | → 3b7cafa5 (docs) |
| `681745cb` | docs(coupon): trace actual clawback transaction callers | → 3b7cafa5 (docs) |
| `ace11a90` | fix(encounter): reject malformed present frozen action contracts | → 3b7cafa5 |
| `6dfff57d` | docs: record E03-16 source contract audits | → 3b7cafa5 (docs) |
| `6b0dc2f8` | docs: record E06-14, E06-15, E06-16 source contract audits | → 3b7cafa5 (docs) |
| `d2b2a8a5` | fix(share): preserve unicode pairs at existing text budgets | → 3b7cafa5 |
| `a55dabc0` | docs: checkpoint 104 source-only items and precise active transfers | → 3b7cafa5 (docs) |
| `acc03a06` | fix(community): apply nullable notification patches atomically | → 3b7cafa5 |
| `0097e1c6` | fix(coupon): lock redemption identity before checking associations | → 3b7cafa5 |
| `8b4ae47a` | fix(waitlist): recheck current activity before creating a new offer | → 3b7cafa5 |
| `b5eb8866` | fix(journey): isolate archive content to the supplied play instance | → 3b7cafa5 |
| `25ede658` | docs: preserve frozen scope patches and actual source-only queue | → 3b7cafa5 (docs) |
| `767c37bb` | docs: resume authorized fixture and receipt work with original writers | → 3b7cafa5 (docs) |
| `3e1349ff` | fix: 当前旅程读取采用既有当前局作用域 | → 3b7cafa5 |
| `a37ce191` | fix: Encounter 当前局读取同步精确作用域夹具 | → 3b7cafa5 |
| `49fd4905` | docs: 明确本地集中验证与已解除历史转交状态 | → 3b7cafa5 (docs) |
| `ff7cddb9` | fix: 评论点赞撤销后保留不可变请求身份 | → 3b7cafa5 |
| `474e54ae` | docs: 固化单修复五开发及三组本地复测交付 | → 3b7cafa5 (docs) |
| `0c5422fd` | docs: 明确通关券阻塞为原发放身份合同 | → 3b7cafa5 (docs) |
| `700e754b` | docs: 登记取消收藏真实交错与原奖励回执合同边界 | → 3b7cafa5 (docs) |
| `aa36698e` | docs: 接收VPS三失败并仅派E02单修复线 | → 3b7cafa5 (docs) |
| `4793f2b3` | fix(test): restore absent receipt fixtures for community regressions | → 3b7cafa5 |
| `edcc2e4f` | docs: record published EXTRA fixture repair and pending local retest | → 3b7cafa5 (docs) |
| `e72eff7e` | docs: reserve forward Cloud six-path boundary as EXTRA read-only | → 3b7cafa5 (docs) |
| `8aebd7d7` | docs: claim two concrete forward fixes within EXTRA ownership | → 3b7cafa5 (docs) |
| `31c66e77` | fix: align ending optional clue keys with accepted whitespace semantics | → 3b7cafa5 |
| `ffba8a3e` | fix: keep transient share read failures retryable over HTTP | → 3b7cafa5 |
| `129d08ea` | fix: align padded epilogue relation keys with publication contract | → 3b7cafa5 |
| `93d7a653` | docs: clarify final clue and relation key delivery scope | → 3b7cafa5 (docs) |
| `725743fa` | docs: record two delivered EXTRA fixes and actual forward-team ACK | → 3b7cafa5 (docs) |
| `f3665655` | docs: preserve legacy node-id parsing boundary and resumable queue | → 3b7cafa5 (docs) |
| `22eec0b2` | docs: acknowledge public EXTRA VPS evidence and isolated retest candidate | → 3b7cafa5 (docs) |
| `86524e2a` | docs: close EXTRA E02 failures with local targeted e72 pass | → 3b7cafa5 (docs) |
| `ed598258` | docs: link public evidence for e72 targeted VPS pass | → 3b7cafa5 (docs) |
| `b45664de` | fix(test): use supported database diagnostic in share isolation control | → 3b7cafa5 |
| `65db518b` | docs: record E06 fixture repair and preserve independent SQL concern | → 3b7cafa5 (docs) |
| `b7f7a008` | docs: record b456 VPS admission refusal without test outcome | → 3b7cafa5 (docs) |
| `2226ee5b` | docs: close E06 targeted repair with public b456 VPS pass | → 3b7cafa5 (docs) |
| `fabc3522` | docs: claim concrete comment replay target fix and prepare owner handoff | → 3b7cafa5 (docs) |
| `faefbaf8` | fix: bind comment creation replay to its parent target | → 3b7cafa5 |
| `d0156ab1` | docs: deliver parent-bound comment replay fix and request narrow RR dependency | → 3b7cafa5 (docs) |
| `ad0ffc86` | docs: finalize narrow RR handoff without changing delivered candidate | → 3b7cafa5 (docs) |
| `dd211a8a` | docs: record faef ordinary pass and activate authorized RR continuation | → 3b7cafa5 (docs) |
| `29fe9236` | fix: 评论重复键恢复按当前读查找已提交赢家 | → 3b7cafa5 |
| `e9b9e191` | docs: 交付评论RR恢复补丁并签收独立普通验证证据 | → 3b7cafa5 (docs) |
| `55c453d7` | docs: 登记本地冻结RR候选及普通定向准备状态 | → 3b7cafa5 (docs) |
| `cdc94fa5` | docs: 签收29fe普通64项实测证据并保留真库未验 | → 3b7cafa5 (docs) |
| `1ffca2d8` | docs: 记录仅保留现有验证入口并关闭新增真库方案 | → 3b7cafa5 (docs) |
| `1b3905f9` | docs: 交付POI和候补定向事实及精确合同接续 | → 3b7cafa5 (docs) |
| `62f5f4e5` | docs: 明确既授源码锁与当前合同阻塞 | → 3b7cafa5 (docs) |
| `1bfa81a5` | fix: POI引用选项按既有上架状态对齐保存合同 | → 3b7cafa5 |
| `02b99bea` | docs: 交付POI查询合同修复和两项定向回归 | → 3b7cafa5 (docs) |
| `fb312a6b` | docs: 登记POI候选本地冻结及212串行排队 | → 3b7cafa5 (docs) |
| `a76268d5` | docs: 签收POI单类两项H2回归与锁拒绝历史 | → 3b7cafa5 (docs) |
| `dedb44d5` | fix: 原完成券身份未知仅审计并保留其他冲正 | → 98faa6da (owner decision 2: `UNKNOWN_IDENTITY_NOT_RECOVERED`) + f5d2cff0 (`E04_07Test`) |
| `13be9976` | fix: 旧广场收藏读写仅接受受保护迁移映射 | → 3b7cafa5 |
| `923170ab` | fix: 完成券保存精确原回执并保留未知审计边界 | → 3b7cafa5 (receipt `history_id`, migration, E04_07MySqlIT) + 478c5b23 (receipt fixture) + 98faa6da (clawback identity) + f5d2cff0 (`E04_07Test`) |
| `032090c7` | docs: 交付身份与原券回执三包及未验证边界 | → 3b7cafa5 (docs) |
| `e63d8ee9` | docs: record combined candidate admission refusal and correct parameterized coun | → 3b7cafa5 (docs) |
| `54fdc951` | docs: correct precheck running claim with second formal admission refusal | → 3b7cafa5 (docs) |
| `b034a4aa` | fix: 修正旧广场回归夹具的H2别名标识符引用 | → 3b7cafa5 |
| `40bb25f6` | docs: 签收35项异常并交原E02夹具修复候选 | → 3b7cafa5 (docs) |
| `acbd5f54` | docs: 签收原E02两类8项实测通过并释放修复槽 | → 3b7cafa5 (docs) |
| `0ee9a998` | fix: 阶段结算沿有效非空冻结节点检查完成门槛 | → 3b7cafa5 |
| `530e7228` | fix: 新增候补登记复用活动资格及报名截止检查 | → 3b7cafa5 |
| `541d36b2` | docs: 交付新增候补准入及阶段冻结门槛源码候选 | → 3b7cafa5 (docs) |
| `ff6c1567` | docs: 签收候补与冻结门槛四类60项普通回归通过 | → 3b7cafa5 (docs) |
| `6143c37f` | fix: 隐藏取消或下架活动的旧引用卡并保留正文 | → 3b7cafa5 |
| `c35a4635` | docs: 交付活动引用卡过滤源码及四项普通回归候选 | → 3b7cafa5 (docs) |
| `6c6f82d7` | test: 为评论重放集成夹具补齐活动引用空表前提 | → 3b7cafa5 |
| `26d8245b` | docs: 签收活动引用卡四项通过并分记自有真库夹具补表 | → 3b7cafa5 (docs) |
| `6c446369` | fix: 补齐三个社区真库夹具的活动空表前提 | → 3b7cafa5 |
| `8fe437b1` | docs: 记录三个真库夹具源码交付与未验边界 | → 3b7cafa5 (docs) |
| `a9ad53ae` | test: 补齐 E04-07 真库夹具撤销流水的商家归属 (#285) | → 3b7cafa5 |
| `8822ab38` | fix: 主题运行时终态归档改读本局结局正文快照 (#287) | → 3b7cafa5 |

| B05 / #231 commit | port |
|---|---|
| `2ac55d20` test: 补齐B05独占真库夹具的活动空表前提 | → 8d2a552e |
| `5ee9d50d` fix: 社区私有附件短签与稳定媒体身份重试 | → 8e1a981b (xcx parts excluded) |
| `b0dc1019` fix: 社区附件短签固定使用HTTPS | → 8e1a981b |
