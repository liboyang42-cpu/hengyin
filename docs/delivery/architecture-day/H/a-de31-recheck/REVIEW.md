# H：A de31 修复复审与 C1eb 迟到同意回归

结论：A `de31f95` 已在源码上封住原 H-A136-01 的 **PUT** 经济字段改写和 H-A136-02 的中标删除竞争；Java/真库仍未验证，不能签运行验收。追全部写入 caller 时确认另一项**既有 P1：后台裸 POST 仍可插入无原扣分/预占依据的报名，再经会员取消凭空返积分/库存**，编号 H-ADE31-01。它不是 de31 引入的回归，但阻断“报名经济写侧已全部闭合”的结论。

C `1ebfbf6f` 对 H-C9-01 的客户端修复已由 H 原反例复跑确认：原 H 12项全部通过；当前11文件158项实际全通过。只关闭该具体Node层漏口，不证明C后端或最终联合候选已验收。未改 A/C/E 生产、未重复应用 APPLIED patch、未 dispatch、新增Agent或启动Java/数据库。

## 候选、范围与隔离

- 唯一仓库：`https://github.com/liboyang42-cpu/hengyin`；公共基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- A：`de31f95da65921101b61a88c6b536cfb57ca0478`，parent `6788c741c43d9516aa7c691ca93110677c7e1acb`，tree `0996558bee360b3ae07d03efd614c64dc1537a7c`，相对parent15文件。完整recursive tree未截断。H从公共基线建立独立快照，取得全部56个变更生产Java/XML，**当前全部2720个生产Java/XML blob逐个一致、无删除路径**，所有Java/SQL caller搜索基于该视图，未拿旧master冒充候选。
- C：`1ebfbf6f6303e0d71bcd787be9e958266e5689b6`，parent `09b1c1ad317c0f1449aef0ec378f48665638cb82`。依赖未变的C9da视图，取得并核对后续实际Page/测试/报告变更；独立运行原H用例，不按作者自报158裁决。
- H工作树 `/workspace/hengyin-h-review`，分支 `codex/architecture-day-h-review`，开始 HEAD `c99d095ca9e03c2a3820778478c6eb9c6cf5dec6`，开始时干净。只写本H目录的报告、证据及未应用测试patch。
- 已读公开 `4a6e3cf` 的 `docs/delivery/architecture-day/COORDINATOR/governance-scope.md`：按原经济身份、拒绝零未授权业务效果及分层证据复审，不以局部修复宣称WP/G0–G5全部完成。身份清单见 `identities.json`。

## 原 H-A136-01：PUT 漏口源码已封，运行未知

真实链：UI `api/business/registration.js:updateRegistration` / `views/business/registration/index.vue:submitForm` → `CmsRegistrationController.edit:228` → `editableRegistrationFields:248` → `CmsRegistrationServiceImpl.updateCmsRegistration:255` → 通用XML。

新helper创建全新 `CmsRegistration`，只复制 `id,activityTitle,ticketName,realName,phone,email,address,addressId`。不是不断补黑名单；新增领域字段默认不透传。`pointUsed/pointsReturned`、member/ticket/quantity、原号/父号/request fingerprint、状态/支付/核销、价格/来源/退款依据、create/update审计字段均留空，由原内部Service仅补本次updateTime。无PII权限仍对新实例执行既有PII保护。源码未见实体构造默认值把这些经济字段重新填回。

当前整个生产视图中，外部通用 update Service 的唯一Controller caller就是该PUT；其余是Service内部首单汇总。**没有全局削弱Mapper写能力**：

| caller | 仍保留的合法能力 |
| --- | --- |
| `CmsRegistrationServiceImpl.createMultiTicketRegistrationsInternal:763`–`:772` | 内部冻结首单payable、paymentType、fee、原积分抵扣等，再一次性扣原积分；直接Service/Mapper未套外部白名单 |
| 同Service `completePointPayment:1996`、`updatePaymentStatus:2033`、`generateVerificationQrcode:2078` | 既有内部状态/核销码写入；前两者原生产直接caller仍未发现，不把它们猜成在线绕过 |
| `TeamGroupServiceImpl:1486` | 原团购退款取消标记，经既有内部Mapper；没有被白名单误吞 |
| 原下单内部 insert `:574` / `:894` | 保留原业务创建字段、实际价格/额度/库存逻辑 |

旧Controller的21条route/security注解与公共基线逐字相同；Merchant Controller23条也相同。本轮没有验证Spring安全过滤链/真实admin token/租户矩阵，不把注解不变当全域权限验收。两Controller新来源hash需root按已授权流程更新provenance；A报告已列旧指纹门禁1fail，不冒称候选全门禁绿。

