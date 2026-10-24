# A：WP00/WP01 数据库写入口事实清单（待裁决，不是权限表）

结论：全仓已枚举 **2990条Mapper statement，其中1327条写statement**；现有gate的shape判定为930条LITERAL_SHAPE_ONLY、397条UNKNOWN。生产2442个Java文件中记录1877处带类型的Mapper写调用位置；直接JDBC API只有调度会话锁类两处，另有真实生成器动态DDL入口。下面的“writer”仅表示代码具备调用能力，**不等于业务允许，也不证明部署数据库GRANT、实际RBAC、Quartz启用或运行态可达**。先供root/业务owner裁决，再由F接精确约束；本候选不改生产/测试/CI/baseline/鉴权/资金政策。

## 观察身份、范围与方法

- 唯一公共仓库 `https://github.com/liboyang42-cpu/hengyin`。远端 `refs/heads/codex/architecture-day-ci-integration2-20261023` 经`git ls-remote`确认前缀0c0d61266对应完整 **`0c0d61266b2ae54d77c5ad9cf8b3c8fdb86d4b16`**，随后显式fetch该SHA；tree `f944c065f7186d0d6e4eca6129e11dbe6f0c20df`。源固定在此，不追随之后分支移动。
- 独占工作树 `/workspace/hengyin-a-writer-facts`、分支 `codex/architecture-day-a-writer-facts`，开工干净；仅本目录。原A候选均冻结，D复制hunk/G现金结构源不动。
- 从固定Git blob枚举全部`*/src/main/java/*.java`（递归2442个）、生产resource XML（284个）、生成模板。不是按提现/报名等目录名筛选。没有读取数据库、环境秘密或生产配置内容。
- **SQL只复用**`ci/architecture/gate.py.inventory/sql_shape/literal_write_target`及它依赖的既有`ci/sql_splitter.py`，没有新增SQL解析器。`observed_table_field_writers`原样保留，baseline不重建/不扩大。gate和baseline SHA256见summary；本观察源不成为新的enforcement baseline。
- Java使用标准库词法索引：屏蔽注释/字符串后定位声明、接收者类型、调用位置和接口实现候选；每个文件/方法/SQL有hash。它不是javac、Spring容器、字节码或完整Java调用图。重载、继承、内部类、lambda、反射/链式取bean等不作确定运行态解析；自动入口逆向追踪全部标SOURCE_CANDIDATES，人工核对的重点链另列。

## 全量数量及KNOWN / UNKNOWN

| 事实 | 数量及严格含义 |
| --- | --- |
| Mapper写statement | 1327，含insert/update/delete；`path#statement`集合与既有gate一致；select标签前导DML额外扫描本源为0 |
| 字面目标 | gate可提取1271条、共368个不同字面目标；56条目标UNKNOWN（包括别名/JOIN、TRUNCATE、`${sql}`），不是剩余语句无效果 |
| 原始表/字段观测 | gate给出389个table hint键；含ON DUPLICATE等导致的`id/member_id/registration_id`误命中，不当作389张真实业务表或授权owner |
| 列 | gate原始column_hints不是完整写集合；动态SET/trim常为空，`#{...,typeHandler=...}`及子查询也会污染提示。DELETE/TRUNCATE无列集合仍有整行/表效果。人工选择的14条语句字段证据另列，不把其余列猜成KNOWN |
| Java直接Mapper写位置 | 1877；1699显式import/同类类型、54显式FQN、9同类Mapper default helper、115仅唯一简单类名候选。全部只是源码调用；最后115项尤其不能当运行态绑定证明 |
| 不确定Java路径 | 40处同名未解析调用；6处链式接收者/同名方法引用观测（含Redis同名delete，不算SQL写事实）；36条XML写statement未找到带类型直接调用，不能标dead或允许删除。整文件field-receiver补扫没有新增未索引位置，也不证明覆盖任意动态别名 |
| 入口索引 | 1486个带HTTP mapping的方法、165个公共Task/Job bean方法候选；后者不是已启用任务数。保留完整class/method annotation及源码permission expression，不把没有`@PreAuthorize`等同匿名可写 |
| 动态select观测 | 5条`${params.dataScope}`类select单列UNKNOWN可能效果，不计作已证写SQL；不能仅看select标签忽略动态扩展 |
| 直接SQL / 注解 | 两处JDBC API均在MySqlSessionLock；源中未观察到MyBatis Insert/Update/Delete/Provider注解。依赖生成/运行态provider仍UNKNOWN，不推断全应用无其它DB能力 |
| 反射 / 生成器 | 223处宽口径runtime-dispatch词法观测（包括method reference/getMethod误命中，非223个反射漏洞）；10个SQL/调用相关生成模板；实际Quartz目标来自数据库，未读运行库。资源XML在gate模式外未发现mapper/DML标签，不等于classpath/外部插件被证明无writer |

