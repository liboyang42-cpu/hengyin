# F：完整治理目标、自动约束边界与 writer 方案

结论：已按公开完整规范重新映射全部 WP00–WP17、Q1–Q10、A01–A15、T01–T22、G0–G5；此前“13 包缺公开规格”的限制已被消除，不能继续用它缩小目标。本交付是 **doc-only 方案和静态证据**，不是新增 policy、业务实现、同候选 CI 或整包验收。155/685 冻结代码、旧 baseline、workflow、业务 XML 均未修改。

## 1 身份、证据层级与改正

| 身份 | 完整值与含义 |
| --- | --- |
| 公共仓库 | `https://github.com/liboyang42-cpu/hengyin.git` |
| 本交付 parent / 规范提交 | `4a6e3cf423594f4e4dc4b30aa769a90048f6aa6e` |
| 被观察/协调方在测的核心 source | `07172e6fddf28f99ea4ad214b5c92cfe7608e441` |
| 不可变公共参考 | `2ec68899099a1e250df0949a8383bf85b5d3ffe0` |
| 本任务分支 | `codex/architecture-day-f-governance-evidence`；从公共 4a6e3cf 建独立工作树 |
| 原 gate helper | `155c88ef52f7f75023de464c8a886c29d017cdba`，保持冻结 |
| 原只读 import helper | `02e69111a83d19d2ad53051a44c629d5e0a5a38a`；仅复用查询，不因此接入 CI |
| 原 producer | `685743902d32252adb2cb320a5f5e59b4af2f866`，保持冻结 |
| 规范文件 SHA256 | `aa0469b16953b604639305f1db13400e16545519d9c386a4f6a5c2ab8da24043`；不是规范内引用的上游材料 hash |

已完整读取 [governance-scope.md](../../COORDINATOR/governance-scope.md) 全 391 行，包括职责/实现合同、全部施工包、自动规则、资源口径、故障矩阵、B01–B10 与发布停止规则。Git 差异确认 07172→4a6e3cf 只有该规范文件；因此不能把另一个候选的绿项拼到 07172，也不能把规范文本当成实现事实。

本轮只写本目录 Markdown/JSON。源码/测试/配置/schema 不变；没有生产、DDL、外部付费动作、Java/数据库运行或 CI dispatch。当前 07172 的 36 SQL 与全 CI 状态由协调方负责回读，本轮没有取得它们的终态报告。**真实 jdeps 及完整编译依赖 SCC 仍为 UNKNOWN，不作无环结论。**

文件说明：

- `queries.md`：可重跑的实际只读查询，路径通过公共 helper checkout 环境变量指定，没有本机/私有路径；不是新 scanner/框架或验收命令。
- `evidence-07172.json`：本轮固定核心 source 的真实静态聚合、排名完整成员、变更路径、helper 身份及规范全部 18 个标题。
- `historical-6788-evidence.json`：保留前轮静态原记录；所有数值只属于该历史 source。其中 Markdown 命中结果、未知规格说明由新规范取代，不能当当前缺规格/缺实现结论；其不完整源级子图 SCC 空列表观察不是 compiled/jdeps 无环证明。
- `verification.json`：本 doc-only 范围、JSON/查询语法/规范 ID 覆盖、秘密扫描等实际检查；不登记业务通过。

下文“可复用”表示源码/工具存在；“缺证”表示本轮尚未取得指定候选上的完整证明，**不等于业务未实现，也不等于已完成**。既有报告中的“未运行/待施工”等历史文字不覆盖当前运行事实。

## 2 实际查询、最高耦合与传播

查询取 immutable Git blob，复用原 XML、SQL splitter、Java lexical/import helper；不执行候选 Java、不访问数据库。fan-in 是显式 import 的不同引用源文件数，fan-out 是不同项目类型数，不是方法调用数、运行期 SQL 次数或业务域数量。

