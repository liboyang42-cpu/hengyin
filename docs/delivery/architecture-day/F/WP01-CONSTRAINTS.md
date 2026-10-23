# WP01 模块边界/SCC：最小治理合同和实测取值

结论：现有可执行源码子集在 cae548ab 固定，本批只补三个旧债/环回归及可验证取值，不增加依赖、解析框架或门禁规则。完整编译后模块图尚无真实证据，System 内业务域 owner 尚未定义，不能把源码子集的无环当完整治理通过。根 wave2 的 CI/harness 和 A/D SQL 修订仍由各 owner 负责，本批没有接线或 dispatch。

工作树 `/workspace/hengyin-f-module-contract`，分支 `codex/architecture-day-f-module-contract`，父候选 `cae548ab7fa486998421e0f3815cae5d29111705`。未重写 527、10a73、cae548、8aaa 或根候选；与 8aaa SQL 维护互相独立。取值针对本次实际回读到的完整 wave2 SHA `765ea0366f43029b6fbd33df777e3f86e19c74e7`，不把移动分支名当冻结身份。

## 实际数值与真实 owner

`wp01-constraints-values.json` 从不可变 Git blobs 复算公共 2ec、固定 527 和上述 wave2。三者六模块 POM 相同，已解析模块图均为以下 10 条边，SCC 均为空；这是有限声明/import 图的结果。

| 来源 owner | 已解析的目标 owner |
| --- | --- |
| admin | common、framework、generator、quartz、system |
| framework | common、system |
| generator | common |
| quartz | common |
| system | common |
| common | 无 |

固定 527 有 3,508 条已解析跨模块 import、53 条精确私有包存量：admin→system 51、admin→quartz 1、framework→system 1。wave2 有 3,509 条跨模块 import，私有存量仍为 53，未增加模块边。物理源码单元数为 admin 280、common 151、framework 60、generator 13、quartz 23、system 1,901（公共 2ec/527 的 system 为 1,900）；这些不是编译 class 数或测试执行数。

包 owner 仍按实际 Maven 目录/具体类型确定：business.util 横跨 admin/system；admin 的 PlayNarrativeSupport 不得被包前缀误归 System。其余真实包清单复用 dependency-baseline.json，不能把 common/framework/system 等 Maven owner 偷换成业务域。

三个快照的未知项均为：通配 import 92、文本块/Unicode 源文件 13、未解析本地 import 88、直接 FQCN 使用 178、共享包隐式使用源文件 21。wave2 相对 2ec 的本次诊断为 0 findings、9 review_items，graph_complete=false。它没有在 wave2 上执行冻结 CI gate，也不是 Java/字节码证明。

## 只拦新违规的固定合同

1. 跨模块 internal/mapper 引用只对新增具体源文件/目标/kind/owner 身份阻断；Java 的 public 修饰符 不等于业务 API 授权。普通方法体变化、已存在的相同私有引用不因“碰过文件”被追溯阻断。未解析引用列 UNKNOWN，正常 PR 审阅，不附七项审批 JSON。
2. SCC 在已明确的 owner 图上计算，不把每个 Java 类当作一个业务模块。仅阻断相对可信比较 base 新增或扩大的 SCC，包括两个旧 SCC 合并；旧 SCC 未变/缩小/拆解不要求每个 PR 重修。附新边 witness。
3. 固定存量只用于精确审计和防洗白，不能永久授权。旧私有引用清除后再引入，应按当次比较 base 判为新违规；旧环拆掉后再形成，也必须报红。固定 baseline 字节不随候选扩大，工具升级须重新分析同一冻结公共输入并审查差异。
4. 影响 base 复用已有可信事件规则：PR 实际唯一 merge-base；manual 校验真实 default_branch 与固定公共 origin fresh fetch 后取唯一 merge-base；push 使用事件 before。候选仅要求公共 2ec 祖先，固定 527 库存从可信 verifier checkout 再生，兼容根按 delta 组装分支。
5. 不将任意 Java/测试/workflow 改动本身判违规。文档/测试小改不引入额外 Java 报告要求。现有 Java/真实存储收据验证继续复用根工具，架构记录不替代 Backend 实际测试。

## 可复用工具与编译后补全路径

已复查 ci/architecture/dependencies.py、gate.py、ci/real_storage_it.py、ci/dependency-audit-gate.py、scripts/verify_retained_dependency_package.py、scripts/verify-candidate-resources.py，以及现有六模块 POM、Backend 构建/产物步骤。漏洞/SBOM 工具不提供项目 class 依赖图；迁移拓扑也不能当 Java 图。已有标准 ZIP 读取和资源/构建身份可复用，不自读 constant pool 或造字节码框架。

