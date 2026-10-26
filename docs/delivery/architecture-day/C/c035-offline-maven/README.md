# 固定组合 HTTP 的 Maven 离线缓存接线 SOURCE

Parent：`c0355527bd2082ea957b08fd684a4a8887470858`。本包只含代码与静态合同，没有原运行状态、私有日志、内部标识或凭据；缺失插件的旧运行具体坐标仍 UNKNOWN，不从源码推定旧故障的完整根因。

## 修复合同和真实 caller

原 workflow 由 host Maven/default settings 预取，原固定 workload 用 pinned Maven3.9.11/空 global+user settings/RO `/cache/repository` 执行。缓存文件存在不等于该 execution core、repository ID/context 与 lifecycle 需要的插件已经可离线解析。本片保留现成 package、provider-get 和缓存，不新增 dependency/plugin版本或恢复平台。

- `workflow.review.patch` 只将新固定 scope 的 Resolve 步骤改调 `combined_http_budget.py warm-cache`。旧三类/default/reusable选择、host预编译分支、原 class caller、两段ABI及实际bootstrap/loopback/network清理代码不改。
- `warm-cache` 复用原 pinned Maven JDK image/原 `.m2/repository`/原模块 targets，在独立短命、精确CID/nonce/labels的准备容器中执行原 `package -DskipTests`、原 dependency-plugin3.10.0 对 Surefire JUnit provider3.5.6 的 transitive get，再做同 core/settings/repo 下的 `-o test -DskipTests` 预检。这些都是准备，不是原case/XML/HTTP负载通过；原 `fixed_maven_command` 保持 `-o test`、原 test class、Mockito startup agent、真实fork、全部原断言，不加skip或执行网络。
- 准备容器在原依赖下载阶段使用普通 bridge；不打开实际 workload 的 internal namespace。镜像、1CPU/1GiB/no-swap/256PIDs不增，准备Maven仅512MiB heap；真实 workload父/子JVM原heap不动。固定 readonly rootfs、cap-drop ALL、无privileged/socket、同task UID；只映射原source RO、原targets RW、repo RW、独占launch RO、临时/tmp。无host settings、tokens、SDK配置或任意命令/class/profile入口。原repo在真实 workload中仍RO。
- `EMPTY_SETTINGS` 一处生成准备/执行的相同配置正文。准备记录真实image ID、实际Maven3.9.11 core JAR hash、source/run/attempt/class、原POM hashes、cache全regular文件（包括 `_remote.repositories`）的有界count/bytes/树摘要，以及实际HostConfig/cgroup/OOM与精确cleanup。缓存写入前拒绝symlink；不删tracking/lastUpdated元数据或改来源来冒充可解析。
- `prepare`、真实run前后验证同源准备收据、POM/设置/缓存摘要与同image ID；budget最终投影绑定该cacheProof。真实JAR/source、MAVEN/SUREFIRE PID/cgroup、schema、UUID和receipt门槛保留，缺Surefire/XML仍失败。两段ABI签名和先后顺序不变。
- 准备失败先保留原exit/稳定type和有限phase，raw logs只留精确private子树；只提取reviewed标准plugin坐标。使用既有 `cleanup_slot` 独立核owner并stop/remove/核CID+name absence，foreign不动。证据写失败仍尝试精确清理但不能签通过；失败保留自己的private journal。仅固定 warm-cache CLI安装TERM/INT/HUP的有界unwind处理，清理后保原129/130/143，不注册全局daemon、重试容器或runner。

新public `combined-http-cache.json` 只签 `preparationOnly` 的离线准备证据，不签应用启动、业务case或Q4/Q8。原artifact目录机制不变，private/raw永不上传。

## Apache源码依据和有限诊断

已只读核对 Apache `maven-3.9.11/maven-core/src/main/resources/META-INF/plexus/default-bindings.xml`：jar默认lifecycle绑定resources3.3.1/jar3.4.1等，root POM又明确覆盖compiler3.15.0/Surefire3.5.6。不能仅用host的`package`成功推定另一个Maven core的默认插件缓存可用。

同tag `PluginResolutionException.java` 明确消息为 `Plugin <id> or one of its dependencies could not be resolved:`；这种失败可以早于“Failed to execute goal”。`diagnostic.review.patch` 复用owned `safe_plugin_resolution`，仅当原goal投影未找到plugin时补精确reviewed坐标/version/RESOLUTION_BEFORE_GOAL，不输出cause、URL、settings、credentials、任意版本或私有路径。无坐标仍保持原unknown，旧Help/category/goal投影不变。

Resolver `maven-resolver-1.9.24` 的 `EnhancedLocalRepositoryManager` 明确按 `_remote.repositories` 的repo ID/context判断已缓存远程artifact是否可用；本包保留并摘要这些原文件，不把JAR存在当充分证明。参考源码SHA256：default-bindings `840095fa1185d887032a2e5a73f64921be6a5777d75d42ec233f93ba9bc2097d`，PluginResolutionException `8fbe16ec8ef7ab3079d439e692e1ba1e2d560f69904a11c1872cb6081f1cb6e6`，EnhancedLocalRepositoryManager `0a54bea8978bd769300cca1e7fdb2493062e1f9cfb4cf8c01dbd8aac8d34b092`。这些是静态参考，不是任何旧运行具体缺失坐标的证明。

## 组合、控制源码与 NOT_RUN

C未编辑任何共享实际文件。root须同时应用3份窄review.patch：workflow调用、原diagnostic owner接缝、原controlled-entry两条预取断言改读真实owned固定命令（保原provider/version与完整matrix/default/权限断言）。actual generic shell、auth owner/default0构造、F Java/统计、SQL及生产代码未改。shared pre/postimage与patch摘要另附。

原51控制方法/断言保留，只补实际positive预算fixture所需cache证明；新增12控制SOURCE，共63：工具链/固定参数、准备收据及来源/设置/版本/cleanup、POM/cache drift、repository tracking/symlink、ABI拒绝异core、有限plugin投影/unknown、原diagnostic调用、模拟transport准备成功/原nonzero/foreign拒清/写失败仍清，以及signal typed unwind。模拟不代表Maven/Java执行。

仅Git差分/应用检查、摘要与限本片秘密模式扫描；没有执行测试、parse/import、编译、Maven/Java/DB/Docker/HTTP或CI。新增源码全部 NOT_RUN。原已通过bootstrap/后台结果不借给新source；实际固定负载、原case XML、真实cache/core与内外层清理仍须root同源统一验收，不承诺本片已经通过运行。