`KNOWN_LITERAL_TARGET_ONLY`只确定gate支持的字面目标；`LITERAL_SHAPE_ONLY`也不证明作用域、授权、原子性、拒绝零效果或字段完整。凡UNKNOWN保持UNKNOWN，不以当前跨域writer反推允许。

## 重点域：真实写位置与权限入口

精确行锚+SHA见`manual-chain-anchors.json`；每条XML→Java直接调用→入口候选见`focus-index.json`。下表人工读过关键调用/检查；仍非运行态授权验收。

| 域 / 表、列示例 | namespace → 当前写调用 / 真实入口 | 权限 / scope事实与限制 |
| --- | --- | --- |
| 提现：`member_cash_withdraw`状态/扣回证据；`member_cash_withdraw_fund_fact`原身份/金额/kind；`ums_member.balance` | MemberCashWithdrawMapper：CashWithdrawServiceImpl写状态/渠道/回补；WithdrawalBalanceAdmissionService同事务登记DEBIT并bind。ApiWalletController.withdraw及ApiClubSettlementController.withdraw都调用ICashWithdrawService；WithdrawTransferQueryTask.run调用syncPendingWithdrawals | Wallet取getAppUserId且快速提现开关；Club入口另校验本人为club.memberId。后台CashWithdrawAdminController：manage用于重查/人工回补、freeze用于冻结；业务复核/证据仍在Service，不以权限代替原扣款。任务bean及迁移存在不证明sys_job已启用 |
| 报名：`cms_registration`会员/票/数量/积分/原号/支付状态 | CmsRegistrationMapper：通用后台add经insertCmsRegistration；内部正常createRegistrationInternal/createSelfPlayPassInternal直接mapper。AccountDeregistrationMapper仅清所列PII；TopicEditionFundsMapper.classifyUnknownExplorePurchases经JOIN修purchase_kind | CmsRegistrationController add/edit/remove分别原business:registration:*权限；API创建/取消取原App会员。TopicEditionFundsController.repairPurchaseKind有topic:edit及canEditTopic当前scope入口。PII与经济列不同，不因三个namespace就认定三个均可改交易依据；裸POST已复现P1仍待产品选择，不在本批修 |
| 支付intent/回调：`cms_registration.payment_status`；`wx_payment_callback_inbox/outbox` | CmsRegistrationMapper claimPaymentProcessing等；inbox由InboxService.register、ClaimService.claim/release、MutationService.apply写不同阶段；Mutation.apply调用业务mutation后markDone；outbox由既有原号补偿生产/消费写侧 | ApiRegistrationController.callback是@Anonymous，入口交WechatPayService.handlePayCallback解析/验签，再走业务流程；匿名回调不等于无需供应商事实校验。Java callback接口/lambda实际目标、外部provider终态和联合事务未由词法图证明。lease writer与业务终态writer是观察到的分工，不是允许任意Service写inbox |
| 主题现金：`coop_settlement.amount/status`、claim/detail/adjustment、现金三证据表 | CoopSettlementMapper由CmsTopicServiceImpl原结算/出款及ClaimService.persist写；CoopSettlementDetailMapper.refreshOriginAggregate也UPDATE coop_settlement.amount/verified_heads。现金证据新Mapper的record* default helper调用insert*，源中未找到生产调用record*的入口 | CoopSettlementPayoutTask.run→payoutDueCoopSettlements；实际调度配置未知。alias UPDATE目标在gate为UNKNOWN但人工可确认写coop_settlement。不能因此准许新直接改原额；新三表writer存在不表示资金共同fence/消费者已接通，也不选择退款算法或多票固定费 |
| 玩法receipt/progress：`game_command_receipt.outcome/result_json`、`player_node_progress.status`、`photo_check_intent.state` | GameSessionRuntimeMapper除RuntimeService外，ApiClubLead.unlockChapter和ApiPlayProgress.executeBasicIntent也直接claim/complete；updateAppliedReceiptResult用于原广播重发读回结果。PlayerNodeProgressMapper由ProgressService/ShopDayProofService写；PhotoCheckIntentService拥有photo intent各阶段 | 队长入口当前resolveLeaderMemberId及事务，基础玩家入口basicIntentScope和原身份/版本检查；GameSession controller/Service还有自己的actor policy。无统一后台permission串不等于公开授权；原receipt补充结果更新已有具体恢复用途，不能擅定所有APPLIED正文永久不可改。C合同/真实故障测试仍是验收来源 |
| 通知intent：`im_notification_intent`原键/payload/hash/status/message_id | 当前两个写statement的直接writer都在ImServiceImpl：enqueueSystemNotification/ deliverSystemNotification。ISystemNotificationIntentService.enqueue由原业务生产者调用，原报名通知也使用它；ImNotificationDeliveryTask唤醒原意图投递 | 这是内部接口，无独立HTTP授权；producer必须沿各自业务scope，不能因为helper共用就给所有调用者授权。task公开bean方法只表示可调用，当前paused迁移也不证明真实运行态状态 |

