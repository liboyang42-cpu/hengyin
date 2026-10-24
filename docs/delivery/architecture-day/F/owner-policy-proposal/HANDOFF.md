# A事实 → root决定 → F增量规则：交接约束（尚未启用）

结论：直接引用 A 正在制作的既有 inventory，不要求 A 换格式、重跑或另建批准文件。以下只是字段含义与联结判据；本页没有新的事实扫描、business owner 指派、policy 或豁免。

## 1 事实行怎么联结

现有 CSV/JSON/表格能表达这些信息即可；缺项保留 UNKNOWN，由 root 选定首片范围时补证，不能由 F 自动猜值。

| 事实信息 | 对接判据 | 不能据此推断 |
| --- | --- | --- |
| 观察 source 完整SHA、文件路径/blob | 保留 A 实际观察的公共 source；新candidate相关blob未变可沿用，不要求全仓SHA相同 | 新candidate全链已验收或部署身份相同 |
| `statement_key=path#id` + `namespace#id` + module | 前者接现有 gate inventory，后者接运行期 MyBatis identity；两者均保存。重名/一对多映射须显式消歧 | namespace 是业务域；换路径/id可继承旧例外 |
| XML tag 与实际写操作 | tag只是声明；实际UPDATE/DELETE/INSERT或REPLACE/UPSERT/多语句分别标明，不能把tag当完整operation证明 | REPLACE只有插入效果；select/affectData一定只读 |
| table/column事实及确定程度 | 区分已知字面目标、列hints、未知集合、人工审定完整字段集；DELETE/REPLACE另记整行/复合影响 | columns=[]代表无写；typeHandler是业务列；hints补集代表未写 |
| body/传递include指纹、未解析理由 | 对接 `sql_shape` 原指纹；未解析/循环/dynamic-refid保留原理由，文件级片段变化仍关联审阅 | 展开一次或字段声明完整就证明所有参数分支 |
| mapper→service→entry证据 | 保留实际符号/引用位置及证据层级；多个caller不合并为一个“批准入口” | import引用、方法名或同包证明运行调用必经公开command |
| 渲染/受控捕获及参数覆盖 | 引用获准控制场景的脱敏SQL/测试ID；标明覆盖了哪些分支/参数 | 一个代表性SQL样本证明全部动态SQL、权限/原子性或资金安全 |

建议用现有字段补充 `UNKNOWN` 或说明，**不是新schema/审批系统要求**。A全生产事实清单仍归A；F本轮仅复用已有XML样本，不替它宣称注解/JDBC/Provider/反射/存储过程全部覆盖。

## 2 root需要作出的决定

每个首片对象只需正常PR/领域review给出以下决定，并引用上面的事实行与源码：

- 哪个业务事实/字段组的唯一写owner；混合表不得默认整表归属。
- 哪个namespace确为批准的纯业务writer；其余按精确statement/command绑定，不从现存技术writer推断。
- 哪个公开command允许改变哪个fact、执行什么操作；整行删除/REPLACE须单独说明。
- 哪些scope/身份/版本/原子回执/恢复合同和测试ID适用；N/A给业务原因。复用已有command/ledger定义，不每次普通代码变更填七项JSON。

现有多writer表不是“多个批准owner”，单writer表也不自动成为纯业务域。主干曾合入的代码不自动授予写权。A的source观察、root的业务决定和真实测试回执是三个不同证据层，不能相互代签。

## 3 第一个实现切口的验收输入

| 条件 | 到位后的最小动作 | 未到位时 |
| --- | --- | --- |
| root选定fact范围并批准两端owner | F可在既有literal target + static_findings上判新增确定矛盾 | 保持UNKNOWN；不填默认owner，不生成current-candidate基线 |
| 精确statement/纯writer绑定、公开command定义 | 机械检查缺登记/歧义；新statement不继承旧债身份 | 无法可靠枚举或归属的写不能声称已自动守住 |
| 混合表字段效果仍只有hints | 只输出定向UNKNOWN/过期证据，要求原PR审阅/受控渲染测试 | 不临时造SQL parser，不用hints做deny/allow完整语义证明 |
| 新policy正常review并冻结trusted verifier | owners/commands等实际执行数据加入原protected-file核对，再由root唯一接线repin | candidate自填approved或刷新事实不成为批准；原baseline不变 |
| 正常/违规临时Git fixture通过 | 运行真实source CLI，证明确认越界红、普通改动/历史不重列 | 当前文档负控设计不是已经执行的自测 |
| source/run/attempt原报告齐全 | 原assert_it_ran/identity工具核对对应候选实际执行、0skip/失败 | testCompile、其他suite绿或缺图均不代替所需验证 |

