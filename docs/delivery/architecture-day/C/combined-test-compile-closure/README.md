# 固定组合 HTTP 用例测试编译闭包（SOURCE，NOT_RUN）

结论：在 `ead7abc3ad98ceb66829e2a9bd65c5a0c04e0962`，固定入口的静态 Java 测试源引用闭包为 **18 份 admin 源文件**，不是只编译入口或只编译 imports。现 compiler **3.15.0 已支持 POM 的 `testIncludes`**；仅在 admin 增加显式 profile 即可表达清单，其他生产 reactor、资源、原测试方法和 Surefire 选择均可保持。实际 profile/POM、三阶段调用和旧输出清理由 B/root 单一实现，本包没有编辑这些文件。是否按预算完成、实际编译输出及启动等价尚未执行，不能据此宣告容量/业务通过，也不能把原截止期限归因到某个具体测试源。

父提交、分支：上述 SHA；`codex/architecture-day-c-combined-compile-closure`。只有本目录新增；没有改 Java、POM、workflow、gate、B helper 或真实 case/断言。路径和行号全部针对该父提交。未把内部运行日志、资源状态或原 payload 搬入公共交付。

## 可直接交给 B 的文件

| 文件 | 固定含义 |
|---|---|
| `test-includes.list` | 18 行，路径相对 `chengyinhub-admin/src/test/java`；每行直接成为一个 `testIncludes/testInclude` |
| `source-sha256.txt` | 同顺序的完整仓库源路径及各自 SHA256 |
| `source-edges.tsv` | 每条必须保留的引用边、原调用行和理由；并非仅 imports 表 |
| `required-test-classes.list` | 37 个源码可证明的顶层、具名内部类及原反射明确点名的 `$1`；缺少任何一个应拒绝，不能借旧 class |
| `runtime-resources.list` | 固定 stub、经济夹具原 SQL 和直接 mapper；不是用来过滤全部资源的 allowlist |
| `admin-profile.xml.fragment` | 只供现 admin POM `<profiles>` 下合入的完整固定 profile 示例；不是执行入口或新 profile 选择器 |
| `contract.json` | 同基线、三处消费者、保留约束、动态边界及 NOT_RUN 的机器可读合同 |

每个包含的 `.java` 必须整份编译，包括其没有被本次 Surefire 选择的方法。Generic 的原 26、Freeze/Reuse 的原选择和断言完全不改；本 profile 只适用于固定组合类，不用于旧 scope。原组合唯一 test 的 `@Timeout(150)`、经济身份/UNKNOWN/回执/并发/通知正向及精确清理不改。

## 容易漏掉的实际闭包

1. `CrossDomainCombinedLoadMySqlIT.java:45` 调用同 package 的 `GenericGameplayHttpE2eIT.LocalInitializer`；编译单元是完整 Generic 外层源文件。外层 `:532` 引用 `ItSchema`，`:1043-1047` 引用 `R5WeRunFixture`，均是必要编译源，即使那些 Generic 方法不在本次执行集合。`MybatisItDatabase` 还由 `P0AcceptanceSchema:33-34,90` 使用。
2. `CrossDomainStorageFixture:153,157` 调用真实付款 helper；完整 `WechatVerifiedFactTestSupport` 又在 `:15,24,104-105,157` 引用同 package `WechatPayTestWiring`，后者 `:27,61` 回引前者。必须同时编译，不能因本次只调用 `realFacts` 就删其余方法或改成假 reader。
3. `CrossDomainSchedulerStage:89-106` 用 `SchedulerFenceProcessProbe.class.getName()` 启动独立 JVM，并核对其 coordinator 字节。这两个源都实际位于 **admin** 测试树；不能只看包名把它们遗漏到 quartz 模块。子进程依赖原完整 Surefire classpath。
4. `CrossDomainCombinedLoadMySqlIT:331-337` 真实 `Class.forName` 四个固定名字，均已登记。`CombinedControlledProviders$1` 是已有字符串合同，因而可以明确拒绝其缺失；Generic initializer 内匿名 Policy/SecurityManager、Gameplay child 的匿名 MVC 配置、付款 helper 匿名事务/JDBC 等的其他数字编号未猜测。保留 18 源的**全部**编译输出，不按本清单删匿名 class，不把 lambda 的 JVM 动态类当可预先虚构的 `.class` 文件。
5. `GenericGameplayHttpE2eIT:456-484` 复制固定 `scripts/e2e/E2eWxContentSecurityStub.java.stub`，运行 JDK compiler（`-proc:none`、原 test classpath），再加载固定 `com.chengyinhub.e2e.E2eWxContentSecurityStub`。它不是 Maven test-source `.java`，应保留原只读仓库文件、真实 JDK 与原运行接线，不能为了有限 includes 去掉它或预造成功 class。

