# A-WP12：资金与报名状态迁移核对及单点修复候选

结论：确认并修复一处真实入口漏口：后台报名通用 PUT 会把客户端的 `registrationStatus` 原样传到按 ID 更新的 Mapper，能绕过取消、过期和支付命令改变报名生命周期。本候选只在现有保护 helper 中剥离这个字段，复用现有 Controller 安全测试；没有修改权限、业务迁移规则、B 网关或 G 结算。

**代码已完成；Java 编译、Java 测试和 MySQL 验证均未运行，不能据此正式集成。** 实际执行的是 Python 源码反例/负控、XML 清单生成、差异和文件秘密门禁。审批、资金、权益并发和回滚的真实执行证据仍需协调者在获准 GitHub-hosted CI/VPS 收集。

## 基线与隔离

| 项目 | 记录 |
| --- | --- |
| 唯一仓库 | `https://github.com/liboyang42-cpu/hengyin` |
| 本批基线/开工 HEAD | `2ec68899099a1e250df0949a8383bf85b5d3ffe0` |
| 工作目录/分支 | `/workspace/hengyin-a-wp12` / `codex/architecture-day-a-wp12` |
| 冻结的上一批 A | `3bc4bb08caaba4cc300f2df25f90c4a070033bfc`，tree `7ae01cdd84425a78e236fabdfca9960de3199fe4` |
| 隔离 | 新工作树直接来自公共基线；不包含上一批 A 的代码，不改其分支/工作树；无私有仓历史迁入 |
| 提交边界 | 沿用用户批准的“仅供隔离 CI 的候选 commit/push”例外；不代表通过根 AGENTS 的编译/测试门禁 |

仓内搜索未发现 `WP12` 的独立定义。本批按协调任务中的“资金/报名状态迁移”核对以下真实链路：玩家报名建单/支付/取消/过期、退款审批和出款、核销/权益/撤销、商家承接报名、两条提现腿及回执恢复。会员续费、其他商品订单、B 内部 SDK 行为和 G 内部结算不在本批独立验收结论内。

源码状态写入索引见 [state-write-inventory.json](state-write-inventory.json)：10 个相关 Mapper、76 条命中状态字段/删除的语句，列出源位置、WHERE 和动态条件。它包含内部通用写接口，**不等于每条 SQL 都有外部可达入口，也不是运行后的 SQL/锁证明**。Mapper `<include>`、动态条件及调用者必须一起读；本报告列的是已追到的入口合同。

## 实际字段取值

以领域对象及 SQL 为准，不能把名称相似的枚举直接套入数据库。

| 聚合 | 持久状态及关键保护 |
| --- | --- |
| `cms_registration` | `payment_status`：0 未支付、1 支付中、2 已支付、3 失败、4 已退款；`registration_status`：1 待确认、2 报名成功、3 取消、4 过期；核销 null/0 未核销、1 已核销。积分返还另有 `points_returned` CAS |
| `refund_application` | 审批 `status`：0 待审、1 通过、2 拒绝；`payout_status`：0 渠道处理中、1 成功、2 人工待处理、3 人工已处理待复核、4 人工已复核、5 待派发、6 派发中。报名变为 4/3 不能单独证明现金已到账 |
| `registration_entitlement` | 0 应得且占容量、1 已得、2 失效；`redemption` 为 ACTIVE/REVERSED；整单 scan 的 status=2 表示撤销 |
| `cms_registration_merchant` | 审核 null/0 待审、1 中标、2 驳回；中标写 status=1，驳回写 status=3，驳回后合法重提回 status/audit=0/0；取消另用 del_flag |
| 银行提现 | status=0 待审、1 通过、2 驳回、3 已打款；用户撤回仅软删本人待审记录 |
| 微信零钱提现 | 本批不重复修复；原基线状态不能代替冻结 A 候选中的 DEBIT/原意图/UNKNOWN/回补证据合同，验收须联合 A+B 候选 |
| 回调 inbox | 0 WAITING、1 PROCESSING、2 DONE、3 RETRYABLE、4 IGNORED_STALE；owner+fence+lease 约束终态写入 |
| outbox | 0/3 可领取，1 处理中、2 完成、4 人工；自动领取必须 money_affecting=0，不能把租约重领当作付款未执行证明 |

