# G / WP04：冻结合作合同与结算证据候选

结论：已实现真实冻结 reader、关联/履约窗查询、领域归一和经典入口拒结闸门，并编写行为回归及 MySQL 夹具。仅静态检查实际执行；Java 编译/单测、MySQL、真实事务回滚/并发/资金链路均未验证。此提交仅供获批 CI 隔离候选，不得据此正式集成、合并或部署。

## 基线、隔离与候选身份

- 仅公共仓库 `https://github.com/liboyang42-cpu/hengyin.git`，初始公共基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 本批父提交 `051c1c108653eeb1cbc4f3b69f8ae25728c99e34`，分支 `codex/architecture-day-g-fact-contracts`，工作树 `/workspace/hengyin-g-fact-contracts`。
- 原工作树 `/workspace/hengyin` 仍在公共基线 `work` 分支，未切换、reset 或覆盖。未引入私有仓库、日志、备份或凭据。
- 首批 7 个 Java/XML 文件逐字与 051c 比较，实际静态核对不变；父目录交付文件也不改。本目录描述的是资金代码有修改的新一批，不能套用首批 `funds_or_economic_eligibility_changed=false` 的结论。
- 精确文件清单和内容身份见 `source-hashes.json`；提交完整 SHA 通过 Git/协调消息提供，避免把提交自身 SHA 写入本提交造成循环。
- 原环境 `ccarenv_b64_Y2NhcmVudl8xMzExODRkMDM4Yzg4MTkxOWNmZDE0NDA5Y2QxMGMwYQ` 曾 `exec-server transport disconnected`，获准只读探测后在同一原树恢复；未重建或凭回忆还原 diff。

## 本批文件和方法所有权

生产代码仅以下 10 文件（完整路径在 manifest）：

| 文件 | 精确范围 |
| --- | --- |
| `FrozenCoopOrderSettlementSnapshotReader.java`（新增） | `read/readFrozen/validRate/repair`：读取实际冻结合同及已开售冻结票目录 |
| `UnavailableCoopOrderSettlementSnapshotReader.java` | 撤下 `@Service`；保留明确返回 null 的负控实现供冻结测试使用 |
| `CoopSettlementSnapshot.java` | topic/发起人/产品/版本/开售冻结标记/履约窗 DTO 字段 |
| `CoopSettlementCandidate.java` | 源主题、数量、实际票归属和逐票履约窗 DTO 字段 |
| `CoopSettlementEvidenceMapper.java/.xml` | TOPIC/NODE/ACTIVITY 证据关联、时间窗、票种一致性、阻断退款与活动资金查询 |
| `TopicSettlementEvidenceService.java` | `lockCandidates` 按独立 topicId 先解析活动 ID；新增 `requireUnrefundedCashBasis` |
| `CoopSettlementContract.java` | 新增 `requireSupportedCashPath`；`eligible/validateSnapshot/inWindow` 领域归一与完整窗校验；原金额分摊算法不变 |
| `CoopSettlementClaimServiceImpl.java` | 仅 `prepare`：支持范围、封窗、退款与空履约选集闸门；`persist/appendExecutedAdjustment` 未改 |
| `CmsTopicServiceImpl.java` | 仅 `settleTopic/settleCoopTopic/applyUnpaidDepositSetoffs`，新增 `requireClassicTopicCashBasis`，claim 服务改为必需并新增 evidence mapper 注入 |

B 独占 `resolvePendingDepositPaymentForSettlement(CoopInvite)` 方法体逐字不变。其 SHA-256 在 manifest；静态检查同时排除本表以外的 CmsTopic 方法改动。G 的结算调用方遇 `depositStatus=1` 直接拒结，不在结算事务内调用此查单/关单方法。B 在事务外按原支付身份查证、补记后，协调者串行接入；本批不把本地状态或租约过期当作外部未执行的证据。渠道首次锁定、确认原支付关闭后才可重新发起的已批准规则不变。

测试变更包括原 8 个相关测试的夹具迁移，新增两类行为测试、一类 MySQL IT 和一个仅用于旧组合测试的计划替身。没有 controller、经济唯一键、根 pom、CI、已有 migration 或其他任务目录改动。

