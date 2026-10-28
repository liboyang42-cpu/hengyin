# F 标准 JDK21 jdeps 真实模块产物采集接线

结论：已实现观察型 producer、专属自测和现有 Backend 中的单点接线。未新建 job/runner/dependency，未改全局 required 判据，不生成/批准 baseline，不因存量债务、missing 或采集错误把 Backend 判红。尚未运行真实 JDK21/取得真实 class 图；不能把 Python 协议夹具当真实 jdeps 结果。

## 基线与独占范围

独立工作树 `/workspace/hengyin-f-jdeps-producer`，分支 `codex/architecture-day-f-jdeps-producer`，父观察候选为公共 `361ef3b23a94c6be2f73f74134a40d827f2ee3da`。没有重写根候选、旧 manual/WP01/迁移候选；没有访问私有仓历史、凭据或生产。

改动仅新增 ci/architecture/collect_jdeps.py、test_collect_jdeps.py、本 F 交付证据，以及 ci.yml 的 Describe tested Boot Jar / 紧邻原 artifact 上传区域七行。根原始 Surefire XML + source/run/attempt/hash 上传步骤、F manual 默认主干比较、真实存储 workflow/harness/registry、所有 schema/Java/POM 均保持原字节。没有将尚未集成的 WP01 baseline 偷带进根候选。

## 具体接线与身份

原 Build and test backend 执行 clean test，没有六份完整 JAR；后续已有 startup 步骤才执行 package。本批追加在现有 Describe the tested Boot Jar 的 Git commit、资源一致性及 build.json 生成之后，不移动任何原步骤。紧邻的 Preserve tested Boot Jar 上传列表增加 target/architecture-jdeps/，复用原 artifact 名称/retention/并行预算，不另外触发 CI。

```sh
python3 ci/architecture/collect_jdeps.py --source . \
  --artifact chengyinhub-admin/target/chengyinhub-admin.jar \
  --identity chengyinhub-admin/target/chengyinhub-admin.build.json \
  --output chengyinhub-admin/target/architecture-jdeps
```

producer 将实际 HEAD/tree 与 GITHUB_SHA、可信仓库、run/attempt 和已有 build.json、Boot JAR hash 绑定；PR 的 checkout SHA 与 candidateHead 分开，不把 merge 产物冒充 head 测试。严格拒绝脏源树的采集完整性，不读取或输出环境凭据。

读取 root POM 的六模块，在真实 target 中取 admin .jar.original 与其余五模块 primary JAR。admin plain JAR 的全部 class 必须与 Boot 的 BOOT-INF/classes、target/classes 一致；其余五 JAR 必须与 Boot nested JAR 原字节一致、class 与对应 target/classes 一致。记录每份原路径、SHA256、class 数，保存六份原字节输入以供根回读。只用标准 zipfile 枚举 class 条目及其 SHA256，不解析 class 内容/constant pool；重复、歧义 owner、异常 ZIP/项目 multi-release 等均 UNKNOWN。

复用 gate 的 XML/Git/SHA256/JSON helper 和已有 JSON 的 references/module_edges/review_items/findings 形状，不添加 Maven 依赖或新框架。源码 gate 的权限/合同不被此采集器改变。

## 标准工具、输出及不能证明的部分

仅在获准 runner 运行 JAVA_HOME/bin/jdeps，先记录 --version 和工具文件 SHA256，版本必须为 21；使用 --multi-release 21 -verbose:class -filter:none。输入是六份精确原 JAR 的副本，classpath 的第三方 JAR 只来自该 Boot JAR，临时目录由本次采集独占并清理。不执行应用，不调用数据库，不添加 --recursive、--ignore-missing-deps 或 suppress 开关。版本/分析命令分别限时 30/180 秒。

现有 chengyin-tested-backend artifact 中 architecture-jdeps/ 包含：observation.json、class-index.json、jdeps-version.txt、jdeps.stdout.txt、jdeps.stderr.txt、invocation.json、inputs/六份原模块 JAR；observation 内再次给出证据文件 SHA256。错误/超时也保留可取得的原始输出；缺产物不是无环。若连证据目录都无法写入，CI 日志显式记录 UNKNOWN，缺报告不能被读成采集通过。

