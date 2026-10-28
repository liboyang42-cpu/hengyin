# WP04 A：消费 B 真实成功事实与原 inbox 引用

A 调用侧代码已写，静态检查见同目录输出；**Java/JUnit/MySQL/CI 全部未运行**。直接父 `b681cf0604d2e039c59ee1ca5034a71ec4525801`，独立分支 `codex/architecture-day-a-wp04-verified-facts`。不包含兄弟候选 fd350 的创建首锁8行，root须按 hunk 成组叠加。

## 真实依赖与必要接缝

实际 fetch/read 的 B 最新组合源为 `3893bc66d097ff4980ea0b76f3285197f0a3fbe3` + `041534c7681e480f80eb8afc911f65bf1ed13eda`（含0ff SDK拆分；初始6d事实合同已核对），依赖真实 `WechatVerifiedPaymentFact/Reference`、`WechatVerifiedPaymentFactService.Stored/requireVerified`、`registerVerified/processVerified`、Fact Mapper/SQL 和 test support 的 **realFacts**。没有复制 B Java/DDL，也没有本地 DTO、内存生产事实、默认成功或 credential/hash fallback。此 A 头单独没有这些 B 类型，不能独立编译；root组合B0ff路由与B事实后验收，不整 blob 覆盖 paymentAttemptGateway/Qualifier。

`required-B-current-reader.review.patch` 是必须由 B/root 接受的现有 reader 一行事务补丁（未应用 B 文件）：`requireVerified` 独立只读 REQUIRES_NEW，读取已独立提交的不可变事实。保持原签名、字段、校验与 hash 算法，不加业务锁。

理由可复现：取消 MANDATORY 事务先读 registration 建立 RR view；官方查单在 `recordVerified(REQUIRES_NEW)` 新建事实并返回原引用；旧 `requireVerified` 普通 SELECT 加入旧 RR，读不到该已提交事实，合法首次补记回滚。新 SQL 测试显式 RR，并先断言旧普通 COUNT=0，再要求同事务补记成功。**未经 fresh-reader 接缝组合，此项在B041（该 reader 仍为普通 SELECT） 上预期暴露缺口；不能凭静态检查称 B/A组合已通过。** 未借助新服务或 A 自建事务平台绕开 B reader。

## A 实际接线

- 支付 `handleWechatPayCallback` 的 SUCCESS 先解析真实 ref，由 B reader 核验 kind、业务号、原微信 attempt、transactionId、merchant/app/channel/CNY/整数分金额，再 `registerVerified/processVerified`。BUSY/缺证据/不匹配不能走普通 process 获得成功。正常原控制 inbox/fence/topic/current registration/CAS/效果/DONE 次序保留。
- 同事务 `applyConfirmedWechatPaymentWithinTransaction` 也会在原领域锁内经私有付款状态机读取、核对真实事实，使用事实的原 transactionId 完成原支付 CAS；不只信调用方的 SUCCESS Map。其原入口不领取新 inbox 的约束保留，不能在已持 topic 的事务后面反向领取 inbox。终态行仍保存原 provider transactionId；G 后续需显式绑定实际 ref/原分项，不能拿不存在的 inline inbox 回执造证据。
- 退款 `handleRefundNotify` SUCCESS（含大小写变体）统一成原 SUCCESS 事件身份，校验真实 REFUND ref／provider refundId／原 transaction／支付号／merchant/app/channel/CNY/分金额，再绑定 sidecar。mutation 内以当前锁定退款/报名的原 transactionId、原退款号及精确金额与实际 typed fact 二次比对，payout 与 DONE 同事务；不把 Map 或新配置当凭证。
- UNKNOWN/PROCESSING/缺字段不产生成功许可；不换原业务号，不释放事实引用，旧未绑定 DONE 不能升级成真实成功 ACK。实际通知仍只有首次终态提交后发出，失败回滚保留 B 已成立的不可变事实。

支付查单的 `trade_state=REFUND` 本身不含具体原退款及到账凭证，原分支却直接置整单已退、返全部扣分。本片改为 FAIL/保留原 inbox 待查证、业务零变化。合法原退款仍经 `RefundService.handleRefundNotify` 的实际成功引用处理；没有关闭该入口，没有猜部分退款应返多少积分或重算分项。

## 原测试与新增源码

