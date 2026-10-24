# F：TopicQuery批读的精确规模依赖方向

结论：允许 `E_TOPIC_QUERY → D_CONTENT_SIZE_POLICY` 一条类型职责方向。root称D_SIZE_RULES的实际已冻结owner ID为D_CONTENT_SIZE_POLICY，其唯一类型是 `com.chengyinhub.business.service.support.TopicSizeLimits`；复用原ID，不新建/重命名owner，不授权整个support包。parent `f8531bfd6cca90d88f9c281d1904a170b635f990`（承接bd33/0e00和确定SQL写owner修复），分支codex/architecture-day-f-query-size-direction。生产源码/CI未改。

核实公共 `b9b894b170d75b4e6315f2bde41ede8f551522c6` 的真实源码及E批读交付：TopicQueryService.java:20 import，selectChapterTemplates:450 使用 `Math.min(TopicSizeLimits.MAX_NODES, orderedIds.size() - offset)`，用于已准入章模板ID批读的大小；未在本规则修改批读/授权/额度。TopicSizeLimits中MAX_NODES为public static final int 300，当前实现另有纯规模检查方法。两个文件完整Git blob/SHA256及原始行见source-evidence.json。此为源码事实，不据常量推定实际JAR无class引用；标准编译器是否内联/保留该引用，必须由真实JDK raw/index回读确定。

policy增量逐JSON断言只有一条allowed_directions新增；仍56类型/45职责/现52方向。原51方向、全部forbidden_backrefs（含Query禁止回Facade/publication/claims/runtime）、3private Mapper、2whole-table facts、public2ec baseline和locking提案全保留。新方向是class级技术依赖许可，无法证明只访问MAX_NODES、具体方法或传递纯读；不给Query或Size任何新表/字段/command写权。其它SDK/G未登记类型仍未登记，不用unowned推导授权。

新增3项，复用现有actual JAR/raw/class-index协议读回的Python fixture：精确Query→TopicSizeLimits正控（保留graph_complete=false）；Size→Query反向硬红及两条具class/hash witness的SCC；已有Query→Size但Size全raw source行缺失仍UNKNOWN。原80producer方法AST逐个不变，本次83/83通过；全architecture控制199/199、0skip（python-tests.txt）。这是NOT_COMPILED协议fixture，不是实际javac/jdeps或业务模型运行。

gate.py/collect_jdeps.py没有新增算法或解析；实际执行数据文件只owners.json，另专属test_collect_jdeps.py及本目录。旧所有scope/cache/owner/候选policy自修改拒绝仍生效。六可信文件SHA见trusted-files.sha256。root实际只纳bd33时须同时取bd33→本候选完整delta（包含f853的gate/test修复），不能只拿owner字节让trusted文件不一致。若root暂不纳f853，不能声称该冻结verifier已匹配。

真实Java/javac/jdeps/JAR/leaf/DB/CI全部NOT_RUN；本机未调用Java。原模块9边仍仅旧观察，不据此声称新完整图或全局无环。root唯一审定双CI ref；工具将在独立后续commit从原c135字面bd33路径配套绑定本完整SHA，无0e00回退、无候选参数覆盖。仅missing evidence不得PASS。无新job/框架/依赖/生产操作；回退只撤检查policy增量，不改变业务持久合同。
