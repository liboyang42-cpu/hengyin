# B 支付网关 v8 候选交付

结论：本轮已在源码中修复 H-B01 的旧关闭覆盖新尝试问题，并应用回调 HTTP 204/500 ACK。恢复合法、原单同状态的 CLOSED/REFUND 领域分发，保留终态重复成功 ACK。候选仍须由协调者在同一最终 SHA 上编译和运行相关 Java/MySQL 测试；本报告不宣称验收通过。未合并、未部署、未调用真实微信、未运行生产 DDL。

## 基线、隔离与身份

- 唯一公共仓库 https://github.com/liboyang42-cpu/hengyin ，参考基线和开工实际 HEAD 均为 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 原工作树 `/workspace/hengyin`、分支 `work` 保持原状。B 独立工作树 `/workspace/architecture-day-b`、本地分支 `codex/architecture-day-b-pay-gateway`；本地历史 HEAD 仍为 `3bca5e05328ded4dc47fddd4614f30eff13a28fa`，当前文件为其后的隔离候选。
- PR https://github.com/liboyang42-cpu/hengyin/pull/573 ，远程候选分支 `codex/architecture-day-b-pay-gateway-v4-ci`。本轮只追加一个聚合提交，父提交 `218ecae05a78185e689ad3bc115bea2e51aa752c`；最终 SHA 由提交回执和交付消息给出，不重写任何旧候选。
- 历史 `0902914` 转账、`4f270d0` 错误 Source、`3bca5e0` 报告，以及 `1c7be84/efa5c06/dda99e5/44f8e62/218ecae` 均保留。旧 all-reject Source 已移除，不可集成旧 HEAD。
- 先前未编译测试即提交是我的执行失误。用户后续明确批准“已审查/秘密扫描的独立 codex CI 候选可先 commit/push”例外，本轮仅使用这一例外，编译测试通过才正式集成。没有自行 dispatch、rerun 或取消 CI；之后由 root 统一执行。
- `files-v8.txt` 为精确 Java 文件；`artifact-sha256-v8.txt`、`patch-apply-evidence-v8.txt` 为完整公共基线补丁及拆分补丁校验。旧工件不删除。没有引入私有仓库历史、配置、备份或秘密。

## 失败机制与最小合同

| 方法范围 | 失败机制 | 本轮合同 |
| --- | --- | --- |
| WechatPayServiceImpl.freezePaymentIntent / lockPaymentBusiness | 预先准备结果可能在业务已关闭后仍发 SDK；旧 CLOSED 提交后可创建新别名 | 同一业务行 FOR UPDATE 内复核四域 payable、业务金额、原意图/最新尝试，再提交冻结记录；实际外层事务未提交时拒绝 SDK |
| queryOrderStatus / recordConfirmedClosedAttempt | CLOSED 标记提交和领域关闭有窗口 | 只标记所观察的实际微信号；同一业务行串行；有调用方事务时 REQUIRED 加入，避免挂起持锁调用方造成自锁；无外层时短事务提交 |
| closePaymentAttempt / lockPaymentAttemptForUpdate | 旧观察关闭时重新解析 current，会误关新号；领域只按 id/status 关闭 | SDK 关闭及回读固定观察号；领域关闭事务必须持有业务行锁并核对最新 attempt/商户/业务号，原观察过期则零关闭 |
| MerchantCommerceService.createOrder / reconcileOrder / closeObservedOrder | 订单未提交就下单；旧 CLOSED 覆盖新 attempt；自调用权益方法原子边界不足 | NOT_SUPPORTED 应用入口；订单/意图提交后事务外 SDK；已付 CAS+权益同短事务；关闭前同事务 attempt 栅栏；NOT_FOUND 永不凭时间关闭 |
| ClubMembershipServiceImpl.closeInvoice / reconcileWechatInvoice / applyWechatInvoiceObservation / closeObservedInvoice | 查询后 SDK 关闭丢失原号；旧 CLOSED 释放新尝试账单 | 观察号贯穿关闭；原金额/账单校验保留；关闭 CAS 与当前尝试校验共事务；只接受 CLOSED，不接受 PAYERROR/REVOKED |
| RegistrationCheckoutServiceImpl.createOrReplay / retryPayment 两重载 / createWechatOrder | 外层事务未提交；本次拒绝被当作原号未执行 | 订单/支付原意图先提交，事务外 SDK；本次本地失败不释放原 PAYING |
| OrderCloseTask.reconcileExpiredPaying / closeObservedRegistration | 同类旧关闭覆盖；NOT_FOUND 超宽限直接回补 | 保留实际号，原尝试关闭 + 当前尝试栅栏/settlePayingOne 同事务；NOT_FOUND/REVOKED 保持未知，不回补 |
| TeamGroupServiceImpl.settlePayingRegistrationsBeforeCancel / closeLocally | 活动取消支付收口丢原号；NOT_FOUND 超时回补 | 仅修改 PAYING 支付段，计划携带实际号，关闭在既有业务事务内核对当前尝试；未知拒绝取消，不改变权限、退款金额或 G 结算 |
| CmsRegistrationServiceImpl.applyWechatPayCallback 的 CLOSED 分支及懒加载网关字段 | 已恢复的旧 CLOSED 通知也可能覆盖新 attempt | 在原 inbox mutation+DONE 事务内强制实际号与当前尝试栅栏；缺实际号拒绝，不保留危险 fallback；原状态机/交易绑定保留 |
| ApiCoopController.depositCreate / depositStatus | 缺 openid 先改支付中；仅本地状态无法恢复漏单 | 先确认付款人 openid；统一查单事实核验后仅 SUCCESS 调已有三参数 markDepositPaid；APP 入口只盘点 |
| ApiRegistrationController.payCallback | FAIL JSON 仍 HTTP 200，微信认为已成功接收 | 仅业务 SUCCESS ACK 为 204 空体；FAIL/null/格式错误/分发异常为 HTTP 500 JSON FAIL；HTTP 头按不区分大小写处理；不回显原通知 |

