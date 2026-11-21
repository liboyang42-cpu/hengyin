# 答案归一 + 结算客户端 + 时区 一波修复 2026-11-14

分支 `fix/answers-checkout-1114`（基线 origin/master）。依据：`audit/fe-route-review-1114` 的 RESULT.md。
约束：不碰生产、不改 ci/architecture 与 workflow；Java 构建/测试按 AGENTS 只在 CI（VPS 侧）跑，本地未起 Java；本地只跑 node 单测与 lint:ui / lint:scope（未跑任何 selftest）。

## 1. 答案归一（E-A1–E-A7）

**共享归一器** `AnswerNormalizer`（chengyinhub-system/.../support）：NFKC → 去 `\p{Z}\s\p{Cc}\p{Cf}`（含 NBSP、U+3000、零宽、BOM）→ 去首尾 `\p{P}` → 小写。繁简不自动互转，只能写显式别名。归一后为空永不命中。分隔符常量 `SEPARATOR_REGEX = [、,，;；]`。

**「只放宽、不收紧」的实现方式**：每个判定点都是 `旧规则命中 || 归一器命中`，旧规则代码逐字保留（`BasicTextAnswer.matchesLegacy`、`AnswerPolicy.matchesLegacyAdvancedType`、旧 `matchesAny` 循环、旧 hangman 键、typeIn 原 equals）。因此任何今天判对的输入必然仍判对。

| 项 | 改动 |
|---|---|
| E-A1 | `BasicTextAnswer`、`AnswerPolicy.matchesAny`（含别名）接入归一器；NBSP/U+3000/零宽/「故宫博物院。」/「圣·约翰」可过 |
| E-A2 | 无 `answerPolicy` 的 qa.TYPE 改走 `AnswerPolicy.matchesAdvancedType`（旧比对 ∪ 归一器）；有政策走 `matchesAny` + 新候选集 |
| E-A3 | 备选分隔符统一 `、,，;；`；候选集 = 旧分段 ∪ 新分段 ∪ 整串（保证旧写法仍命中，如含 `;` 的整串答案）。作者编辑器拆分与作者预览判定改用 `utils/answer-text.js`（同一常量，测试对照 Java 常量防漂移） |
| E-A4 | `HangmanGameplay.keyMatches`：旧键不中再按 NFKC 键比一次，「ＡＢＣ」=「ABC」 |
| E-A5 | typeIn：原 equals 不中时，目标与输入两边 trim + 归一器（区分大小写时只做 NFKC+去空白，不折叠大小写） |
| E-A6 | 采用「键盘按目标出键」：`extraKeys` 取目标里 a–z 之外的字符（区分大小写时含大写）补一行键；输入只接受基础键或目标内字符。不收紧校验器，避免存量主题保存/运行被拒 |
| E-A7 | 删除 playkit-qa 未被生产调用的 `matchesAnswer` 与 `_matchesAnswer` 导出；改为 `tests/unit/answer-text-vectors.test.js` 共享向量 |
| 定向 QUIZ | `OrientTaskServiceImpl`：QUIZ 新建/更新的哈希改用归一器；提交时先比新哈希，不中再比旧归一哈希（存量行不失效）。SCAN 保持原归一 |

**限制（诚实说明）**：定向 QUIZ 答案库里只存哈希，存量行无法重算成新归一，所以存量题仍只按旧口径判（不会变严），作者重新保存该题后才享受新归一。

**证明**：`AnswerNormalizerTest`
- 向量：全角数字字母、NBSP、U+3000、零宽、「故宫博物院。」「「…」」、「苹果，梨」备选、hangman「ＡＢＣ」、繁简需别名、纯标点不命中；
- 性质测试（固定种子，各 4–6 万随机对）：BasicTextAnswer / qa.TYPE 无政策 / qa.TYPE 带政策，旧规则命中 ⇒ 新规则必命中，并断言样本中旧命中数足够大。

## 2. 结算 / 支付客户端

