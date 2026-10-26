# 固定 stub 三 Java 源码编译接缝

Parent: `663e6282a09d0a70d0127dc09e50c3d762dce426`。

本增量只补 `GenericGameplayHttpE2eIT` 的 `java.nio.charset.StandardCharsets` import。
其新增 `preparedContentSecurityClasses` 在原 parent 第 543 行使用未限定的
`StandardCharsets.UTF_8`，但 import 列表没有该类型；同文件原有调用使用完整的
`java.nio.charset.StandardCharsets.UTF_8`（238、240、300、606 行）。这是源码可证的
类型名称解析缺口，不需要新增依赖。补 import 后不改任何方法正文或调用顺序。

限定读取三 Java 的新增方法、imports 与直接调用者所得接缝记录：

| 来源 | 定义与调用边界 | 本增量 |
| --- | --- | --- |
| GenericGameplayHttpE2eIT | preparedContentSecurityClasses(Path,String) 声明 throws Exception；registerPreparedContentSecurityFixture 的原 try/catch 承接；listener 只捕获未被重新赋值的方法参数 application | 仅补 import；类加载、CodeSource、proof、刷新后原 assertContentSecurityFixture 均不变 |
| CombinedSurefireRuntimeProbeTest | captureTestClasspath 为同包 static，返回 Map<String,Object>、声明 throws Exception；bootsActualProjectPlatformRuntime 也声明 throws Exception | 不变 |
| CombinedContextConfigurationTest | 两新增普通 @Test 与 copiedPreparedSecurity 均声明 throws Exception；直接调用上项同包方法，反射调用 Generic 的两个 private 方法；P0AcceptanceSchema.repoRoot 定义为同包 static Path | 不变；原九方法及新增两方法正文完整保留 |

普通 backend suite 的两新增控制仍只使用 TempDir、原固定源与已有错误类型 class
进行文件/拒绝控制，不读取 owner 准备成品、不执行编译、不 assumption/skip。
受控组合仍必须有真实准备成品，缺失/漂移拒绝，绝无运行期编译 fallback；
实际 context 刷新仍执行原完整正向安全断言。classpath 四键、21 源/40 最低 class、
14 原运行身份、业务断言和资源围栏不变。

边界：这是静态源码修补，不声称它复现了原 CI 的具体错误，也不声称编译通过。
原编译失败的具体文件、行和其他可能诊断仍 UNKNOWN。未运行解析、import、测试、
Java、Maven、数据库、容器、HTTP 或 CI，均 NOT_RUN。仅做 Git 差分/空白检查、
限定补丁敏感词检查与文件摘要。