`domain/enums/PaymentState.java` 的数值与上述报名字段不同；生产 Java 搜索仅发现该枚举定义，未见其被报名迁移调用。此处记录差异，不修改共享枚举。

## 真实入口 → scope → 迁移 → 回执/事务 → 恢复 → 测试

下表中的允许迁移是**基线源码所实施的行为**；待定合同另列。测试列仅表示已找到相关源码，全部 Java 套件执行状态为 NOT_RUN。完整方法索引见 [test-inventory.json](test-inventory.json)，含 38 个既有套件的测试注解计数及 void 方法（也含 fixture/helper，不能当作运行用例数）。

| 真实入口/调用链 | owner / scope | 允许迁移及拒绝门槛 | 回执/事务及恢复 | 已有测试/证据缺口 |
| --- | --- | --- | --- | --- |
| `POST /api/registration/quote`、`payment-readiness`、`create` → `RegistrationCheckoutServiceImpl` → `RegistrationOrderServiceImpl.createOrReplay` → `CmsRegistrationServiceImpl.create` | 登录 member；当前协议、minor gate；真实 owner/ticket/可售/容量/资金可收取 gate | 必填 requestId；同 member+requestId 及等价 fingerprint 回放原单；3/4 终态不回放为创建成功。新单通常 0/1，零现金走专门完成入口 | 创建/积分扣减/库存权益预占在现有事务；唯一键 winner；duplicate 后读原单；业务身份不因响应正文失效释放 | `RegistrationOrderServiceBoundaryTest`：无业务键、终态回放、fingerprint、waitlist；`RegistrationConsentGateTest`：拒绝先于 checkout；`RegistrationFundsMybatisIntegrationTest`、`RegistrationEquityMybatisIntegrationTest`：数据库效果。后两者未实际执行 |
| `POST /api/registration/pay`、`pay/app` → `retryPayment` → `beginPaymentAttempt` → `createWechatOrder` | 登录 member 必须等于原单 member；readiness/minor/金额、有效期、可售 gate；保留原 registrationNo | reg=1/pay=0 → pay=1；pay=1 可沿原号恢复；终态/异主拒绝；外部参数只在 durable PAYING 提交后返回 | `beginPaymentAttempt` 事务 CAS；SDK 在其提交之后。确定的本地预下单失败走 release 1→0；不确定保留 1 供原号查证/迟到成功。不能靠新一次本地拒绝证明旧次未提交 | `RegistrationPaymentRetryContractTest`、Funds suite 的 durable claim、uncertain preorder、definite local failure、late success；旧 UNKNOWN 后再本地拒绝的完整同原号链路还需 B 合同及联合验证；不在本批猜补 |
| `POST /api/registration/callback` → B 验签 → `handleWechatPayCallback` → `WxPaymentCallbackMutationService.apply` | 匿名 HTTP 回调不等于信任请求正文；签名/金额/交易身份必须经 B；业务层冻结金额、订单身份校验 | `claimPaymentSuccess`：pay∈0/1、reg=1 → 2/2；交易号冲突不可覆盖。`repairHistoricalPaidHalfState`：仅 pay=2/reg=1 且冻结金额/交易号匹配。FAIL/关闭路径 pay∈0/1→3，不能覆盖成功。零现金专门入口，不接受伪造现金 SUCCESS | inbox 以 PAY 身份登记；领取 owner/fence；锁业务目标后本地业务与 DONE 同事务。失败 rollback/release 后重试；成功重复只承认原事实，历史修复必须另有精确守卫 | `RegistrationPaymentAtomicityContractTest` 是源码/反射合同，不能当回滚证据；Funds suite 的并发成功、金额差一分、下游失败回滚、成功/失败竞争；`WxPaymentCallbackFencingMySqlIT` 才可提供真实 fence/锁证据，均未运行 |
| `POST /api/registration/cancel` → `cancelRegistration` | 必须原单玩家；已核销/已支付不可走未支付取消 | `cancelUnpaidIfActive`：id+member、reg=1、pay∈0/3 → reg=3/pay=3/points_returned=1；重复取消同 owner 返回既有结果 | 事务内 CAS winner 才恢复票库存、返积分、释放权益；任何必要写失败抛错；失败不提交半次取消 | `RegistrationCancelExactlyOnceContractTest` 仅源码合同；Funds/Equity suite 才覆盖 SQL/积分权益真实效果。需联合 CI 证明拒绝时整向量零变化 |
| `OrderCloseTask` / paying reconciliation → `closeExpiredOne`、`settlePayingOne`；另有过期 `/pay` 同步触发 | 系统任务，按到期及当前持久状态筛选；归属检查先于用户支付路径的过期处理 | reg=1/pay=0→pay=3/reg=4；pay=1 的关闭/取消必须先查证渠道并按代码完成关单；UNKNOWN 保留。单次 NOT_FOUND 不可被泛化成外部未执行证明 | CAS winner 后事务释放库存、积分 marker/权益；paying 分支先处理查得的成功，失败保留原号。**同步过期支付拒绝会提交释放**，见决策 D1；基线 NOT_FOUND+grace 规则见 D2 | Funds suite 的 expired rejection、UNKNOWN untouched、close failure、multi-ticket whole order；`OrderCloseUnpaidCursorTest`、`OrderClosePayingReconcileTaskContractTest`。存在明确预期冲突，不能仅因测试存在宣称合同合格 |
| `POST /api/registration/cancel-refund`、`cancel-by-owner` → cancellation read gate → `TeamGroupServiceImpl.cancelPaidRegistration*` | 玩家等于 member；owner 路径为主题作者/活动主题或俱乐部负责人；保留既有 RefundPolicy，不重写 RBAC | paid2/reg2 且符合未核销/探索票部分退款规则才取消；整单按 parent siblings 加锁并核对现金余额；部分履约走人工 case；纯非现金 `claimRegistrationRefunded` winner→4/3 | 本地状态/积分/权益/库存/退款单事务提交；有现金先落固定 RF 业务身份/待派发，外部退款 afterCommit，进程断在提交后由 reconcile 沿原号补派；G 账务为现有委托，本批不验其内部 | `ActivityCancelTest`、Funds/Equity suite：异主、核销竞争、整单现金余量、外部关闭排序；不证明 G 或真微信链路 |
| `/business/refund` POST、PUT `/review` → `RefundServiceImpl.saveRefund/reviewRefund` → `payoutAfterReview` | 既有 review 权限；真实 operator；通过须 maker≠checker；原报名现金及剩余额度核对 | 审批0→1/2 CAS；通过后原报名资格2/2→4/3。审核未应用/拒绝不得触发 SDK | 审批/本地退款影响事务；Controller 仅 rows>0 且通过时在事务外派发；崩溃后从持久待派发恢复 | `RefundControllerReviewPayoutTest`、`RefundRetryAndRemainderMySqlIT`：审批前后顺序/余量/幂等。尚无本轮实际 SQL 运行证据 |
| 退款派发、`RefundReconcileTask`、`POST /api/registration/refund-callback` → B → `RefundPayoutServiceImpl` | 已通过原退款单；固定原 refundNo；回调冻结金额及业务事实匹配 | 待派发 null/5 或过期6→6+token；受理→0，未知→5，确定失败→2，最终通知→1或人工2；6 租约过期不证明未退款 | 先保存原退款号，事务外调用，沿原号查询；callback inbox 及本地终态原子提交，迟到成功不能变成第二次出款 | `RefundPayoutMybatisIntegrationTest`、`RefundNotificationMybatisIntegrationTest`、`RefundRetryAndRemainderMySqlIT`；真实渠道语义归 B/联合验收 |
| `/business/refund/manual/{claim,takeover,release,handle,verify,refresh}` | 既有 review 权限；owner token+lease；经办人与复核人不同；不改政策 | 人工2领取/接管；同 owner/token 未过期才 handle2→3，异人 verify3→4；已 SUCCESS/PROCESSING/UNKNOWN 的渠道单拒领 | 原退款号先查证；失败不凭租约过期宣称未执行；人工凭证随状态 CAS，保留原单。空 refundNo 视从未提交及 CLOSED 解释见 D3，不能无证推广到提现 | `RefundControllerManualClaimGateTest` 明确列空号、UNKNOWN、PROCESSING、SUCCESS、CLOSED；`RefundPayoutMybatisIntegrationTest`。本批只登记其现有政策，不给 B 的 CLOSED 官方语义背书 |
| 玩家核销码 `/api/verify/*`；`/api/registration/scan_dynamic_code`、`scan_group_member_ticket`、`scan_qr_code`、`scan_qr_code_chapter`、`scan_qr_code_station` → 当前可用 gate / ledger | 原玩家查码；执行核销须真实 merchant/station/organizer scope；用户已支付且报名有效；当前时间窗；人工退款 case 冻结 | 当前 pay2/reg2，未核销时 `verifyIfNotVerified`→verification1；权益0→1及 ACTIVE 核销唯一身份；重复/异主/不可用票不发奖 | 锁当前报名与权益、CAS/唯一键 winner，在现有事务写核销/权益及本地效果；G settle 为现有委托。旧 `/verify_answer` 与 legacy coupon route 不应发资金 | `ApiRegistrationVerifyGateTest`、`RegistrationCurrentUseGateTest`、`RegistrationVerifyAndSettleTest`、`VerifyRefundCasMutualExclusionMyBatisTest`、`P0RedemptionChainMySqlIT`、`PerkLedgerServiceTest`。Controller mock 不能证明数据库或 HTTP AOP 鉴权 |
| `RegistrationAutoVerifyTask` → `autoVerifyExpiredSession` | 系统执行，paid/registered/unverified，过期缓冲及退款排除条件 | 当前匹配才0/null→1；无虚构个人 verifier；只处理允许的活动单 | 按当前行 CAS，后续奖励/结算复用现有入口；定时任务不得复活退款单 | `RegistrationAutoVerifyTaskTest`、Funds suite 的 autoVerify 与并发 gate；未实际运行 |
| `/business/registration/redemption/reverse`、`reverse-parent`、`verification/reverse` → ledger/整单撤销 | 既有 reverseRedeem 权限；真实后台 operator+必填原因；已结算/不允许的票型拒绝 | ACTIVE→REVERSED；verification1→0；scan→2；权益1→0须报名仍 pay2；退款只 invalidate 未核销权益0→2 | 现有事务写撤销与审计，整单/父单任一失败全回滚；奖励冲回选择沿用原政策，不能通过通用 PUT 伪造撤销 | `CmsRegistrationControllerSafetyTest`、`WholeOrderVerificationReverseTest`、`RedemptionRewardClawbackMyBatisTest`、`PerkLedgerMapperSqlTest`。权益 restore SQL 仅显式看 pay2，其他有效性由调用者负责；真实竞争需 MySQL，不把省略 reg2 单独定性为可达错误 |
| `PUT /business/registration` → `edit` → `stripImmutablePaymentFacts` → `updateCmsRegistration` | 既有 edit 权限；PII 独立原规则不变 | 通用编辑不得成为支付/报名/核销状态命令；**基线漏剥 regStatus，本批补齐**。正常 ticketName 等原有编辑字段照常 | 沿用字段剥离合同，Mapper 动态 null 不写列；请求可200并修改正常字段，**并非整请求被拒绝**。无需新回执/状态机 | 原 `editStripsPaymentAndVerificationFactsFromGenericPut` 扩断言；新增 `genericEditCannotCancelExpireOrResurrectRegistrationLifecycle` 覆盖目标1/2/3/4。只测 Controller 出参给 Service，不冒充数据库验证 |
| `DELETE /business/registration/{ids}`；POST 通用新增 | 原 remove/add 权限 | delete 已禁用；add 仍原通用新增，所需后台建单规则尚未明确（D4） | 不删除资金记录。本批不把内部 Mapper raw delete 的存在等同于公开删除可达 | `CmsRegistrationControllerSafetyTest.removeRejectsDeletingPaymentRecord`；通用 add 全面规则需另决策 |
| `/api/registration/merchant/{create,update,cancel,select}`；后台选择/驳回 → edit service / `DispatchServiceImpl` | 现有 PROJECT_MANAGE/effective merchant scope；更新/取消本人承接单；选择和驳回沿现有 topic/后台授权；不重写权限 | 驳回可按 expected status/audit+member+del0 CAS 重提至0/0且清审时；待审null/0→中标1/1或驳回3/2；已中标不能借普通更新/取消退回待审 | dispatch 事务、节点锁/当前状态 CAS；同节点中标唯一规则；冲突零行报告失败；取消留软删审计。非本单的商家开户状态不混用 | `MerchantRegistrationEditGateTest`、`ApiRegistrationMerchantOperatorScopeTest`、`RegistrationMerchantAuditTimeContractTest`、`CmsRegistrationMerchantControllerSelectTest`、`CmsRegistrationMerchantControllerAwardBypassTest`、`DispatchServiceImplTest`、`MerchantCancelAuditGateTest`；需将真实竞争测试与静态 SQL 断言分开 |
| `/api/withdrawal/create/delete` → `BankWithdrawalCommandServiceImpl` / `UmsMemberWithdrawalServiceImpl`；后台 `/business/memberwithdrawal/review,payout` | 登录 member；协议及 challenge；后台既有 audit 权限；真实 operator；银行凭证不改变现有复核人数政策 | 创建0，审核0→1/2，确认打款1→3带原凭证；本人只撤回待审0；后台通用增改删已拒绝 | 原请求键+fingerprint；申请/扣款流水同事务，驳回/撤回 CAS winner 返余额及流水同事务；复核打款 CAS 不再自动发钱 | `BankWithdrawalCommandServiceImplTest`、`WithdrawalPayoutStateMybatisTest`、`UmsMemberWithdrawalControllerSafetyTest`；这里只核对既有合同，不重复 A 的资金修复或替其联合验收 |
| `/api/wallet/withdraw`、`/api/club/settlement/withdraw` → `CashWithdrawServiceImpl`；后台 `/business/cashwithdraw/*`；定时 sync | 登录 member；前置冻结及可提额；既有财务权限/双人复核/冻结 | 以**冻结 A `3bc4bb08`** 的原扣款及原意图证据门槛为准；UNKNOWN/PROCESSING/迟到 SUCCESS/一次 NOT_FOUND 不能回补；CLOSED 未获证实不认最终失败 | 原号不换、先持久意图再 SDK；自动/人工共用经济效果至多一次和事务；保留调查及冻结。本批不复制/修改冻结 A 代码 | 冻结 A 自有报告/证据测试及新 MySQLIT 由根登记；B 联合验证尚在进行。本分支单独基线不能代表已集成 A |

