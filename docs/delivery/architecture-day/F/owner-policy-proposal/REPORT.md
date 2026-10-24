# F / WP01：owner-policy 最小执行合同提案（doc-only）

结论：可在现有 gate 上先加**明确批准 owner 的新增写边**和 policy 结构/冻结校验，不需要 ArchUnit、新 scanner 或新 CI job。当前 namespace owner、observed writers 和列 hints 均不能直接升级为 business owner。混合表字段权、Java 实际调用是否经过公开命令及动态 SQL 完整效果仍需 root/A 的批准合同与行为证据；本候选没有实现新规则或修改 baseline。

## 身份和实际只读观察

- 公共观察基线：`0c0d61266b2ae54d77c5ad9cf8b3c8fdb86d4b16`；分支 `codex/architecture-day-f-owner-policy-proposal`，独立 worktree；只写本目录。
- transport `663c2c634e4cff53c876630fe4e166d3fcd29d20`、verifier `155c88ef52f7f75023de464c8a886c29d017cdba`、producer `685743902d32252adb2cb320a5f5e59b4af2f866` 不修改。H 的 ec65 复审/组合用例由 root 回读集成；本任务不重复改 transport 或代签其测试结果。E 接线仍归 E。
- 依据完整规范 [第8节 A01–A15](../../COORDINATOR/governance-scope.md)，并结合5.1字段组写权、5.2公开边界及WP01验收；逐条差距见 [A01-A15.md](A01-A15.md)。0c源码观察不是 source gate 执行。另有50f完整身份的CI/JAR/jdeps证据经root独立核验后报告，F仅引用、未独立下载回读，详见交接页；其静态模块图无环不能代替业务owner、运行时图或当前6ec候选验证。
- 实际执行的只读查询是**原有** `gate.inventory(source)`、`literal_write_target` 和 Git blob 对照，不构造 Mapper→service→entry 全量图，避免与 A 的事实盘点重复。该 source 的 XML tag 语句：select1663/insert398/update731/delete198；原 hints 覆盖389表；字面目标样本368表/247技术 namespace，其中19表多个 namespace。统计只覆盖现有 gate 支持的 XML，排除“全生产写已完整覆盖”的解释。
- `column_hints` 仍有 `typeHandler` 伪列样本（CmsRegistration/OmsOrder 的 PII backfill、UserInvoice.updateTitle），见 [evidence.json](evidence.json)。动态标签理由528次、text substitution1次、unsupported shape95次，是理由次数，不能相加当未知语句数。这证明不能用 hints 的补集/空集判定“未写字段/无副作用”。

## 当前实现与信任边界

`inventory` 的 owner 固定由 `module + ':' + mapper.namespace` 拼出；`static_findings` 从**可信比较 base 的字面 DML 目标**构造 known_owners。新/变更 XML 的已有表出现新 namespace 判 `NEW_TABLE_WRITER`。新表、复杂目标、同 namespace 扩列、Java 新调用者不获得完整业务写权检查。

`owners.json` 是解释性文字；`commands.json` 是4条高风险链的路径/恢复源/测试参考。`check_source` 不把两者加载成机器业务授权。现有冻结文件仅 gate.py、baseline.json、locking-select-baseline.proposal.json；候选改 owners/commands 目前不改变自动 verdict。这不是一个已经可用的业务 policy，也不应误称已冻结授权。

可直接复用 `tree/blob/xml`、现有 `ci/sql_splitter.py`、`inventory/sql_shape` 的 path#id、namespace 和传递 include 指纹、`literal_write_target`、`static_findings`、可信 `comparison_base/manual_base` 与 `check_source` 的受信任 checkout 字节核对。默认主干实际 merge-base 修复保留；不重回 candidate^1，不把2ec到未来所有candidate的历史重新列成新项。

## 最小数据合同（尚无真实 owner 授权条目）

沿用两份现有清单，不另建审批 JSON 或重复 action ledger。新增字段仅在 root 审定后由独立 verifier revision 启用；现在一个真实表/字段/business owner 绑定也不填。

