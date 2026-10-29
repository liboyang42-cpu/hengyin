# 保证金关单 SUCCESS：按原证明分流

结论：c9a16 的无条件 `DEPOSIT_PAYMENT_SUCCESS_DURING_CLOSE` 拒绝过宽，把有原持久证明的合法正向也拦截。本片恢复证明决定准入，未新增金额/退款/人工重试政策；旧 c9 文档的“所有迟到 SUCCESS 必须再试”解释由本片明确取代，旧提交/证据不改写。

Parent `e82229ef85e58159078a42956560356074192541`，tree `ed211d0215b206a682697d9470307c0fb0181d53`；独立 `codex/architecture-day-g-e822-deposit-proof`。只改分派的 CmsTopic 私有 pending-deposit 方法、两 unit、直接真库 fixture 与 G 文档。未下载大日志/原 artifact，故原后台三 error 引用 root 已核 XML，非本轮自跑。

## 三方谓词

| 输入 | 原当前结果/证据 | 本片结果合同 |
|---|---|---|
| 347b 原 IT 首次 close 后 SUCCESS | 原 facade 的 house fact double 会给 Reference；未装真实 fact 表，first assertThrows 实际 nothing thrown。这个断言本身不能证明“所有已付都强制再试”政策 | 保留原方法名、首次拒绝/原行不变/不抵扣/重试已付全部断言意义，把缺证明前提显式安排 |
| c9 改装真实 fact 表且保有精确 Reference | 原 SUCCESS 已真实 commit，仍用无条件 guard 强迫首次抛错，与合法正向冲突 | 有完整引用继续现有短 TX/current fence/typed 消费；不得仅因关闭过程中变成功就拒付 |
| e822 两 unit 原三个正向 case | `paid(ATTEMPT)` / 原回读携带 typed Reference、金额/币种/原 TX；unit 只验证路由，非 DB 验证 | 原参数 false/true、原正向结果与断言逐字保留；由下述 real SQL 正例证明真实持久资格 |

生产唯一 hunk：`resolvePendingDepositPaymentForSettlement` 的同原 business + wx attempt 精确 readback 校验之后，SUCCESS 必须经既有 `CoopInviteServiceImpl.checkedDepositPaymentReference` 检查附件。随后原 `applyObservedDepositPaymentForSettlement` 在 topic→invite→attempt 锁/原金额复核下调用原四参 `markDepositPaid`；真实 owner 的 `requireConsumablePayment` 校验持久 fact/hash、business/attempt/app/merchant/requestHash、current/not CLOSED，才供原 paid CAS 与回执。没有新 reader/bean/接口，也不从最近 fact 猜缺失引用。

缺/畸形附件仍是明确 IllegalStateException；合法但错误 hash、旧 attempt、CLOSED 后成功仍由原 typed owner 拒绝且保留事实/恢复票。CLOSED 才可进原 closable/status1→5；SUCCESS 沿 paid/status2，绝不转未缴或走 setoff。原 E7b 锁外查证、close 异常后精确查原号、短 TX、typedReference、current fence 和成功读回均保留。claim/分项/自动抵扣算法、SDK/FactService/Invite owner 及清理零改。

## 配套源码

- `CoopDepositPaymentSettlementTest`：原两个 lateSuccess 参数与其他场景全保留；增一普通 test 内的 missing id / missing hash / malformed hash 三形状，要求 pending/50元不变、从不进入 paid、unpaid CAS 或 attempt lock。
- `CoopSettlementTest`：原 `settleCoopTopic_closeReadbackSuccessConsumesExactTypedReferenceAndNeverSetsOff` 全部原断言不动；增缺附件反例，要求原错误原因、pending/50元、head/claim/detail/adjustment 全空、无退款/paid 调用或状态 CAS。仅新增必需 `anyString` 静态 import。
- `CoopDepositSettlementAttemptMySqlIT`：原 11 case 保留。原迟到成功负控现显式让真实 gateway 的第一次 SUCCESS 返回副本缺引用：`callRealMethod()` 先执行真实 SDK 查证和 B real SQL fact commit，再仅删除副本附件，不改原 fact/SDK/state，不 mock 成功。因此即使原事实保留，调用者也不能借 latest 猜引用。原 assertThrows/原 row 不变/重试paid/原交易/50元/closed为空、重放与 fact/hash 断言仍保留；新增同 fact id 错 hash 的真实 owner 拒绝、原行不变。
- 新 `lateSuccessWithCommittedExactProofFreezesImmediatelyAndReplayNeverDeducts`：复用现成独占 UUID 库、canonical fact/intent schema、真实 reader/writer/paid owner。无缺附件包装；第一次 resolve 必须直接返回 false（已付），status2 原 TX/原号/50元，真实 fact 与原 requestHash/5000分对应，原 intent 未 CLOSED，无退款号，第二次重放字节不变、fact/intent 各1且只 close 一次、无 closable。两侧重放使用首次原 pending observation，锁内读取当前已付行并精确重放；没有把当前 status2 对象冒充新 pending 输入（该 private helper 明确要求观察 status1，生产入口亦只派 status1）。只有原 SDK 边界 double；finally 原精确自有库清理保留。

新增声明为 unit各1 + SQL1（SQL原11→12）；参数化输入未删/拆/跳过。原其他10真库 case（包括旧 attempt / current CLOSED late SUCCESS / 原4→2恢复围栏）字节未改。新 SQL test 的受控投影故障不冒充真实渠道失误或 provider 成功验收。

## 交付与验证

源码/直接 callee/既有 helper/静态 imports/类型/签名已手工对照；Map 副本用 `Map<?,?>` pattern 和 `String.class.cast` 做运行时受检读取，不存在 unchecked generic cast 或放宽生产类型。没有仅 setTxActive 代替真实 writer、没有 noop/完整 freeze mock；既有 unit 交易 double 只保留原路由用途，不当 SQL 证明。

Git diff 与 SHA 可复核；**parse、测试、编译、Java、DB、HTTP、CI 全部 NOT_RUN**。不继承其他 cohort/client/HTTP/真库绿色。root 唯一集成后按本四文件实际影响复验；源码修补完成不等于资金验收完成。
