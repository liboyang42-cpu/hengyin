# 9221 native working set：静态增量交接

父提交：`92210eab634d81a8d4c1c550d37fd9e3dacb65f0`。只交本目录，不编辑 B 的 helper/test/POM、Java、shared entry 或 CI。本包不包含实际运行记录、日志、PID、RSS、采样值或 artifact 元数据。所有验证 NOT_RUN；实际 OOM 的 heap/native/metaspace/stack/file/tmpfs 归因仍 UNKNOWN。

沿用原 `C/combined-memory-working-set/file-access.tsv` 与 `resource-git-index.tsv` 的文件访问边界，不重做旧工作集报告。下面的 TSV/JSON 只记录当前源码增量、引用和未知项；旧表行号/20源/39最低不是当前运行合同。当前权威是 combined_http_budget.py 的 21源、37条 REQUIRED_TEST_CLASSES 加 provider/configuration/photo 三项最低类、全部输出树、六 reactor JAR，以及原组合 identity 的 14类字节绑定。

## 关键事实

- 当前实际 Maven 为128MiB heap/16MiB direct cap/32MiB reserved code cache/SerialGC；Surefire 为384/64/64/SerialGC；两者 Xms16m。scheduler child64已存在，本包不改、不因未观测其角色而删。前置 package Maven512 是另一准备进程，不是实际 Maven 的参数。
- `fixed_maven_command:1660–1668` 仍为完整 reactor `-pl chengyinhub-admin -am test`。同一父 Maven 会构建当前项目模型/解析 test 依赖并进入原生命周期。Maven 3.9.11 DefaultPluginRealmCache 缓存 plugin realm/artifact，到 flush/dispose 才释放；可证存在这些引用，不可据此声称实际占用多少 native bytes。
- Surefire 3.5.6 的 Mojo 需 TEST 依赖解析并从 testClassesDirectory、classesDirectory、依赖/provider 形成真实 fork classpath；不是只读原 fat JAR 的运行方式。子 JVM 有独立 heap/metaspace/JIT/线程，本地映射页可能共享，不能把两份 RSS 简单相加作准确 memcg 归因。
- 原 case 只声明一套 SpringBootTest；组合 initializer 直接调用 Generic 的 LocalInitializer，不创建 Generic 测试对象或第二套 Boot context。配置九方法/Photo 控制在准备阶段另一运行里；不能将它们当应用阶段并存 context。实际成功创建次数未仪器化，仍 UNKNOWN。手工经济 fixture 的第二套 SqlSessionFactory/mapper、Druid/Redis 与主应用同时存在，是隔离目标，不能删掉代替修复。
- 原 Generic initializer:460–478 在 Surefire JVM 内通过 ToolProvider 编译固定 2059-byte stub。JDK 主源明确返回 jdk.compiler 的 JavacTool；这一处并非额外 javac 子进程。固定 stub Source SHA 在 source-hashes.txt；生成 class/编译器实际内存大小 UNKNOWN。URLClassLoader.close 与删除 scratch 不等于卸载全部已加载编译器类/元数据。
- 六 target 是磁盘 bind，源码/cache/budget 是只读 bind，app/tmp 是256MiB tmpfs ceiling。没有整 target/JAR/cache 复制到 app/tmp。不能用不存在的副本解释本次 OOM，也不把 tmpfs ceiling 当已占用。

## B 新候选的 TieredStopAtLevel=1（本包不重复实现）

JDK21 参考 tag 的 compiler_globals.hpp:251–253 默认4，范围0..4；compilerDefinitions.hpp:58–62 定义0解释、1 C1 simple、2/3带 profiling C1、4 C2/JVMCI。compilerDefinitions.inline.hpp:49–80 将正常 tiered 的 level1 判断为 C1-only/simple，不启用 C2。compilationPolicy.cpp:433–498 在 C1-only 时把计算得到的 count 全给 C1，因此**不保证总编译线程减少**；当前没有显式 CICompilerCount，实际线程数 UNKNOWN。