| 位置/字段 | 最小值与机械校验 | 权威边界 |
| --- | --- | --- |
| owners 的 `business_policy.scope` | 明确启用的 fact IDs/规则版本；初期 `PARTIAL`，输出覆盖范围，未覆盖写仍列 UNKNOWN | 覆盖范围不是授权通配符；不能把未知改成“允许”，最终 A01/A02 目标不缩小 |
| `facts[].id/table/columns/business_owner` | id唯一；混合表 columns 显式非空、无 `*`；同字段重叠不同 owner 是 policy歧义红；未映射列 UNKNOWN | table/column事实来自 A；owner 必须 root/领域负责人确认，不能从表名前缀/现存writer推断 |
| `facts[].coverage` | `field_group` 或经审定的 `whole_table`，不默认整表；whole_table 必须确认覆盖全部写事实 | 未完整确认的混合表不能 whole_table；DELETE/REPLACE 等整行/复合效果须单独声明，columns=[]不是零效果 |
| `writer_bindings[]` | 精确 namespace#id→command_id；必要时允许 root 明确确认的**纯域** namespace→business_owner；不得 package glob | mixed Mapper 仅精确 statement/command，不能 namespace 盖章；pure 是批准事实，绝非 observed namespace 的别名 |
| `public_apis[]` | 精确 command_id/entry FQCN#method、公开/内部角色；关联已有 caller 证据 | 路径存在不证明 Java 调用实际经过它；新 caller 可绕过的盲区不伪装成绿 |
| commands 的 `business_owner/writes[]` | 复用现有 id/entry/symbol；每个写绑定 statement_key、operation、fact_id、证据 source SHA+statement/include 指纹、允许公开 command | source fingerprint 是证据新鲜度，不是授权；root须审定 owner 与 command/事实关系 |
| commands 的业务合同 | 明确 scope/权限、身份/指纹、前置条件、version/CAS、原子事实/回执、读回/恢复、test IDs；不适用项写原因 | 这些是 A05 要求的**命令定义一次**，不是每次普通 Java/文档改动提交七项审批；元数据存在不证明行为 |
| `evidence_source_sha/review_ref/test_ids` | 公共完整SHA、普通PR review/领域决定引用、现有测试的精确类/方法ID；指向现有ledger/registry | candidate自填“approved:true”无效；不复制测试报告或发明新人工审批系统 |
| 既有例外 | 本提案新增例外数0；不加 whole-package allow、不改原2ec baseline | 如今后确需既存债例外，只能独立审定精确ID/owner/原因/退出条件，不能候选自生成；本任务没有此授权 |

`evidence_source_sha` 仅记录批准证据来自哪个不可变公共提交，**不要求每次 candidate SHA 等于它**。新鲜度核对的是相关 statement/传递 include（及声明涉及的schema/入口证据）是否仍匹配；仅文档/测试或无关Java变化不能使旧绑定失效。当前 fingerprint 对SQL格式敏感，格式变化可产生定向UNKNOWN，但不能直接报新增业务越权；首片不自造语义归一化器。root需看清第二片精确绑定对所选危险SQL的维护成本，不把它扩大成全仓逐次审批。

A 的交接应分开：**事实**（source SHA、表/列/操作、namespace#id、可解析程度、service/entry可能调用者及置信度、原始指纹/未知理由）与**决定**（哪些fact/字段组由谁唯一写入、允许哪些公开command、整行删除权）。F/root不能把第一份文件读进来就默认第二份成立。字段 hints 不能充当已批准完整字段集。

## 可实施切口和增量判据

**第一片：结构+冻结+已批准的确定新增边。** 只扩现有 `static_findings`/policy loader，不碰 scanner。root 先选少量已经确认的纯 writer 和整表 fact：用现有字面 target 识别 table/operation，用明确批准的 writer→business_owner 和 fact→business_owner 判断新增矛盾。两端均确定且新增写能力不经精确批准的公开 command，即 `NEW_BUSINESS_OWNER_WRITE` 红；同域声明匹配为正常。root 未批准任何绑定前不启用该判定，不从当前368表自动造权限。