核对过整个包含源的声明、同 package 标识、全限定类型、反射及 child main；没有自定义测试父类还需另加。源中 `ClmCommercePaymentProofMySqlIT`（Storage:105）、`AiHttpAdmissionTest`（Transport:165）、`MemberIdentityServiceTest`（Generic:512）只出现在注释，不是编译/反射依赖，不据注释递归引入这些测试。所有实际父类如 `WxSubscribeMsgServiceImpl`、`TencentMapClient`、`DelegatingDataSource` 都来自完整生产 reactor 或已有依赖；未复制新父类/框架。

## 原构建消费者真正要求什么

| 真实源位置 | 要求与本 profile 的关系 |
|---|---|
| 根 `pom.xml:332-368`，admin POM:63-98 | 保留六个生产模块及依赖 reactor；compiler3.15.0/release21/parameters/encoding、原 test 依赖与 surefire3.5.6不变。上游模块的原测试编译也不在本提议内过滤 |
| `ci/combined_http_budget.py:156-180` | package（`-DskipTests` 仍会经过 testCompile）、同命令的离线探测/许可在线补齐，以及 offline test preflight 必须用**同一个**固定 profile。`dependency-plugin:get -N` 只取原 provider，不是编译阶段 |
| 同文件 `:1069-1077` | 真实 `-o test -Dtest=CrossDomainCombinedLoadMySqlIT` 加同 profile；保持原参数、固定 settings/cache、forkCount1/Mockito启动agent，不能新增 skip 或联网 |
| 同文件 `:145-148,246,303` | 源 SHA、干净 checkout、所有 reactor POM 哈希仍要绑定新候选；不能只改 POM 却沿用旧 cache/source receipt |
| 同文件 `:852-880` | `classTrees` 对每模块 **实际 `classes` 与 `test-classes` 合并树**逐文件哈希，并要求每模块原 JAR；没有已冻结的 admin 全测试目录数量清单，也没有要求所有无关测试 class 都存在。保留实际树前后比较，不能把 manifest 改成只哈希 37 个 class |
| 同文件 `:1097-1133` | 预热产物进入真实执行前 `source_manifest()`，执行后必须完全一致；生产 reactor/JAR/源 SHA 不得漂移。有限测试源是新候选从头编译，不能导入旧 helper class 或改成另一个 JAR |
| 同文件 `:1008-1034` | 原 Surefire booter 必须真实包含 admin `target/test-classes`/`target/classes`、只允许本 reactor target 与原缓存路径，并使用原 Mockito javaagent；不改 testOutputDirectory、fork 或 booter 合同 |
| `CrossDomainCombinedLoadMySqlIT:300-340`；`ci/capacity_mixed_probe.py:436-447` | 实际执行的两个生产类必须与同源 JAR 字节相同；原 14 个 `actualClassSha256` key 集合保持。本包 37 个缺 class 拒绝条件是编译完整性补充，不能拿它替换原 14 个运行证明 |

仅 admin profile 的标准配置片段已经给出；默认不激活，三阶段增加相同 `-Pcontrolled-combination-test-closure` 是示例准确语法。若 B 已采用另一个固定 profile id，只统一该 id，不引入任意 profile 入参。`-Dtest` 保留为原 Surefire 选择；`-DtestIncludes` 不是此插件声明的 user property，不能拿它代替 POM 配置。`maven.test.skip` 会直接跳过编译，禁止使用。

必须在第一次 profile package **之前**由原 owner 清掉本任务 admin 旧 `target/test-classes`、`target/generated-test-sources` 及 `target/maven-status/maven-compiler-plugin/testCompile` 状态，防止离线失败后借到旧输出；该接线已由 root 交 B，不在这里实现第二清理器。记录 fresh 编译的实际输入/输出，检查所有 18 输入和 37 个稳定 class。清单是缺失拒绝集合，不是输出修剪器。若出现清单外隐式源，先停止并核对来源，不悄悄扩表，也不靠已有 class 补漏。不要删除完整生产 target/改变标准测试默认行为来迁就此 profile。