源码定位可通过上述类/方法名及 JSON 的 mapper+statement+line 复核。权限和迁移的源码阅读不是运行态安全结论；表中没有为缺测试创造新的业务禁止规则。

## 单点漏口、最小测试与修复合同

失败机制：有 `business:registration:edit` 权限的现有调用者可发送 `PUT /business/registration`，例如 `id=5, registrationStatus=3`。Controller 原本会清除 paymentStatus，却保留 registrationStatus；Service 仅加 updateTime 并传 Mapper；`updateCmsRegistration` 的 `<if test="registrationStatus != null">` 会更新该列，WHERE 仅 id。于是原 pay2/reg2 可以变成 pay2/reg3，既未走 `cancelPaidRegistration` 的支付/核销规则，也未执行取消应有的库存/权益处理；同理可把终态改回 reg1/2。此处未宣称此请求必然立即重复出款，已证明的是交易生命周期可以绕过命令直接改写。

所有当前通用写调用者已搜索：

| 调用者 | 用途/处理 |
| --- | --- |
| UI `src/api/business/registration.js:updateRegistration` → `views/business/registration/index.vue:submitForm` | 现有后台编辑提交完整 form；补丁后状态字段被丢弃、既有普通字段仍可编辑；未改 UI/RBAC |
| `CmsRegistrationController.edit` | 唯一发现的该通用 Service 方法的外部 Controller 写入口；本批只在这里封 lifecycle 字段 |
| `CmsRegistrationServiceImpl.createMultiTicketRegistrationsInternal` 内部首单汇总 | 合法内部金额聚合，必须保留通用 Service/Mapper 能力 |
| Service 私有 `completePointPayment`、公开 `updatePaymentStatus` | 全仓搜索未找到当前生产调用者；记录为内部遗留能力，不作为外部可达漏洞证据 |
| `generateVerificationQrcode` 与 `TeamGroupServiceImpl` 退款后的报名取消标记 | 内部业务写入仍需通用 Mapper；不把全 Mapper 禁写状态当修复 |

