# H 独立审查：A136 报名通用更新与商家取消 CAS

结论：A136 的 `registrationStatus` 剥离及商家取消/选中同一行 CAS 在源码上成立；修正测试资源名后的新源 `1dc32d0` 原 8 个 MySQL 用例已独立核对全绿/CLEAN。仍不能宣布报名写侧闭环：本轮确认一项 P1 既有经济字段漏口、一项 P2 既有后台删除竞争。另保留旧候选已实际发生、后续修复的 P2 初始化错误。没有修改 A 或其他 owner 的生产文件，没有新增 Agent。

## 候选身份与审查范围

- 唯一仓库：`https://github.com/liboyang42-cpu/hengyin`；公共基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- A 候选：`1364aee323c6684e5ad97e3fe4264900866775c6`，tree `ac25d358df9551f5e0710ad5f2655d9d01bfef7b`，parent `dee20f264de16d7b155c414cd5ebcfffff82908d`，分支 `codex/architecture-day-a-merchant-cancel`。
- root 编排候选：`dde91d5c03a85c769e17c2a8c6e88364f4b0d27c`，tree `2df014c5cf40a2d61ac2aa50e79c5aed74ed9b56`。读取完整 recursive tree（13092 项、未截断），**A 相对公共基线的 17 个变更文件在 root 全部 blob 一致**，详见 `candidate-bindings.json`。没有依赖已被 GitHub 截成 300 文件的 compare 来断言完整集成。
- H 工作树 `/workspace/hengyin-h-review`，分支 `codex/architecture-day-h-review`，本轮开始 HEAD `312006df34458c6ae9be7fc4ed698dc987b3c9ad`，开始时干净。独占本目录的报告、证据摘要和审查测试 patch。
- 已读 A-WP12 与 A-merchant-cancel 两份报告、状态写清单、候选完整差异、实际 Controller/Service/Mapper/UI 调用者和相关测试。对相关 root Service/Mapper 另取实际源码，不将 A 基线行号套在 root 上。

## H-A136-01 / P1：通用 PUT 仍能改取消时实际返还的积分依据

这是候选承接的**既有漏洞**，不是新增那一行状态剥离引入的回归。触发者需要现有 `business:registration:edit` 权限；不是匿名玩家可以直接调用后台接口。该权限当前能改经济事实，与接口“只能改非交易业务字段”的保护合同冲突。

准确调用链与位置：

1. `chengyinhub-ui/src/views/business/registration/index.vue:376` 提供 `form.pointUsed` 输入，`:825` 编辑加载整行，`:839` 将整份表单交 `api/business/registration.js:updateRegistration`。这不只是臆造一个无调用者的私有 Service。
2. `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationController.java:228` 的真实 `PUT /business/registration` 调用 helper；`:248`–`:263` 清了 payment/registration/verification 状态，却保留 `pointUsed`、`pointsReturned`、`orderNum`、`ticketId`、`memberId` 等依据。包括超管在内均如此；`canViewPii` 只处理 PII。
3. root `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java:255` 仅补 updateTime；`CmsRegistrationMapper.xml:460` 的 `point_used`、`:461` 前后抵扣依据、`:445` member_id 等动态列直接按 `WHERE id` 写入，没有原扣分 ledger 校验。
4. root 同一 Service `:2667` 的 `cancelRegistration` 校验当前会员和未支付后，`:2686` 从已存 `pointUsed` 决定返还，`:2688` CAS 领取取消，`:2702` 按 **该 `pointUsed`** 调 `umsMemberMapper.addPoints`。`UmsMemberMapper.xml:302` 为实际 `point = point + #{point}`。原会员入口是 `ApiRegistrationController.java:327` 的 `POST /api/registration/cancel`，不是只能测试调用的内部方法。

最小复现（仅在受控测试库，不调用真实支付）：

1. 正常创建会员 M 的一张未支付活动票订单 R：`registration_status=1,payment_status=0,point_used=0,points_returned=0,order_num=1`，库存已真实扣 1，记录 M 积分为 P。
2. 有报名编辑权限的后台账号发送 `PUT /business/registration`，JSON `{"id":R,"pointUsed":1000}`。不传任何状态字段；本候选仍把 1000 写入。
3. 原会员 M 取消 R。保持原 ticket/quantity，令正常库存回补成功；该路径会给 M 加 1000 积分，尽管原下单没有扣 1000。预期应只能回到 P，实际源码可达 P+1000。

另一个同根变体是篡改 `memberId` 后把合法返还转给另一个会员；`orderNum/ticketId` 可改变库存回补依据。**库存恢复有 `<=total_inventory` 上限**，不能宣称任意无限加库存；本报告的最小 P1 只用 `pointUsed`，不依赖库存绕上限，也不声称已执行真库复现。

最小修正：在后台通用编辑边界禁止变更原会员、票身份、原预占数量和积分/抵扣/已返还依据；保留现有内部支付/汇总 Mapper 的合法能力。建议用明确的普通字段白名单，或最小补齐已有 helper 的经济依据保护，不能只删 UI 输入。原订单号、父单/请求指纹、经济上下文等同属原身份，应由 owner 明确一并锁定，不能继续当普通展示字段。不要修改内部退款/取消金额规则来适应被篡改的行。