| 查询口径 | 07172 实际观察 |
| --- | --- |
| tracked Java / System Java | 2438 / 1911 个源文件；259 个 business Mapper 直接同包，258 是旧快照 |
| 物理模块依赖样本 | 6 模块、10 条源级观察边、3512 条解析跨模块显式 import；admin 扇出 5，common 扇入 5；不是完整编译图 |
| 全类型 import 扇入最高 | AjaxResult 240、BaseController 225、ServiceException 199、StringUtils 181 |
| Mapper import 扇入最高 | CmsTopicMapper 40、UmsMemberMapper 33、BusinessAuditEventMapper/ClubMapper 各 22 |
| Service import 扇入最高 | IUmsMemberService 58、ICmsTopicService 45、ISysConfigService 30、IWxContentSecurityService 29 |
| 显式 import 扇出最高 | ApiPlayProgressController 69、TopicRouteRuntimeServiceImpl 60、GameSessionRuntimeServiceImpl 52、ApiTopicController 50 |
| 可识别字面写目标 | 365 表、246 技术 writer namespace；19 表多 writer，观察到的最大 namespace 扇入为 2 |
| Mapper 写表扇出最高 | CommunityPostMapper 22、OfficialEventV2Mapper 11；AccountDeregistrationMapper/CommunityTrustMapper/GameSessionRuntimeMapper/RoamMapper 各 9 |
| 单表 statement 扇入样本 | cms_topic 22、cms_registration 21、ums_member 18；不是 writer 数 |
| 2ec→07172 路径差异 | 530 总路径；生产 Java/Mapper 60（admin 6、system 53、quartz 1）；测试 127（admin） |
| 反向 import 传播样本 | 49 个变更类型/Mapper namespace 种子，46 个直接 importer 路径，其中 25 个未在上述生产差异中；观察传递可达类型含起点共 77 个 |
| 路径历史 | 13 个触及生产/Mapper/测试的区间提交；提现/广播 MySqlIT 两路径各触及 4 次，PhotoCheckIntentMapper/ApiClubLeadController 各 3 次；不是事故数量或因果归因 |

已有 helper 明示的未解析项：FQCN 使用 179、wildcard import 93、未解析本地 import 88、共享包隐式引用 21、text block/Unicode 13。这些与接口派发、Spring/XML、反射、生成代码一起构成盲区。不能利用上述图裁掉测试，不能把控制器行数、测试文件数或 degree 作为架构验收阈值。

特别是 WP09 的 ApiPlayProgressController 扇出 69、WP10 的 ICmsTopicService 扇入 45/CmsTopicMapper 扇入 40，只说明职责拆分的实际影响面值得优先表征；**没有证明应用用例归位或职责根因已消除**。2ec→07172 的历史差异也不是 Q5/T15 的三项实际变更演练。

## 3 三层 ownership 与最小 A02 方案（未实施）

1. **技术 package/module**：由物理 Maven 模块、Java 类型位置、Mapper namespace 确认。259 个 business Mapper 同包，不能当 259 个已确认业务域，也不能因同属 System 默认允许跨域写。
2. **业务 table/field**：按规范 5.1，一个事实一个写所有者；混合表必须按字段组登记暂时 owner 和唯一受控入口。`ums_member`、`cms_registration` 等同时包含隐私/状态/经济事实，不能宣布整表只归一个域。
3. **业务 command**：精确到受控 Service 方法、Mapper namespace#id、允许事实/字段组、scope 和原子/回读/恢复合同。不是把整个 Mapper 或包名贴上某域标签。需要 root 与领域负责人确认，F 不猜金额、退款、结算和授权语义。

现有 `owners.json` 是技术解释；`commands.json` 只登记 4 条高风险参考链，不是启用全业务的完备命令/授权表。baseline 的列 hints 甚至包含 `typeHandler`，不能直接用于字段授权。`AccountDeregistrationMapper` 跨 9 表的已知隐私处理与资金写侧共享表，是必须区分字段/命令的真实例子，不代表所有跨表写都越权。

复用点为 `check_source → inventory/sql_shape → literal_write_target/static_findings`、原 `ci/sql_splitter.py`、可信 compare-base、path#id 与传递 include 指纹、既有 gate/policy 固定 SHA 保护。**不再造 scanner、不新增依赖/job、不扩大 2ec baseline。**

root 确认少量 command/field owner 后，才可在现有 owners 合同内加机器可用的精确条目：command 符号和 namespace#id、目标事实/字段组及 owner、允许的公开命令/桥接范围、适用测试 ID、必要的精确例外/退出条件。范围明确后由正常 PR 审阅；不新增每次普通变更的七项审批 JSON，不复制当前 action ledger。机器策略必须纳入可信 verifier 字节保护，不能由候选自生成并自批准。

