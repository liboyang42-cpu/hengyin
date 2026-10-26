# 固定 Surefire provider/runtime 真启动预热

父提交：`a20a38f08fa3a04b793f9e9de7ae881dd95fbb19`。仅静态 SOURCE 合同，不含内部运行、回执、日志或实际缺失坐标。

## 修补合同

原 package/provider-get/skipTests offline preflight 能证明编译与仓库快照，不能证明 Surefire 的动态 provider/launcher 已完成解析和 fork。此包不猜缺失 artifact，不新增依赖声明/版本。仍让当前 Surefire 3.5.6 按同一项目测试 classpath 自行选择真实 runtime：准备阶段先在线、再离线各执行一次固定 `CombinedSurefireRuntimeProbeTest`，最后保留原 offline compile preflight。准备共用原容器/镜像/core/settings/repository、1 CPU/1 GiB/no-swap/256 PID 和同一 1200 秒 deadline，没有另起 runner 或开放实际组合执行网络。

probe 无 Spring、DB、Redis、资金 owner 或 provider HTTP。它通过真实 Jupiter `@Test`、Surefire fork 命令、原 Mockito startup agent、源码/class 身份及实际加载的 launcher/engine/API/Mockito CodeSource JAR 证明当前启动链；版本从实际加载 JAR 读回，不按任意缓存目录选赢家、不硬编码 launcher 版本。在线/离线分别生成新 XML 与私有 JSON，绑定 source/run/attempt；复制后只公开有界、脱敏的哈希和已审 artifact 族/纯数字版本，不公开原 XML properties、命令、路径或异常正文。

两个阶段必须均真实执行 1 test、0 failure/error/skip，且 fork 身份不同、加载的 runtime JAR 版本/hash 相同并与原仓库文件匹配。最终零退出/skipTests/旧 XML 均不能代替这套证据。缓存再次采用会重核证明与 class/JAR；真实组合仍沿原独立 `-Dtest=CrossDomainCombinedLoadMySqlIT`、forkCount=1、read-only/offline/internal-network、原 XML/HTTP240 和混合故障门槛，预热不算业务通过。

## 最小接缝与保留项

owned：`ci/combined_http_budget.py`、`ci/test_combined_http_budget.py` 及本文。必要 Java/POM 接缝单列 review.patch：新增一份 test-only probe，现固定 profile 只追加这一个编译输入。原 C 18 份源正文、37 个必需 binary names、原 14 项运行 class 身份、生产 reactor/JAR/source/git.properties、资源复制与全树哈希不动；19=原18+独立预热1，不缩减任何原业务 case。probe 正常全量 suite 中仍执行 fork/class-loading 断言，仅在显式准备 phase 下写专用证明，不新增默认 skip。

原首次清理范围加上本次专用 admin target/provider-runtime；精确路径/无 symlink 围栏、原测试/生成源/编译状态清理及物理资源清理都保留。实际执行的 Maven fork 参数复用同一固定常量；准备中的两个真实 fork 使用与实际消费者相同的 Maven 128 MiB heap 和原 test fork 参数，不提高预算。

原 83 条 Python 控制全部保留，新增 5 条 SOURCE，现 88 条；必要工厂接入新证明。新增 Java 1 个预热 test，不替代现有测试。XML 缺失/跳过/失败、旧身份、不同 fork/运行、runtime JAR 漂移、仅 skip preflight 均拒绝。合成文件/transport doubles 仅为协议控制，不冒充真实 Maven/provider 验收。

所有解析、import、控制/测试、编译、Java/Maven、Docker/DB/HTTP/CI **NOT_RUN**。本包不会认定原缺失 artifact、插件版本或环境根因已知；由 root 与共享诊断片同源集成后统一实际验证。
