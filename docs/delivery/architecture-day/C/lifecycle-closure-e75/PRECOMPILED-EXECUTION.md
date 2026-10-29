# 固定同源产物复用；SOURCE / NOT_RUN

本片叠在 ec8f509d1b55ed2ed0fcc4d7acf1120496af2ae3，完整交付以 e75fb84 为 base。
只交 root-owned `precompiled-execution.review.patch`，没有直接修改共享 POM/Python。

固定机器合同：

- profile 仍 `combined-http-fixture-closure`，父 POM 默认 `combined.execution.precompiled=false`。
- 仅 `fixed_maven_command()` 添加 `-Dcombined.execution.precompiled=true`；goal 仍 `test`、`-am`、同原 class/fork/agent/offline/settings/预算。不是 direct surefire goal。
- 父 compiler 的 `default-compile.configuration.skipMain`、`default-testCompile.configuration.skip` 绑定该私有属性；无 phase/goals override，无全局 maven.test.skip/skipTests。admin 原同 execution 的 implicit=none/21 includes 保留。
- `fixed_cache_command()` 逐字不改：完整本源 package、ONLINE、真实 stub 编译、Config11/Photo23、OFFLINE、原 offline preflight 均保持默认 false；不借旧产物或跳真实准备。
- 业务创建前仍原 `verified_cache` / `source_manifest` / stub / JAR / 全树 / compiler inputs 与 outputs 证明；创建后 Cmd 精确等于固定 argv，执行后仍原完整前后证明。
- 新 actual Initializer 在原 Spring 初始化之前调用 `requirePreparedTestClasspath(root, actualRawProperty)`。固定两文件原 owner/0700 parent/0600 file/非 symlink/限长校验，实际原 UTF8 bytes 必须同时等于 online.classpath 与 offline.classpath；不 decode/归一/去重/重排/改 pinned codec。
- 配置仍原 11 方法，业务仍原 1 方法。已有 classpath 控制追加实际消费正向、空值/字节漂移/缺失/权限拒绝；ordinary Config 测试仅自身 TempDir，不依赖预热成品。shared Python 追加 3 个直接控制，按本基线实际源码计旧 128 个 test 方法正文不改，合计 131；不混用其它协议文件的合并计数。

固定版本 primary 源（完整下载 hash 在 primary-sources.tsv）：

1. compiler 3.15.0 `AbstractCompilerMojo.executeReal:743–750` 在增量判断之前取得 compiler。`CompilerMojo.execute:224–235` 的 skipMain、`TestCompilerMojo.execute:203–208` 的局部 skip 均在 super.execute 前 return。因此可避开重复编译体与该 compiler 查找；不保证插件 ClassRealm/Plexus 对象完全不创建或立刻释放。
2. Maven 3.9.11 `MojoExecutor:167–175` 在成功 execute 后调用 PhaseRecorder；`PhaseRecorder:39–52` 登记原 lifecycle phase。compiler 正常 return 保留 compile/test-compile phase，并未跳过 reactor。
3. 同版 `ReactorReader:133–163` 对本 reactor 的 compile/test-compile phase 返回各既有 classes/test-classes。即使 skipMain 不执行 CompilerMojo 末尾的 artifact.setFile，原 loose-output 分支仍成立。故不用失去 phase 记录的 direct surefire:test。
4. 同版 `MavenModelMerger:468–491,552–553` 按 execution id 合并。父 default-testCompile 新唯一 skip 键与 admin 原 implicit/testIncludes 合并，未替换原 includes。
5. Surefire 3.5.6 `SurefireMojo:560–562` 的 skip 判据仅 skip/skipTests/skipExec；私有 compiler 配置不设这三个 Surefire 参数。`AbstractSurefireMojo:2702–2743` 用 project artifacts + main/test 输出构造 CP，不依赖 generated source roots；实际新增原字节比较仍独立拒绝任何漂移。

3 个 shared 控制精确名：

- test_verified_execution_reuses_compiler_outputs_without_skipping_surefire_or_preparation
- test_reuse_property_is_compiler_execution_local_with_original_lifecycle_and_admin_inputs
- test_reused_execution_requires_verified_cache_and_full_source_before_any_app_creation

这是源码可证的重复工作集闭合，不是内存定量证明。不能声称整个 Maven 插件 realm 消失、RSS 必降某值或本次 OOM 精确来自 compiler。完整 Spring、四物理 held、双 child、72 身份/240HTTP、全部时限/预算/原退出与精确 cleanup 不变；是否整组适配 1GiB 仍 UNKNOWN，交 root 最后统一验收。所有控制/解析/编译/运行均 NOT_RUN。