```text
for 本次可信 compare-base 到 candidate 的新增/修改 statement:
    target = 原 literal_write_target(...)
    继续执行原新增技术 writer 规则
    if target 未确定: UNKNOWN_WRITE_TARGET
    elif command 业务 owner 未确认: UNKNOWN_COMMAND_OWNER
    elif 目标为混合表且写字段归属未可靠确定: UNKNOWN_FIELD_OWNER
    elif 目标事实/全表 owner 未确认: UNKNOWN_FACT_OWNER
    elif 既有桥接命令 body/include 指纹变化但新效果无法证明: UNKNOWN_BRIDGE_EFFECT_CHANGED
    elif command owner != 目标 owner 且不是精确获准桥接命令: 阻断 NEW_BUSINESS_DOMAIN_WRITER
```

同包/同 namespace 的字段扩写、Java 调用者改变、权限撤销后的可达性，不能由当前 writer 规则完整识别。UNKNOWN 必须关联正常 PR 审阅、代表性 SQL 渲染/捕获及对应行为测试；**当前仅输出 review_items 不证明审阅已完成，A02 仍有这项验收缺口**。不因缩减误报而把它改成“已安全”，也不把任意 Java 变更本身判违规。

| SQL/变更 | 当前可确定或应保留的边界 |
| --- | --- |
| XML 明确字面目标 | 能确认技术 writer；已知表出现新 namespace 时沿用 NEW_TABLE_WRITER；不能据此证明 scope/CAS/经济语义 |
| include/动态条件 | 原传递指纹可复用；若目标明确只能确认声明了该表写能力；动态字段/谓词效果仍 UNKNOWN，不冒充完整渲染 |
| 新表/全部 writer 已从比较基点移除 | owner 未知须显式列出；不能凭旧观察恢复授权或自行设默认业务 owner |
| 注解 SQL/Provider | gate 当前不解析；07172 词法候选与独立生产 Java grep 均无直接 DML/Provider 注解命中，不表示未来受保护；出现时列精确 UNKNOWN，再分配可靠覆盖 |
| schema/别名/多表复杂写、动态目标、JDBC、插件/反射 | 当前目标/字段/运行期效果覆盖不完整；按具体形状保留 UNKNOWN，代表性真实 SQL 补证，不造第二 SQL/Java parser |
| 本地事务/外部动作 | 静态字面表不能证明回执同事务、原意图、UNKNOWN 查证、lease/fence、恢复可达；必须按 T 矩阵验证 |

最低正反例：未改共享表/文档改绿；普通同 owner scoped 修改不自动越域；新 namespace 写已知表红；业务 owner 全部明确而新增非 owner 写红；未映射字段、Provider/动态目标明确 UNKNOWN；桥接效果改变不得自动放行；候选自改 policy 不得自批准。当前 select 标签伪装 DML 的旧索引一致性潜在盲点可用内存反例复现，但 2ec/历史 6788 实际命中均为 0，不能把假想负例称为现有业务事故。

## 4 WP00–WP17 真实目标与自动约束缺口

所有 18 包目标现已明确。表中“缺证/待核”不是“业务未实现”；旧专题报告、类存在或测试登记不等于本次 07172 验收。每个包均保留规范中的前置、合同和回退要求，不用本表缩减启用范围。

