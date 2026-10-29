# 固定内容安全 stub：Java SOURCE 接线

父提交 `0969e2a4890be4969a2cdbc476715673a45f0161`，仅在公开 `92210eab634d81a8d4c1c550d37fd9e3dacb65f0` 上叠有上一份 C 静态文档；三份 Java preimage 与 9221 相同。本增量不含 B C1/helper/POM 或 root CI 改动，不覆盖其后像。旧 0969 consumer review.patch 被本实现取代，不再应用旧 patch。

实际改动仅三个既有类，无第22源码/新顶层或嵌套类型：

- GenericGameplayHttpE2eIT.registerContentSecurityFixture 仅在精确 `CHENGYIN_HTTP_E2E_TESTS=CrossDomainCombinedLoadMySqlIT` 分支调用只读 prepared consumer 后返回。原非组合方法余下正文保持；组合缺证明不调用 ToolProvider、不回退编译。
- consumer 的小私有方法校验原固定 stub 2059 bytes/SHA、同源 proof、唯一输出树、文件/目录 canonical 与无 symlink、class hash、真实 class loader/CodeSource/父类型；注册原同名 Primary bean，并在该真实 context refresh 时调用现成 assertContentSecurityFixture 全部原正向/拒绝语义断言。只读成品，无删/写/编译动作；错误保持失败。预期输出树严格仅四层 classes 目录、原固定 class 与 proof.properties，共六个 descendant；extra/source副本/临时文件不能留在该目录。proof 至多1024 bytes，三行、唯一三键、仅 hex 值，不接受注释/空行/重复键；class 为非空、至多1MiB格式上限，不代表实测大小。
- CombinedSurefireRuntimeProbeTest 原 ONLINE/OFFLINE 分支导出原 surefire.test.class.path；普通 phase==null 路径不变。保留原 source/run/attempt/fork/agent/五 runtime artifact 字段；新增 testClasspath。原 private directory 验 owner/canonical 后置700；新独占文件600，原 UTF-8 字节不规范化、不追加换行、不按缓存扫描重建。每个原条目必须为现有 canonical 本地 `/cache/repository` 或原六 reactor target 路径，包含原 admin classes/test-classes；不接受空条目/current dir、相对/URI/foreign/symlink/dot路径。保留顺序和重复项，byteLength 小于1MiB（沿用原 booter manifest 的有限量级）。仅导出文件名/hash/长度/条数，不导出 cp 路径串。
- CombinedContextConfigurationTest 原九个方法正文无修改，新增两普通无参 @Test，总11。原唯一业务用例/HTTP240/并发/SQL/金额/权限/UNKNOWN/预算及清理不变。

新增控制：

1. precompiledContentSecurityFixtureKeepsOriginalChecksAndRejectsUnprovenOutputs：普通 backend 单测也必须独立运行，因此该控制不依赖预算准备产物。TempDir 内保留原固定 stub 源字节，以一个既有真实错误类型的 class 证明“文件哈希一致仍不得冒充固定 stub”；只对文件/证明校验部分有一致输入，完整消费必须精确拒绝且零 bean。缺文件、错 source、原源或 class 漂移、重复/多余 proof、额外输出、symlink 同样必须拒绝。没有合成成功 class、javac/ASM/skip，也没有将这些负向文件控制称为正向 provider 验收。真正原 stub 的正向语义由上面的实际 context refresh 入口执行，沿原生产配置/bean 调用现成原断言，不依靠单测伪造通过。
2. providerClasspathCapturePreservesExactBytesAndRejectsUnownedEntries：有限本地路径/文件控制，placeholder 不加载为 JAR。验证 ONLINE/OFFLINE 字节/哈希/长度/条数与600权限、不覆盖原文件；错误 phase/空/相对/URI/foreign/symlink/dot/缺条目/控制字符/过长输入拒绝且不新增文件。原九控制无删减。

## 必须与 B/root 一起集成的顺序

ONLINE provider probe（单独原一方法）成功并生成 online.classpath+原.json → 按原固定源/同JDK/完整原cp编译stub并生成三字段 proof → 原 Config11+Photo23 → OFFLINE provider probe → 原实际组合。保留已协调的此顺序；Config 本身已去除对预算产物的依赖，也能在普通 backend 单测执行，不能以 skip/假成功 class/在控制内再编译补齐。实际组合才承担真实成品加载与 context refresh 正向语义证明。

B 唯一负责固定生成/真实 compiler exit、外层 cache/Maven exit 分离、online/offline cp 私有读取与路径/逐项SHA证明、所有JSON严格键集合和控制、source_manifest 新生成物前后等价、原全21源/40最低/全树/六JAR/14身份。机器字段与拒绝边界见 contract.json。新成品独立于 target/test-classes，不能增加扫描发现或豁免原 UNREVIEWED_TEST_COMPILATION_OUTPUT。

本提交没有编译或执行任何源码/控制、parse/import、Java/Maven/DB/Docker/HTTP/CI。全部 NOT_RUN；只提供源与调用合同，不能宣称内存收益/完整组合验收。没有本次 runtime 材料入库；原预算/JVM策略未改。root 合成源齐后统一验收。
