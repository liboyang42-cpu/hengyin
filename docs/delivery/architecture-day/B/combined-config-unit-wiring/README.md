# 固定组合配置单测接线 SOURCE

父提交：`a8433884d404b7dcc903acc4b2c487443e85416f`。仅静态 SOURCE 合同，不记录内部运行、日志或实际回执。本包不修改 C 的 `CrossDomainCombinedLoadMySqlIT.java` 或 `CombinedContextConfigurationTest.java`，也不把配置接缝认定为原运行已证实的首因。

## 实际调用与字段

复用原 pinned 准备容器、原 Maven/core/settings/cache/profile/deadline。只有 ONLINE_PROVIDER_RUNTIME 的固定选择改为 `CombinedSurefireRuntimeProbeTest,CombinedContextConfigurationTest`；OFFLINE_PROVIDER_RUNTIME 仍只选择原 probe，原 offline compile preflight 不变，真实业务仍唯一 `CrossDomainCombinedLoadMySqlIT`。不新增 CLI、workflow、环境选择器、容器或网络。

现 test-only profile 追加且仅追加配置单测这一源。编译输入20=原19+配置unit1；最低必需class39=原37+原probe+配置unit。原37字典和probe独立哈希保留，新添 `configurationTestClassSha256`；缺任何一项拒绝。全部实际class tree继续哈希，不按39修剪；生产reactor/JAR/source/git.properties、原14项实际运行class身份和所有资源复制不变。

ONLINE 前精确删除两个原 Surefire XML，以免旧报告补位；真实命令成功后，把配置单测原XML复制到既有 private `target/provider-runtime/configuration.xml` 并设600权限。该目录仍由原首次清理和无symlink围栏管理。报告必须恰好包含原类的两个方法，各一次，2 tests / 0 failures / 0 errors / 0 skipped，禁止重复或 retry/flaky 标签；原 probe 的精确XML验证复用同一有界 helper，双fork及runtimeJAR核验保留。

`warm_cache` 新增 `CONFIGURATION_UNIT` 子阶段，通过 `configuration_unit_proof(compilation)` 保存 `configurationUnit`；`verified_cache` 同时重核该字段，不能仅有skipTests/零退出或旧缓存哈希就通过。字段为：

```text
purpose=CONFIG_ASSEMBLY_ONLY, businessExecuted=false
sourceSha, runId, attempt, testClass, methods
sourceSha256, testClassSha256, xmlSha256
tests=2, failures=0, errors=0, skipped=0
```

source/class必须与本轮编译证明相同；XML必须来自固定私有路径、大小有界且非symlink，DOCTYPE/ENTITY拒绝。公开投影只含固定类/原方法、数量及哈希，不带XML properties、输出、凭据或异常正文。新错误码 `CONFIGURATION_UNIT_PROOF_REQUIRED` / `CONFIGURATION_UNIT_XML_REQUIRED` 纳入原白名单。root共享 `ci/test_http_e2e_entry.py` 无需额外hunk，本包不改它。

## 控制源码与边界

原100个控制/方法保留，新增3个 SOURCE：ONLINE唯一执行/保留两次provider与真实业务入口；两个原配置方法及XML/权限/source/class负控；缺class、缺报告或报告漂移不能创建/复用缓存。必要原helper装配增加配置收据及synthetic class，原 `test_all_37...` 两处断言仅改为从20源扣除probe+unit仍为原18，以及synthetic完整树由39变40（37+probe+unit+原匿名输出），原37必需类循环与其断言不删。现103是声明数，不是执行结果。

配置测试只检查属性覆盖和原 DruidProperties 对未初始化连接池的组装，不证明完整Spring web启动、数据库、Redis、资金、HTTP240或真实组合业务。原1200秒/1CPU/1GiB/no-swap/256PID、pinned镜像/版本、read-only/offline/internal-network、身份与实际退出/清理/业务接受门槛全部保留。

所有 parse/import、控制/测试、Java/Maven/编译、Docker/DB/HTTP/CI **NOT_RUN**。仅源码读取、编辑、diff/apply-check/hash；最终同源验证由 root 统一执行。