## 原 H-A136-02：删除竞争源码已封，运行未知

真实链：`CmsRegistrationMerchantController.remove:230` 做原友好预检，然后 `:249` 调有事务的Service。`CmsRegistrationMerchantServiceImpl.deleteCmsRegistrationMerchantByIds:193` 在 `@Transactional(rollbackFor=Exception.class)` 内：

1. 拒绝null/空/非正ID，TreeSet去重并升序。
2. 对**全部**ID执行 `selectForDeletionByIdForUpdate`，将结果保存；没有边锁边删。
3. 全批已锁后，逐当前行检查存在、当前topic scope及 `auditStatus!=1`。
4. 最后才一次DELETE；affected rows必须等于唯一目标数，否则抛ServiceException。

新XML锁读使用 `FOR UPDATE,useCache=false,flushCache=true`，不会因Controller旧普通读或MyBatis缓存空值而沿用快照。行锁持有至Service事务结束：选中先提交则读到audit1拒绝；删除先获锁并提交则选中看到行不存在而0行。原ID-only DELETE在本受锁事务内使用不构成同一竞态旁路。

Controller只在代理异常已经向外抛出/事务回滚后转换Ajax拒绝，没有在Service内吞异常提交部分删除。单条 `deleteById:234` 自身也有事务，复用同一整批路径；即便内部self-call不再触发一层代理，外部单条入口已有事务。全生产搜索没有发现直接调用Mapper裸单条delete的活跃caller；Mapper保留不等于已证明外部可达。

scope继续使用原 `canViewTopic`，实际Authorizer仍仅固定超管userId=1有topic scope，与原Controller一致；没有新增角色授权或把普通商家变成后台管理员。动态委托/撤权并发未测试，不能泛称跨租户授权已经全部通过。

原H两个反例已由A采纳；增加了真实代理/同DataSource事务、旧RR当前读、多ID赢家、当前topic迁移、ID排序去重、真实partial DELETE后计数不符回滚等负例。**这些仍是未运行源码**，不是已经看到数据库回滚。

## H-ADE31-01 / P1：未保护的后台 POST 可新造可返还的经济依据

这是此次“所有caller”复审确认的**既有同域入口**，不是原PUT修复失败或de31新回归。调用者需要 `business:registration:add` 权限（或现有超管权限），不是匿名玩家。真正存在的UI调用是 `api/business/registration.js:addRegistration`，与PUT共用后台表单，表单本来就带member、票、数量、pointUsed等字段。

精确位置：

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationController.java:214` 的 POST权限/入口，`:220` 将整份请求直接交 `insertCmsRegistration`；没有新白名单，也没有走原真实建单命令。
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java:243`–`:245` 仅补createTime并调用原Mapper，不扣积分、不预占库存、不核对原来源/原经济身份。
- `chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml:311` 起的动态INSERT透传 `point_used`、member/ticket/orderNum、payment/registrationStatus等，生成原id。受影响语句在de31没有改动。
- 同Service `cancelRegistration:2667` 按member和未付状态检查后，`:2688` 条件取消，`:2696` 起恢复请求行的票库存，`:2702` 按该行pointUsed加会员积分。该会员入口是真实 `ApiRegistrationController.cancelRegistration:327`。

确定性最小复现（只供批准测试库，不运行真实支付）：

1. 使用真实建单命令给会员7下零积分订单，实际占原票1席：total=10、remaining=9，会员7积分100。会员8积分37，未为伪造单扣分或预占。
2. 已有后台add权限的账号POST新行：新的registrationNo、ownerType2/ownerId55、ticketId9/price10、memberId8、orderNum1、originalAmount/totalAmount/payableAmount均10、`pointUsed=1000,pointsReturned=0,paymentStatus=0,registrationStatus=1,verificationStatus=0`，以及真实schema必需的payExpireTime。该请求满足NOT NULL列；不借缺字段fallback或绕过SQL严格模式。
3. 裸POST只插行，原积分仍37、remaining仍9。由会员8取消伪造的原id，取消CAS可领取；现有源码会给8增加1000积分并把库存9→10。原会员7那一席仍有效，故同时虚增了可售容量。

**必须说明库存上限**：restoreInventory确实有 `remaining+qty<=total`。这里先建立真实占席留下9→10的空间，完全在该上限内，不声称任意无上限加库存。核心P1是无扣分依据的1000返还；源码机制确认，但H没有执行Java/MySQL，不声称已观测线上损失。

