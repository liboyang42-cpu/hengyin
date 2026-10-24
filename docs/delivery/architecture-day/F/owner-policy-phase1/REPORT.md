# F / WP01：两项 AI whole-table 写权的增量门禁

结论：实现root已批准的两项确定写权；复用原inventory/literal_write_target/static_findings和冻结机制，不新增scanner、依赖、框架或job。新增21项专属source CLI临时Git自测、连同既有94项共115项通过；CI成本合同通过。**候选尚未接入真实CI**：现有workflow仍固定155，必须root独立审定新trusted verifier完整SHA并唯一repin后启用。本片不是完整A02、WP01或WP13验收。

## 身份、范围和失败机制

公共仓库仅 `liboyang42-cpu/hengyin`。本片parent/开工HEAD为 `50f3c4d3c67383b8ea2e3e230d7f16f2e73f3eb6`，独立分支 `codex/architecture-day-f-owner-policy`，干净树开工；未切换或修改其他工作树。新增观察 `6ecaea01e0e1a3f3182189615bc00cf9f791fd47` 仅用于复核两项来源。相对原public2ec，本片不重做已修项、不复制私有仓库历史。155/685/663原提交保持冻结。

只修改 `ci/architecture/gate.py`、`test_gate.py`、`owners.json`、`commands.json`、同目录README及本交付目录。未修改生产writer/SQL、root workflow/registry/harness、数据库或依赖。

失败机制：旧 `NEW_TABLE_WRITER` 仅由可信比较base中的已知字面表目标推得技术writer；base无该表时，外来namespace首次写ai_quota_reservation会只列合同review，不能体现root已明确批准的唯一业务writer。先运行新增负控，实际旧CLI返回0、findings=[]、PASS_WITH_REVIEW_ITEMS，测试失败；再补窄policy根因，负控返回1/UNDECLARED_WRITE_BINDING。不是通过改业务SQL迎合规则。

## root技术写权决定与源码事实分开

| fact（均whole_table） | 唯一business owner | 唯一纯writer（均system模块） | 原公开应用合同 |
| --- | --- | --- | --- |
| AI_QUOTA_RESERVATION / ai_quota_reservation | AI_QUOTA_RECOVERY | com.chengyinhub.business.mapper.AiQuotaReservationMapper | AiQuotaRecoveryService.prepare/reserved/stage/retain/requireRefund/runDue |
| PHOTO_CHECK_INTENT / photo_check_intent | AI_PHOTO_INTENT | com.chengyinhub.business.mapper.PhotoCheckIntentMapper | PhotoCheckIntentService.prepare/executeOnce/read/markApplied（read有两个重载） |

此为root批准的技术写权，不改变产品/调用者授权、有效稿计费、额度退款或金额政策。play可经PhotoCheckIntentService请求APPLIED，不能据此直接Mapper写。public_methods只登记原应用合同，**不证明Java实际调用必经Service**；本片不建立方法调用图、字段级policy或全仓逐变更审批。

A事实来源为公开 `748ad71bd1de141396fd8e2d9685c35706fd5b84`，原观察source `0c0d61266b2ae54d77c5ad9cf8b3c8fdb86d4b16`。直接读取A原mapper-writes/source-files与SHA256SUMS，不要求A换格式/重跑，不执行其collector或新建parser。13条精确path#id对应7条quota、6条Photo写；逐条核对A原sha256与原source的gate指纹，再与50f/6ec原blob和传递指纹核对。原A记录authorization=NOT_DETERMINED仍保留，批准只来自root决定；其它余额/报名/回执/现金/DDL等未获授权，未导入policy。见 [evidence.json](evidence.json)。

## 实现合同与增量判据