| WP / 真实目标 | 已有可复用代码或工具（存在性） | 自动约束边界与必须补的证明 | 对应验收 |
| --- | --- | --- | --- |
| WP00 基线和启用范围冻结 | public SHA/tree、COORDINATOR 候选记录、release_manifest、当前调用者/action ledger | 必须核对完整启用范围/端点/所有生产副作用与运行身份；报告引用不等于数据库/部署身份。AGENTS 技术栈本来已正确，不重复修 | G0、Q9/Q10 |
| WP01 自动架构约束和行为安全网 | 155 源 gate/精确 baseline/负控、685 producer、assert_it_ran | A01–A15 尚非全部自动证明；实际 compiled graph/SCC UNKNOWN；业务 owner、注解 SQL、动态审阅与真实行为证据仍分范围补齐；不引未批准 ArchUnit | A01–A15、G1/G2 |
| WP02 提现回补资格与资金证据 | CashWithdrawServiceImpl、WithdrawalBalanceAdmissionService、事实 Mapper；A 交付及专属测试 | 缓存属性/字面写规则不能证明扣款/回补守恒、合法未提交退出与 UNKNOWN 原号查证；需同候选自动/人工竞争、迟到成功及故障回滚读回 | T01–T03、Q1–Q3 |
| WP03 支付入口事实一致与领域路由 | WechatPayServiceImpl、支付 inbox/claim、域内消费与当前测试 | 必须逐四路校验预期 appid/mchid/currency/amount/order/transaction，唯一域路由、ACK 与乱序/fence；不能靠统一路由存在或返回码证明 | T04/T05、Q1/Q2/Q6 |
| WP04 主题结算闭环 | CmsTopicServiceImpl、CoopSettlementEvidenceMapper、冻结 reader/claim/明细及 G 专属测试 | 不将 SQL evidence 当完整资金证明；合法正金额真正增账、守恒、退款冲销、真实 DDL/并发/提交失败仍按本候选读回。业务金额/退款规则不由 F 设默认 | T06、Q1/Q2/Q10 |
| WP05 玩法写命令与章节推进 | ClubLeadMapper、玩法 action/receipt、当前小程序 pending/action ledger 及 C 合同 | 原身份/原 expected、合法新 attempt、章节 CAS、切号与进程恢复须端到端；静态存在 key 或事务不能证明身份/语义一致性，无旧小程序危险 fallback | T07–T09、Q2/Q3/Q6 |
| WP06 完成事实查询与统计口径 | TopicRouteCompletionCoordinator、ExploreCompletionReadServiceImpl、当前统计/完成事实测试 | A 必须覆盖事实/来源/唯一性/消费者和奖励资格；B 批量 CompletionQuery/只读影子/回填按范围迁移。不能以某条 Photo 完成链替代所有 runtime/统计/经济口径 | T10/T11、Q1/Q5/Q6 |
| WP07 官方广播可靠送达 | OfficialBroadcastServiceImpl、投递表/CAS、D 专属 MySQL/合同 | false/异常/响应丢失/模板缺失/部分通道成功、统计口径和重启恢复需证明；锁查询属性与有表均不证明已送达或未知授权可重投 | T12、Q2/Q3 |
| WP08 恢复合同复用与可靠性覆盖 | 原媒体/支付 inbox、退款、IM/掉落/徽章意图与任务，D signup、G 调度准入专题 | 目标是所有生产副作用分类和领域恢复合同，不只 signup/Photo。需原事务回滚无事件、提交后崩溃、旧 fence、毒任务/部分 fanout/积压隔离；不统一九表、不全禁 afterCommit | T05/T12/T13、Q3/Q4 |
| WP09 玩法应用用例归位 | ApiPlayProgressController、已有 TopicRouteCompletionCoordinator/reward services；import 扇出样本 | 根因是事务编排回应用用例；先表征再一次一个端点机械移动。尚不能从 degree/行数/Photo 边界宣称归位；需直接调用用例且权限/传播/锁序/奖励/异常效果等价 | T14、Q5/Q6 |
| WP10 主题职责和领域依赖整理 | ICmsTopicService、CmsTopicServiceImpl、TopicSaleFinalization 等已有能力 | 根因是编写/发布/销售生命周期/结算编排的责任和依赖方向；保留薄门面，funds 通过冻结查询合同。不能用改包/拆小类或关闭结算代替职责解耦 | T14/T15、Q5、A01–A04 |
| WP11 查询、缓存和资源预算 | 路线缓存、IM batch/scope IT、ci/perf/manifest.json 及现有性能脚本 | 全部热点 SQL 次数/权限、稳定游标全集、项数/单项/总字节/TTL/并发淘汰、provider 降级不缓存均须量测；不是只看 IM 一条查询，也不宣称 20 页总请求恒定 | T11/T16、Q4/Q8、A10 |
| WP12 状态、授权和客户端单一状态 | A 状态写索引/入口保护，既有角色/HTTP/当前客户端合同 | 保持 DB 编码；实际状态矩阵/错误 outcome、HTTP actor/tenant/admin-app 权限、迟到 UI 响应需验证；静态字符串安全断言不能独立代替真实 HTTP 与数据库零未授权效果 | T08/T17/T18、Q2/Q6 |
| WP13 业务可观测性和隔离 | RequestIdFilter/MDC、AlarmService、ThreadPoolConfig、原通道/任务设施 | 必须补 command/entity/source receipt/jobRunId/event/fence/candidate 跨链关联及敏感日志约束；每通道超时/并发/队列/重试预算与混合故障量测。Photo 事务首修不是本包完成 | T13/T19、Q4/Q7/Q8 |
| WP14 调度、多实例与灾备演练 | MySqlSchedulerCoordinator、SchedulerSessionLockMySqlIT、原任务锁/epoch/fireTime | 领导丢失/旧任务迟到、重复触发、Redis 丢失语义与备份后外部成功但本地意图丢失须隔离演练；核对原编号/ID 高水位并实测 RPO/RTO。不预换 Quartz cluster | T13/T19/T20、Q3/Q8/Q10 |
| WP15 迁移、候选发布和回退 | migration_ledger/audit_migrations_applied/release_manifest、原迁移门禁 | layout/hash/SQL 自测不等于真实前态迁移；须 ledger+checksum+schema+data assertions 与 UNKNOWN、重试/失败恢复、兼容 JAR 停新写回退读回；不执行本任务 DDL | T21、Q10、G2/G3/G5 |
| WP16 多人多 Agent 施工治理 | 独立公共任务分支、保留文件、单一共享合同集成及候选记录 | 需文件/表字段/接口事件 owner、两组并行任务与语义冲突裁决、最终同一候选完整回归；工单/自报/文本无冲突不等于集成验收 | T22、Q9 |
| WP17 综合业务验收与收口 | 规范完整 Q/T/B/G 矩阵及既有 CI/当前客户端入口 | 独立 QA/业务与集成人联合；启用 B01–B10、正常角色、事故恢复和三项演练、所有失败/UNKNOWN/关闭项逐条读回。不得开发者单独自签、以 CI 绿代替业务验收 | Q1–Q10、T01–T22、G0–G5 |

