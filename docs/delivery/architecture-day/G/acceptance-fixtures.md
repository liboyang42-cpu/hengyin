# 夹具与执行计划（尚未运行 Java/MySQL）

本目录 Python 只校验合成规格的期望集合、金额和静态证据锚点。它不启动业务服务，不证明 MyBatis、Spring proxy、数据库锁、外部调用或事务原子性。以下用例由集成人放到已批准 VPS/项目 GitHub-hosted CI 执行；不要在任务 VM/Mac 自建 Java/MySQL/Redis。已有源码测试可复用，不以测试文件存在冒充通过。

## 结算夹具：先复现失败机制，再实施最小补丁

| ID | 最小夹具/操作 | 必须断言的集合、金额和失败效果 |
| --- | --- | --- |
| S01 | 正金额 classic，无有效 coop；重复执行 SettlementTask | 每次拒绝且 CAS 回滚，coop/detail/balance/points 零新增；不能只断言异常文本。复用 TopicSettlementIdempotencyTest。 |
| S02 | 完整冻结 TOPIC 商家订单、合法已支付已核销报名及扫码；真实 Spring 注入当前 reader | 当前明确 MANUAL_REPAIR、没有零元头；未来授权补丁才要求精确 claim/detail ID 集与非零金额，拒绝“只搬字段”的成功测试。 |
| S03 | owner_type=2 的主题派生场次票；另加无关 topic 场次 | 记录 SQL 候选包含本 topic 壳，但当前 Java 排除；技术修复后保持已规定经济 context 的精确期望集合，无关场次永不进入。 |
| S04 | TOPIC id=activity id 数值碰撞；NODE id=另一活动 id；同节点不同主题 | 当前 scopeCovered 存在类型缺失；修复后数字相等也不覆盖，无越界付款；必须通过真实 Mapper SQL。 |
| S05 | 商家团核验：order、merchant、activity、member 各错一个，另有个人 scan | 对冻结证据规则逐一验证；不得凭 group 缺失偷偷选择另一证据分支。候选集合每次精确比较。 |
| S06 | 时间在 start 前、start、end−1ms、end、end+1ms；结算快照早于截止 | 早结拒绝；end 相等排除；窗前证据不拿来补足已批准履约；上海日窗与票窗口不混，资金补丁补技术校验。 |
| S07 | 同人两报名、order_num=2、一人一票、重复证据；多个 node 同受款方合同 | 沿现有 distinct registration 计量，Q1 未确认前不按购票数量扩付；不把现有 payee 去重删掉，不用 duplicate scan 放大金额。 |
| S08 | 比例分摊 3×0.01×50%；ID 逆序输入 | 目标 0.02；id 最小两条各 0.01，第三 0；重排输入不改结果；头等明细和。固定费和超拆/负额/非分单位另有负控。 |
| S09 | 相同经济 key，DIRECT 与 COOP 或 NONE 竞争，不同 HTTP requestId/TTL | 唯一 claim；跨 route/source LEDGER_CONFLICT、无第二笔经济效果；响应正文清理不删除 claim。 |
| S10 | 付款、核销、退款并发；已核销后退款、部分退款、退款在途 | 无双付、无把退款权益复活；原始明细不变、调整事件可追溯且只执行一次；未接退款消费者不得报完成。 |
| S11 | 在 claim、detail、聚合头、奖励、余额、余额流水、paid 各写入后注入失败 | 同事务本地效果与回执终态一起回滚；重试只得到一套业务键，不依赖 Mock 的“调用一次”。 |
| S12 | 保证金外部请求超时，返回 UNKNOWN，租约过期后重试 | 先持久化原意图；事务外调用；保留原业务号查证，不生成第二号；未确认不得释放/再退。G 未核验外部实现。 |

既有测试入口：CoopSettlementContractTest、CoopSettlementClaimServiceImplTest、TopicSettlementEvidenceServiceTest、TopicSaleFinalizationServiceImplTest、SettlementEntryServiceImplTest、SettlementClaimServiceImplTest、CoopSettlementPayoutTransactionTest、SettlementRefundDeadlineGateSqlTest。Mock/SQL 文本断言只能证明其覆盖范围；事务/并发最终以受控 MySQL 验证。

## 完成事实夹具：逐集合双向比较