最小回归先在已有 `CmsRegistrationControllerSafetyTest` 编写：原保护测试加入 registrationStatus=3 及其 null 断言；新增循环传1/2/3/4，验证四次 Service 写入都未携带 lifecycle，id/ticketName 正常保留，未调用 perk ledger。随后生产 helper 增加唯一一行 `r.setRegistrationStatus(null)`。没有新依赖、框架、接口或 schema。

实际源码反例：基线缺少剥离，四个目标值均能到动态 SQL；修复后均为 null；移除唯一保护行后同一反例重新出现；原权限注解完全一致。**这是静态源码负控，不是 Java 测试红/绿或 MySQL 实测。**

修复方法清单（未全文件重写）：

- `CmsRegistrationController.stripImmutablePaymentFacts`：增加一条字段保护；`edit` 及 helper 注释同步。
- `CmsRegistrationControllerSafetyTest.editStripsPaymentAndVerificationFactsFromGenericPut`：增加 lifecycle 输入/断言。
- `CmsRegistrationControllerSafetyTest.genericEditCannotCancelExpireOrResurrectRegistrationLifecycle`：新增最小回归；类注释与 assertNull import 同步。

## 拒绝零效果证据与待决合同

经济/业务/权益/外部动作零变化应覆盖完整前后向量：报名及 siblings、支付/退款身份、余额和积分流水、库存/entitlement/redemption、退款/出款单、回执终态及 SDK 调用次数。脱敏拒绝审计、限流控制记录可以例外。现有 Controller mock 的 never/noInteractions 只覆盖其观察范围，源码/反射测试只覆盖写序合同；H2 结果也不能替代 MySQL。缺少完整断言仅标成覆盖缺口，不据此判定业务错误。