共享最小接口新增 `IWechatPayService.closePaymentAttempt(businessNo,observedWxNo)` 与 `lockPaymentAttemptForUpdate(businessNo,observedWxNo)`。旧 Map 方法签名不变。唯一生产实现为 WechatPayServiceImpl。协调者须审阅这两个接口与上述报名 mutation 的精确增量；未改 mapper、根 pom、软件依赖或 CI 工作流。只增加本迁移的一条空依赖登记。

## 四域创建与事实来源

| 域 | 创建/提交 | 确认与领域效果 |
| --- | --- | --- |
| CLM | renew/resumeInvoice 的短事务提交 invoice(out_trade_no,amount_fen)，再 generic JSAPI | handlePaidInvoice 保留归属、冻结快照、金额、交易号、CAS及权益；原短事务 prepare/attach 保留 |
| 商家 | createOrder 短事务提交 oms_order(order_sn,pay_amount)，再 generic JSAPI | handlePaidOrder 保留业务类型、金额、交易号、CAS及权益原子提交 |
| DEP | depositCreate/depositCreateApp → prepareDeposit 提交 coop_invite(deposit_out_trade_no,deposit_amount)，再 JSAPI/APP | markDepositPaid(no,cents,tx) 及状态入口主动补记；未修改 CoopInviteServiceImpl/G 文件 |
| 报名 | checkout → 独立订单创建和 beginPaymentAttempt 提交 → JSAPI/APP | handleWechatPayCallback 的 mutation+DONE 同事务、owner/fence 保留；超时关闭复用 settlePayingOne 的库存/积分/权益处理 |

每次 SDK 预下单先永久冻结 merchant、稳定业务号、实际微信号、biz_type/id、attempt_no、appid、APP/JSAPI、CNY、分金额、openid、description、notify_url 和长度编码 SHA256 原参数指纹；无 TTL，不覆盖/释放业务唯一身份。首次提交锁渠道及请求；UNKNOWN/超时/NOT_FOUND 只能保留原意图。仅确认原号 CLOSED 后允许新确定性 WXP 别名，旧号/旧参数永久保留。超过微信32字符的商家业务号也使用确定性别名。

通知先 SDK 验签，再按原商户+实际号查询微信原单，核对 appid/mchid/trade_type/CNY/total/transactionId/state/单号；有新表冻结记录则同时匹配。不是从任意配置 appid 集合猜订单渠道。合法历史原单是原创建事实，缺表/读取异常不会降级跳过。SUCCESS、CLOSED、REFUND 仅在通知与原单事实同状态匹配后进入原领域；不接受 NOTPAY/PAYERROR/REVOKED 作为通知资金终态。CLOSED 的报名 mutation 再以原尝试栅栏防止旧状态覆盖新尝试；重复终态成功 ACK 保留。