WP13 的具体静态观察：RequestIdFilter 已对入站 requestId 做安全字符/长度处理并在 finally 清 MDC；这只证明请求边界的一段代码。ThreadPoolConfig 的普通池源码为 core 50/max 200/queue 1000/CallerRunsPolicy，另有 scheduled executor；这些值不是已批准容量/实测饱和行为，也不证明跨通道隔离。需要追到实际各 provider/任务的池、HTTP timeout、连接、重试 owner，再在批准资源内做 T19；不能修改预算去掩盖拥塞或把超时当外部取消。

## 5 A01–A15 自动化边界

| A | 当前可复用/自动检查 | 仍须覆盖，不能宣称已证明 |
| --- | --- | --- |
| A01 模块公开边界 | 技术模块/类型位置、冻结 import 查询样本、JDK producer | 业务映射、新生产类 owner、实际编译跨域 internal/Mapper/entity 边界；compiled 图未知 |
| A02 数据写权 | 原 XML 字面新 writer、作用域粗形状、指纹和 review_items | command/field owner、注解 SQL、动态渲染/真实捕获、人审与测试完成；本报告仅给最小方案 |
| A03 依赖方向 | POM 依赖增量与源级观察、既有 context/startup 测试入口 | 模块与注入图分别取证、compiled SCC/扩大旧环和 Spring 真装配；@Lazy 不算消环 |
| A04 控制器职责 | 当前入口/真实调用者、已有用例与 mapper 源码可审 | 新改命令入口→应用用例映射及精确旧债；WP09/10 的行为表征/职责归位未被通用机器规则替代 |
| A05 危险写契约 | commands.json 四链参考、既有 action ledger/端点合同 | 所有启用危险写 scope/身份/前置/回执/恢复及行为用例；不把所有 POST 当危险写或只贴注解 |
| A06 资金原子性 | 相关 MySQL suites、现有 ledger/事务断言、报告回读 | 同 source 正常增账、故障点/并发/原身份不释放及前后守恒；Mock/H2 或源级门禁不能替代 |
| A07 恢复与栅栏 | inbox/intent/claim 与领域已有合同/任务 | 全关键链 claim/接管/旧 token 完成、外部 UNKNOWN 不盲重发、进程故障；不全局强制单表 |
| A08 状态与错误合同 | 状态写索引、现有状态 DTO/客户端/权限测试 | 真实矩阵、稳定 errorCode/outcome、拒绝数据库/权益/外部零未授权变化，保留安全审计 |
| A09 客户端调用一致 | API-existence、action ledger、当前调用者/当前小程序测试 | 方法/字段/权限/结果语义、原 key/expected/主体隔离与迟到响应；路径存在不够 |
| A10 SQL 和资源预算 | 特定 IM 查询计数/EXPLAIN 准备、cache/性能合同脚本 | 启用热点数据规模、缓存字节、线程/连接/队列、N+1、混合压力/故障实际曲线 |
| A11 迁移与数据真实状态 | 原 migration layout/safety/ledger/checksum 工具 | 真目标前态/实际结构/数据断言/历史 UNKNOWN 与应用版本一致；本任务不执行迁移 |
| A12 测试实际执行 | assert_it_ran、real_storage_it/registry、原始 XML/hash/source-run identity | 每工单必需清单、实际 source/run/attempt、无漏跑/skip；登记和 mvn 退出码不是全部证明 |
| A13 候选一致性 | release_manifest、candidate resource manifest、Jar/XML/source 身份上传 | 运行 JAR/config/schema/client 组合独立读回，同候选完整绿；不拼历史/不同分支结果 |
| A14 公私仓和环境边界 | secret-file/gitleaks、环境/runner/生产目标限制 | 历史/资源/部署授权仍需流程取证；通过字符串扫描不自动授生产权限 |
| A15 基线不可洗白 | 固定 2ec blob 精确再现、candidate/trusted gate 与 proposal 字节校验、负控 | 新业务 owner 策略需 root 审定并可信固定；不把当前 SQL 漏口洗入旧债、不自动 re-freeze |

