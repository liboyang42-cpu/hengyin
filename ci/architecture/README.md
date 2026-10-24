# F：架构增量门禁与报告回读

本工具使用 Python 标准库，只做可可靠识别的源级检查。它不要求普通 Java/测试/文档变化提交七项 JSON 审批或 Java 测试回执；审阅仍走正常 PR code review。源码门禁和真实测试回读是两个独立入口。

## 固定存量，增量比较

`baseline.json` 始终从公共 `2ec68899099a1e250df0949a8383bf85b5d3ffe0` 的 git blob 精确再生成核对，保持 2,948 条 Mapper 语句、94 条依赖声明、表字段写入口提示及传递 include 指纹；不按新候选重建、增补例外。其字节及 verifier 的修改需要受信任 verifier 的新固定 SHA，不能由候选自豁免。普通门禁测试、README 变化本身不阻断。

本次影响另用可信 GitHub CI 事件校验的 `--compare-base`，不是永远 diff 2ec→candidate：

- PR：candidate 为事件中的完整 head SHA，compare-base 必须等于 head 与事件 base SHA 的唯一实际 merge-base。CI checkout 必须是该 head 或父节点恰为 base/head 的 synthetic merge。
- push：candidate 与事件 after、GITHUB_SHA 相等，compare-base 必须是事件 before，并且为 candidate 祖先；全零 before、非祖先强推等不能静默改用旧 baseline。
- workflow_dispatch：candidate 为 GITHUB_SHA；从真实事件 repository.default_branch 读取主干名，校验 origin 是固定公共仓库 HTTPS 地址，显式 fetch 此主干到 origin tracking ref 并与 FETCH_HEAD 核对，再取其与 candidate 的唯一 merge-base。缺事件、错误远端、fetch 失败、歧义 merge-base 均拒绝，不回退到 candidate 父提交。执行 source 时再次核对；主干在两次计算间移动导致比较点变化时拒绝并重跑。
- 缺可信 CI 上下文、错误仓库、短 SHA、任意祖先冒充 merge-base、自身充当 base 均拒绝。GitHub 事件文件和 verifier 必须由受信任工作流提供；这是 CI 来源信任，不是本地环境变量的密码学证明。

已进入比较 base 的历史改动不重复列出，也不修改固定存量 baseline。原技术 owner 检查仍从比较 base 的字面写目标取得 Mapper/module 写入口；新增的窄 business policy 则只使用独立受信任版本中经root明确批准的两项事实，不从 observed writers 推定业务权限。未改历史不要求后续每个 PR 重审，也不成为新增写能力的许可。

## 自动阻断与审阅项

| 类型 | 自动行为与界限 |
| --- | --- |
| 新增依赖/扩大 scope | 比较标准化 group/artifact/type/classifier、声明数量及已知 scope 使用范围；新增坐标、增量声明、test→compile 等扩大为红。仅格式、版本变化或 compile→runtime 不作为新增依赖，POM 仍列审阅项。不是 effective POM/传递依赖分析。 |
| 跨技术 owner 写 | 新/变更语句的字面写目标是已有表，而 module/Mapper namespace 不在比较 base 的已知写入口中，判红。表名不明确或新表无既定 owner，列 UNKNOWN，不猜归属。 |
| 两项已批准 whole-table 写权 | 仅 `AI_QUOTA_RESERVATION` / `PHOTO_CHECK_INTENT`：新增确定写边的 module+namespace 未声明为纯writer，报 `UNDECLARED_WRITE_BINDING`；已声明但属于另一批准业务owner，报 `NEW_BUSINESS_OWNER_WRITE`。比较 base 无该表也适用。按 path#id、writer、操作、字面目标比较新增能力，旧债不因普通修改反复列出；删后重引/改id/新增操作不能借旧债。固定前缀目标确定、字段动态时可同时红且列 UNKNOWN；不证明完整效果。 |
| 明确无作用域写 | 新/变更的可识别字面 UPDATE/DELETE 缺 WHERE 或 WHERE 恒为 1=1/true，判红。复用 `ci/sql_splitter.py` 处理引号/注释，避免字符串中的 where 伪装作用域；select 标签伪装的直接 DML 同样检查。 |
| 动态/复杂 SQL、include | 不做完整 SQL 语义证明。动态标签/表名、未解析/循环 refid、复杂 SQL、删除语句、新表等输出 `review_items`，交普通 PR 审阅。保留跨 namespace 传递 include 指纹和片段文件级提示，动态 refid 片段变化不会漏出清单。 |
| Java、测试、workflow 变化 | 不是违规本身，不要求 JSON 审批/Java 报告；由既有测试/成本/报告门禁及 PR review 处理。不声明自动证明 JDBC、反射、授权、事务、版本/CAS、意图/恢复和拒绝零副作用。 |