`ums_member.balance`的三条精确mapper命令共观察到10处直接调用、8个Service/helper类（CmsTopic、MerchantClawback、MerchantReferralBounty、Refund、UmsMember、UmsMemberWithdrawal、SettlementDebtLedger、WithdrawalBalanceAdmission）。这说明“同一namespace”不足以证明单一经济命令owner；不由本清单判定其中某次合法扣回应被禁止。

## 直接SQL、动态DDL、调度与实际权限的边界

- MySqlSessionLock两处prepareStatement，一处常量`SELECT DATABASE()`，一处`scalar(sql,wait)`；人工沿本类调用看到GET_LOCK、IS_USED_LOCK、RELEASE_LOCK，没有在这里观察到业务表DML。命名锁有会话副作用，不能写成“无外部效果”。不执行数据库验证。
- 生成器是**真实动态DDL能力**：`GenController.createTableSave`（`/tool/gen/createTable`）带`@ss.hasRole('admin')`，SqlUtil.filterKeyword后经既有Druid SQLUtils筛MySqlCreateTableStatement，再到GenTableService/GenTableMapper#createTable `${sql}`。目标表/列运行时才给定，UNKNOWN；记录已有筛选，不称任意SQL注入已证，不把admin视为本轮生产DDL授权。模板不直接等于已部署写侧。
- Quartz `JobInvokeUtil`从`sys_job.invoke_target`解析bean或Class.forName并Method.invoke；任务启动、暂停、别名及参数由运行库决定。本轮仅看到bean、调用源码与迁移引用；例如通知原意图迁移写paused。**没有读DB GRANT、sys_job实际行、sys_role/sys_menu/sys_role_menu/sys_user_role或外部Spring条件配置**，无法证明实际DB写用户权限、哪一角色已获哪一permission、当前谁能登录执行。
- SecurityConfig存在动态Anonymous白名单和最终anyRequest.authenticated规则，源码permission expression与角色关系必须分开。完整annotation及config文件hash都保留。继承权限、AOP代理、AppPrincipal、资源scope、supplier/lambda、ObjectProvider/getBean、mapper代理及外部classpath仍可能改变真实路径；没有把静态候选图当作授权证明。

## 少量待root裁决的单写候选（不是新放行清单）

机器格式见`priority-owner-questions.json`，每项指向当前确切statement+hash及直接调用。