`writer_bindings` 如选择 exact statement，新增未登记 statement 在**明确启用且要求声明**的 fact 范围内可报 `UNDECLARED_WRITE_BINDING`（登记缺失，不能谎称已经确定跨域）；纯 writer 的新 statement 可继承其明确批准的业务身份。范围外/target不确定则 UNKNOWN，而非默认通过为安全；仍保留既有技术 writer/scope/cache 规则。一个批准的业务 command 不会默默抹掉已有 `NEW_TABLE_WRITER` 红，合法技术迁移由 root 单独裁定，不在这片加豁免。

增量只比较可信 CI base→candidate。完整 identity 至少含 statement_key、writer/command、table/fact、operation；新 statement 不能借旧 namespace 的债“祖传合法”。新/修改语句的 owner写边减去base同identity的边，只对新确定矛盾红；未改历史不反复要求审。确能确认同一写能力且只有格式/谓词文字调整时，不把旧 owner债改称新边；scope变更仍由既有规则/审阅测试处理，不能借此自动证明授权没变。字段效果或操作无法可靠确认时不可用 hints 比较出“未扩大”，必须 UNKNOWN。

**第二片：混合表精确绑定。** 先利用 root 审定的 statement→fact/字段组绑定及原 fingerprint。未改且绑定仍新鲜的精确 statement 可对照已批准声明；新增/修改 body 或传递 include 使绑定过期时，列 `UNKNOWN_WRITE_EFFECT`/`STALE_COMMAND_BINDING`，要求普通PR审阅和代表性渲染/实际SQL，不能拿旧字段声明、正则列 hints 自动放行。若启用范围要求声明而无**匹配当前写能力指纹**的登记，则因登记缺失红；不是因为动态SQL本身“必定违规”。声明只证明已登记审阅，UNKNOWN绝不升级为效果已证明。没有额外可靠字段分析能力时，不能声称自动拦住所有同Mapper扩列；不临时造 SQL parser。

**Java caller 绕过公开 command** 不在这两片静态能力内：已有 XML 未改也可能新controller/service直接调用它。A 的 service/entry事实用于定向PR review/测试；现有源码import helper与标准jdeps只能补影响线索，不能证明方法级调用链/授权。下一授权需明确这种 UNKNOWN 的 review/test责任，而不是声称一张registry已守住全部公开边界。

动态 include 的叶片变化复用既有传递 fingerprint；动态refid未解析沿用文件级 review item。注解 SQL/Provider、JDBC、存储过程、插件、反射、运行期多表/别名写不在现有 inventory 的完整能力内；新增相关Java只可列定向 UNKNOWN并登记审阅，不把任意Java变化变成违规，也不把没扫描到的写记为不存在。root若要机器保证所有新增未知写已登记，须另授可靠入口枚举/现有工具接线范围；本片不能伪称完成A02全覆盖。

## 冻结与基线防洗白

1. 原 public2ec 的 exact inventory/SQL fingerprint 和 locking proposal 逐字保留；它们是存量观察，**不是业务许可或已批准债豁免**。不从0c/新candidate重新freeze。
2. 新可执行 business policy/commands 从**受信任 verifier checkout**读取，与当前 gate 同源；将实际参与判定的 owners.json、commands.json 纳入 check_source 现有 protected files，candidate字节不匹配即 POLICY_CHANGE。业务owner变更、删除fact/command、拓宽columns/bridge、清空scope都不能在同次candidate自批准。
3. root先正常review清单和正反自测，单独冻结完整 verifier SHA，再在唯一CI接线位置 repin；保留现有成本/条件/主干merge-base。无需新job/ArchUnit或常驻runner。candidate生成的A事实可作证据，不能作自动生成授权/自扩baseline的入口。
4. baseline移除旧债后重引、改namespace/id、换include、改owner或扩大列/公开命令范围，不能继承旧精确条目。增量历史已合入可信base不重复列，但“被合入过”绝不是业务授权来源。对于缺乏事实/不完整图，只保留 UNKNOWN，不因缺图声称无环/无跨域。

