# B 支付网关 v4 候选交付

结论：已用具体持久实现替换旧 all-reject Source，完成四路统一事实核验、永久支付意图/首次渠道锁定、CLOSED 后新尝试与原号保留，并修复商家建单事务边界及转账 UNKNOWN 分类。静态验证与 efa5c06f 的 Java 21 编译已通过；既有精选39项通过但未覆盖 B 新增支付用例，B 相关测试仍待主 CI 结果，不可集成或发布。新表为提案，未执行 DDL。

## 基线、隔离、候选身份

- 唯一仓库 https://github.com/liboyang42-cpu/hengyin ，公共基线与开工实际 HEAD 都为 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 原 `/workspace/hengyin`、分支 `work` 保持原状；B 独立工作树 `/workspace/architecture-day-b`、分支 `codex/architecture-day-b-pay-gateway`，历史 HEAD `3bca5e05328ded4dc47fddd4614f30eff13a28fa`。
- 历史转账 `090291402461c91377f5a494e92f01cf0887746a`、旧错误 Source 候选 `4f270d0451cc5f13737d0be2b40819fc3629445a` 均保留、不重写。不要直接集成历史 HEAD。
- 此前未编译测试即提交，是我的执行失误，环境受限不能替代提交门禁。用户后续明确批准“审查/秘密扫描后的独立 codex CI 候选可先 commit/push”例外；本轮仅按该例外发布隔离候选，编译测试通过才可正式集成。不合并、不部署。
- 最新完整补丁、拆分补丁及 SHA256 见 `artifact-sha256-v6.txt`；旧 v1/v2/v3 工件保留。`files-v4.txt` 列出精确 Java 文件。

## 已授权的精确领域方法与实际修改

| 文件/方法 | 原失败机制 | 最小修复 |
| --- | --- | --- |
| MerchantCommerceService.createOrder、reconcileOrder；私有 paymentTransaction | 订单外层事务未提交就请求微信；对账自调用付费方法缺少新的原子事务；NOT_FOUND 可本地关闭原单 | 入口 NOT_SUPPORTED，短事务提交订单/幂等重放结果后事务外 SDK；查询网络在事务外，付费 CAS+权益仍同短事务；NOT_FOUND 不关闭，仅原单 CLOSED 可终结 |
| ClubMembershipServiceImpl.reconcileWechatInvoice | PAYERROR/REVOKED 也能关闭本地 PAYING | 仅 CLOSED 关闭。renew/resumeInvoice/closeInvoice 及 prepare/attach 已有正确短事务，核对后不改 |
| RegistrationCheckoutServiceImpl.createOrReplay、retryPayment 两重载、createWechatOrder | 外部调用若被上层事务包裹会不耐久；本次参数拒绝不能证明原号之前未执行 | 应用入口 NOT_SUPPORTED，使独立订单/支付状态 service 提交后再 SDK；本次失败不释放原 PAYING |
| ApiCoopController.depositCreate、depositStatus | 缺 openid 也会先置保证金支付中；状态入口只读本地漏单无法恢复 | 先确认 openid 再 prepareDeposit；事务外统一查单，仅核验 SUCCESS 后调用已有三参数 markDepositPaid，保留领域金额/交易号/CAS |
| ApiCoopController.depositCreateApp | 已无外层事务、prepareDeposit 返回后才 SDK | 只核对，未改 |

未改非支付业务/权限/G 结算；没有改 mapper、接口签名、根 pom、CI 工作流或软件依赖；新增迁移登记于规范目录，并仅在共享 ci/migration-dependencies.json 增加本表的一条空依赖登记，由协调者统一集成，未运行 DDL。没有新增 Agent、后端实例、数据库或生产操作。

## 四域创建、永久事实、确认路径

| 域 | 创建链及本地金额事实 | 回调/查单后领域处理 |
| --- | --- | --- |
| CLM | renew/resumeInvoice → 已提交 club_membership_invoice(out_trade_no,amount_fen) → createGenericOrder(JSAPI) | handlePaidInvoice 保留归属、snapshot、version/CAS、交易号绑定；恢复/关单复用统一 queryOrderStatus |
| 商家 | createOrder → 已提交 oms_order(order_sn,pay_amount) → createGenericOrder(JSAPI) | handlePaidOrder 保留类型、金额、交易号/CAS及权益同事务；reconcileOrder 用短事务补记 |
| DEP | depositCreate/depositCreateApp → prepareDeposit 独立事务提交 coop_invite(deposit_out_trade_no,deposit_amount) → generic JSAPI/APP | markDepositPaid(no,cents,tx) 保留金额和交易号 CAS；depositStatus 新增主动补记；G 既有补记仍用同一网关 |
| 报名 | createOrReplay/retryPayment → RegistrationOrderService/CmsRegistrationService.beginPaymentAttempt 提交 → createOrder/createAppOrder | handleWechatPayCallback 保留订单归属、混合支付金额、交易号绑定；现有 mutation+DONE 同事务实现不变；OrderCloseTask/TeamGroup 继续用统一查单 |