首片仅新增明确业务owner写边/声明校验，不悄悄撤掉原NEW_TABLE_WRITER、scope或locking-cache规则；出现合法技术迁移冲突由root单独决定，F不加宽白名单。源级owner规则不证明Java方法级路径、完整SCC、事务或恢复语义。

## 4 2026-10-24协调方新证据的使用边界

协调方报告 source短名 `a0f` / run `40043910446`：E4真实MySQL/Redis全PASS、0skip，独立CLEAN/Redis DBSIZE0；F663/H组合源码同次testCompile通过，尚未JUnit执行。这里原样保留为**root报告的状态**，未独立下载完整source身份/XML/清理artifact，不把短名当正式candidate身份。

### 50f首份compiled evidence（取代此前“末段运行中”状态）

root已独立核验并报告以下证据；**F引用该报告，未独立下载回读CI artifacts**：

- 完整source：`50f3c4d3c67383b8ea2e3e230d7f16f2e73f3eb6`；[fullCI 40043150702](https://github.com/liboyang42-cpu/hengyin/actions/runs/40043150702)最终SUCCESS。
- 下载的testedBootJar SHA256：`e1055a6d6220462545ac6f706489fad8192ca95e2f301008e5e69fc6e2882470`，与build identity/source/tree一致；本页未收到独立tree值或run attempt，不补造这些值。
- jdeps实际JDK `21.0.12.1`、exit0；11份input/stdout/index/invocation等产物hash全匹配，`missing=[]`、`static_graph_complete=true`、`graph_complete=false`、verdict `OBSERVATION_ONLY`。未批准baseline/SCC。
- source gate：0block/87review。原始Surefire XML 1834份hash全核验，跨phase原始合计14416tests、0failure、0error、619skip；不是唯一business case数，不代表所需每个suite均0skip，不改变原漏跑/跳过判据。

九条已解析静态模块边（左列一行一个来源，右列列出各独立target）：

| 来源module | target modules | 边数 |
| --- | --- | --- |
| chengyinhub-admin | chengyinhub-common、chengyinhub-framework、chengyinhub-quartz、chengyinhub-system | 4 |
| chengyinhub-framework | chengyinhub-common、chengyinhub-system | 2 |
| chengyinhub-generator | chengyinhub-common | 1 |
| chengyinhub-quartz | chengyinhub-common | 1 |
| chengyinhub-system | chengyinhub-common | 1 |

root对这九条边计算出六个singleton SCC（admin/common/framework/generator/quartz/system各自一个）。只能说**此候选的该静态module图无环**；bean、XML、reflection、运行时与System内部business domain依赖仍UNKNOWN。该图不授权任何table/field/command owner，不补足A01/A02业务边界，也不是已审定的基线或新增SCC阻断规则。F不把它freeze成认可baseline，现有精确baseline保持不变。

### 尚在运行/尚缺证的边界

root另报告新source短名 `6ec` / [fullCI 40044336447](https://github.com/liboyang42-cpu/hengyin/actions/runs/40044336447)仍在unit阶段，且不含短名 `01` 新修。本页未获这两者完整SHA，不当作正式candidate identity；6ec不能继承50f终态。F663/H组合的testCompile事实仍不能迁移为transport49项JUnit已执行，也不能从上述跨phase总数推断所需49项已运行。

本页不触发CI、不改变transport/E接线或采集产物，不覆盖之前0c XML观察。后续真实结果由root按完整source/run/attempt绑定，F再据获授权的精确规则实施。没有新policy、业务owner绑定或baseline条目。
