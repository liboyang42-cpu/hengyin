# B 对 11c / 最新 master 的最小移植库存与冲突报告

结论：**不能把旧 B/8d3 整分支合回 master。** 最小待移植 B 范围为 30 个文件（9 个支付生产文件、20 个支付测试、1 个新增迁移）；另须由 root 处理既有 E2E 显式接口适配 2 文件及迁移登记 1 文件。B 的旧 HTTP controller 排除，保留 #586 的 SUCCESS 204 / 其他 500；B 的 WechatPayCallbackHttpAckTest 是不同测试文件，保留其四路分发/重复ACK断言。本轮只新增 B 目录中的此报告、11c及最新master两份完整库存及两处冲突的 review.patch；没有修改生产、A 测试、共享 CI、#586 或既有分支历史，也没有生成可直接集成的新代码候选。

A 安全链交叉审查已先完成，报告提交 `235dd4a52aac4cf4bd5cf1e069b3ece91645d307`。静态未见安全链/JWT/AppToken误替身或身份覆盖的确证问题；35条实际执行仍由 root 以最终组合 SHA 回读。本次不重复该审查，不修改 root 的 6c11 构造器适配。

## 最新主线保护补充（发布前再次读回）

本轮最新只读master HEAD为 `3937086e92164ac72162f263d5d794d88368500d`，tree `3f6ec21c80350075a9dc511453cd1f0705087eb9`，完整树12797 entries、truncated=false。它的两个parent为 `667a8302929e55f41e299eb2bb394391e01c4fc5`（#585 merge）和 `3cacabd79af9548449ff85383ccd0b3b476e21ef`（#586）；#585的第一parent为11c，故三项主线变化已在当前master祖先内。**后续创建支付候选仍须再次读取当时master，不固定用11c或本次393。**

最新master与8d3的完整两树差异为 **236文件 = 72个仅B变化 + 161个仅master变化 + 3个双方变化**；公共基线→最新master为164路径。新增双方变化是ApiRegistrationController，由#586持有，因此B该旧文件排除；CmsRegistration/迁移登记的master blob仍等于11c，本报告两处最小review.patch仍适用。最新完整逐blob库存见 [port-master-393-inventory.tsv](port-master-393-inventory.tsv)，SHA256 `d75786cda7baac9f6c0f47236798445b4ee8f294bdb7d43567173e6987e5a3ac`。下文232路径仅为此前11c快照，不代替此最新库存。

#585保护路径：ApiClubController.java、PrivacyVisibilityExportMySqlIT.java、ApiClubBecomeLeaderCertImagesAcceptanceTest.java、RoamMapper.xml；#586保护路径：ApiRegistrationController.java、WechatCallbackHttpAckTest.java。全路径及最新blob均已列入库存，B不修改这六文件。保留#567 PointsValuePolicy/0.02元每积分/50元上限及TopicSizeLimits30章300节点；保留#586支付与退款两路HTTP204/500，不回退#585隐私/资质图。委托消息报告#586三项真实测试已通过；本轮没有自行取得其XML，作为协调方提供的既有证据，不记为B实际运行。

## 固定身份与完整差异

| 身份 | SHA / tree |
| --- | --- |
| 新主线 #567 merge | `11c8055e179930cd63a1d6e296b8285b0586dd16` / `99042a25713445028217379d87fc8ba83cb14562` |
| root 正在验证的旧 B 候选 | `8d3d15e68ca13696140e7b54e8908ad232c502d9` / `1c68394e8727ef87f43dc75382ff17418467b791` |
| 两者公共基线 | `2ec68899099a1e250df0949a8383bf85b5d3ffe0` |
| B 当前报告工作树基线 | `/workspace/architecture-day-b-h-tests`，`codex/architecture-day-b-h-regression-tests`，`235dd4a52aac4cf4bd5cf1e069b3ece91645d307` |
| #586 HTTP 所有权 | `3cacabd79af9548449ff85383ccd0b3b476e21ef`，parent `05c91471e0b0e8a34343065d777156d1063c94fc`；仅 controller + WechatCallbackHttpAckTest，无 service 修改 |

GitHub compare 为三点比较，不能把它当最终两树差异。两个方向分别读回：公共基线→8d3 为75路径，公共基线→11c 为159路径；两完整递归树分别12833/12796个entry，均 `truncated=false`。逐 blob 比较最终两树得到 **232个不同文件 = 73个仅B变化 + 157个仅主线变化 + 2个双方变化**，无清单外差异。双方同时变化只有 CmsRegistrationServiceImpl 与 ci/migration-dependencies.json。