主动查单同一事实校验；回调/查单 Map 同时给稳定 `out_trade_no` 与实际 `wx_out_trade_no`，退款保留 `wxOutTradeNo` 映射及原金额规则。

## H-B01 并发验证设计

`WechatPaymentIntentMySqlIT.oldClosedObservationCannotCloseNewAttemptAndNewSuccessCreditsExactlyOnce` 使用实际 MySQL、JdbcTemplate、DataSourceTransactionManager、业务代理事务、两个线程和 barrier：旧查单提交 CLOSED → 暂停旧领域关闭 → 同一业务新建 APP 别名 → 恢复旧关闭 → 栅栏拒绝旧关闭 → 新 SUCCESS 两次通知 → 读回业务 paid/新交易号/一次权益记录；旧号/旧应用及 CLOSED 标记不变，再次旧栅栏仍失败。

该测试使用真实网关和 MerchantCommerceService；SDK 为 double，领域 mapper 为 JDBC-backed Mockito adapter、权益落专用 fixture 表。它证明该竞争日程与事务/落库合同，不声称实际 MyBatis XML 或四域完整端到端已验证。会员、报名 task、报名 CLOSED inbox 补对应过期观察负控，四域 freeze 补业务已不可支付时拒绝。

## 实际 CI 证据、四项 MySQL 与 HTTP 注册

**已实际执行的旧候选证据**：

- `efa5c06f` GitHub-hosted Java21 编译了 system1900/admin280 生产源及 admin1892 测试源；精选39项既有测试通过、0失败/错误/跳过，该精选不覆盖 B 支付用例。
- root 取回 `218ecae` run40019783921 / job120641220198 完整日志：实际13822项，4 failures、0 errors、631 skipped，不能验收。三个 WechatPayCallbackForgedSignatureTest 失败根因为 B 的 SUCCESS-only 闸门错误阻断合法原同状态 CLOSED/REFUND（夹具原单事实本已完整），本轮修根因且保留原冲突/重复断言。另一个 WechatPayReadinessTest 仍期待 ambiguous 缺省，本轮只把断言改为明确 false，保留 fractional-cent/overflow 发送前拒绝和 SDK never 验证。
- 旧 `218ecae` gitleaks、migration layout、migration safety 通过；不冒充最终新 SHA 的扫描或 Java 结果。

**本轮本地实际执行**：`git diff --check`、Python 范围/缓存微信0.2.15 JAR符号核对、秘密文件及凭据标记扫描、全补丁 apply --check/应用后逐字节比对。详见 v8 证据文件。这些不是 Java/JUnit/MySQL 执行。

**本轮未执行**：新最终 SHA 的 Java 编译、相关 JUnit、四项 MySQL、真实 WeChat、生产链路。没有在云 VM/Mac 起 Java/数据库替代受控环境。root 已获授权可运行 GitHub-hosted 临时 Java/MySQL，无需再次等待执行批准；生产 DDL 仍禁止。

MySQL 类恰好 **4 个 @Test**，先前统计错误把 @TestInstance 算成第五项，静态计数现已改为正则边界：

1. fourDomainsRetainOriginalParametersAndConflictRollsBackWithoutOverwriting
2. concurrentChannelChoicesHaveOneCommittedWinner
3. oldClosedObservationCannotCloseNewAttemptAndNewSuccessCreditsExactlyOnce
4. sdkObservesCommittedIntentOnIndependentConnectionAndAmbientTransactionCannotSubmit