UNKNOWN 在当前gate只输出 review_items，不能证明人审完成。未来可在启用的高风险命令登记精确 UNKNOWN 的正常PR审阅/渲染/测试引用；机器验证新鲜度/缺失，root仍审实际内容，不用candidate布尔值代签。否则 A02“未知新增写未登记审阅”仍是**自动化缺口**，不能以PASS_WITH_REVIEW_ITEMS宣布A02通过。

## 负控验收设计（未编写/未执行）

授权实现后扩 `test_gate.py` 的现有临时Git fixture，正常/违规配对跑真实 `source` CLI；不新增scanner或真实业务数据。

| 控制 | 应有结果 |
| --- | --- |
| 已确认同域 writer→fact；文档/普通Java/测试修改；已合主干历史 | 无新增owner finding；历史不重复；不额外索要Java报告 |
| 新 statement/namespace，明确归A域，却新增写明确归B域fact且无受控command | 确定越界红；不能仅靠技术同包过关 |
| 混合Mapper只批准某statement；另一个新statement借同namespace写另一字段组 | 缺精确绑定红（适用已启用fact）；不自动继承业务owner |
| root已批准字段组不重叠；相同table/column被赋两个不同owner | 前者结构正常；后者policy歧义红，不随机取一个 |
| 仅某public command允许写指定fact；伪造同名方法/改symbol或binding owner | 不匹配/未声明红；字符串“viaCommand”不能证明实际调用经过，仍需行为测试 |
| body/传递include变了但沿用旧绑定指纹；A事实文件自动刷新说已批准 | UNKNOWN/stale登记缺失红（启用范围）；事实刷新不成为授权；无新增baseline |
| 新动态/注解/Provider写，未可靠枚举或字段未确定 | 明示UNKNOWN覆盖缺口；已登记UNKNOWN不声称安全；没有可靠枚举时不伪称CI已拦住所有漏登 |
| 删除已修旧债后重引；改statement id/namespace借旧豁免 | 按新identity判；明确新违规红，不能放大旧baseline |
| candidate改owners/commands/清空scope/加通配bridge/修改baseline | 可信verifier执行POLICY_CHANGE/BASELINE_LAUNDERING红，自测不能用candidate自己版本 |
| 本分支第一提交越界，第二提交仅docs；manual candidate^1伪base | 继续按真实默认主干merge-base红；已合主干历史正常不重列 |
| 调用者绕开command但XML不变 | 目前静态UNKNOWN，需控制测试真实打穿应用边界；禁止把“本checker没报”写成正控通过 |
| 缺必需报告/skip/错SHA或attempt；jdeps missing/缺图 | 原回读工具红或观察UNKNOWN；不接受别候选绿、不画空图无环 |

最后两行不是新增 owner checker 可独立证明的性质，必须复用现有报告回读/业务fixture。上表是验收合同，**本轮无新checker、自测或业务实现**，不能写成负控已经红过。

## 实际验证、交接与回退

本轮只读 inventory/Git、规范ID/文件指纹、doc-only scope/JSON/链接及秘密扫描，输出见 verification.json。未执行 `gate source`，未伪造CI事件；未跑架构自测、Java/MySQL/Redis/jdeps、模型或CI，不dispatch。A仍拥有全生产写事实清单，root仍决定业务 owner 和后续精确gate范围。

root需要确认的最少决定：首批fact/字段组及唯一owner；哪些writer真为纯域、哪些只能精确statement绑定；允许公开command及整行删除权；每条 UNKNOWN 的普通PR审阅/测试责任。决定前本候选不得变成active policy。doc-only可独立revert，无DB/账本/回执/迁移/工作流影响，原baseline和冻结候选不动。

后续交接补页见 [HANDOFF.md](HANDOFF.md)：沿用A已有格式，明确事实identity/确定程度、root业务决定与既有gate的联结；追加协调方2026-10-24的50f终态、完整source/JAR身份与九条静态边；保留E4/testCompile/transport49和在跑6ec的独立验证边界。619skip及跨phase原始计数不改写为全suite零跳过。
