# 固定组合测试 Surefire 离线闭包（SOURCE / NOT_RUN）

父提交 `a20a38f08fa3a04b793f9e9de7ae881dd95fbb19`。只新增本目录，未编辑 B 的 helper/POM、workflow、gate 或 Java。本包不重做已交18源/37class闭包，也不复制运行日志或内部资源状态。

**静态结论：原 provider-get 与 `-DskipTests` preflight 没有覆盖 Surefire 按项目 Platform 版本动态解析 launcher 的路径。** 当前真实 POM/BOM 链给出的版本是 **6.0.3**，不是 provider 自身原 Platform **1.12.2**。最小可用接线是在同一个原 cache owner 中，保留 provider-get，追加已有 dependency-plugin3.10.0 的固定 launcher6.0.3 transitive get；实际 test 继续原只读缓存、离线/内网、真实 fork、原 case/XML/receipt 与独立 cleanup。

这不是原运行缺失坐标的诊断。原具体 artifact、失败 resolver 分支、实际完整 dependency tree 未见可信证据，均 **UNKNOWN**。源码可证明应预取的闭包不等于证明它就是原失败根因；新 get 成功也不等于 Surefire boot/测试通过。

## 立即供 B 使用的固定机器合同

`coordinates.json` 列出了版本来源、resolver 条件、两个固定预热根、launcher 的发布态 runtime graph 和证据边界。`prewarm.review.patch` 仅供 root/B 串行应用；本工作树没有改对应 helper。其三处窄 hunk 为：

1. `ci/combined_http_budget.py:301-305` 的原 goals，在 provider-get 后追加同工具、同 settings/cache 的 `org.junit.platform:junit-platform-launcher:6.0.3 -Dtransitive=true`。
2. `:319-321` 的 stage/goal zip 同步为三项，防止插入 get 后误把原 offline preflight 截断。
3. `:465-469` 只加入新固定 phase 枚举，不扩大任意异常文本/坐标/路径的公开范围。

原 `-N org.apache.maven.plugins:maven-dependency-plugin:3.10.0:get` 已是实际 caller，`GetMojo:90-102,139-153,184-186` 真正声明并使用 `artifact`、`transitive`，无需新插件、POM 依赖或不存在的 dry-run 参数。追加 get 只在原允许联网的缓存准备阶段，不能带到真实离线执行容器，也不改网络/cache/资源限制。

## 版本来源逐段可追溯

| 来源 | 当前可证事实 |
|---|---|
| 根 `pom.xml:19-21,116-123,363-367` | Java21；导入 `spring-boot-dependencies:4.1.1`；原 Surefire 固定3.5.6 |
| admin POM `:81-96` | 原 `spring-boot-starter-webmvc-test` 与 `spring-boot-starter-restclient-test`；均通过发布POM `:50-54` / `:56-60` 引入 `spring-boot-starter-test:4.1.1` |
| Boot4.1.1 BOM `:121,3386-3392` | 原 `junit-jupiter.version=6.0.3`，导入 `org.junit:junit-bom:6.0.3` |
| starter-test4.1.1发布POM `:98-103`；junit-jupiter6.0.3发布POM `:75-93` | `junit-jupiter:6.0.3` 带 API/params，engine 为 runtime 依赖；不是只有 API 的项目声明 |
| junit-bom6.0.3 `:68-110` | Jupiter API/engine/params 和 Platform commons/engine/launcher 均6.0.3；不要套旧JUnit5“Platform1.x”映射 |
| Surefire3.5.6父POM `:23-27,111`；maven-parent47 `:1007-1013`；junit-bom5.12.2 `:103-115` | provider 构建自身 `versions.junit5=5.12.2`，经原父BOM给 Platform engine/launcher1.12.2；与项目BOM属于不同依赖图 |
| provider发布POM `:79-115`；providers父POM `:48-53` | 原 runtime 图含 common-java5、surefire-shared-utils、surefire-api、Platform engine/launcher；provider自己的 Jupiter engine/API/params 是 **test scope**，不等于给消费项目提供 engine |

未运行 effective-POM/dependency:tree；以上是当前源和固定发布POM的声明推导，不伪称已读取实际 fork classpath。项目没有新增 Surefire plugin dependencies；官方 plugin/common POM 中测试用 JUnit/Vintage 的 test-scope 也不能当作消费项目的 plugin runtime engine。

## Surefire 3.5.6 真正执行路径

以下均为固定 tag 的 `maven-surefire-common/.../AbstractSurefireMojo.java`，链接及原文本摘要见 `official-sources.tsv`：