| 类别 | 已找到的具体证据 | 仍待验证/决定 |
| --- | --- | --- |
| 身份/归属/协议/终态拒绝 | OrderBoundary、ConsentGate、VerifyGate、MerchantEditGate 的拒绝前不调用业务/SDK断言；Funds suite 的金额错分、过期支付恢复、不可核销等路径 | 需要在获准 CI 执行；部分 mock 没有检查完整数据库向量，不宣称全链路零变化 |
| 本地失败原子性 | Funds suite 的 downstreamDatabaseFailureRollsBackClaimAndRewards、expiredCloseRollsBackWhenPointReturnUpdatesNoMember；退款/权益/回执 MySQL 套件 | 全部未执行；源码事务注解不能替代真实 rollback |
| 重放/竞争 | unique business identity、CAS、inbox owner/fence；Funds 的并发成功/退款、verify/refund 互斥、P0 redemption、退款余量套件 | 原身份及经济效果至多一次须根联合 CI；通用 PUT 本次补丁没有新增资金写入 |
| **D1：过期支付拒绝** | `beginPaymentAttempt` 声明 `noRollbackFor=RegistrationOrderExpiredException`；catch 后 `closeExpiredOne` 再抛；已有 `expiredOrderRejectionClosesTheOrderAndReleasesHeldResources` 明确要求释放 | 与严格“拒绝零效果”冲突：需决定它是独立可见的过期收敛命令，还是将关闭移到后台且支付拒绝本身零变化。本批不自作决定取消原恢复行为 |
| **D2：NOT_FOUND 与原意图** | Funds suite 既有“过 grace 且无可用 prepayId 的 NOT_EXIST 则关单释放”测试；也有 grace 内保留/关单失败不改本地/UNKNOWN不改测试 | 时间流逝或单次查无不能独自证明从未受理。需确认 B 的支付查询/关单合同、先前 intent/unknown 与迟到成功处理证据；此处未判既有测试通过即可合规，未改 B |
| **D3：退款人工接管证据** | 现有 manual claim 测试将空 refundNo 视为从未提交，CLOSED允许领取；UNKNOWN/PROCESSING/SUCCESS拒绝 | 必须确认历史记录是否总在 SDK 前持久原号、CLOSED的退款官方语义。退款 CLOSED 不等于零钱 detail FAIL；没有迁移/生产调查就不填假证据，不把此假设推广到其他聚合 |
| **D4：后台通用建单/其他编辑字段** | 当前 add 直接调用 insert；通用 edit 仍有身份/票价等原有字段可写；已保护支付和核销字段但不是全白名单 | 后台人工建单/更正允许哪些冻结事实、如何回执/审计尚未给定。当前单行修复不代表其他字段已安全验收；不在本批以猜测政策实施跨字段重构 |