- **E-B7**：`checkout-workflow.js` 超时不再把 `paymentSettled` 置真、不再丢真回调，只启动旁路核对原订单（不建新单、不再拉起支付）；核对确认已付 → `onPaySuccess`；不明 → `unknown`；真回调迟到则作废旁路核对并走正常核账；新增 `recheck()`。页面 `onPayUnknown` 弹「刷新订单状态」，并后台轮询（5s×6，核对在途不占次数），确认已付走 `_onSelfPlayPaid`；轮询/刷新期间仍不明不重复弹窗。
- **E-B8**：`_loadSignupProfile` 区分请求失败与资料为空；失败只 toast「资料没取到」，不弹「还差姓名和手机号」、不跳资料页、不覆盖已缓存资料。结算页资料行失败时保留原值。
- **E-B9**：报价 `failed` 不再回退标价：总价/明细为「—」，支付按钮改「重新获取价格」（`onCheckoutRequote` 只重取事实，不建单）；`onSelfPlayConfirm` 对 failed/refused 兜底拦截。
- **E-B10 / D-24**：`_rebuildUsed` 在每次点击确认（新购买意图）复位；删除无调用且只覆盖一条支付通道的 `_retrySelfPlayPayment`。
- **E-B13**：评价提交带 `request_id`（页面一次意图复用同一个，成功后清）；服务端 `ApiCommentController.addComment` 新增可选 `request_id`（格式 16–64 位），Redis 键 `comment:add-request:{member}:{rid}` 去重：在途拒绝、已落库返回原评论、失败释放；无 request_id 与旧调用完全不变（保留 6 参重载）。无新表、无 migration。
- **走查：支付失败 toast 盖住同意行**：结算页开着时 `<cy-toast lift>` 抬到底栏之上。

## 3. 时区 / 订单状态

- **E-D4**：`requireWithinTimeWindow` 用 `clock.now().toInstant().atZone(BUSINESS_ZONE)` 取分钟，不再依赖 JVM 默认时区。`TimeWindowBusinessZoneTest` 在默认时区为 LA / UTC / 奥克兰下验证。
- **我的订单**：已付款但从未开局（无 `firstSuccessfulScanAt`）的自玩通行证显示「可使用」（key `not_started`），开局后才是「进行中」，到期仍「已结束」。原三条把「未开局」夹具当进行中的断言已按新语义更新。

## 测试 / 校验

新增或改动：
- JUnit：`AnswerNormalizerTest`、`TimeWindowBusinessZoneTest`、`ApiCommentRequestDedupTest`（未在本地运行，交 CI）
- node：`answer-text-vectors`、`self-play-payment-recovery-1114`、`journey-rate-request-id-1114`、`checkout-workflow`（重写两条旧的「丢回调」断言为新契约并加 4 条）、`r2-store-checkout-view`、`playkit-typein-terminal`、`order-status`、`r11-g3-order-truth`

本地已跑并通过：本次改动涉及的 node 单测文件（checkout-workflow、r2-store-checkout-view、playkit-typein-terminal、order-status、r11-g3-order-truth、self-play-payment-recovery-1114、journey-rate-request-id-1114、answer-text-vectors 等）、`lint:ui`（UI-GATE-0 PASS，已同步 topic/index/index.js 的 U4-DYNAMIC 基线行号与 digest）、`lint:scope`（PASS）。
全量 `tests/unit` 本地运行耗时过长，截至提交时未出结果；以 PR #831 的 CI 为准。
Java（JUnit）按 AGENTS 不在本地运行，等 CI。已同步更新两条旧断言（`AnswerPolicyTest` 的 U+3000、`AdvancedActionJudgementTest` 的无政策全角），它们原先钉的是「不放宽」，与本次「只放宽」决定一致。

PR：#831（未合并）。CI 状态以 PR 为准，失败项在 PR 内继续修。

STATUS: PARTIAL - PR #831 已开，CI 与全量 node 单测、Java 测试结果待出
