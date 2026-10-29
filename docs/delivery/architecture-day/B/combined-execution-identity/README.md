# 组合执行身份与失败收场 SOURCE

父提交：`a2f20508f747b8ce69dc92c59c8d6642ff3d64d7`。本目录只记录静态源码合同，不公开内部运行、日志、PID、凭据或实际回执。

## 已证实的源码接缝

Surefire 3.5.6 的 [JarManifestForkConfiguration](https://github.com/apache/maven-surefire/blob/25ea054860a5c1e5932b360d8aa0a31944c2b841/maven-surefire-common/src/main/java/org/apache/maven/plugin/surefire/booterclient/JarManifestForkConfiguration.java) 第 149–167、177–194 行，优先以 manifest JAR 的父目录 relativize Class-Path，再编码 URI、折行和写入 manifest；只有不能 relativize 时才使用绝对 URI。读取的官方源码 Git blob 为 `4bd12a3630a81a0a355d022e88671c03eba7d484`。C 的同父公开证据 `6566a492cadf5975767dbc7a410c93573b5b3b1f` / `combined-booter-binding/contract.json` 已并入本包直接控制场景，不另叠加它的重叠 review.patch；完整 boot/test/provider 条目全部保留，非只挑两个目录。

原 booter_binding 先要求 manifest 原文包含绝对 classes/test-classes 字符串，会拒绝上述合法相对 URI。修补按原 manifest JAR 位置展开 URI，在容器命名空间规范化后，严格将 `/cache/repository` 映射回原 host cache，并核验精确目标目录集合和每个真实条目；不能用相似路径子串签名。保留并核验真实 booter/manifest/Mockito agent 哈希、固定 ForkedBooter 主类、单一 manifest、缓存与原模块 target 范围、无 symlink/远程 URL/query/fragment，以及原 source/JAR/class tree/cgroup/进程准入。无新镜像、依赖、参数选择器或网络。

这是可由官方源码和本仓 guard 证明的不匹配；没有旧运行的具体原 reasonCode，就不能认定它是该次实际运行的唯一首因，也不能据此声称业务曾经进入或已修复通过。

## 首因及调用者

原 main → run → run_owned_app → sample_java → booter_binding 路径不变。run 在异常退出时保存当前内存中的部分进程证据和首个失败，避免只剩上一次成功轮询的旧快照。firstFailure 保持 `{phase, reasonCode, type}`，reasonCode 只来自原或新增逐字 `SAFE_RESOURCE_CODES`；未知保留 `UNCLASSIFIED_RESOURCE_FAILURE`。附加 substage 和同文件受审 function/line 也是固定白名单，不输出任意异常消息、argv、环境、私有路径或 raw log。

首因同时保存在原 budget.json 与 combined-http-budget.json，后续 cleanup 错误单独记录，不覆盖原首因。遇真实候选 PID、原 cgroup/namespace 已核但 booter 绑定失败时，单列 `rejectedJavaCandidate`（原身份摘要、`accepted=false`）；不放进已准入 javaProcesses，不作为通过证据，避免把“没有已接受 fork”伪装成“没有 fork”。root 的共享入口可按原 sourceSha/runId/attempt/class 和这三个键投影；本包不编辑共享 `ci/test_http_e2e_entry.py`。原 run/finalize CLI、namespace probe 输入输出、read_redis_cleanup_proof/redis_cleanup_proven 形状均兼容，无额外 shell/workflow 接线。

## 失败时仍取真实清理证据

原 finalize 在未记录 Maven 退出时先删 app，且完全不尝试 namespace bind。本包先精确清理原 Redis，再在尚存活且 CID/label/image/硬预算核验通过的原 app 中执行现有 Java bind probe；原 MySQL CID/PID、网络 namespace、probe 源哈希必须仍匹配。无新容器、代理、宿主 bind 或替代 namespace。即使 bind 失败，仍尝试原 exact app 清理；app 已退出、namespace 漂移或无法真实 bind 时仍是 UNKNOWN，容器删除不替代端口证明。

未知 Maven 退出继续为 null，不把 app 清理终态当成 Maven 退出。成功、失败和业务 XML/HTTP 接受条件原样保留；有 firstFailure、原非零退出、缺 JUnit 进程或缺实际业务收据，绝不因清理和 bind 成功而变绿。预热、19 编译输入、原 37+probe 必需类、原 14 运行 class、1200 秒/1 CPU/1 GiB/no-swap/256 PID、read-only/offline/internal-network 均不改。

原 88 个控制及其全部断言未改，新增 12 个控制 SOURCE（共100）：折行/编码相对 URI、绝对 URI 等价、literal-plus 不变空格、原目录/cache/agent/主类拒绝、authority/foreign traversal、真实候选身份在绑定成功/拒绝两边的区别、有限诊断、当前部分快照保留、失败路径真实 bind 调用次序、bind 失败继续清理和 namespace/源/预算负控、已停 app 不伪造端口证明。C 的10个场景以独立 test 或 subTest 全部保留。测试使用显式 synthetic manifest/transport doubles，不冒充真实 JVM/Docker 验收。

解析、import、测试、编译、Java/Maven、Docker/DB/HTTP/CI 全部 **NOT_RUN**。仅源码读取、编辑、diff/apply-check/hash；实际运行、动态原因、原240 HTTP和完整组合验收仍由 root 同源验证。