## 失败机制、调用链和最小测试

旧 runtime reader 恒为 null，因此只能拒结；同时旧 CmsTopic 的可选服务注入允许缺 claim 服务时回退到 live shareMode、主题/商家聚合。旧 mapper 将经济 context ID 当 topic，并以 scope 数字推活动关系，不能证明真实父子归属；时间判断只有 cutoff，没有票的 start。证明某个报名被核销不等于证明当前商家/节点/团接待。

现在调用链：`executeTopicSettlement` 的每主题事务/CAS → `settleTopic` → `settleCoopTopic` → `prepare` → 真实 reader → 先解析 `cms_activity.topic_id` → 锁报名并联查票 → 当前读阻断退款 → 合同选集/金额 → 既有守恒断言 → `persist` 的 claim/detail/header → 既有积分/退款意图。同一主题事务中的任一错误必须回滚；这一实际 MySQL 行为尚待 CI 证实。

新增测试先给有行为的最小反例：ID 同数不同归属、票错 owner、start 之前/end 恰好端点、错误团订单/商家/活动/主题、已撤销 scan、缺冻结条款、scalar 与 JSON 不一致、空服务/空计划、正金额合同无履约、活动有钱而主题合计为零、固定费 qty 缺失/0/2/3、退款净额未证。正控使用真实 lineup reader、真实新 reader、真实 prepare 和 persist，仅 mock 数据边界，断言非零金额和 canonical claim；不能将这个 mock 正控写成真实结算运行通过。

旧五分项组合测试移入显式计划替身，保留金额守恒、每受款方独立快照、去重、收款人和积分顺序断言；它们不证明替身模拟的 CLUB/团路径已开放。真实证据绑定由新领域测试和实际 SQL IT 负责。两个旧依赖危险行为的测试改为更严格的拒结：不得在事务内查单关单抵扣；不得改写 claim 原始金额充当保证金调整。

## 可支持路径与继续拒结的具体事实

本批运行时仅接通以下交集，任一字段/证据缺失不降级：

- 经典定向 `productType=ORIENTEERING`；有效未删除合作单；冻结版本 `v1`；有开售标记且生命周期明确已开售。
- `SUPPLY + merchant + TOPIC(topicId) + GUIDED_TICKET + REGISTRATION_SCAN`；收款 member 为冻结 `toId`，不解析 live club owner。冻结 `fromId` 与主题发起人一致，入口所持主题与计划发起人也须一致。
- 报酬模式/费率/固定费读取冻结 JSON 并与冻结标量一致；主题平台/保底参数齐备，避免调用 pricing helper 的 live 参数 fallback。公式、比例和最大余数分配不变。
- 实际主题票归属与 GUIDED 的 mode/sub_type 一致，逐票 `start <= evidence < end`，且整个冻结票目录窗已结束；不从 topic 日期填补缺失票窗。
- 报名成功/已支付/已核销，冻结 scope 内该商家的真实扫码，金额非空且非负。固定费仅 qty=1；比例型沿用真实 eligibleSales，绝不乘购票人数猜固定费。
- 无待审或已批准退款；无活动壳已支付资金混入经典主题现金基数。没有可证履约的正报酬合同拒结，不以零终态消耗业务身份。