每次 SDK 下单前，具体 freezePaymentIntent 以真实业务行 FOR UPDATE 校验已提交身份/金额，再独立提交 wx_pay_order_intent。网关发现环境仍有实际外层事务即拒绝，不以内部 NOT_SUPPORTED 假装业务已提交。

冻结记录包含 merchant、business 原号、实际微信号、biz_type/id、attempt_no、appid、APP/JSAPI、CNY、分金额、openid、description、notify_url 及长度编码 SHA256 原参数指纹；无 TTL、无删除/覆盖。业务唯一 active 约束跨 merchant，防止 UNKNOWN 时换 merchant 绕过；同业务尝试序号永久唯一。

用户决定已落地：首次提交锁定渠道及请求参数；超时/UNKNOWN/NOT_FOUND 不改号、不换渠道。仅统一查询核验原单 CLOSED 后写 closed_confirmed_at；下一次使用同一业务号生成新的确定性 WXP 微信别名，旧号/请求永久保留。首个业务号超过微信 32 字符上限时也使用确定性别名（现有商家 UUID 业务号长度 38/39，原样提交本就不符合协议）。回调、查单、退款运输层映射实际微信号，领域仍绑定稳定原业务号；附加 wx_out_trade_no/wxOutTradeNo 供追踪。领域已终结的订单不会被此网关自动复活。

通知先 SDK 验签，之后仅接受 SUCCESS；按实际微信号与原 merchant 用已初始化 JsapiService 官方查询，校验 appid/mchid/trade_type/CNY/total/transactionId/state/单号；有本地冻结记录还须逐项一致，然后才进入四域。不是“任意配置 appid 皆可”。历史既有微信原单本身作为创建时不可变事实，不读取会过期/清空的 payParams。没有 Source 空壳。

主动查询使用同样原单/快照校验。错字段或缺必需事实/查询异常不传领域写入；NOTPAY/CLOSED 的可选 transactionId/tradeType 可缺失，但仍要求原应用/商户/金额/币种。迟到非 SUCCESS 通知不调领域、不解渠道锁，重复成功仍由既有领域 CAS 和交易号绑定处理。

## 转账独立合同（供 A）

Map 签名保持不变。明确发送前本地拒绝显式 ok=false,ambiguous=false；SDK 调用后全部 ServiceException（包括 SYSTEM_ERROR/5xx/429）、超时、不完整受理响应显式 ambiguous=true，保留原批次/明细和参数。ok=true 仅受理，不能当到账。A 仍须用原 DEBIT/SUBMIT_INTENT 核验，不得用新一次本地拒绝证明原号从未执行。

查询只有 batch FINISHED 才查询明细；批次/明细的 merchant/app/batch/detail 身份、batchId/detailId、单笔数量及金额全部匹配，明细状态 FAIL 才 result=FAIL。批次 CLOSED 没有已证实“所有明细未到账”的保证，返回 UNKNOWN，A 不得按 CLOSED 回补。详见 `A-transfer-contract.md`。

官方依据：
- https://pay.wechatpay.cn/doc/v3/merchant/4012065168 原批次重试/查证、早期 NOT_FOUND。
- https://pay.wechatpay.cn/doc/v3/merchant/4012458841 发起批量转账、SYSTEM_ERROR 等。
- https://pay.wechatpay.cn/doc/v3/merchant/4012458877 批次状态 CLOSED/FINISHED 语义。
- https://pay.wechatpay.cn/doc/v3/merchant/4012458902 明细 FAIL 与字段。
- https://pay.wechatpay.cn/doc/v3/merchant/4012791859 原单查询，appid/mchid 为创建该原单时值。

## 调用者与共享边界

已搜索全部 createGenericOrder/createGenericAppOrder/createOrder/createAppOrder、queryOrderStatus/closeOrder/submitRefund 与 transferToBalance/queryTransferResult 调用者。除上述领域外，TeamGroupServiceImpl、OrderCloseTask、CoopInviteServiceImpl.markDepositPaid(String)、CmsTopicServiceImpl.resolvePendingDepositPaymentForSettlement 也读取网关支付事实；唯一实际转账消费方 CashWithdrawServiceImpl。

给协调者/G：CoopInviteServiceImpl.markDepositPaid(String) 在其事务中查询，CmsTopicServiceImpl 的保证金补记也可能持有领域锁查询；本轮未修改这两个文件，不碰 G 结算/退款金额规则。queryOrderStatus 的关闭事实提交只写独立意图记录，领域业务修改仍由原调用者负责。

## 实际验证和未验证

