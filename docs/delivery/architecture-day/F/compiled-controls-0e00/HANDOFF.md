# 按root巡查要求固定0e00的独立工具版本

本分支 `codex/architecture-day-f-controls-0e00`，parent c135；**只改工具固定引用及匹配控制、交付指纹/补丁**。不改任何producer/policy/业务源码/workflow，不把170旧35型快照带回root。

本版本唯一verifier `0e00ce639dea8e7f266dd326878532f45c59a032`（55类型/44职责/51方向、缺行UNKNOWN；原164+3协议控制和19 locking，186通过）。与root上一条E_TOPIC_QUERY要求对应的完整56型终版 `bd33ed0be7fafaf89daaec2eb772c15b034d3ec0` 及固定它的c135候选**同时保留，不重写**。0e00版本不声称覆盖E_TOPIC_QUERY；root若最终接入56型应采用bd33/c135，不以本55型工具替换56型policy。两个版本均是单一完整SHA硬绑定，无CLI浮动ref/候选自批准。

六可信文件SHA见0e00-trusted-files.sha256；另附bd33对照。两版仅owners.json hash不同，producer/gate/commands/2ecbaseline/lockingproposal完全相同。可在对应干净可信checkout `sha256sum -c <manifest>` 独立核对；这是交付指纹，不是新批准/例外清单。

沿c135提供的命令使用 `--leaf-only`：仅root获准CI，实际空class/interface、JDK-only Object行、无项目外向边；实际source/run/JDK/JAR/class/raw hash均独立记录。完整三项负控仍要求原主JAR/identity/11项证据先通过；同模块有别类但声明类型全raw缺失、未知输入/错版本/脏verifier全部UNKNOWN或拒绝，不能伪绿。派生控制JAR不是tested主JAR，不引生产Common→Framework依赖。

实际8项Python/0skip通过（python-tests.txt），原5工具用例保留；0e00原186、bd33原190是分别版本的协议结果，不与本8项相加作同源Java结论。官方OpenJDK源码只作静态依据；真实leaf/三边JDK/完整JAR/DB/CI **NOT_RUN**。没有假class图/原始输出回填，没有VM Java。

唯一双ref补丁来自公开7da3（5caf→0e00），零上下文临时Git应用模拟通过；root审定后自行接线，若已选择bd33不可盲回退。源约束覆盖仍PARTIAL：反射/方法调用/传递纯读/其它写owner保持UNKNOWN，不声称全图无环。下一项将继续现有SQL写owner规则可确定语法漏检根因，不增scanner或baseline豁免。
