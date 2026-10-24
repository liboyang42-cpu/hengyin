# 当前施工状态（用户授权继续）

**整体未完成。原截止时间不再停止本次继续施工，10分钟协调已恢复；所有剩余失败已分配给原8个会话，root唯一集成，最后统一验收。** 完整范围仍为WP00–WP17 / Q1–Q10 / A01–A15 / T01–T22 / G0–G5。未合并、未部署、未执行生产DDL或微信发布。

## 同一修补基线

公开基线`d19276f56b3eb2a34ed8fab06e41ea8ea654a0d0`，分支`codex/architecture-day-repair-base-20261024`。含原订单号长度修补、单人团费用修补、C声音代次及夹具修补、F精确公开来源约束，现已加入D289e同号ASR/文本终态竞态的完整3文件：原member锁内复核ASR，存在时不另写文本终态，同scope回读原身份、冲突scope拒绝，不二扣不换号；新增3条barrier测试源码。该组合Java/DB及完整CI尚未运行，不能继承ca0e或局部绿色。

## 真实失败输入与所有权

旧候选`ca0e0d2598727f6e567c802b7a664f9634d945c6`的标准CI40122522919已失败。1866份原始XML有15069个唯一testcase、无重复：14384通过、31failure、7error、647门控skip，涉及19个失败suite。失败case不是业务BUG计数。

真实存储run40122516880的65份原始同SHA回执：46类PASS、19类FAIL；65份均有独立CLEAN。与workflow的计划/汇总等job计数分开。所有原记录及后续修补证据仍绑定各自来源，缺验证不算功能BUG。

| Owner | 当前明确责任 |
|---|---|
| A | 报名付款/Club/整体退款的4个unit suite及3个真库suite、原事实共享factory |
| B | 退款/提现2个unit suite及RefundRetry、ApprovedRefundFence、PaymentIntent真库 |
| C | 动作台账15处未知多写合同、U4/U5/U6精确静态错误与必要调用者 |
| D | ASR投影/原shop gateway及真实Redis quota用例、289e必需直接调用者 |
| E | 主题/内容6个unit suite共同facade装配及TopicContentGraph真库 |
| F | 真实Spring循环/托管代理装配2例及必要精确边界配置 |
| G | 7个通知/轮次真库共同canonical migration fixture及CLM/Commerce原付款证明 |
| H | 4个玩法/核销查询unit suite及漫游留存真库 |
| root | 剩余High依赖告警、共享契约、registry/依赖/生成台账、唯一整合与最终验收 |

精确类名、触发与所有权见[剩余失败分工](RESIDUAL-FAILURE-OWNERS.json)。原8会话复用，不创建第9个。原工作区与未交补丁保留；环境不可用者交完整聊天diff，不用active数量代替产出。

## 执行与验收

先把共同根因、真实调用者、装配与测试源码修齐，不新全仓审计、不逐补丁反复跑全批CI。保留全部金额/身份/权限/UNKNOWN/迁移/负控制断言，不删测试换绿色。必要修补完整收齐后冻结一个组合候选，root统一审查与标准CI/真实存储回归。

已有局部证据仅限相应片段：C声音5个Node文件156/156、核销源断言9/9、种子控制9/9；F来源引用owner报告3/3。以上不证明完整组合。原804项真库声明以及新增Graph负控后805项声明均不是通过数。真实HTTP身份、外部供应商、进程终止/恢复、容量与业务验收的缺口继续保留。

用户已批准业务规则见[批准记录](approved-settlement-voice-policy-20261024.json)，公开方案参考见[五项规则对照](五项业务规则-公开方案对照-20261024.md)。不新增费用、依赖或权限政策；不执行生产DDL、部署、微信或真实付费外呼。完整终点与范围见[治理范围](governance-scope.md)。

## 本轮已收取的必要修补

A完整两段聊天diff（8文件）、B完整主补丁（4文件）及独立共享历史factory补片、D93a372f6、F2faf2427、Hcfc6c3bd均已应用。没有生产付款保护放行，原金额/库存/回滚/账号/未知态断言保留；全部新的Java/DB组合仍NOT_RUN。C2cd05f1c（精确台账与静态证据）、E2f09aa52（真实主题装配及新增负控）亦已应用；Gfff8f312完成barrier与canonical audit fixture，root串行接入纯读ConflictException不标记外层事务回滚。Gdb3eb060附9项只读schema诊断且保留原异常。六个共享通知C前检失败根因仍OPEN，需一次隔离单类元数据诊断，其他已交付者不重派审查。不是整体完成。

High告警已定位为转台工具Puppeteer→BiDi→Zod3.25.76；根修补使用同主版本Puppeteer25.16.0官方依赖链，锁至Zod4.6.7，不添加漏洞豁免或改安全门禁。配套Puppeteer API加载、实际BiDi容器提前拒绝及原GLB头自检已接原依赖CI job；源码NOT_RUN，Grype与完整组合待最后同SHA验证。


root已逐调用核对C精确证据，U4固定8字段路线投影5项、U5原核销身份/UNKNOWN可见恢复3项，登记与digest冻结同步；无全局豁免/删零多写断言。Graph类32→33、联合65唯一类/805项为源码声明，Java/DB NOT_RUN。最终台账根据同一完整客户端源码重建，实际条数待生成回读。

必要单类诊断已启动：候选 `aab91b9eb7b2732de09aa5513a5be8d5856c81a2`，run `40127844664`（仅 `CommittedOfficialNoticeMySqlIT`），不算最终验收；原9项前检仍失败即照常抛出。两声音错误恢复组件接线后台账源库存5121，localtap2185/event2541（分类变化待C精确说明）；原未知多写为0仅为源码合同，不是执行通过。

C已完成定点生成器调用者对照：saveDraft普通分支仍POST、拒绝分支无请求；profile本人扫码与访客关注按variant合同均保留；注销apply真实wx.login归external，effectClasses.write/consent→apply/异常原status链仍保留，external验收仍验证写权威证据。root据此冻结台账5121/localtap2185/event2541，零未知多写断言不改。运行元数据已定位通知失败为CHECK_CLAUSE引号转义；G正在补前向严格入口及负控，不执行生产DDL，旧migration字节保留。