实际执行：工作树/基线/范围核对；git diff --check；Python 读取已缓存微信 0.2.15 JAR 的方法/类型；完整补丁 apply --check、应用到基线提取副本后逐字节比对；候选密钥文件门禁及 Python 内容规则扫描。证据文件记录命令和输出。这些均不等于 Java 编译/JUnit。

测试源码：转账8、回调矩阵9（四路正确/错误/缺失 appid/mchid/currency/amount/订单/交易号/渠道、查询错事实、重复乱序、冻结错配、关闭解锁、新别名路由）、签名测试31、主动查询5、意图锁6、真实 MySQL 意图 IT5；另补商家事务提交/NOT_FOUND、会员非CLOSED不释放、DEP主动补记与未知不改、退款别名测试，并更新报名原号晚到成功的真实存储用例。未执行修复前红测，不声称 red/green。

GitHub-hosted 的精选回归已在 efa5c06f 实际编译后端及全部测试源码，39项执行且无失败/错误/跳过；该套不覆盖 B 新增支付用例。B 相关 JUnit、新意图表 MySQL IT/四域完整链路、查询应答验签负控、真微信及生产仍未通过或未执行。MySQL IT 用真实 JdbcTemplate/DataSourceTransactionManager/两连接观察/并发；其领域表是最小 SQL 夹具，仅证明网关/意图表合同，不冒充四域全链路。

获准 CI 点名测试建议（检查每个 admin 测试实际存在、skip=0）：

```sh
mvn -B -pl chengyinhub-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WechatPayTransferClassificationTest,WechatPayCallbackFactsTest,WechatPayCallbackForgedSignatureTest,WechatPayOrderQueryTest,WechatPaymentIntentLockTest,WechatPayAppChannelTest,WechatPayNotifyUrlTest,WechatPayRefundNotifyUrlTest,WechatPayReadinessTest,RefundVerifiedFactsTest,WechatPayClubMembershipRouteContractTest,MerchantCommerceServiceTest,ClubMembershipServiceImplTest,ApiCoopDepositStatusTest,ApiCoopDepositAppPaymentTest test
```

新表 DDL 未获执行批准，WechatPaymentIntentMySqlIT 暂不调用。批准隔离 CI DDL 后，显式设置 B_PAYMENT_INTENT_DDL_APPROVED=1，在 GitHub-hosted 环境点名执行；缺环境硬失败，不以假库或跳过算通过。现有 RegistrationFundsMybatisIntegrationTest 也需获准存储门禁运行。没有改共享 CI 清单，由集成人接入。

## 迁移/预检/回退与阻塞

DDL 仅 `chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql`，从未运行。必须先获批并核对 MySQL 8 实际结构、ascii_bin、generated unique/null、金额分精度、字段长度、业务号唯一性，按四域扫描所有活跃支付；上线前停止旧 writer 并核对未知/历史请求。新代码查表异常不会降级放行，不能在缺表时先上线。

历史有微信原单但无本地冻结参数时，回调/查单继续按真实原单核验；新预下单会在首次冻结前查证，并拒绝覆盖已存在的原单。不得拿新 appid 猜历史参数或直接补写请求指纹。历史 CLOSED 无快照的同业务再次发起目前保守阻塞，需按官方原单与保留参数逐笔审核后迁移，不能自动伪造 backfill。历史活跃但微信仍 NOT_FOUND 的 UNKNOWN 必须在迁移预检中单独隔离、保留原号，不能作为无历史的新单放行；这仍是迁移验收阻塞，不能靠没有小程序用户跳过资金记录盘点。

回退先停止新预支付，保留新增表/所有原号/参数/回执/账本/审计，不 DROP/DELETE，不退回会改号/UNKNOWN 回补的旧逻辑；若已使用 WXP 别名，必须保留映射读取能力，否则回调/退款无法正确归属。故不是删表/回滚 jar 即可的可逆上线。

仍阻塞：受控 Java 编译和相关单测；新表执行审批与真实 MySQL 并发/提交/回滚证据；历史活跃 UNKNOWN 预检/原参数补证；四域真实端到端金额/交易绑定与到账后乱序验收。现有报名 mutation+DONE 原子提交保留，不声称其他三路已迁入 inbox。

首次 CI（efa5c06f）完整 gitleaks 通过；迁移布局/清单门禁因 SQL 放在 docs/proposals 失败，已移至规范 chengyinhub-admin/sql 并补 PR 影响分类。保留原候选提交与所有原始证据。后端编译/单测结果另见 CI 回读文件。

追加静态修正：查单异常测试夹具补齐意图数据库，使 SDK 404/5xx 真正被调用；会员非 CLOSED 负控断言实际查单/关单。dda99e55 迁移布局通过，迁移安全门还要求本表显式依赖登记，已追加单条空依赖。主 CI 后端失败摘要的日志连接器返回 Transport closed，已请求协调者提供 Maven 摘要，不能声称 B 支付用例通过。
