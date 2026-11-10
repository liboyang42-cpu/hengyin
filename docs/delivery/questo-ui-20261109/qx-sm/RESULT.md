# qx-sm 隐式状态机：结果

分支 `claude/qx-sm-20261110`，基于 `origin/staging/questo-review-20261110`，草稿 PR 合入 `staging/questo-review-20261110`。

## 盘点

全量清单（英文，按风险×用户影响排序）见 `docs/architecture/state-machines/INVENTORY.md`，供代理使用的说明见 `docs/architecture/state-machines/README.md`。共记 18 台隐式状态机：后端 12 台、小程序 6 台。其中涉及资金的 6 台（报名支付、退款、旧订单、优惠券、提现、押金）本轮只盘点不重构。

## 条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 风险 |
|---|---|---|---|---|---|
| SM-1 盘点 | 完成 | 写清每台状态机的状态存放位置、写入口、非法或不可达迁移、缺失态、竞态和用户可见症状 | `docs/architecture/state-machines/INVENTORY.md` | 人工核对，每条带文件定位 | 无（只有文档） |
| SM-2 审核单状态机 | 完成 | 新增 `AuditTaskState` 枚举，作为状态值域、裁决迁移表和「未结」集合的唯一真源。原私有 `canTransition` 和 5 处手写的「待审或已升级」判断都改为委托它。SQL 与业务结果不变 | `AuditTaskState.java`、`AuditTask.java`、`AuditTaskServiceImpl.java` | 先提交特征测试，在原代码上通过（9×9 迁移真值表，含 null 和未登记值，加上未结判定）；重构后同一测试原样通过。审核相关 73 个测试类 468 例中 463 例通过，另 5 例是 `AuditReportLaneMyBatisTest`，需要 MySQL，本环境没有库，属于环境原因 | 低。纯委托，真值表已被逐格钉住 |
| SM-3 审核单写入口检查 | 完成 | 新增两项检查：只有登记过的 6 条 Mapper 语句能写 `audit_task.status`，写「已撤销」时必须带未结条件；Mapper 里每个 `status in (...)` 都必须等于未结集合 `0, 3` | `AuditTaskStateMachineTest.java` | 6 例全部通过 | 低。以后新增写入口需要先登记 |
| SM-4 席位邀请状态机 | 完成 | 新增 `SeatInvitationState` 枚举。3 处通用 `transition` 调用统一走 `transitionGuarded`，迁移表外的组合直接拒绝。现有 4 条迁移（0→1、0→2、0→3、1→4）都在表内，行为不变 | `SeatInvitationState.java`、`RegistrationSeatInvitationServiceImpl.java` | 原有 `RegistrationSeatInvitationServiceTest` 改动前后都是 18/18 通过；新增 `SeatInvitationStateMachineTest` 5 例通过（覆盖非法迁移，并检查通用写入口只出现一次、Mapper 写状态都带旧态条件） | 低。不碰报名支付和退款 |
| SM-5 小程序状态机工具 | 完成 | 新增 `createStatusMachine`，只负责展示和本地守卫；未知状态码一律视为未知，不猜成已知态 | `chengyinhub-xcx/utils/state-machine.js` | `node --test tests/unit/qx-sm-state-machine.test.js` | 低。主包约增加 3 KiB |
| SM-6 协作邀约状态统一 | 完成 | 新增 `coop-invite-status.js` 作为唯一状态表。`coop-invite-view.js` 的 `STATUS_LABEL` 和 `INVITE_BADGE`、俱乐部详情页的 `RECEIVED_STATUS_TEXT` 都从它派生，文案和徽章逐字不变。另加检查，禁止在别处再手写这张表 | `utils/coop-invite-status.js`、`utils/coop-invite-view.js`、`pages/club/detail/index.js` | 特征测试先在原代码上通过（覆盖 0–5、字符串码、null 和未知码），改后原样通过；引用协作邀约或俱乐部详情的 133 个单测文件共 1796 例全部通过；`UI-GATE-0:PASS` | 低。界面像素不变 |

## 验证汇总

- 后端：`mvn -q -pl chengyinhub-admin test -Dtest='Audit*Test,*Audit*Test,…,AuditTaskState*Test,RegistrationSeatInvitation*Test,SeatInvitationStateMachineTest'`。除需要 MySQL 的 `AuditReportLaneMyBatisTest`（5 例）外全部通过。
- 小程序：`node --test tests/unit/qx-sm-*.test.js` 共 7 例通过；引用协作邀约或俱乐部详情的 133 个单测文件共 1796 例，0 失败。
- `node tests/lint/ui-integrity.js --warn-as-error`：改动前后都是 `UI-GATE-0:PASS`。
- 主包：`mainpkg-size-report` 显示压缩前合计 3162KB，按 0.633 折算约 2001 KiB，低于 2048 KiB。本线约增加 2 KiB，余量偏紧，请留意。
- 没有改数据库结构，没有改已有 migration，没有改支付、退款或结算的结果，也没有放宽权限或归属校验。

## 待你决定

1. **报名支付确认把「已过期」当成「处理中」**（`utils/checkout/registration-payment-verifier.js`）。现在订单超时关闭后，客户端会轮询 20 秒，最后显示「结果未知」，结算流程也被锁住。直接改成「失败」最简单，但如果是「过期后才支付成功」（支付状态 2、报名状态 4），会把已扣款的用户显示成失败。需要你决定这种情况怎么显示。这属于支付展示，本轮没改。
2. **退款打款结果覆盖**：`RefundApplicationMapper.updatePayoutResult` 允许在打款状态 0、2、5、6 下回写，不校验租约。迟到的网关结果可能覆盖已被人工认领或被其他处理器持有的打款。是否收紧？这属于资金，本轮没改。
3. **管理端可直接改的状态**：旧订单 `oms_order` 和优惠券 `sms_coupon_history` 的管理端 PUT 能直接写状态，例如把已用券改回未用。是否加迁移守卫？
4. **组队「已开始」态**：`PlayTeam` 从来不会写入「已开始」（2），却有代码读取它；队伍到期结束后成员仍显示「已加入」，而解散时会把成员移出。这两种结束方式的成员语义需要你定，定了之后再做 PlayTeam 状态机（下一个试点）。
5. **活动删除竞态**：删除前先统计已付报名再软删，中间不加锁。这部分在发布编辑器相关文件里，等 UI 线合入后再处理。
6. **成团文案不一致**：状态 2 在 `club/enroll` 显示「已散团」，在 `merchantinfo` 显示「未成团已散」，而且 `club/enroll` 按文案字符串计数。请确定统一用哪个文案。

## 跨线

- 没有依赖其他线。俱乐部详情页 `pages/club/detail/index.js` 只改了一行常量和一行 require；如果其他线也改了这个文件，合并时注意。

STATUS: 完成（草稿 PR 待审；6 项待你决定）