建议联合 CI 先运行修改的 `CmsRegistrationControllerSafetyTest` 并完成 admin reactor 编译；状态合同再复用上表已存在的真实 MySQL 套件，沿用项目固定 MySQL 版本和环境 gate，禁止用 skip、放宽 baseline 或 mock 冒充成功。执行由根统一安排，本批没有派发 CI、运行 Java、建立数据库或连接微信。

## 实际检查、迁移与回退

实际命令和输出见 [verification.txt](verification.txt)。可复跑的轻量命令：

```sh
python3 docs/delivery/architecture-day/A-WP12/audit_static.py
python3 ci/secret-file-gate.py
git diff --check 2ec68899099a1e250df0949a8383bf85b5d3ffe0
```

审计脚本需要公共基线及冻结 A 分支，并检查同机 `/workspace/hengyin-a` 工作树未变；异机可独立复核源码/JSON，但不能把缺少冻结工作树当业务测试失败。索引类型按测试源码特征分类，部分 suite 同时含 mock/XML/DB，类型不能证明测试真正启用。

候选文件为两个 Java 文件及本目录的报告、索引、静态复核脚本/输出/哈希清单。`candidate-files.json` 记录除清单自身以外的候选文件 SHA256；最终 commit/tree 由 git 生成后随交付消息报告。

无 DDL、无数据迁移、无资金记录删除；历史不一致报名不会自动修复，必须有原支付/取消/退款事实后另行调查。本补丁仅阻止后续通用编辑生命周期。代码可独立 revert，但 revert 会重开该入口；无法以回退代码恢复已经被旧入口改坏的业务记录。冻结 A 候选、B 网关、G 结算、schema、根 pom、CI 工作流、RBAC 均未修改。
