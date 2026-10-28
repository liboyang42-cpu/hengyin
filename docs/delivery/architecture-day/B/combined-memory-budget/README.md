# 固定组合内部内存分配 SOURCE

父提交：`8efbea982aff88e22b1158c2bb0b551555b16d57`。本目录仅有静态源码合同，不公开内部运行、PID、日志、内存回执或其他未获准运行资料。不是业务通过报告。

## 已知与未知

原 runtime Maven 显式 heap 为128MiB，Surefire 为512MiB，未显式设置 direct-memory 或 code-cache 上限。OpenJDK21 [VM.java](https://github.com/openjdk/jdk21u/blob/jdk-21.0.8-ga/src/java.base/share/classes/jdk/internal/misc/VM.java) 第132–145、253–265行表明，未给 MaxDirectMemorySize 时 directMemory 取 Runtime.maxMemory；读取的官方源码blob为 `3e7351f63210be2864f1a27c4fb39063cdb8852d`。因此仅 heap 限额并非整个JVM或整个cgroup内存限额。该参考不是对实际pinned镜像补丁号、实际direct占用或原OOM具体组成的测量。

本轮没有堆/native/tmpfs归因证据，不声称泄漏、某个bean或某段下载为根因。B owner没有可证明需要删除的大份运行文件；原target挂载、生产JAR、20源/39最低class、全实际树、原14运行身份和/tmp tmpfs均保持，不用删除物料冒充降低负载。已确认相邻 scheduler fixture 的固定子JVM未显式限制内存且清空环境，不能从父MAVEN_OPTS继承限制；这是后续完整业务的必要接缝，不能倒推其已参与原启动失败。

## 固定内部配置及准入

| 原实际角色 | heap上限MiB | direct上限MiB | code-cache保留上限MiB |
| --- | ---: | ---: | ---: |
| runtime Maven | 128 | 16 | 32 |
| Surefire/真实组合 | 384 | 64 | 64 |
| 原scheduler薄JDBC子进程（仅组合scope） | 64 | 未显式声明/不证明 | 未显式声明/不证明 |

runtime Maven与Surefire固定初始heap16MiB及SerialGC；原scheduler child只核固定-Xmx64m，不伪造它的collector/direct/code-cache参数。不限制/删减metaspace、线程、并发、payload、业务case或断言。这些组件限制不是总RSS上界，也不等于已观测使用量；native、栈、文件页等仍由原1GiB/no-swap cgroup约束。编译预热Maven仍保留原512MiB配置。ONLINE/OFFLINE两个真实probe调用和实际业务消费者复用同一runtime Maven及fork常量；配置unit仍只ONLINE一次，真实组合仍原唯一case/HTTP240。Maven/core/镜像/POM/settings、1CPU/256PID/1200秒及read-only/offline/internalnetwork不变。

`jvm_memory_binding(argv, role)` 在原PID/start/cgroup/namespace/booter/agent绑定链内核对逐字参数，拒绝缺失、重复、冲突heap/direct/code-cache或collector参数；收据只保存固定profile与数值，不输出argv。实际MAVEN/SUREFIRE主体回读还要求同一 `jvmMemory`。原未知descendant不取得新的内存profile证明；原已知SchedulerFenceProcessProbe使用固定profile，不能沿无界默认值通过该证明。真正cgroup OOM仍拒绝，没有重试、放宽或伪造退出。

## 必要共享caller（root/C唯一应用）

root唯一接线 `CrossDomainSchedulerStage.java`：只在原精确组合selector时给原child增加-Xmx64m，空scope、standalone与其它selector保持原argv。B不编辑C Java，也不再提供冲突的完整child参数review hunk；该Java接缝及真实固定builder控制由root合成。原classpath/主类、清环境、原用户/schema/业务ID、两个worker、原Druid/原锁/fence/回执、物理子进程与全部断言均须保留。没有新runner或启动选择器。

同一已编译的 `CombinedContextConfigurationTest` 保留原两方法，并新增精确方法 `schedulerChildHeapIsFixedOnlyForOriginalCombinedScope`。B固定 `CONFIGURATION_METHODS` 追加该名字，精确XML与收据为3/0/0/0；仍只ONLINE一次，OFFLINE仍原probe，不变20源/39最低class或全树哈希。原2-case报告无法补位，重复、retry、缺任一原方法或新方法仍拒绝。root必须把这两份Java源码与本包同源合成后才验证，本包不声称未读回的Java后像已核。

## 有限观察及原控制

在原轮询中记录固定cgroup memory.current/peak及anon/file/shmem/kernel/kernel_stack/pagetables/sock；固定进程status只投影RSS/匿名/file/shmem/swap与线程数。按原PID/start身份保留最新值及已观察RSS峰值，不扩采样频率，不累积无限时间序列；缺字段标未知，不替代任何原limits或OOM事件校验。file包含shmem，两者不是可加归因；这些值不能证明heap/native细分。无NMT、raw日志、argv、环境、私有路径或异常正文输出。

原103个owned控制方法及全部业务断言保留；必要golden主体/argv helper接入新固定profile，配置XML/receipt/负控从2精确更新到3并显式保留原两方法；新增5个控制SOURCE，现108声明：准备/实际参数一致，缺失/重复/冲突拒绝，有限计数与OOM拒绝，主体回读缺profile拒绝，原scheduler heap与新增第3配置方法合同。其它owner原控制不动。没有执行解析/import、控制、Java/Maven/编译、Docker/DB/HTTP或CI，全部 **NOT_RUN**；仅源码读取/编辑/diff/apply-check/hash。根因细分、384MiB heap是否足够以及完整组合结果仍须root同源验证，不能据本包宣称通过。