- `:955-959` 先 `isSkipExecution()` 返回；`SurefireMojo:559-562` 明确包括 `skipTests`。Maven 仍可能为该 goal 建立 plugin realm/下载声明依赖，但这个返回点没有选择 provider、计算动态 classpath 或启动 JUnit。
- `:969-988` 扫描测试；不存在匹配测试会在 provider 解析前返回或失败，因此选择虚构测试名加 `failIfNoSpecifiedTests=false` 不是 provider boot 检查。
- `:1142-1179` 基于 test classpath 选择 provider。Platform 候选 `:3103-3105` 要求无 Platform runner 且有 Platform marker；`:2326-2332` 的 marker 优先是 plugin Platform engine，否则项目 Platform commons。当前原配置按项目 commons6.0.3推导，不能直接拿 Surefire3.5.6 当 launcher 版本。
- `:3122-3136` provider 根版本来自实际 booter artifact3.5.6；resolver解析 provider 的原 runtime closure，再解析**明确配置的** plugin dependencies。
- `:3138-3168` 是条件分支：plugin提供 Platform engine 则合并；否则 API存在且engine缺失时才按 API版本补 Jupiter engine。Vintage还要JUnit4存在、Vintage缺失，并且此前 API-only 分支已经设定 engineVersion。不能无条件补任意 engine/Vintage；当前 starter 已声明engine6.0.3，源码没有理由再新加项目依赖。
- `:3170-3173,3202-3223` 先移除与项目test deps重叠的 provider map key，再调用 `alignProviderVersions`。**即使某key已移除，`:3209` 仍先 resolve launcher 及其runtime transitives**，然后只替换 map 中剩余的对应项。这条 resolver 请求没有出现在原单独 provider-get 的发布依赖图中。
- `SurefireDependencyResolver:135-163,185-210` 使用原 Aether runtime 过滤解析；`AbstractSurefireMojo:2812-2824` 还会按原 booter artifact计算 fork classpath。保留原 plugin/provider get及完整 Maven 缓存，不手工拼几个 JAR 替代 resolver。

## 可用最小预热与不能声称的保证

原 package/preflight 中的 `-DskipTests` 继续只表示编译/生命周期与显式 get 的预检；不能再由它推出动态 provider boot 已验证。建议 B 在原收据中明确记录这两个固定 `resolutionRoots` 及 `providerBootstrapVerified=false`。这个布尔只是预热证据边界，不能成为实际运行失败的豁免条件，也不替换原 maven exit/cache manifest/profile/18inputs/37classes。

现成追加调用的准确 artifact 参数是：

```text
org.apache.maven.plugins:maven-dependency-plugin:3.10.0:get
-N
-Dartifact=org.junit.platform:junit-platform-launcher:6.0.3
-Dtransitive=true
```

必须沿原 `common=cache_maven_arguments()` 传递固定 settings/global-settings/local repository，并保持原 get非零→准备失败、原总期限/预算、精确cleanup。`coordinates.json` 中列出的6个发布态 launcher runtime JAR只是描述原解析图；Maven还必须自己获取合法 POM/parent/BOM/metadata。不要手写下载文件或绕过缓存哈希。若源BOM/plugin依赖变化，先重新核版本和resolver分支，不接受 arbitrary coordinate 输入。

本包不提供能“跳过case但证明相同provider已boot”的假命令。去掉skip会实际执行原用例，需要真实业务资源，不能在准备阶段偷偷执行；无匹配类、`maven.test.skip`、自造dryRun都不能证明原 fork。最终完成证据只能来自原受控执行路径的 Maven+Surefire 两个真实 JVM身份、原唯一case XML/业务事实与清理门禁。

## B 对应直接控制 SOURCE 建议（本包不改他的测试）

保留原83控制及全部业务门禁，针对接线补足：固定 get 顺序 provider3.5.6→launcher6.0.3→原offline preflight；三项目标均有stage、不得被zip截断；精确拒绝任意版本/仓库/环境坐标注入；保持真实 `fixed_maven_command` 无skip且只读offline；get/预检成功仍不能把 providerBootstrapVerified 或业务通过签真；追加阶段失败仍保首非零和独立exactcleanup，不借旧cache receipt。原日志无具体依赖坐标时维持UNKNOWN，不因为固定清单新增就回填旧运行诊断。

所有本轮源码/配置/收据建议均 **NOT_RUN**。只读取公开primary源码/POM、当前项目源、做文本/Git/摘要；没有执行 Maven、Java、DB、Docker、HTTP业务、测试、解析器或CI。这里只交能证明的必要预取入口及条件规则，不宣称已找全任意环境的插件依赖、不承诺下一轮必过，不改生产/权限/金额/UNKNOWN/预算。