1. **余额经济效果**：同一UmsMemberMapper的balance三命令由8类写。root应指定资金命令责任边界及原ledger/DEBIT/债务/退款回执合同；新Java调用也需审阅，不能只约束namespace。保留现有合法资金合同，不借此重构公共余额写侧。
2. **报名交易依据与修复/PII分列**：明确交易创建/状态/积分/库存依据由哪个命令负责；purchase_kind修复和注销PII各可写的列另审。裸POST业务方向仍待用户，不以现有add权限或其他writer反推合法代付/赠送/补录。
3. **玩法receipt终态与progress**：确认C当前三个写侧路径及原重发补充结果的窄合同；未来新增Controller直接写同一mapper必须显式审阅。不造通用状态机，不擅定所有恢复写非法。
4. **现金原额/派生聚合/出款状态**：两个namespace都写coop_settlement，且部分目标为alias UNKNOWN。由G/root区分原额、明细派生和payout状态写权；无生产调用的generic update/新cash helper保持UNKNOWN，不默认为新入口许可。金额规则未定不编码。
5. **动态schema能力**：现有admin生成器DDL与生产迁移权限须由既有数据库治理owner单独裁决。F可报告运行时动态目标UNKNOWN，不把模板/当前admin角色自动纳入领域表writer白名单；本批不改RBAC、工具或生产DDL。

## 给F的最小后续建议（root批准后再接）

- 保持公共2ec固定baseline及现有增量gate机制。此快照仅供观察，不能复制到baseline、补存量豁免或把所有observed writer自动视为批准。
- 经root/领域owner确认后，按**表+字段组+语义命令+确切statement/Java调用**登记窄规则；新/变更caller也出审阅项。SQL动态列/别名/JOIN解析不充分时保持UNKNOWN，让现有review机制承接，不用表名正则强行分配owner或新增平台抽象。
- 最小证据链可复用本清单的`statement_key/sha256`、`namespace`、`literal_write_target`、`direct_call_ids`及方法hash；同namespace新增Service/Controller写调用不能逃过审阅。具体自动红线、既有合法helper及恢复入口须先批准，不在本批替F修改CI。
- 自动约束自身需正/负控：新namespace写受管字段、同namespace新caller、动态目标、alias/JOIN、insert-ignore、DELETE/TRUNCATE、跨namespace include变化、method reference/getBean与生成器模板。不要把Java词法扫描结果当编译或完整静态分析，也不要要求普通文档/测试变更提交新审批平台。

## 文件读取与复验

| 文件 | 用途 |
| --- | --- |
| `mapper-writes.jsonl` | 全1327条精确path#statement、namespace、gate传递include指纹、XML节点/文件hash、原shape/hints、字面目标及UNKNOWN |
| `observed_table_field_writers.json` / `table-column-index.jsonl` | gate原始观测及表/列提示→statement索引，明确区分literal_target和hint_only；不是授权 |
| `java-write-calls.jsonl` / `java-methods.jsonl.gz` | 全带类型写调用的位置/类型/statement键；写者祖先和入口相关方法的原文件/方法hash |
| `java-source-call-edges.jsonl.gz` / `statement-entry-candidates.jsonl` | 稀疏源码候选图及XML到可能Controller/Job的方法ID；不证明可达/实际运行/允许 |
| `entrypoints.jsonl` | class/method mapping、permission/role/Anonymous原表达式，未读实际角色分配 |
| `focus-index.json` / `manual-field-observations.json` / `manual-chain-anchors.json` | 六重点域、14条人工字段事实、实际调用/scope/配置/迁移行锚与hash |
| `*observations.jsonl` / `unknown-java-write-name-calls.jsonl` / `chained-or-methodref-write-name-sites.jsonl` / `unindexed-mapper-write-calls.jsonl` / `generator-and-residual-xml.jsonl` | 明确未知和旁路能力；空补扫文件也不作为不存在动态writer的证明 |
| `source-files.jsonl` / `summary.json` / `verification.json` / `SHA256SUMS` | 固定源身份、数量、检查记录及制品hash；不是业务/真库验收receipt |

复现：在含完整固定源Git对象的隔离checkout运行`python3 docs/delivery/architecture-day/A-writer-facts/collect.py`，再运行`curate.py`、`verify.py`。压缩JSONL为标准gzip、固定mtime=0，可Python gzip或`gzip -dc`读取；只是减少重复path体积，不是日志包。

本批实际只跑Git/Python、原gate提取、XML计数/关联/hash自洽与有限秘密格式/diff检查；Java/MySQL/Redis/DDL/真实HTTP/RBAC/资金/CI dispatch全未执行。F约束、业务owner批准、运行态GRANT/RBAC/调度、调用图漏边及财务原子性均未验证。无生产/schema/迁移/依赖影响；回退仅撤本目录，不改账、不删回执、不改变合法业务。