`findings` 是可识别阻断项，非空退出 1；`review_items` 是仍需审阅的未知/合同提示，单独存在不导致退出 1。`PASS` 或 `PASS_WITH_REVIEW_ITEMS` 只代表该源级检查，没有表示业务合同安全或后端测试通过。

`owners.json.business_policy` 只启用两个whole-table事实及其唯一纯writer，`commands.json.business_commands` 引用原 AiQuotaRecoveryService / PhotoCheckIntentService 公开合同；整份owners/commands与gate/原baseline/locking proposal/实际producer共六个文件按可信verifier字节冻结。未声明或无效policy不得静默关闭检查，candidate修改owner/删除fact/清空scope/伪造command都须独立review的trusted revision，不能自批准；CI固定ref由root单独repin；候选不能自改绑定或producer后批准自身。

输出 `business_policy.coverage=PARTIAL`；范围外字面写列 `BUSINESS_WRITE_OUTSIDE_POLICY`，动态/未知目标或效果列 `UNKNOWN_BUSINESS_WRITE_EFFECT`，不把UNKNOWN叫安全或宣称完整A02。原四条高风险链仍是代码/恢复源/已有测试参考。所有command引用均不证明Java调用必经Service，不授权其它domain直接调用Mapper，不是新审批系统或运行时合规认证。scope、唯一身份、关键意图、版本/CAS、恢复调度和原子提交只有业务 owner+相应故障/并发测试才能确认；静态解析做不到的必须保留在 PR 审阅中。

## 三项私有 Mapper 的编译引用回读

`owners.business_policy.private_ports` 仅声明 AiQuotaReservationMapper→AiQuotaRecoveryService、PhotoCheckIntentMapper→PhotoCheckIntentService、AiThemeDraftIntentMapper→service.impl.AiThemeDraftRequestService 三条精确class绑定（均system）；直接只读依赖也受限，`$`内部类不按前缀允许。它与SQL writer身份分开；第三条是独立技术持久端口约束，不新增 whole-table fact / pure writer / business command，也不把技术class owner当作其它表/字段的批准业务owner。新增绑定须独立trusted revision。

`collect_jdeps.parse_output` 可在跨模块过滤前额外收集这三个目标；默认调用和原五项返回值、模块图格式/观察口径不变，不增加System自环或基线。`gate.py compiled-ports` 从实际Boot/original模块Jar/target classes重枚举并核对原11项证据、实际source/tree/run/attempt/JDK工具hash、class-index和invocation，再重读raw；不信候选JSON的references/findings/complete声明，不再运行Java/jdeps。

新CLI退出码为0（仅三个声明目标的静态引用完整通过）、1（确定的其它consumer，PRIVATE_MAPPER_CONSUMER）、2（证据/目标/索引/位置/覆盖UNKNOWN）。已解析确定违规即使同时存在其它UNKNOWN也返回1，缺图/缺边不通过；不证明方法调用、反射/XML、运行授权或完整A01/A02。源级规则继续工作，source的既有退出码不变。CI只能采用同job固定可信producer产生的raw，hash自洽不是任意producer诚实的密码学证明。

现有root工作流已使用两个固定可信checkout、单次producer及post-build回读；本片不改它，root需独立审阅并同时repin同一新完整verifier SHA。第三目标真实同System编译负控待root在隔离CI执行，Python ZIP/protocol fixtures不是编译证据。参数、忽略target可信checkout、单次采集及失败保留原artifacts的建议见 `docs/delivery/architecture-day/F/private-mapper-compiled/CONTRACT.md`；第三目标最小delta、具体Java负控与未验证项见 `docs/delivery/architecture-day/F/theme-private-port/REPORT.md`。

## 新职责 owner 的编译方向约束（窄增量切口）

`technical_dependency_policy` 与两个 whole-table fact 分开，不授表/字段/命令业务权限。它登记 d49 已实际落地的八个技术职责：A withdrawal admission、C hint application/node access、D graph/copy/content rules、E quota recovery、G settlement evidence；精确类名/模块/原God目标在冻结owners.json，不按包目录或观察次数推断owner。当前明确方向为 hint→access、graph→copy/rules、copy→rules；这个新职责切口要求无环，原全局存量环/2ec SQL与依赖baseline均不改，不把存量全图变成每PR门槛。

原 `compiled-ports` 在同一次真实JAR/11项证据回读后复用raw parser检查方向、回依赖原God以及scoped owner SCC：`OWNER_GOD_BACKREFERENCE`、`NEW_OWNER_DEPENDENCY_DIRECTION`、`NEW_OWNER_CYCLE` 为确定阻断。类/模块/原始输入缺失时，包括owner子结果在内都保持UNKNOWN。成员的binary companion仅纳入限制，不授予私有Mapper消费权，原三项exact Service规则仍独立保留；默认采集和模块图格式不改，不新增Java/jdeps调用或job。