- module_edges / references：仅标准 jdeps class 行中已解析且与项目 class owner/实际输入位置匹配的边，不混入 POM 声明、Java import 或字符串猜测。
- missing_dependencies：保留 not found 的 source/target class（以及 artifact summary）。不能把它们当已经解析的边。
- review_items：不认识的输出、缺 owner、位置不匹配、stderr、覆盖不足或采集异常；保留原始 stderr/exit。此时 collection_status=UNKNOWN、static_graph_complete=false。
- source_identity_verified / input_artifacts_verified 分别记录身份与六份 class/原 JAR/包内一致性是否完成。仅标准工具成功、输出全识别、六 owner 都出现、无 missing/未知时才报告 COLLECTED_STATIC_REFERENCES。这不是测试或迁移验收。
- graph_complete 永远 false：反射/XML/运行时依赖及 System 内业务 owner 仍 UNKNOWN；scc 为 null，baseline 为 null，verdict 为 OBSERVATION_ONLY，findings 为空。producer 不评 SCC、不自动新增例外或接受候选，也不让缺图变成“无环”。根须真实回读产物及输入身份后另行审定冻结 baseline；采集当前候选不等于候选自生成自通过。

该观察不新增架构红灯：有效报告中的 UNKNOWN 不改变 Backend 原判据；原有可信增量 gate 继续决定新确定违规。命令执行/证据未生成时也不应被读成架构通过。当前调用继承 Describe 的既有前置条件：若更早的构建/startup/原检查失败而该步骤 skipped，本批没有伪造采集，artifact 没图就是未采集。

## 实际验证与明确未运行

- producer-selftests.txt：18/18，正常采集协议、source/run/hash 伪造、missing/非零退出、格式/覆盖未知、缺原 JAR、陈旧 class、owner 重复、非 21、超时原始输出、shadowed target、ZIP 负控、CLI 观察语义及 shell/上传接线。
- architecture-selftests.txt：当前根候选 57 项既有门禁 + 18 项本批，共 75/75；没有假称包含另分支 WP01 的全部测试。
- existing-resource-tests.txt：现有 ci.test_candidate_resource_manifest / ci.test_dependency_audit_gate 共 16/16；ci-cost.txt：PASS。Describe shell 只用 bash -n 检查，没有执行构建命令。
- 所有 .class/工具响应均明确为 SYNTHETIC_NOT_COMPILED/fake runner；本地没有执行 Java、javac 或 jdeps。真实 JDK21 stdout 格式/耗时/完整度、实际 source/模块 JAR 身份、真实图均待根在现有批准 CI 回读；没有启动 CI、数据库、Docker/VPS/SSH 或生产工作流。
- 原文件冻结、单点 CI diff、diff --check、文件/私钥门禁及 Gitleaks 实际结果见 verification.json。提交/推送仅用既有隔离候选例外，不能声明正式集成/部署通过。

## 独立集成前提：根 report 路径

本观察基线的 Record backend test report identity 把 backend-test-identity.json 写入仓库根，.gitignore 未覆盖该路径；后续既有 verify-candidate-resources.py --expected-commit 使用 git status --porcelain 包含未跟踪文件。因此该 report 文件可能使后续 Describe 在 producer 之前被拒绝。专属自测确认不能静默忽略这种脏树。本批按授权范围未修改原 XML/identity 上传区，也未放宽任何干净树判断；请根将该 report 移至 RUNNER_TEMP 或已忽略的 target 并同步上传路径，避免与共享根修改抢写。producer 自己的所有产物都写到已忽略 target 下。

无 schema/DDL/迁移、业务运行时或新增 required 判据。回退只需移除新增 producer/tests 及该七行 CI 接线；保留原 Backend、原 XML/身份上传、原 artifact 和 manual-base 修复。真实 baseline 的批准和未来增量比较另行由根处理。