完整232行见 [port-11c-inventory.tsv](port-11c-inventory.tsv)：每行包含路径、处理分类、双方相对公共基线是否修改、双方精确 blob SHA1 和字节数，缺文件记为 `-`。库存 SHA256：`e1718e38dd74fa6488596b788af92245d31232bf83081dfc574bba0a5857c4d5`。所有 `KEEP_MAIN` 的157文件须保留11c字节，禁止从旧分支覆盖；其中包含主线积分配置seed、PointsValuePolicy、TopicSizeLimits及其调用方/测试。此清单没有请求移入 root batch1 的旧 A/C/D/E/F 或 CI 工作流。

| 8d3相对公共基线的75路径分类 | 数量 | 后续处理 |
| --- | ---: | --- |
| B_PAYMENT_CORE | 9 | 只移下表支付方法；CmsRegistration从11c按新增行移植 |
| B_PAYMENT_TEST | 20 | 必要支付回归；以最终新主线SHA重新编译和运行 |
| B_MIGRATION_REVIEW_ONLY | 1 | 原intent新表提案；不执行生产DDL |
| ROOT_97_EXPLICIT_ADAPTER | 2 | root97已有E2E适配，保持抽象接口和显式false实现；不default成功 |
| ROOT_MERGE_ONE_KEY | 1 | root只合并一条migration依赖登记，不能复制旧清单 |
| EXCLUDE_HTTP_OWNER_586 | 1 | 仅排除B旧ApiRegistrationController；不并改#586的controller和WechatCallbackHttpAckTest |
| ARCHIVE_B_DOCS | 41 | 历史证据保留原提交引用，不整批带进新主线 |

## 精确方法范围与必须保留的合同

| 生产文件（路径前缀与全名见库存） | 最小支付范围 |
| --- | --- |
| WechatPayServiceImpl | 原转账submit/query的ambiguous分类；四路预下单/冻结及原号映射；handlePayCallback与queryVerifiedOrder/queryOrderStatus的原单事实校验；closePaymentAttempt、lockPaymentAttemptForUpdate及业务行锁；退款只保留原实际微信号映射，不改退款政策 |
| IWechatPayService | 仅新增abstract closePaymentAttempt(businessNo,observedWxNo)、lockPaymentAttemptForUpdate(businessNo,observedWxNo)，原Map形状不变 |
| MerchantCommerceService | createOrder先提交业务单再SDK；reconcileOrder传递观察到的原attempt号；新增closeObservedOrder/paymentTransaction，同事务原attempt栅栏+本地CAS |
| ClubMembershipServiceImpl | closeInvoice、reconcileWechatInvoice/applyWechatInvoiceObservation、closeObservedInvoice，W1 close不重解析W2，W2的真实SUCCESS可回填，保留owner/金额/version/CAS |
| RegistrationCheckoutServiceImpl | createOrReplay与retryPayment两个重载的事务边界；预下单异常不因本次本地拒绝而释放旧PAYING意图 |
| CmsRegistrationServiceImpl | 一个ObjectProvider字段 + processPaymentCallback内部CLOSED分支原wx_out_trade_no/currentattempt栅栏，共11行新增；其余全部保留11c |
| TeamGroupServiceImpl | reconcilePayingBeforeCancel支付段/closeLocally及PayingPlan传原观察号；NOT_FOUND保留UNKNOWN，不用宽限证明未执行；不改结算/退款政策 |
| OrderCloseTask | reconcilePaying及closeObservedRegistration支付收口；原观察号同事务核对后才本地CAS，不按NOT_FOUND时间回补 |
| ApiCoopController | depositCreate先验openid后prepareDeposit；depositStatus按网关原事实+现有markDepositPaid补记；不改CoopInviteServiceImpl/CmsTopicServiceImpl/G方法 |

同一业务首次意图锁定渠道和确定性微信号，冻结原商户/应用/币种/分金额及原参数hash，UNKNOWN不换号或换渠道；仅官方原号CLOSED确认后可创建新alias。冻结与本地关闭共用业务行锁，旧CLOSED只能栅栏原attempt，不能关闭新attempt。拒绝不改业务经济/权益，允许必要认证/审计控制。通知先验签，再核验官方原单和冻结事实，四路沿用原域金额、归属、状态CAS及交易号绑定。报名业务mutation与inbox DONE继续同事务。

现有真实 WxPaymentCallbackMutationService、WxPaymentCallbackInboxServiceImpl、WxPaymentCallbackInboxMapper.xml 和原 inbox/outbox migration 在11c/8d3逐blob相同，分别为 `100f684ba7169d1db0ab056aac2ddcc5ab6d8b2b`、`66b3180ef351fc433ee5a09a712b164b41ee3a67`、`32181a20f33a62391eafaff5d752ab9c5d68dd0b`、`bd9053b41216476e557064c9c374baa8f4eeb8ce`。不重复移入这些文件，不扩大为所有路由已完成inbox迁移。

## 两处共享冲突及HTTP排除