- `VerifiedRegistrationReceiptGuardTest`：9 普通单测，只证明 typed reader／拒绝／busy 分支，不冒充存储或 SDK 验证。
- `RegistrationTopicFenceMySqlIT`：父13→本头20。原13方法场景保留；旧退款 helper 现真实写 B fact 表、读 B reader、绑定实际 sidecar。新7项：实际A支付+引用+DONE同提交/同号重放；Map-only零效果；DONE失败回滚后原事实恢复；旧未绑定DONE拒绝；旧RR后新提交事实首次合法补记；裸REFUND不能返积分/撤销整单；同业务号/金额但另一渠道transactionId的真实事实不能完成本退款。使用真实 B Fact/Inbox 与原生产领域 XML/事务，不用 MemoryMapper；测试生产者构造有明确字段的 fixture DTO，不声称真实微信验签/付款。
- 原 `RegistrationFundsMybatisIntegrationTest` 仍96。95项测试正文逐字节保留；原裸REFUND并发项两请求、六处断言全部保留，旧“SUCCESS、80→100、返还位1、订单4/3”改为“FAIL、维持80、返还位0、订单2/2”。这是撤掉无凭证经济效果，不删并发场景。实际合法创建/扣积分/库存及付款原断言仍在原95项中，未减少。
- 96项只在共用 fixture 用 B **realFacts(dataSource)** 与原 DDL 填入明确测试事实；原 inbox pass-through 的“每次重跑业务CAS、不记DONE”目的保留。其新增 verified adapter 必须经显式 reader 和同 ref 绑定才运行原 mutation，没有证据默认拒绝；**这个 focused suite 的 inbox 仍是桩，不作为实际 sidecar/DONE证据**。那部分由上述20真库类证明。
- 原 `PaymentCallbackFenceEntryTest` 10项未删，fixture补完整原scope与当前锁读，出站mock对齐B `WechatPaymentOperations`，原金额负控使用真实typed fixture再量测金额拒绝；锁前原单消失现在由统一fence拒绝，告警预期精确改为callback_not_completed，不冒充HTTP或SQL实跑。
- 原静态 `RegistrationPaymentAtomicityContractTest` 保留断言，适配先定义 Mutation、后择 verified processor 的代码形状；没有拿文本顺序冒充实际事务结果。

两套真库类执行前都要求受控 runner 的 `B_PAYMENT_FACT_DDL_APPROVED=1`；仅候选 scratch DDL，未执行生产 DDL。原96的 suite门控不变。root登记真实源码条数、合成source和迁移顺序后统一运行；本候选没有 dispatch 或修改CI/registry。

## G 接缝与剩余

`G-typed-refund-callsite.review.patch` 仅提议（未应用）：既有 `ICoopSettlementClaimService.reverseCompletedRegistrationRefund(refundId, Reference)`；在当前已证实 REFUND 终态之后、同 mutation/DONE 事务中调用，传**实际 B ref**。G必须按原冻结订单分项、原受益头和真实核销席位冲回，原事件幂等；接口没有 default/占位成功。该 typed 提议取代先前未应用的无 ref 渠道终态提议。人工双审须用自身真实人工回执，不能冒充 WeChat ref。

G仍拥有付款贡献/已收口头/原分项反冲、两种先后及失败恢复。A保留原 `settlementEntryService.recordOnPayment` API/次序，本片未将实际 ref 作为新G参数传入；G的精确typed付款端口与inline终态关联需root协调后实现。不能仅凭本片topic锁、provider事实或旧DIRECT明细宣称WP04全闭环。

实际已跑仅 Python/冻结 scan_java、保护文件/95正文/六断言检查、`git diff --check`、G review.patch 定位检查。文件 hash 和依赖见 static-result.json／B-reader-base.json。所有实际 Java、MySQL、回滚、清理、provider/G金额场景均 NOT_RUN。下一必要项：B041显式queryRefundFacts(originalPaymentRef, originalRefundNo)要求读回原PAYMENT Reference；正常回调已有实际sidecar可关联，inline补记目前仅保存provider transactionId，缺持久的原fact id/hash关联。不能从配置/别名/临时Map补造，也不能在现有inbox→topic路径下反向领取inbox造成锁序倒置。需root/B先裁决同事务inline绑定窄端口及统一锁序，再由A接原引用/G原分项；本片不宣称该恢复闭包已完成。root/B合入fresh reader及G真实ref端口后统一回归。D通知真库fixture仍需D/root按本真实fact合同接线，本片未覆盖D文件。