最小修正建议：堵住外部裸建资金行。若后台确需代报名，应由owner/root确认actor/受益人/计价/预占/原请求身份语义后走既有受控建单命令；未明确前可拒绝该危险裸建入口。不能把“原积分/数量一律改0”当自动安全方案（仍可能形成无预占可释放的库存/权益），不能为了外部入口去削弱内部合法INSERT或给数据库填假扣款证明。

附 `unbacked-post.review.patch`：在A真实Funds类新增一个反例，先正常建单占席，再用真实handler POST伪造行并走真实会员取消，断言余额/库存/原流水守恒；安全拒绝分支也断言零新行。只使用现有事务/表/fixture，无新依赖。**仅通过patch应用检查，未编译/未运行**。若采纳，Funds94→95，需要root单独登记并在批准CI获取原XML。若后续选择新的合法代报名响应协议，应按真实原订单身份调整测试适配，不改余额/库存守恒断言。

## 原测试完整性与实际执行

H逐个提取原@Test方法完整正文，与精确6788 parent对照，旧方法无删除、无正文/断言改动：

| 类 | parent | de31 | 旧方法变化 | 执行 |
| --- | --- | --- | --- | --- |
| RegistrationFundsMybatisIntegrationTest | 92 | 94 | 0 | 未运行 |
| MerchantRegistrationCancellationMySqlIT | 8 | 15 | 0 | 未运行 |
| CmsRegistrationControllerSafetyTest | 10 | 13 | 0 | 未运行 |

商家fixture由裸new改为真实TransactionInterceptor/DataSourceTransactionManager代理，新增scope桩仅显式允许77、拒绝88；不冒充真实RBAC。Funds新增2例使用真实建单扣分/占席、handler白名单、原会员取消、余额/原积分流水与两张票回读；完整旧92方法逐字保留。旧8项在其他SHA通过不能代替当前15项，新122项源码存在也不是122项执行。

本轮实际：Python源码/blob/注解/方法保留检查、caller搜索、H新patch `git apply --check`。A Java/MySQL/HTTP执行均为0；root负责真库，本H未启动或派发。具体清单见 `test-preservation.json`、`static-evidence.json`。

## C1eb：H-C9-01 的独立复跑与完整generation守卫

真实修复位于Page `_submitKitPhoto`：intent单独冻结authorization+login generation，题目单独冻结session/version/四处node值；不再依赖可被原地修改的state对象。`consentCurrent` 组合operation、原intent指针、原Page上下文和原题目；传入真实同意helper，fulfilled/rejected入口再验。catch先捕获owned再settle，只有owned才fail，因此页面重新绑定新A登录也不会把旧intent变成当前。

H把**原先失败的原H测试文件/测试正文保持不变**，在C1eb Page和实际helper下直接执行：12tests/12pass/0fail/0skip，见 `c1eb-h-original-consent.txt`。没有用C自报替代H原红例，没有运行临时修正钩子。

随后H逐文件实际运行原11文件，Node v24.19.0、`node tests/unit/<name>.test.js`：**158tests/158pass/0fail/0skip**，输出与哈希见 `c1eb-node-158.txt` / `c1eb-node-evidence.json`。原H12与158重叠，不能加成170个独立用例。`separate-consent-feature` 实跑25项，包括原9项和fulfilled/rejected×8种旧上下文；覆盖A→B→A后Page重绑、page epoch、组件node、Page node原地改变、scope、session、version、新intent。当前合法同意失败仍准确增加本操作photoFailSeq，未通过吞掉所有错误反馈来变绿。

因此H-C9-01在该候选的具体Node行为已闭合；原125/旧parent50+9等历史记录保留。本轮不重新修/重复应用root已暂存的APPLIED事务patch；C后端Java/最终联合MySQL、控件台账、微信模拟器/真机与供应商仍未由本次结果证明。

## 裁决与回退边界

| 检查 | 结论 |
| --- | --- |
| 原PUT经济字段改写 | 代码合同已闭合；真实积分取消读回待root |
| 原中标删除竞争/部分删除 | 代码合同已闭合；真实15项与失败回滚待root |
| 后台裸POST经济事实 | **H-ADE31-01/P1仍阻断同域闭环** |
| 旧92/8及Safety10断言 | 正文完整保留，仅源码证明 |
| C迟到同意原H反例/全generation相关158 | 实际Node通过；不代表后端或全量权限验收 |
| 整体发布/生产数据/供应商 | 未验收；没有合并、部署、真实支付或生产操作 |

本H只有文档、证据、测试patch；无schema/权限/依赖变更，不执行DDL。回退PUT/删除保护会重开原两漏口，不能当安全降级；原报名/库存/积分记录、意图、回执和审计均须保留。对历史裸建/篡改行应基于原真实账本调查，不猜默认扣分或批量清理记录。