## 6 Q1–Q10 与 T01–T22 证据需求

| Q | 本目标不能省略的验收 | 本报告可提供与明确不能提供 |
| --- | --- | --- |
| Q1 正确性 | 合法首次经济效果与重复/并发/重启至多一次、守恒 | 提供写入口样本；不能提供 MySQL 经济读回 |
| Q2 状态可靠 | 非法迁移/越权零未授权效果、UNKNOWN 不冒充终态 | 提供规则边界；不能提供全部状态/身份矩阵通过 |
| Q3 可恢复 | 每个接受命令可定位终态/待恢复、原 ID 恢复 | 指向已有恢复源；不能以源文件存在证明崩溃恢复 |
| Q4 故障隔离 | 慢 AI/地图/单通道下支付读回守预算、积压有界 | 指向池/通道；未运行混合故障负载 |
| Q5 变更成本 | 三项实际变更演练，其他域内部写侧零修改 | 给第 7 节具体计划；历史 degree/路径统计不计演练完成 |
| Q6 可测试 | 每启用写链正常/拒绝/重复/并发/丢响应/重启，无必需缺失/skip | 可复用报告工具；未对 07172 全清单作执行验收 |
| Q7 可观测 | 失败业务 ID 串起入口/意图/事实/回调/任务/恢复 | 确认 requestId/Alarm 等设施；未取得完整脱敏相关性轨迹 |
| Q8 可扩展 | 固定资源/规模/并发容量曲线，多实例前接管和幂等 | 不把配置值/import 图称容量；需预算内实测 |
| Q9 并行安全 | 两组任务、单 owner 共享合同、同候选完整门禁 | 本 doc-only 范围可查；不能代签整轮 T22 |
| Q10 回退演进 | 停新写保读回/原意图恢复，账本/未知付款/会话解释不破坏 | 给非破坏回退边界；没有实际扩展/切换/灾备演练回执 |