- `owners.business_policy`：schema/coverage=PARTIAL、非空scope、唯一fact id/table/whole_table/business_owner，以及精确module+namespace→owner/command的pure_writers。禁止通配、混合字段、空scope、重复表/fact、歧义writer。`commands.business_commands`只登记两个原command、owner、writes fact IDs与public_methods；原四条参考链不变。
- loader只读trusted `HERE`，校验fact/command/writer关联和每fact唯一纯writer，缺失/无效即BUSINESS_POLICY_INVALID，不静默降成禁用或默认owner。整个owners/commands加入原protected-file字节比较，连同gate、baseline、locking proposal共五文件冻结。candidate自改owner/删fact/清scope/伪command必须POLICY_CHANGE，不能自批准；新trusted revision由root正常review、单独冻结完整SHA。
- source入口继续校验完整candidate/clean tree/固定public2ec/可信CI比较base；PR和manual仍用实际唯一merge-base，不用candidate父提交。仅新/变更statement参与规则，身份为path#id + module:namespace + 可确定操作 + 字面target。新增确定写已启用fact，未声明纯writer→UNDECLARED_WRITE_BINDING；另一已批准business owner→NEW_BUSINESS_OWNER_WRITE。base无该表仍检查。
- 同identity同已知整表写边的存量债不在谓词/格式普通修改时重新列为新越界；已有债不是授权。删除后重引、改id/namespace/module、改变操作或目标构成新边。REPLACE与INSERT分开。原NEW_TABLE_WRITER、UNSCOPED_WRITE、LOCKING_SELECT_CACHE不撤除、不桥接豁免；既有scope/合同审阅照常。
- 只用原literal_write_target，不用column_hints作字段证明。固定leading target可确定、字段动态时可同时越界红和UNKNOWN_BUSINESS_WRITE_EFFECT；目标`${table}`、动态/未解析include、复杂effects仍输出定向UNKNOWN。已知范围外写为BUSINESS_WRITE_OUTSIDE_POLICY；没有新owner阻断不表示安全，输出明确PARTIAL和not_proven。
- include片段变化沿用原传递指纹；跨namespace静态include把目标改成已启用表时按新目标红。动态refid仍由原文件级review承接，不声称已解析所有分支。注解SQL/Provider/JDBC、存储过程、插件、反射、别名/JOIN/多表/多语句完整效果、同Mapper新Java caller与方法级Service路径均未完整证明，不能据此宣布A02全覆盖。

## 实际执行与未验证

Python 3.12.14，命令与输出见 [checks.txt](checks.txt)。新增21项测试运行真实 `gate.main` source CLI/parser/checker，使用临时Git和隔离public-base/事件/trusted路径；不mock verdict、不使用业务库/网络，不能冒充真实GitHub事件执行。manual负控仅将网络transport替换为本地bare origin，真实执行fetch/ref/merge-base校验。覆盖同owner、外来/另一批准owner、同namespace换module、正常文档/Java、旧债不重列/移除后重引/改id或操作、include重写、UNKNOWN、policy/command/baseline伪造、两提交洗白等。

`115/115 OK`包含原producer的合成fixture测试，不是实际jdeps采集结果；本轮未跑Java/HTTP fixture/MySQL/Redis/jdeps/模型、未dispatch CI，也未执行真实候选的可信source CI。secret-file gate/gitleaks/diff/冻结范围检查记录于 [verification.json](verification.json)。

root另外报告6ec/fullCI40044336447 SUCCESS，1840原XML hash全匹配，F49（含H组合/三项兼容）、E11/C32同源0failure/error/skip；F引用，未独立下载回读。新JAR/jdeps仍待root核验。a0f E4真库结果另源，不能合称此候选全部WP13或真实provider已验收；50f的九边静态无环观察也不变成approved baseline/SCC。

## 接线、迁移与回退限制

root须先review四个执行/数据文件及自测，在独立trusted revision固定完整SHA；将相同文件delta集成candidate，再由root仅修改既有ci.yml verifier ref指向新SHA。旧155对gate变更应拒绝，不能为本候选开豁免、自行用candidate代码批准自身或跳过source gate。本片不改workflow、不增加job/并行/审批平台。候选身份以最终Git提交为准，文档不自造CI成功回执。

原public2ec baseline和locking proposal bytes未改，新增豁免0。无DDL/数据/依赖/生产行为迁移；回退须root协调trusted ref与五个冻结文件同版本，不能只回退owners或清空scope，也不能把关闭新增门禁称为业务已安全。后续字段组与其它owner须root另批准；本片不自动吸收A其它五项建议或全观察清单。