必须回归：真实库建立零积分预扣订单，经真实后台更新适配层后再走有事务的原会员取消，回读积分余额/流水、订单、库存，断言没有凭空奖励；另验原非零预扣只返原数、owner/ticket/quantity 不变、正常标签编辑仍生效。附带的 Controller 回归只是边界负例，不冒充这个资金链路。

## H-A136-02 / P2：后台批量删除可在预读后删掉刚中标的行

同样是本次相关调用者审查确认的**既有旁路**，不是把商家新取消 CAS 说成错误，也不是资金双花结论。

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationMerchantController.java:230` 的 `DELETE /business/registrationmerchant/{ids}` 在 `:238` 预读检查 scope 和 `auditStatus!=1`，然后 `:246` 批量删除。
- `CmsRegistrationMerchantServiceImpl.java:184` 没有事务内锁定/状态重验，直接委托 Mapper；`CmsRegistrationMerchantMapper.xml:301` 的 DELETE 仅 `WHERE id IN (...)`。
- 并发选中实际由 `DispatchServiceImpl.confirmRegistration:50` 经过 merchant/topic 锁、竞争位校验，`:63` 调 `confirmWinnerIfPending`。该 SQL `:187` 重验 `del_flag=0`，但不能阻止已预读的删除在它提交后执行。

准确时序：T1 有 remove 权限且在主题 scope 内，读到 R=`audit0/del0` 后暂停；T2 合法选中 R 并提交 `audit1/status1/del0`；T1 继续 ID-only DELETE，仍删 1 行并返回成功。中标记录和其竞争位消失，违背入口自己“已中标不可直接删除”的约束。当前选中流程没有创建活动/票/coop_order，**不能沿用旧测试注释声称此时必然产生孤儿活动或直接出款**。

最小修正：把删除决定放到有事务的专用 Service 中，对全部目标按稳定顺序做当前读锁定，再校验 scope 与状态后删除；或采用带完整状态/归属条件的删除/软删除 CAS，批量任一目标失配时整个操作拒绝并回滚，不能返回部分成功。禁止只给 Controller 加事务却继续普通快照预读后 ID-only DELETE。保留已有中标行及回执/审计，不物理清理履约事实。

必须回归：真实生产 Mapper + 独立连接确定性执行上述时序，确认中标行完整保留、删除拒绝；多 ID 其中一行中标时其余行也不得部分删除。补丁中的用例调用真实 Controller/Service 和 SQL，只 mock 合法 scope；用 spy 在预读返回前提交赢家，确定性复现时序，不声称覆盖 HTTP/RBAC。

## H-A136-03 / P2：本次 MySQL 8 个用例在初始化前被 Mapper 资源名阻断

已读取真实 CI 原始 JUnit XML，不是看到 CI 红就猜业务断言失败：

- run [40029223893](https://github.com/liboyang42-cpu/hengyin/actions/runs/40029223893)，attempt 1，source=`dde91d5c03a85c769e17c2a8c6e88364f4b0d27c`。
- job `120673135037`，artifact `12533678636`，原 XML/ZIP 哈希及最小错误堆栈见 `mysql-evidence.json`。
- XML：**tests=1, errors=1, failures=0, skipped=0；0 个业务方法执行**。这个 1 是类初始化 error，不是完成了 1/8 业务用例。MySQL 8.0.45、RR、STRICT_TRANS_TABLES；清理 receipt 为 CLEAN。
- `MerchantRegistrationCancellationMySqlIT.java:50` 使用 `P0AcceptanceSchema.sql(source,"CmsRegistrationMerchant")`；helper `P0AcceptanceSchema.java:83` 只追加 `.xml`，因此尝试加载不存在的 `mapper/business/CmsRegistrationMerchant.xml`。真实资源为 `CmsRegistrationMerchantMapper.xml`。
- 最小修正：实参改为 `"CmsRegistrationMerchantMapper"`，不要改共用 helper、放松门禁或跳过测试；重新跑同一组并核对新 SHA、8 个 test case 原 XML、清理。若 owner 接纳本目录 SQL 反例，则新总数是 9，需同步真实登记。
- 同 SHA 的 run `40029205360` 则在 checkout 步骤失败，不能用于任何 Java/DB 业务结论。本报告不重新验收其余四个已由协调者负责的类。
- 后续进度单列：协调者已提交 `1dc32d08095f5a621e6d665c75345b15a74e7c21`，独立读取 GitHub commit 确认其相对 parent `f9409bec4d0407b5eb7d6a6f9512828f0bef6e2e` 只改上述资源名一行。定点 run `40029966352` 的 head 正是该 SHA；首次读取时仍 `in_progress`，尚无 artifact，不提前声称 8 项已通过，也不把它与旧 SHA 的其他 35 项拼成同源 43 项 PASS。协调者已核对旧 run 的 Photo17、Signup18 原 XML 全绿/CLEAN，本 H 本轮没有重复下载验收这些 XML。
- **后续已完成并独立读回**：同 run `40029966352` 的 job `120675638553`、artifact `12533974185`，原 XML 包含完整 8 个业务 testcase，tests=8、failures/errors/skipped=0、CLEAN。新源与旧源的证据各自保存；详情及 XML/ZIP 哈希见 `mysql-rerun-evidence.json`。这关闭了 H-A136-03 的测试装配阻塞，未关闭 H-A136-01/02；没有把异源结果合计签成一候选 43 项通过。

## 调用者与竞争合同核对

| 实际入口/调用者 | 本轮结论 |
| --- | --- |
| 后台玩家订单 PUT → 通用 Service/Mapper；UI 整行编辑 | 状态剥离本身正确；H-A136-01 的经济依据仍可写 |
| 内部多票首单汇总、二维码保存、TeamGroup 退款取消标记 | 必须保留内部合法写入；不能全局禁止 Mapper 改状态来代替外部边界保护 |
| 玩家付款/回调、未支付取消、弃单 | 与 `cms_registration_merchant` 是不同聚合；玩家 cancel SQL 限 reg1/pay0或3，不能用它推断商家付款/选中存在竞争；本轮没有重新签署 B 全部支付候选通过 |
| `ApiRegistrationMerchantController.cancelRegistration:87` | 获取登录 principal；经 MerchantAccessService 的 active owner/operator + PROJECT_MANAGE 取得 ownerMemberId，非从请求体接收 owner；新 SQL 再验 member_id。未发现本差异新增跨店取消入口 |
| `MerchantAccessServiceImpl.require/resolve` | 现有 active merchant/member/operator 及权限检查；未执行真实 token/RBAC、撤销操作员与取消并发验证 |
| `ApiCoopController.confirmCandidate:1171` | 校验主题发布者（含既有 owner scope 解析）后调用 Dispatch；后台 select 同样先检查 topic scope |
| `DispatchServiceImpl.confirmRegistration/confirmNodeMerchant/confirmInvitedMerchant` | 商家/主题锁及原竞争位规则；选中 SQL 新增 del0 与取消 SQL 共同互斥：先取消则选中0行，先选中则取消拒绝/0行。源代码上未见这两条命令可互相覆盖 |
| MerchantRegistrationEditServiceImpl → updateEditableFieldsIfStateMatches | 白名单，不改 member/topic/node；按 expected status/audit + member/del CAS。取消后编辑、选中后重提会失配；没有把请求 expected 自行替换成当前值 |
| 后台商家 edit、sharingRates | 两个路由已明确拒绝；全仓生产搜索未发现 `updateSharingRates` 或通用 update 的外部活跃调用者，因此未把保留的旧内部方法猜成在线复活漏洞 |
| 后台商家 remove → deleteByIds | 活跃路径，H-A136-02 确认有竞争旁路 |

## 同一候选裁决与交付

| 检查 | 源码裁决 | 实际执行/缺口 |
| --- | --- | --- |
| A17文件与 root 对应 | 一致 | 完整 Git tree/blob 核对已执行 |
| 后台 registrationStatus 不能直接迁移 | 符合本次单点合同 | 新增 Java 用例未在本轮获得独立执行证据 |
| 后台经济依据不可变 | **失败：H-A136-01/P1** | 明确源码链；经济回读未执行，不冒充已观察到损失 |
| 商家 owner/current state CAS | 源码符合 | dde91d5 原8项未启动；新源1dc32d0原8项真实MySQL全部通过/CLEAN |
| 商家取消与选中相互覆盖 | 未发现新代码问题 | 真实完整 Dispatch 并发、回滚、唯一槽迁移仍未验 |
| 后台删除与选中互斥 | **失败：H-A136-02/P2** | 确定性源码反例；附真库回归 patch，未执行 |
| 新审查测试 | 2 个有意义反例 | `git apply --check` 对 A136 精确文件通过；Java未编译/未运行，无红绿声明 |
| 本轮云环境执行 | Python XML/源码/哈希核对、patch 应用检查 | 没有在云 VM 启动 Java/数据库；没有真实付款/供应商/真机测试 |

独占交付文件：本报告、`candidate-bindings.json`、`mysql-evidence.json`、`mysql-rerun-evidence.json`、`static-evidence.json`、`economic-basis-and-admin-delete.review.patch`。patch 只提供给集成人安排受控 CI，不自动应用到 A 文件；原8项复跑已修好资源名，新SQL反例仍未执行。若两个反例均接纳，ControllerSafety 10→11，MerchantCancellation 8→9；原编排 43 不可原数照抄。

无 schema 变更，无 DDL 执行。回退本 H 报告/未应用 patch 没有运行影响；回退 A 状态剥离/取消 CAS 会重开已修漏口，不是安全降级方案。若确认线上已有被后台改写的积分/库存依据，应先按原账本核对，不能批量猜默认值、清库或删除资金记录。本轮没有为这些历史修复制定新业务规则。