注册入口为 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/WechatPaymentIntentMySqlIT.java`；显式点名运行，不能以默认 Surefire 不选 `*IT` 算通过。要求 RUNNER_ENVIRONMENT=github-hosted、B_PAYMENT_INTENT_DDL_APPROVED=1、回环临时 MySQL；使用已有 CHENGYIN_MYBATIS_IT_* 受控测试配置，随机专属 schema，缺条件硬失败。四项当前 **实际执行0，待 root 回填同 SHA 证据**。

HTTP 类 `WechatPayCallbackHttpAckTest` 恰好 **5 项**：成功204（含大小写HTTP头）、错误/缺省/坏ACK500、异常500、四路真实网关分发的拒绝及异常500、四路重复成功204。实际路由和 HTTP，SDK/领域 double，不声称数据库或验签真实链路。生产注册入口是原 `POST /api/registration/callback`，没有新建 endpoint。

root 可在已批准的 GitHub-hosted 临时 MySQL job 中显式选取（不要在本云 VM 执行）：

```sh
B_PAYMENT_INTENT_DDL_APPROVED=1 mvn -B -pl chengyinhub-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WechatPaymentIntentMySqlIT,WechatPayCallbackHttpAckTest,WechatPayTransferClassificationTest,WechatPayCallbackFactsTest,WechatPayCallbackForgedSignatureTest,WechatPayOrderQueryTest,WechatPaymentIntentLockTest,WechatPayReadinessTest,MerchantCommerceServiceTest,ClubMembershipServiceImplTest,PaymentCallbackFenceEntryTest,OrderClosePayingReconcileTaskContractTest test
```

既有真实存储回归还须使用仓库 `ci/funds-mysql-it-gate.sh` 对应环境点名 RegistrationFundsMybatisIntegrationTest（CHENGYIN_FUNDS_MYSQL_IT=1、既有 Oracle MySQL8.0.45 门禁），确认未 skip。无新增依赖或放宽版本/验收门槛。

## 转账独立交付（A）

Map 签名不变。本地确定发送前拒绝显式 `ok=false,ambiguous=false`；调用 SDK 后 SYSTEM_ERROR/5xx/其他 ServiceException、超时、身份不符/不完整响应均 explicit ambiguous=true，原批次/明细/参数继续查证。ok=true 仅受理，不是到账。A 还须核验原 DEBIT/SUBMIT_INTENT，不得用新一次拒绝证明原号未执行。

查批次只在 FINISHED 后查明细；merchant/app/batch/detail 身份、ID、单笔数量及金额匹配且 detail FAIL 才 result=FAIL。单纯 batch CLOSED 返回 UNKNOWN，不能证明未到账。官方依据和字段映射见 `A-transfer-contract.md`（4012065168/4012458841/4012458877/4012458902）。

独立公共基线提交 `1c7be849402d5519035e2c2f11cd6a6a819ce288` 保留。完整 transfer-v2 patch 21910字节，SHA256 `d2bae8b120ce03cb6993373e0cf3b89b1cabc33ca4a20484c3c48029e50ddee4`；`transfer-v2.patch.gz.base64` 为可无损解码的完整工件，不依赖截断 diff。

HTTP 协议依据：https://pay.wechatpay.cn/doc/v3/merchant/4012791902 、https://pay.wechatpay.cn/doc/v3/merchant/4013070368 （200/204 成功 ACK，失败应4xx/5xx）。

## 迁移、回退限制与剩余事项

- 新表仅 `chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql`；已写最小 migration/preflight，未运行生产 DDL。生产启用必须先按 `migration-preflight.md` 核对真实列/金额/单号唯一性及历史活跃 UNKNOWN，禁止假 backfill、清空资金/回执/审计。获准临时 CI DDL 与生产授权分开。
- 历史存在微信原单但缺永久请求快照：回调/查单仍可核验官方原单；预下单拒绝覆盖。历史 CLOSED 无原参数的自动重发仍保守阻塞；历史活跃但 NOT_FOUND 的 UNKNOWN 必须迁移前隔离补证，不能按新单猜渠道。这是上线前迁移阻塞，不是 all-reject 的正常新单实现。
- 回退先停止新支付提交；保留表、原号、映射、参数、回执、账本与审计，不 DROP/DELETE，不回退到 UNKNOWN 回补逻辑。出现 WXP 别名后必须保留映射读能力，不能直接退回不认识别名的旧 jar。
- 搜索过所有预支付/查单/关单/退款及转账调用者；其他调用者包括 OrderCloseTask、TeamGroupServiceImpl、CoopInviteServiceImpl.markDepositPaid(String)、CmsTopicServiceImpl.resolvePendingDepositPaymentForSettlement。后两文件未改。G 保证金结算已先持 invite 行锁，与 B freeze 串行；但其现存 REVOKED/PAYERROR 可本地关闭策略交协调者/G 审核，本任务不擅改。
- 新最终 SHA 的编译/单测/四项 MySQL/barrier/HTTP尚无通过结果；四域完整领域/MyBatis端到端、官方查询验签负控、生产预检仍未验证。现有报名 mutation+DONE 原子合同保留，不声称其余三域已全迁 inbox。
