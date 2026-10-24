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

协调方同时报告 `50f` 全CI启动已过，H8/JAR/jdeps末段仍在运行。此为当时进度，不是终态。transport49项实际JUnit执行仍未证明；E4绿不能迁移为transport绿，testCompile不能迁移为测试执行。当前root/sourcegate/compiled jdeps在本任务仍UNKNOWN，等待root实际终态回读。

本页不触发CI、不改变transport/E接线或采集产物，不覆盖之前0c XML观察。后续真实结果由root按完整source/run/attempt绑定，F再据获授权的精确规则施工。