## 官方依据（固定版本原源码）

只读取了以下公开文本，没有构建插件或运行 Maven：

- [compiler3.15.0 TestCompilerMojo](https://github.com/apache/maven-compiler-plugin/blob/maven-compiler-plugin-3.15.0/src/main/java/org/apache/maven/plugin/compiler/TestCompilerMojo.java)：`:59-64` 是 `testCompile`/TEST dependency resolution；`:75-95` 定义 test source roots/output 和 `@Parameter testIncludes`，后者没有 `property=`；`:402-437` 把 includes/excludes 交给真实 source scanner，空 includes 才补全匹配。`:185-186` 保留 `${project.testClasspathElements}`；`:203-208` 的 `maven.test.skip` 是直接绕过路径。读取文本 SHA256 `565082dbec520f273bad86772b2e5304d0b7085e7d52da5a2cceab2a6150c29c`。
- [同版本 AbstractCompilerMojo](https://github.com/apache/maven-compiler-plugin/blob/maven-compiler-plugin-3.15.0/src/main/java/org/apache/maven/plugin/compiler/AbstractCompilerMojo.java)：`:934-966` 增量编译仍从 `getCompileSources` 得到源集合；`:1415-1440` 使用上述 inclusion scanner 扫 source roots。无需改 `useIncrementalCompilation` 或发明新插件来应用有限清单。读取文本 SHA256 `6dfb5af9884822a498c6caf400a0e1357309046befc5e0106f6185c41c040532`。
- [surefire3.5.6 SurefireMojo](https://github.com/apache/maven-surefire/blob/surefire-3.5.6/maven-surefire-plugin/src/main/java/org/apache/maven/plugin/surefire/SurefireMojo.java)：`:70-94` 的 `@Parameter(property="test")` 只在运行 individual tests 时覆盖 Surefire includes/excludes；不是 compiler 参数。读取文本 SHA256 `d089e015a2d1a2d5d451d6bdd9ff448d13416d6b2a039b692f7922a5ec474a27`。

## 资源和 UNKNOWN 边界

`runtime-resources.list` 的 mapper 来自 Storage:143-146 的13个固定 XML，再加真实 fact helper:218 的 `WechatVerifiedPaymentFactMapper.xml`。经济 SQL 来自 Storage:106-132 与 P0 helper 的真实文件读取；fixture 原本在方法内组装的 `wp14_probe` 两表仍保留原 Java 正文，不另建 DDL。完整 HTTP schema 继续由原 `generic-gameplay-fresh-schema.py:66-101` 的 baseline/seed/依赖 manifest/完整 migration 计划负责，不能用这份较小列表替代它。

**不筛选 test resources 或任何生产资源。** 原应用 `application.yml:153-155` 的 `classpath*:mapper/**/*Mapper.xml` 和 domain aliases、`MyBatisConfig:119-128` 的扫描继续指向全部同源生产资源。完整 Spring 应用、JWT、安全 filter、API controller、Mapper、生产反射类型及 runtime 字节码生成不从测试 imports 裁剪。

静态源引用闭包已逐条列齐；仍未证明的动态范围：生产 Spring 扫描可能受其他测试 class 的可见性影响（应用是默认 `@SpringBootApplication`）；javac 的隐式源发现/注解处理输出；实际 bean/资源/provider 发现；实际耗时。均标 **UNKNOWN/NOT_RUN**，不能把有限列表称为已执行验证。包含的 `GameplayProcessRecoveryChild` 的两个 MVC 配置有固定 `gameplay-process-recovery-only` profile（:51,194-212），只编译不激活；不新增替代应用 initializer。后续由 root 同源验证这些边界及原业务断言，失败继续失败。

本次只做源文本、Git 差分及 SHA256；未做 XML/JSON 解析、effective-POM、编译、测试、进程/容器启动、数据库、HTTP 或 CI。没有承诺缩短到某个时限，没有删除/改写任何旧用例或断言，没有把源码清单当作恢复或容量验收通过。
