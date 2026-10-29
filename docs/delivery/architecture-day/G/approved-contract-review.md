# 已批准合同复核：撤回抽象重选，只保留具体矛盾

结论：上一版把 D1–D6 都列为用户选项，范围过宽。**D2 的经济 context 是现有唯一身份约束下的技术修复，不应让用户重选架构。** 已复核公共仓库中的明确【已定】设计、当前冻结规则、账本与测试；不能把交付报告里的提案、draft SQL 或旧规格未实现的部分当新授权。证据组 E25/E26 与原 E01–E24 保留路径、行号、文件 hash。

## 逐项收敛

| 原项 | 既有规则及具体入口 | 本轮结论 |
| --- | --- | --- |
| D1 非合作经典正金额 | `CmsTopicServiceImpl.settleTopic` 主动拒绝；`SettlementEntryServiceImpl.recordPaidRegistration/claimRegistration` 已有逐报名 DIRECT 路径、H8 creator=0 和受益人归属；支付阶段主题不记商家账 | 不向用户重问“禁止售卖还是恢复发布者分润”。当前保护不动；技术修复需证明每笔真实受益人/原始账本完整、没有二次现金与积分，不用 DTO 冒充已结。 |
| D2 context/scope | `migration_settlement_claim_20260811.sql` 明写 commercial_context_id=直接 owner id，7 列经济唯一键不含 route/source；DIRECT REGISTRATION 当前 owner_type=1→TOPIC_EDITION/ownerId，owner_type=2→ACTIVITY/ownerId。`MerchantPayableServiceImpl` 的章节 REDEMPTION 则明确 TOPIC_EDITION/topicId、CHAPTER_SUPPLY | 必须保持既有经济身份，不能为了让 COOP 不冲突改 context、换第三种名称或把 activity 票强扭成 topic。TOPIC/NODE/ACTIVITY 的 scope 是证据范围，和经济 context 不同；补类型化关联/逐行归属属于技术修复。新增实际方法测试钉住 owner=ACTIVITY20、linkedTopic999 的 key 必须是 ACTIVITY20。 |
| D3 单位/收款主体 | 【已定】2026-07-12 spec §3.2/3.3：经典 payout 的真实收款人是 member，俱乐部经 resolveOrderPayee；当前 CoopSettlementContract/测试按 distinct registration 计 FIXED_PER_FULFILLMENT；四店 FREE_EXPLORE 开售另有独立对公快照及 compensation terms | 不让用户在现成两种产品之间选收款人模型。付款账户和 claim beneficiary role/id 分开核对，不把 member payout ID 偷换成 CLUB claim ID；多节点同经济 key 不允许多条 source 抢同一账本。仅多人票“人头”语义与可证履约不一致保留下面 Q1。 |
| D4 退款窗/结算时点 | 2026-07-12 spec §3.1 明定 T+7 + 未结客诉冻结；当前 CmsTopicMapper 12小时/退款截止闸；CmsTopicServiceImpl/RefundServiceImpl 中 9-16 决策已要求冻结原额、后续退款追加扣划、欠款抵扣 | 不重问“等所有窗口还是先冻结”。读写两侧要保持当前批准合同；缺失的 claim 调整消费者、退款并发/原号 UNKNOWN 查证是待修/待测技术项，不能把改变时点作为捷径。 |
| D5 失败/兜底完成与奖励 | docs/theme-runtime/README.md 明确：完成路线但 success 条件不足保存 ENDED/reason=COMPLETED/success=false，不计主题通关；ENDING 发放按冻结 endingId/reasons/success，默认成功终局发放。原 GamePlayerNodeOutcome 保留首次正常/兜底及来源 | 既有规则已明确，移除用户选项。不能因“到过结局”便改 success，不把 fallback 清洗成正常完成，不解除 XP_BADGE_ONLY。 |
| D6 actor/team | game runtime 依据 runtimeScope、TEAM/WORLD owner、冻结团队/站点与 source receipt；route 当前个人完成按 member/session/instance；原统计分 normal/fallback | 本轮仅查询现有个人 route，无新个人展开需求，不把团队完成复制成个人经济资格；如未来产品明确要求个人团队投影，另提交具体规则，不阻塞本轮来源修复。 |

当前已知来源有优先级：最新明确冻结合同/账本不变量与真实原实现先核对；旧 spec 的销售基数/常量百分比不覆盖后来的锁价 snapshot。`docs/delivery/doc2-1023/drafts/` 中的 SQL 是提案，不在 G 执行或视为既成授权。

## 唯一尚需业务澄清的具体场景 Q1（仅恢复多人固定费订单时）

**入口/事实：** `/api/registration/create` → 下单事务 → `CmsRegistrationServiceImpl.createRegistration` 可将 `count=3` 写成同一条 registration.order_num=3（EXPLORE_PASS 单独限购1）。后续 `/api/registration/scan_dynamic_code`、`/scan_group_member_ticket`、`/scan_qr_code` 或领队核验只形成报名级履约；经典 `CoopSettlementContract.FIXED_PER_FULFILLMENT` 按 distinct registration 计一次，旧 spec 写“固定费×核销人头”。现有记录没有为这三个人逐人提供已履约身份。不能擅自把 order_num 乘上钱，也不能把这一条登记当三个人到场。

需要确认的是：**有人一次买3张有人带票、现场只有购买者一条核验时，固定每人服务费如何成立？**

- A：只证明一次履约，按该报名的一份固定费 F 处理，沿现有 per-registration 算法；其余人的票款/权益仍保留，不凭购买数量增加结算。
- B：按实际到场人数付费，但每个人须有可追溯的履约证据后才结相应固定费，最多3F；需要补“成员/席位→履约”合同和测试，不能直接 `order_num×F`。

这不是要求用户选择 context、数据库或架构。它不阻塞当前只读查询和单人保护测试；多人固定费路径恢复前，须由产品确认或提供已批准的更明确合同。G 保留现有拒结/去重保护，不从未核实的数据库推断该场景当前有多少订单。

其余问题均改列技术阻塞：类型化 scope 映射、DIRECT/COOP 相同经济 key 的冲突、冻结 beneficiary 与 payout 主体各自校验、退款追加调整接线、受控环境事务/并发验证。没有建议用户重选百分比、账户或取消保护。