这是声明类集合的结构约束，不是方法/事务/只读性证明。特别不把旧带refresh写的GET状态当只读证据；新readAssigned/runtime投影须由其真实实现/业务合同另验。未登记职责、间接调用、XML/反射、同owner内部类型环及全图仍未证明，`graph_complete=false`；不能拿候选自行生成图来增加baseline或放行。source入口只核对冻结policy，编译方向结论只能来自既有可信post-build入口；没有新审批JSON、全仓新scanner或ArchUnit。

root单一将两处trusted ref从5451672替换为本片审定完整SHA，原单次producer/post-check/artifact上传原样；F不修改ci.yml。原API/持久化/业务实现和测试场景不因本片改变。按当前批次约定，只做新增负控的轻量定点，完整153及真实编译正反控由root统一调度。

## 运行与 CI 接线

```sh
python3 -m unittest discover -s ci/architecture -p 'test_*.py' -v
# CI 环境中，用固定版本 verifier 扫描独立 candidate worktree：
python3 gate/ci/architecture/gate.py source --source candidate \
  --candidate "$SOURCE_SHA" --compare-base "$COMPARISON_BASE_SHA"
```

`.github/workflows/ci.yml` 在既有无条件 `fast_gates` 尾部增加架构步骤，不新增 job/matrix、不改既有检查条件和 `max-parallel: 20`。checkout verifier 使用固定完整 SHA；自测从该受信任版本执行。candidate 使用本 job 唯一临时 worktree，完整历史读取；`if: !cancelled()` 保留前序失败后的诊断，阻断退出码不会吞掉；上传 findings/review_items，清理只触及本 job 的 worktree。

PR 架构检查使用 PR head；原 Backend job 仍按既有 checkout 检查 integrated merge。输出明确区分 candidate_sha 和 ci_checkout_sha，不能把一个 SHA 的 Java 报告当另一个 SHA 的验收。手动冻结候选使用 workflow_dispatch 可统一在该候选 SHA 上实跑。

## 现有报告机制

`evidence --plan ... --receipt ... --reports ...` 是独立的一致性辅助入口，先复用受信任 `ci/assert_it_ran.py`，再核对全限定类名、精确 testcase、报告集合/哈希、candidate/run/attempt 和 Maven exit code。缺报告、零用例、skip、失败/error/flaky、重复 testcase、错候选、错 attempt 必须红。它没有接成普通源码变更的额外审批门槛。

真实 Java/MySQL/Redis 验证继续使用现有批准的 `ci/backend-test.sh`、`ci/funds_it_gate.py`、`ci/real_storage_it.py` 及其 GitHub-hosted scratch workflow。不要把合成 XML 自测、push 总绿或路径过滤 skipped 当成 Backend tests 实跑。回读工具不独立证明存储身份、报告生产者诚实或测试语义覆盖。

当前交付和合法 Java 执行入口见 `docs/delivery/architecture-day/F/FOLLOWUP.md`。旧 `REPORT.md`/verification 是 941612/1ce413 阶段的历史结果，不适用于本轮未提交代码。

## 986 职责增量（iOS 采用合同 v1.1）

在355策略之后保留原13类型/8职责和原4方向，仅追加986已落地的C Admin应用闭包13个精确类型、A GameSharedRunControl、E TopicRoutePublicationCompiler和support.GameplayActionProjection；同批再登记A1e的GameSessionConfiguration、Gca9的AdvancedActionJudgement及E1b的TopicRouteNarrativeProgress（各自冻结源SHA见owners.meaning），合计32类型/27职责/33允许方向。C的13类分别登记，既有Entry→Query、Command→Intent/Completion/Context等实际方向及应用→原hint/access服务明确列举，反向边和新scoped SCC仍由原闸阻断，不以包名通配授予依赖权。

C应用禁反调旧ApiPlayProgressController及三个runtime实现，保留其实际公共runtime接口调用；A共享控制/配置协作者、E编译器/叙事/投影叶子与G判定器禁依赖三个runtime接口或实现。E叙事→编译器为明确允许方向，反向仍红。这些非Application协作者不把旧facade作为回调入口。既有God→新owner调用与未登记类型仍在结构切口外，不自动推定正确。

复用原JAR/class-index/raw回读及冻结策略，无新parser/采集/CI入口；模块图原口径、3私有Mapper/2事实、2ec精确baseline均不改。新增Admin协议夹具同步维护original/Boot/classes/target字节与模块，不把Admin类型伪造到System。

