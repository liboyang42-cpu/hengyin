# F：确定 literal 写操作漏检修复

结论：复用现有 `ci/sql_splitter.py` 和 source CLI，修掉 MySQL DML 修饰符、普通注释、XML command tag 与实际 DML 不同造成的 owner 漏检；不新增扫描器/业务授权/豁免。独立分支 `codex/architecture-day-f-literal-write-scope`，parent `bd33ed0be7fafaf89daaec2eb772c15b034d3ec0`，承接0e00缺行UNKNOWN及E_TOPIC_QUERY。实际执行文件只 gate.py/test_gate.py，此外仅本目录证据；无workflow/Java/SQL/业务修改。

`before-source-cli.jsonl` 为旧bd33真实source CLI的6次隔离Git调用：前5项外来writer返回0且无findings；第6项update标签装insert被误判UNSCOPED_WRITE，未识别business writer。新测试在同一真实CLI及可信policy冻结机制上断言；不是正则源码检查，也没有替换verdict。只替换公共基线/CI event/独立可信checkout的隔离fixture，不运行数据库。

修复：shared scanner剥普通注释但保留引号段，先替换MyBatis值占位；仅固定动态prefix作为证据。literal leading operation不再取决于XML标签；识别UPDATE LOW_PRIORITY/IGNORE、DELETE LOW_PRIORITY/QUICK/IGNORE、INSERT优先级/IGNORE、REPLACE优先级以及可省略INTO的确定单表目标。已存在select-tag DML同样参与技术writer历史边观察。保留INSERT与REPLACE不同能力，以及同条已存在边不重复审、删除重引算新增。未更改inventory/sql_shape/locking fingerprint或baseline格式。

新增6个test方法（包含45个同owner/外来owner/另一已批准owner的modifier/tag/data变体子例），另覆盖跨namespace include、历史select-tag writer、quoted data不伪造写边、动态/多表/执行注释UNKNOWN、动态字段之前确定target仍红、错标签DELETE无scope仍红。新6个方法回放旧bd33：34个子例断言失败、0error/skip（old-verifier-negative-control.txt），未知对照未被误红；首次临时拷贝路径不完整的运行不计证据，纠正原ci/architecture布局后才记录。原91个gate test方法AST逐个未变；其它原producer/locking控制文件不变。完整运行命令 `python3 -m unittest discover -s ci/architecture -p 'test_*.py'`：196/196、0skip、15.904s，实际输出见python-tests.txt。Python3.9 grammar通过；公共2ec inventory重新只读生成，与冻结baseline逐byte cmp相同。producer、owners、commands、2ecbaseline、lockingproposal逐byte等于bd33；六可信文件SHA见trusted-files.sha256。

保留56类型/45职责/51方向、3private Mapper和2whole-table facts；不是回退170/e4旧35型策略。普通已合主干历史不会因本修复反复要求合同审批。只对新增确定literal写边执行原NEW_TABLE_WRITER/UNDECLARED_WRITE_BINDING/NEW_BUSINESS_OWNER_WRITE；原scope/cache/依赖规则全保留。

边界：这是确定leading写目标证据，不是完整SQL语义。动态目标、CTE、多表/别名、schema限定、版本执行注释包裹整句、注解SQL和反射等仍需UNKNOWN/人审。若固定目标已确定，其余动态effects仍UNKNOWN并可同时有确定owner阻断。不能由空findings声称安全、所有writer批准、调用必经Service、传递纯读、数据库正确或无环。未更改金额/事务/权限/持久协议，无DDL/迁移。回退仅回退检查器，不能把已拒新写变成已批准规则；root唯一审阅后双ref接线。

Java/javac/jdeps、真实JAR/leaf、MySQL/Redis/模型/WeChat/CI均NOT_RUN。前置工具交付另见3327（固定0e00，55型阶段）及c135（固定bd33，56型阶段），不可将工具分支旧树覆盖当前policy；新verifier若采用，后置工具须精确绑定新SHA，不能候选自批准。尚需root获准实际compiled正/反控、实际leaf原始行和独立artifact/hash读回。