| T | 真实用例与所需层级 | WP / 本轮执行状态 |
| --- | --- | --- |
| T01 | 提现 UNKNOWN、迟到成功、SYSTEM_ERROR/5xx/NOT_FOUND；MySQL+渠道分类桩及批准查证 | WP02/03；本轮未执行 |
| T02 | 余额回补后/终态前故障、提交丢响应、自动人工竞争；MySQL 真事务守恒 | WP02；本轮未执行 |
| T03 | 无扣款/丢响应/冻结/身份不符及合法未提交唯一回补；持久证据读回 | WP02；本轮未执行 |
| T04 | 四路正确验签下错身份/币种/金额/交易；HTTP/域内 MySQL 零未授权效果 | WP03；本轮未执行 |
| T05 | 并发/乱序回调、退款早到、接管旧 fence；MySQL+真实代理 | WP03/08；本轮未执行 |
| T06 | 正金额真实增账、唯一明细、并发守恒、退款冲销及不合法拒绝；真 DDL/MySQL | WP04；不能用 SQL-only/零金额代替，本轮未执行 |
| T07 | 章节同键/不同键同 expected、首末章与队长移交；DB/当前调用者 | WP05；本轮未执行 |
| T08 | 同键异语义、新 attempt、缺键与合法旧回执；客户端+业务状态 | WP05/12；本轮未执行 |
| T09 | 断网/进程重建/切号/迟到响应，原键原参数恢复；当前客户端真实过程 | WP05/12；本轮未执行 |
| T10 | 经典/个人/团队/重玩/版本/兜底/照片待审/未核销/离队退款事实集合 | WP06；本轮未执行 |
| T11 | 固定快照影子、稳定游标遍历、回填中断与幂等；只读且无经济副作用 | WP06/11；本轮未执行 |
| T12 | 模板/false/异常/丢 ACK/重复/部分通道；送达与恢复读回 | WP07/08；本轮未执行 |
| T13 | 四个提交/派发/外部成功/ACK 故障点与重启原号恢复、旧 worker fence | WP08/13/14；仅批准的精确任务进程，本轮未执行 |
| T14 | 同夹具旧新用例行为等价、权限/传播/锁序/异常与 DB 效果 | WP09/10；不是类变短，本轮未执行 |
| T15 | 玩法规则、通知通道、统计查询三项实际变更与 owner/依赖/回归读回 | WP10/17、Q5；计划见第 7 节，三项全部未执行 |
| T16 | 缓存超容量/长项/淘汰/慢 provider/大列表；资源与 SQL 预算 | WP11；本轮未执行 |
| T17 | actor/tenant/角色/admin-app token错入口及切号重放；真实 HTTP+DB，保留安全审计 | WP12；本轮未执行 |
| T18 | 审核异常/违规/待审、模型前失败/启动后超时与 UNKNOWN 原回执 | WP12/13；本轮未执行 |
| T19 | AI/地图长超时、毒任务、领导丢失下支付/读回及积压可见性 | WP13/14；本轮未执行 |
| T20 | 隔离备份恢复关闭外派、备份后渠道成功但本地整条意图丢失、ID 高水位/RPO/RTO | WP14；不复用旧编号/盲重发，本轮未执行 |
| T21 | schema 前态、失败/重跑/UNKNOWN、数据兼容 JAR 回退和原单可恢复 | WP15；本轮未执行 |
| T22 | 两组不相交任务、共享合同单一合并、语义裁决、最终同 SHA 回归 | WP16/17；本轮只保证自身 doc-only 隔离，不代签整轮 |

“本轮未执行”不否认别的会话已有历史执行。后续必须读取对应 **实际 source/run/attempt** 的原报告并核对覆盖，不把 07172 正在运行的 36 SQL 或全 CI 预先填绿。

## 7 Q5/T15：三项实际变更演练计划，不是统计代替演练

三项均为 **NOT_EXECUTED**。root 先确认选题、启用范围、业务 command/field owner 和公开合同；以下是可执行的演练合同，不授权本任务改业务规则或调用真实付费通道。可以串行、每项单独隔离分支，禁止为“演练”绕开真实正常 PR/回归。