这不证明Query纯读、具体方法无副作用、null语义或父事务/回执次序。特别ce49/IR05要求：null仅表示不适用共享局，已确认共享但缺RUN必须稳定失败，禁止回退旧个人ACTIVE/补局；由A/E/C实际业务测试另验，允许接口类型引用不等于该语义通过。缺真实编译图/类型/位置/身份仍UNKNOWN，未声称全图无环或全域owner完成。root独占双trusted ref，按审定新verifier完整SHA同时repin；本片不动workflow、不新增回归批次。

D最终7f7752a增量只追加TopicPublicationService、TopicPublicationContentGuard、TopicSelfHostActivityService三个System职责（35类型/30职责/36方向），原32类型/27职责/33方向不变。明确允许publication→content guard/self-host、content guard→既有D_CONTENT_RULES；三者禁回依赖ICmsTopicService/CmsTopicServiceImpl。保留旧门面→新应用、guard→公开路线服务、self-host→既有票务服务等实际调用，不把这些接口一概禁用，不因此授整表写权。仍复用同一可信JAR/raw/class-index回读，缺任一新类型或实际产物UNKNOWN；没有新scanner、baseline例外或CIjob。root统一实际编译及集中验收。

06a后续窄增量登记B支付facade/SDK/router/intent/order-lock端口、A转账DTO、D规模规则叶子、G claim/finalization及F日志上下文边界（55类型/44职责/51方向）；原7b3的35/30/36及嵌套32/27/33逐项保留。仍为技术类型依赖，不授业务表字段写权。新精确ID/类型及不可证明边界见 `docs/delivery/architecture-day/F/owner-followup/HANDOFF.md`。生产checker/producer/parser不变；仅测试夹具扩至真实Common/Framework/Quartz打包位置，禁止把这些类伪装成System以取得绿项。缺候选类/实际图仍UNKNOWN，只有原可信规则的确定越界/SCC硬红；不证明immutable语义、锁序、回调/代理循环、MDC脱敏或全图无环。

声明类型覆盖回读：原parse_output增加可选source_rows收集，五返回值及模块图口径不变；compiled-ports要求每个exact member有至少一条原解析器认可的source行。JDK-only依赖（如java.lang.Object）同样计入，不要求业务/项目外向边、不设leaf豁免；仅模块汇总/同模块其它类不足以覆盖该类型。缺行UNKNOWN，原确定违规仍BLOCKED；身份/原JAR/哈希/index/工具校验全部保留。合法空class/interface的OpenJDK21源码核对与协议对照已交F/source-row-verifier；真实JDK leaf形态仍待获准CI，不把协议fixture当实测。

E_TOPIC_QUERY增量（9e14a73e50c9afd5c8f38027c3ecd059967a51d3）只追加精确System TopicQueryService，原55类型/44职责/51方向保留，现56/45/51。保留它的五个Mapper及ISysCategoryService/IMerchantChapterApplicationService七依赖和原门面入向；禁反调ICmsTopicService/CmsTopicServiceImpl及列明publication/claim/finalization/runtime具体写owner，且未批准其它已登记owner出向边。不是整表写权、逐方法权限或传递纯读证明；raw缺行规则同样适用新Query。

新增边界登记棘轮仅覆盖 owners.json 的四个 scope_roots；固定公开 477696c8 为存量身份快照，不能由 candidate 扩大。新增 public Service/ServiceImpl/Controller/Application/Mapper 或确定角色注解的 primary type 须登记；原 DTO/私有 helper 不要求 owner，无法可靠定位角色保持 UNKNOWN。源码登记不替代编译 raw/index 回读或方法级写语义。

RegistrationRedemptionApplication 原端口保留、仅 Redemption→Query 共用谓词单向；其 Mapper 包引用只例外原 CoopOrderMapper 类型，例外不授任何表写权。CI/后置工具必须使用同一独立 reviewed verifier SHA。

该 Redemption owner 的 business.service 与 merchant access 引用同样只保留原精确类型；Seat$Receipt 为独立明确例外，其它 $ 类型不自动放行。方法级授权仍不由 class 图证明。

## 精确恢复语句写边（独立审阅）

`owners.statement_write_owners` 仅声明 B `WechatVerifiedPaymentFactMapper.xml#insertRecovery/#completeRecovery` 对 `wx_payment_callback_outbox` 的两个原事实恢复写边，绑定原 path#id/module/namespace/kind/table/现有 statement+include 指纹及审阅源SHA。独立可信版本批准后，仅精确命中的语句不再报 NEW_TABLE_WRITER；不授同 namespace 第三条语句或同表其它 namespace 写权，不改变原 scope/cache/业务fact合同检查。限定writer的额外确定写边、原语句指纹漂移在比较base equality之前阻断；candidate自改metadata仍POLICY_CHANGE。原2ec baseline/locking proposal不变。未知动态效果仍须人审，不证明方法调用、事务或经济语义；root持有双CI ref最终接线。