这一 flag 可避免高层优化编译任务及其临时结构/生成代码需求，但仍有 C1/JIT/code cache；不是 -Xint，也不限制 metaspace、class space、其他线程栈、direct/native malloc、文件页、tmpfs 或总 RSS。不能从 reserved code cache 推导 committed bytes。短任务可能减少高层优化成本；热代码峰值吞吐可能下降，原时限/240HTTP/并发与断言必须原样验证。B 必须贯穿准备的真实 provider/config/photo probes、实际 MAVEN_OPTS、Surefire argLine、PID profile/admission 及对应控制，不能只改一处启动字符串；scheduler 原64-only合同不要被顺手改成 Maven/Surefire 配置。运行效果和 OOM 是否被解决均 UNKNOWN。

引用的 OpenJDK jdk-21.0.8-ga/Linux v6.12 是主源规则参考，不冒称该 pinned image 的实际 JDK patch 或 runner 内核版本。实际 Java21/pinned image/CID/limits 仍由原 owner证明。

## 可交 root/B 的最小消费候选（NEEDS_ROOT_DECISION）

`precompiled-security-consumer.review.patch` **未应用，不能单独应用当完整修复**：仅固定组合 class 选择时，从固定 owned target 加载准备期编译的同一原 stub，核 source SHA、原 stub SHA、class SHA、类加载器/CodeSource，注册原同名 Primary bean并返回。缺任何证明直接拒绝，不回落 runtime javac。非组合保留原 runtime compiler 路径及原行为，不改 stub内容或真实安全断言。

必须一起串的 B producer/manifest 接点见 `candidate-contract.json`：在原 pinned preparation 内、完整同源 reactor package 后，使用原准备出来的 test classpath、同一个 JDK、原 -proc:none/-classpath/-d 参数编译原 fixed stub；先排除旧输出，再写固定三字段 proof。额外目录放在 `target/controlled-content-security/classes`，**不放 target/test-classes**（stub 带 Component/Primary，放默认扫描 classpath 会改变发现/重复注册边界）。该目录只经原有方式的子 URLClassLoader 加载，不是新的服务器/插件系统；源码21输入/原40最低/全输出树/所有资源/14身份保持，新增生成物另纳 source_manifest 前后 hash证明。

当前 CombinedSurefireRuntimeProbeTest:25 只确认 surefire.test.class.path 存在，没有把完整 cp 落盘；原 JSON 仅五项 runtime artifact。因此必须在同一个既有方法受控 ONLINE/OFFLINE 分支新增私有 capture（不加测试类型/测试源），用实际该属性而非 cache 扫描，按原规范拒绝外部/非本 target/canonical 路径并核在线/离线的有序路径与哈希。未来 private online.classpath/offline.classpath 不进 artifact；准备及 verified_cache 必须绑定捕获/编译来源证明。源副本仅在原准备 owner 的小 scratch，编译后精确删除，不保留到 generated-test-sources 绕过原 source guard。consumer patch 只读新固定成品，不能单独补齐这份目前不存在的 producer/cp 证明。

收益只能表述为移除这一处运行期 javac 调用/其 scratch，不是保证不加载整个 jdk.compiler 或量化 native/RSS 节省；其它消费者仍可能加载编译器。实际场景/错误/安全闸和全资源不变，准备生成证明的新增控制须由 B/root在其 owner源码补齐。

## 不提供的错误捷径

不能直接把 `test` 改成 `surefire:test` 或删除 -am：Maven 3.9.11 ReactorReader:133–163 对 loose class 回退要求当前 MavenProject 有 compile/test-compile lifecycle 状态；预先存在 target 不是本次状态。旧 source_manifest 也没把 project artifact 文件注入新的 Maven model。若要只运行直接 goal，需要 root另裁决真实同源 reactor artifact/model 解析与完整来源证明，不能采用本地仓库旧项目 jar 或悄用缺失依赖成功。目前没有可独立 apply、又保全部原消费者合同的直接-goal hunk，本包明确保持现 goal。

也不建议任选新的 MaxMetaspaceSize/Xss/heap/线程数，或触发 System.gc/清空资源/删业务窗口；这些没有实际内存分类和可用性证据。原 JVM flags 与 JIT 层级规则不是全部 native 上界，RSS/commit/瞬时同刻分解和受影响吞吐仍需最后同源受控验证。