下一步只需在批准 GitHub-hosted Backend 已实际 package 后使用 JDK21 自带 jdeps；不增加 Maven/ArchUnit 依赖，不新增 job、矩阵、runner、并行或每 PR 第二次构建。当前未实施采集器/解析器或修改 workflow。先实际获得标准工具输出，再确定最小输出读取合同，不能先假造输出格式并宣称可用。

- 输入复用 tested Boot JAR、其 SHA256/build.json、已有 clean checkout/嵌入 commit/资源一致性检查，以及同一 job 的 run/attempt。类图身份应绑定这些实际输入；Surefire XML、SBOM 或 UI artifact 均不能充当 class 图。
- 按 root POM 六个物理模块定位 admin 的 BOOT-INF/classes 及五份项目 nested JAR。用 Python zipfile 枚举完整 class 路径、归属和字节 SHA256，嵌套类/生成类也收录；同 FQCN 跨模块重复或缺模块列为无法定位 owner，不按包名猜。所有第三方 JAR 只供 jdeps classpath，不当成本项目 owner。索引读取复用现有 ZIP 安全及包名唯一性校验，不启动 JAR。
- 在同一已批准 runner 记录 `$JAVA_HOME/bin/jdeps --version`、确切输入 SHA256 和原始 stdout/stderr/exit。命令形状为 `jdeps --multi-release 21 -verbose:class -filter:none --class-path <admin classes:project jars:third-party jars> <admin classes directory> <five project jars>`；这是待真实 CI 验证的接线建议，本批没有执行。不使用 ignore-missing-deps、递归扫描全部第三方或 suppress 错误。未解析类型/工具失败不能写“完整图”，现有源码规则仍可对已确定违规判红。
- 把 class 依赖映射回六 owner，复用现有 components/delta 计算 SCC；私有引用精确到 source class→target class→owner，而非模块对通配许可。不要编写调用链/方法写入语义解析器。
- 第一次冻结编译图必须来自经批准路径对公共 2ec 的真实同口径产物；当前没有这份证据。只安排一次基线采集，不能拿 wave2 当前债务反向写进旧 baseline。后续候选复用已有正常构建；非 main/POM/build 变化不强迫额外构建或借用别的 SHA 报告冒充当前结果。JDK/分析口径变化时先重跑冻结输入，禁止扩大旧例外洗白。
- 只有真实输入、完整项目 class 索引、标准输出格式和正反实测都满足后，才可宣称“静态 class 依赖的模块图已覆盖”；反射、XML 装配、外部动作和业务授权仍未知，不是运行时完整性证明。

只读核对已知 run 40016642215：Backend job 120630505611 的 Build and test backend 失败，Describe/Preserve tested Boot Jar 均 skipped；artifact 列表没有 chengyin-tested-backend。元数据记录 wp01-compiled-input-availability.json 仅说明此次观察没有可复用 tested jar，不代替原始日志/收据验收，也不代表尚未读取的 wave2 run 状态。本线程没有启动/重跑 CI 或在云 VM 运行 Java。

## 业务域边界及旧债消减

完整业务域 owner 是仍缺的决策输入：System 中 service.impl/mapper 大量类在平面包，不能从“提现”“广播”等名字自动推断 owner，更不能从本次 A–H 会话归属永久定义代码域。没有已审 namespace 或逐类 owner 合同前，System 内域边界必须标未知，不能伪造全量白名单。需要 owner 提出最小公开 API/所属类边界，F 只将明确合同登记和验证；若标准 jdeps 仍不足再单独评估 ArchUnit，未经批准不加入依赖。

旧债首先按精确 53 引用逐条由对应 owner 支付：引入现有服务 API/移除跨 Mapper 调用的业务变更应有相关测试，F 不越权改业务类；每清除一条，之后重新引入自然被增量规则拦住。不要求所有碰到旧 Controller 的 PR 同时偿还全部债务，不按文件数/控制器行数验收，也不自动清除存量记录或审计历史。

## 本批实际验证与交付边界

新增三项有意义回归：旧私有引用清除再引入红；旧 SCC 方法体变化/拆解绿、重新引入红；两个旧 SCC 合并成更大 SCC 红并列出两条新边。运行 `python3 -m unittest discover -s ci/architecture -p 'test_dependencies.py' -v` 为 28/28，完整 test_*.py 为 85/85；原始输出 wp01-constraints-selftests.txt / wp01-constraints-all-selftests.txt。check implementation、baseline、根 workflow/harness 都与 cae548 字节相同，未扩大自动阻断。

这是治理合同/取值与回归测试交付；可执行源码规则仍是 cae548 的子集，完整编译图/业务域规则未实现。测试为本地 Python/Git 文本夹具，不是 Java/MySQL/jdeps。没有新 schema、DDL、迁移或运行时行为；不触碰 A/D 新迁移和根 Photo/C caller。回退本批只移除这三项新增回归和本报告，不回退既有 checker/manual 修复。隔离候选提交按用户例外执行，未获得完整验证不得用于正式集成/部署通过声明。