| ID | 最小夹具 | 期望/关键负控 |
| --- | --- | --- |
| F01 | classic scan/photo/answer/advanced 各一节点 | 四种按原合同完成写 progress=1；结合现有 ApiPlayProgressCompletionTruthTest 四项用例。照片券限制独立断言。 |
| F02 | generic 扫码/答题验证通过但未 QUEST_RESOLVE | ARRIVAL、TASK_VERIFIED 可以成立，NODE_COMPLETED/THEME_COMPLETED 不预先成立，旧奖励零写；resolve 才生成该任务的完成事实。 |
| F03 | generic route status=1，只有 status=0 progress，无 explore 票 | 精确期望 Tcurrent 包含该局；复现 myCompleted 候选漏入问题；补丁不得制造 progress=1 或发奖励。 |
| F04 | node progress=1 全齐，但线性 FREE_EXPLORE 无票/票缺字段 | 不产生 ENTITLEMENT_REDEEMED；上线前对可达通关/奖励/授权出口要求拒绝危险 fallback。 |
| F05 | default 已完成，replay 新局未完，再将新局合法完成 | current 只读新局，history 保留两局；重玩不再派发平台积分/券/成长值/排名奖牌；同人不同主题 activity=0 不相互撑大。 |
| F06 | game 同 node 首次 FALLBACK，再来 APPROVED；另一个 node 正常 | 首次 outcome 不覆盖，正常/兜底节点集合分别一条；该 member 可同时出现在两个“人数”集合，人数和不得当总人数。 |
| F07 | submission=PENDING/REJECTED/RECORDED/APPROVED | 前两者不完成；RECORDED 仅冻结 EVIDENCE_ONLY 可生成 COMPLETED；APPROVED 来源 AUTO_HASH/MERCHANT_APPROVAL 分开保留。 |
| F08 | TEAM story ending、MEMBER ending、WORLD 控制状态；队员晚加入/退出 | 不把 WORLD 当个人主题完成；沿现有 frozen runtimeScope 保留 TEAM 集合，不新增个人经济资格；保留 gameSession/冻结配置/receipt。 |
| F09 | route=ENDED(2) 与 COMPLETED(1)；story 不同 endingKind | 原 status=2/success=false 不计主题通关；story 已解析不自动是奖励资格，沿已批准终局成功规则独立记录。 |
| F10 | 同 member 两票各核销一半；另一票全必需章核销；到店从未做游戏 | 不能跨票拼通关；可真实核销通关而 progress=0；按票 R/Tpass 集合精确匹配；已核销退款保留历史、无新增资格。 |
| F11 | 受限传感器/兜底/静态码、photo、FREE_COLLECT、default正常四组 | 分别比较完成事实与 points/coupon/xp/badge/drop/medal 资格；依照已批准 policy，禁止“完成=全奖励”。 |
| F12 | 所有入口拒绝/失败、回执 TTL 清理、同 action body 冲突、重复回放 | 业务/经济/权益/外部动作零变化（必要脱敏拒绝审计/限流除外）；业务唯一身份持久、同动作冲突拒绝；历史投影重建零奖励。 |
| F13 | 探索票三分支：默认必选章、自定义角色/需求组阈值、严格零商家纯内容 | 默认逐票 ACTIVE redemption；自定义规则损坏 false；纯内容必须 paid 活动票、明确 topic、无商家/offer/PERK、必选章游戏齐全，通关不伪造权益核销。 |
| F14 | 合法撤销核销冲回过奖励，之后重新真实通关；另设只有历史投影回填 | 前者只对存在且被消费的补发标记允许相应奖励重发，勋章不重复；后者零发奖。不能用“首次终态”删掉现有合法冲回合同。 |

需要对齐的当前消费者：ApiPlayProgressController 的 nodes/advanceProgress/myCompleted/leaderboard、TopicRouteRuntimeService 的状态和参与/完成人数、GameSessionRuntimeMapper 的运营汇总和团队站点完成、ApiCreativeSquareController/ApiStatsController/PreferencePlayService、TopicOnsiteMetrics、PerkLedger、奖励服务，以及小程序 play/merchant/growth 的完成列表/榜请求。完整符号和路径见 caller inventory。源码注释中的历史数据库行数不可作为本轮数据基线。

## 真实验证结果的提交格式

每项保存受控执行入口（CI URL 或获批 VPS 命令）、commit SHA、迁移前置、数据库版本/时区、脱敏 fixture seed ID、expected/actual 主键集合、差集、分为单位的金额、失败注入点、事务提交/回滚结果和重放后不变量。缺任何证据标记 NOT_RUN/BLOCKED，禁止用“预期通过”填通过。

本次 `fixtures.json` 是上述边界的小型合成规格：包含旧/新实例、验证未 resolve、个人/队伍、正常/兜底交叉人数、重复回执和负控；其中 tickets 只覆盖默认必选章规则，不模拟完整业务。`verify_contract.py` 运行记录见 validation-output，不能替代 S01–S12/F01–F14 的业务执行。