1. **报名服务为语义冲突风险，无文本冲突。** 11c blob `91ab6f54d974549265a7fe45894259cbae6207b6`；使用公共基线、11c和B原文件做git merge-file只读三方合成，exit0/零conflict marker。合成仅11行新增、零删除，得到提案blob `21c7877f14d504915da0a766d8742503cafeeabd`。PointsValuePolicy.codeDefaults/rate/maxPerOrder的主线字段与全部调用保留；不得覆盖回旧常量实现。
2. **迁移登记整文件覆盖会丢主线。** 8d3旧清单缺少 `20261023_points_value_config_seed`。最小操作仅在11c dependencies增加 `"20261023_wx_pay_order_intent": []`；解析后删掉此一键即逐对象等于原11c JSON，积分seed登记及所有其他项均保留。共享清单由root合并，本轮只写B目录中的提案。
3. **#586另行拥有HTTP两文件。** 其ApiRegistrationController.java和WechatCallbackHttpAckTest.java均不由B写入。已静态核对#586新增的3@Test：gateway为mock，覆盖支付/退款SUCCESS、非法ACK及抛异常的HTTP应答；未执行真实gateway四路分发。B既有WechatPayCallbackHttpAckTest.java名称不同，其5@Test含真实gateway四路分发拒绝/异常500与重复成功204，静态可继续复用#586控制器，不修改两份HTTP文件；它也不冒充真实SDK或数据库验证。因而该独立B测试保留在20个支付测试中，不能删掉四路回归后以#586三项替代。#586在11c之后，当前393已包含它，不能声称仅有11c树已包含它；最终B组合仍待root运行验证。

[port-11c-conflict-review.patch](port-11c-conflict-review.patch) 仅展示上述CmsRegistration 11行与CI一键新增，不是完整可运行支付补丁，不能单独启用。该patch在两份逐blob匹配的11c文件临时副本上 `git apply --check` PASS；SHA256 `b5e75980fb25f841f50cfa6c5c1b808030b67b70b696e06a77b48c7bcaf9ea43`。未应用到任何仓库生产文件。

## 新主线策略与测试适配结论

主线PointsValuePolicy用于报名/积分商城共同积分汇率与单笔抵扣上限：缺省0.02元/积分、50元上限；TopicSizeLimits为30章/300节点。B不选新规则、不回退这些实现或测试。主线报名手工new实例已初始化codeDefaults，因此新增RegistrationCallbackIntentMySqlIT的真实手工装配没有新必注入字段障碍。该IT种子是已落库且已预扣积分的原订单，仅验证callback/inbox，不证明新报价或新订单积分定价；不能借它宣称共同策略已经验证。原fixture金额/owner/库存/权益断言保持，不为合并改价。

20个B测试的完整路径在库存；其中WechatPaymentIntentMySqlIT保留4@Test含原barrier/APP同参数alias重放，RegistrationCallbackIntentMySqlIT保留3@Test真实Mapper/原schema/代理事务/真实验签入口，CLM三态保留已修a3fdd的动态CAS回读。E2e两文件须复用root97的显式false适配，而非在安全接口加default。新主线组合后须重跑这些测试及主线PointsValuePolicyTest/TopicSizeLimitsTest等既有回归，不能引用旧基线结果代替。

## 实际验证、迁移与后续门槛

本轮实际执行：GitHub精确SHA/commit/完整树只读核验；Python完整232路径分类及双方blob比对；B非文档/非E2E源码与4ffa逐blob核验；三方文本合成、零删除策略保留检查；迁移JSON一键守恒；临时副本patch apply --check及git diff --check。**Java/JUnit/MySQL/Redis/CI实际运行均0**，未启后端、未调用微信、未运行DDL。root正在跑的8d3 full run40062077880结果未在本报告回读，不声称通过，也不能作为未来11c移植SHA验收。

新增迁移仅migration_20261023_wx_pay_order_intent.sql。真实副本预检需核对四域原号/原金额/状态、历史活跃UNKNOWN的原应用证据、重复身份、已有表SHOW CREATE与generated active_biz_id唯一约束；禁止假backfill、清理资金/回执/审计或以NOT_FOUND释放。原[B迁移预检](https://github.com/liboyang42-cpu/hengyin/blob/4ffa600cbb89abfc7e1e5a77d5d99c8aa8b10e3d/docs/delivery/architecture-day/B/migration-preflight.md)可复用，但主线新积分配置seed须保留登记。生产DDL仍未授权执行；GitHub-hosted临时MySQL由root统一安排。回退需先停新提交并保留intent/原号映射读取，不能drop/delete或让旧网关不认识已生成alias。

此轮先交完整库存给root审查。后续仅从移植时再次读回的实际最新master创建新的codex候选，按30个B文件方法级移植并保留3个root适配，再与#586组合；不merge master、不重写旧候选、不整支cherry-pick旧基线。最终以新候选完整SHA登记Java/真实MySQL/HTTP结果；若主线在11c后继续变化，先重算双方blob和冲突，不套用本报告旧行号。