| 演练 | 必须真正做的变更 | 允许范围与必须留存的证据 |
| --- | --- | --- |
| 玩法局部规则 | 由玩法 owner 选一项已启用玩法的常规规则调整，形成实际实现/测试 patch；不由 F 猜规则/奖励时点 | 只改该玩法声明的策略/应用用例与必要公开 DTO/适配；保存基点/候选、真实文件和调用者、依赖差分、正常拒绝/重复及受影响 DB 效果回归；其他域内部写侧改动为零 |
| 通知通道 | 通知 owner 选一个实际通道扩展，接现有 NotifyChannel/typed result、意图与恢复合同，形成实际 patch | 仅所属通知适配及公开合同；成功/false/异常/UNKNOWN/部分成功/fence/重启回归；不改资金/玩法内部写侧；外部真实性单列 G4，不拿测试替身当供应商验收 |
| 统计查询 | 查询 owner 确认业务口径后增加一项真实统计查询，经公开批量查询/只读投影接消费者 | 保存固定数据集、scope/排序/空值/时间精度/集合 oracle、游标及 SQL 预算/EXPLAIN；不能加跨域直接写、奖励双执行或 N+1；依赖差分和真实回归留档 |

每项输出“目标/owner、实际 patch、改变前后影响清单、公开合同修改理由、其他域内部写侧逐路径审阅、相关回归实际回执”。按声明的 **业务域/command** 判断范围，而不是因为都在 chengyinhub-system 就称“只改一个模块”。query 图有盲区时附人审/实际 SQL/装配证据；不能靠空图或源码 grep 证明零跨域影响。

通过标准是三项均真实完成，其他域内部写侧零修改、依赖不恶化、正常与负例行为符合合同；未知作用域、必需漏跑、scope/经济效果变化未审定均不得签通过。三项计划、历史传播统计、单次成功构建或删掉所选功能均不能替代演练。若选题暴露 WP09/10 根因耦合，记录实际受阻和最小职责整改，不放宽 Q5。

## 8 G0–G5、B01–B10 与交付边界

| G | 必需证明 | 本 doc-only 交付边界 |
| --- | --- | --- |
| G0 | 源码/tree/当前调用者/DB 身份/启用范围/owner | 给公共 source/spec/helper 身份；未验证部署数据库和完整启用清单 |
| G1 | 源码架构/业务单测/端点状态/依赖影响审阅 | 给自动约束缺口与静态证据；不代表全 G1 通过 |
| G2 | 同候选构建/必需真库实际执行/权限并发恢复 | 未回读 07172 在跑结果；不拿登记/历史绿拼接 |
| G3 | 实际 JAR/hash/schema/config、重启恢复/预算 | 未访问 VPS 或运行系统；UNKNOWN |
| G4 | 当前小程序/其他调用者/真机核心旅程/批准渠道结果 | 未执行；Mock/静态接口存在不替代 |
| G5 | 用户批准范围、实际发布状态、独立业务签署与回退证据 | 隔离 doc-only commit/push 不构成发布/部署授权或完成 |

WP17 的全业务不能被本报告的热点样本替代：B01 内容/发布冻结、B02 报名/支付/名额、B03 核销/商户、B04 俱乐部角色、B05 社区审核回调、B06 漫游/奖励、B07 消息/已读、B08 账户/隐私、B09 后台运营、B10 订阅/语音等剩余能力都要按启用范围覆盖正常、权限、重复、异常读回。B10 的续期叠加和供应商未知计费不得由 F 猜规则或先加唯一键解决。是否关闭未启用能力需用户确认，不因未测自动排除。

本次交付只为 root 确定小范围业务 owner 和下一批工单提供证据。下一实现批次仍待 root 分范围；没有新增机器 policy、依赖、scanner、baseline 例外、资源购买/runner、DDL 或部署。

本批实际检查见 verification.json：JSON 可读、查询代码标准库语法、完整规范 ID 覆盖、公共相对路径、doc-only 范围、冻结源码字节与 secrets/diff 检查。不为文档新增业务测试，也不把此前 94 项架构自测重新计成本轮执行。新增业务功能数为零，Q5/T15 演练完成数为零。

回退仅撤本 F 文档目录；不会变动账本/回执/业务身份或影响核心二进制。任何整轮资金不守恒、越权、未知动作盲重试、接受命令不可定位、候选/schema 不一致或必需漏跑仍按规范 No-Go；本报告不提供豁免。