| 分支/事实 | 本批行为及具体阻断 |
| --- | --- |
| TOPIC | scopeId 必须等于实际 topicId；direct registration 的经济 context 是 `TOPIC_EDITION/ownerId` |
| NODE | 读取 `cms_topic_node.id -> topic_id`，不能因数值相等算关联。旧 scan 没有 node/station 外键，团证据也不能证明特定节点，故不授予节点现金资格；冻结 writer 的 NODE+GUIDED 与既有 lineup 要求 EXPLORE_PASS 的冲突继续拒结 |
| ACTIVITY | 先按实际父外键取得 active activity IDs，再锁报名。经济 context 必须为 `ACTIVITY/ownerId`，即使 ownerId 数值恰等于 topicId 也不得归到 TOPIC。现有经典现金头和销售合计只覆盖主题票，本批未实现活动头拆分，故明确拒结。现金保护计数包含已删除壳和已取消但仍已支付的资金，不能因展示过滤隐藏资金 |
| CLUB/团 | SQL 要求团成员与团接待同 topic/activity、当前冻结 order/merchant，且两段时间均在票窗内；但冻结 club 经济主体到最终收款 member 的证据未齐，因此不开放经典 CLUB 现金路径 |
| 退款 | 当前读按 `refund.source_id -> registration.id` 真实来源关联，避免信任退款冗余 owner。待审/已批准（包括已执行）均拒结；后者净额及已执行调整链尚未接通，不能继续按原支付总额算分成。驳回退款不阻断 |
| 保证金支付中 | 拒结并交 B 的事务外查证；不猜未付，不换号 |
| 保证金应缴未缴 | 对合作 claim 原始头的抵扣先拒结，待有调整事件；不先更新保证金再触发原头不相等错误。既有非 claim 应收抵扣规则未扩大 |

`settlement_claim` 七列 canonical 经济唯一键、来源合同、route 冲突保护与详情聚合均未改。HTTP 去重或正文 TTL 不能替代它，D2 已是技术归一，不再要求用户重新选择身份。

## 剩余业务问题与技术阻断

唯一待用户回答的业务问题仍是协调者已提问的固定费多人票：一次 qty=3 的报名只有报名级核销时，按一次履约计 F，还是等到有逐人/席位证据才按实际人数计费（至多 3F）。入口是 GUIDED registration → `prepare/originLines` 的 FIXED_PER_FULFILLMENT 分支。本批对 qty!=1 保持拒结，不重复提问。

其余是技术缺证或未验证项，不推给用户猜业务：ACTIVITY 独立经济头/现金基数、NODE 履约身份、CLUB 冻结收款人、退款净额与结后调整消费者、保证金 claim 调整、并发付款/退款/锁序与真实回滚。`appendExecutedAdjustment` 全仓没有生产调用者，不能据有方法名声称结后退款已冲销；上线前须修复并验证。

## 实际验证与未跑检查

实际运行的轻量检查见 `validation-output.txt`：Git diff 空白检查、仓库 secret-file gate、自定义正文凭据模式扫描、XML/mapper id 和 include 解析、22 个源文件指纹、冻结 7 文件、B 方法体和 CmsTopic 方法范围核对。扫描器不是 gitleaks；未安装新依赖。

`test-status.json` 逐类列出新增测试数量与 NOT_RUN。没有运行 Maven/Java/H2/MySQL/Redis、VPS、真实付款/收费/退款、生产查询、部署或微信上传。当前 VM 仅获准轻量静态检查；Java/MySQL 统一由协调者在已批准 CI/VPS 安排。原 H2 夹具仅同步新列形状，未执行且不可替代 MySQL。

建议受控 CI 先编译并执行本批新增两个单测类和全部八个变更测试类，再显式运行 `Wp04CoopSettlementEvidenceMySqlIT`，最后补主题完整事务/冲突/退款并发验证。MySQL IT 从仓库 base_schema 精确抽取 8 张真实 DDL，使用随机专属库名且要求显式回环 host；测试中的建表/清理只允许在获批隔离测试库执行，未在本环境执行。缺配置失败，不 skip。

## 迁移、集成与回退

无新增生产 schema，无 DDL 候选或执行；只使用既有冻结列/票/扫码/团/退款/账本能力。缺少既有列或旧脏数据持续拒结，不能以清库/补默认值绕过。

协调集成必须按 CmsTopic 方法串行合入 B，核对 manifest 中 B 方法原文指纹；不得用整文件覆盖 B 后续提交。测试/编译通过前只可保持隔离候选。若未发生任何实际结算写入，可回退本批代码，回到明确拒结的旧 reader；一旦实际存在 claim/detail/header 或退款意图，回退不能删除这些记录、释放经济唯一身份或重写原始金额。需要按原业务身份对账和追加调整，不能机械回退数据库。
