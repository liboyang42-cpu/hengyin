# 固定组合用例总内存与文件工作集（静态 SOURCE / NOT_RUN）

父提交 `8efbea982aff88e22b1158c2bb0b551555b16d57`。只新增本目录，未改B helper/test/POM、原Java或rootCI。原运行数据/回执/内部路径不纳入公共供体。当前具体内存主因仍 **UNKNOWN**；没有原逐进程RSS/heap/native/file/tmpfs测量，不能从源码替它补数。

## 可立即串 B/root 的结论

1. 当前**真实执行路径的声明参数**为Maven `-Xmx128m`（helper:1161-1173）、Surefire `-Xmx512m`（:33,1404-1412）；合计640MiB只是两个Java堆的配置上限。cache package的512MiB、旧shell的1024MiB不是本组合执行父JVM参数，不能混用或再当成待修点。
2. `CrossDomainSchedulerStage:27-29,48-54,89-95` 在到达原case阶段时会启动3个独立child、正常流程最多2个同时存活。原child启动没有`-Xmx`，也不会继承父Maven的heap：原构造器清空env，直接执行java而非mvn shell。不能用“父进程128MiB”当这些JVM的堆上限，更不能假定JVM会按进程数平分1GiB。
3. `/tmp`为原256MiB tmpfs上限，不是预占256MiB；文件页/映射JAR也不因readonly而免于memcg记账。MySQL、Redis使用独立CID/cgroup，只共享原netns，不应把它们自己的768MiB/32MiB tmpfs错误加进app cgroup。
4. 当前prepare/run是**宿主磁盘target/cache bind mount**，没有整target/JAR复制到`/tmp`的接线。保留此来源，不另造mini-JAR、classloader、target副本或tmpfs复制器。现阶段没有可据源证明的“删掉一份整JAR tmpfs拷贝”修复。

可独立评审的最小JVM候选为 `scheduler-child-heap.review.patch`：仅在父环境原精确class selector为 `CrossDomainCombinedLoadMySqlIT` 时，给原child加 `-Xmx64m`；原独立scheduler场景argv不变，main/classpath/环境/原号/锁/回执/物理kill/exit/cleanup都不变。**64MiB是有界候选，不是源码能推导出的实际最小需求或已证修复。** 选择它是为了给轻量非Boot coordinator子进程分出明确份额，而不是继续由同一容器可见内存做独立默认ergonomics。

候选下128+512+2×64=768MiB是名义最大heap和；错误收尾可能与原32MiB namespace witness重叠则为800MiB。剩余256/224MiB**不是**经测native/tmpfs/file-cache余量，仍可能失败。不给metaspace/code cache/stack猜一个小值，也不为了候选失败而抬高1GiB/CPU/时间或减场景。root决定是否采用该test-only候选，未实际应用到Java。

## 文件闭包与来源

- `file-access.tsv`：prepare、actual Maven lifecycle、真实case、namespace收尾的逐路径读写矩阵与原caller行。
- `resource-git-index.tsv`：当前公共Git中358个原main/test资源文件的路径、Git blob SHA1、原blob字节数。完整main资源/Mapper扫描继续保留；该表不是运行资源裁剪allowlist。
- `fixture-git-index.tsv`：原20个测试编译源、固定namespace源码、原canonical SQL/stub等直接fixture文件的同类Git元数据。
- `source-sha256.txt`：本包用到的当前helper、POM、workflow、fixture和关键Java消费者的SHA256。
- `working-set.json`：全部声明JVM/可能child、configured thread pools、文件/内存归属、UNKNOWN和原围栏机器合同。

生产`target/classes`、`target/test-classes`、fat JAR、依赖缓存各JAR及原booter的实际字节大小**不在公共Git**，保UNKNOWN；不拿`.java`或`.xml`源大小冒充编译产物大小。原20输入和39minimum只是编译完整性，不是只留39个class的授权。原source_manifest必须继续哈希完整实际class树及全部生产reactor JAR；原case还把真实生产类与原fat JAR字节比对，不能移除。

运行输入可复用原磁盘文件，未发现需要第二份独立JAR/target来满足classpath的源码理由。但不能据此把整个target改只读：原`mvn test`会走resources、compiler/incremental状态、git-properties和Surefire阶段，原目标目录也是这些生命周期的合法输出。`classes`/`test-classes`的**内容前后哈希不变**不代表插件不会尝试写文件、检查mtime或生成状态。没有单独证明这些writer前，不给B一个会导致新PermissionDenied的readonly-remount hunk。

必须可写的原边界包括：六模块target生命周期输出、admin `target/surefire` booter/properties/channel、`surefire-reports`原XML、`provider-runtime`原准备证据、`http-e2e`原schema报告/日志/投影、原app `/tmp`临时文件。源码根、真实cache执行mount及`/budget`对app继续readonly；host仍可按原owner写release/stop。原privateenv为0600，不公开、也不拷进新工作集。

主case JAR identity在 `CrossDomainCombinedLoadMySqlIT:309-336,352-355` 使用ZipFile和DigestInputStream流式读取；不是一次把fat JAR装入Java堆。host helper `digest:350` 使用`read_bytes`，可能临时载入整个输入文件，但调用在host Python，不是app JVM；这不排除共享file-cache记账，也不能据此断言它导致了app OOM。没把它作为此次主因或顺手重构B函数。

## JVM、native与文件页的证据边界

下面是固定公开**实现参考**，不声称实际镜像patch或runner kernel正好等于该tag；全文哈希见 `official-sources.tsv`。

- OpenJDK21u `jdk-21.0.8-ga` 的 `gc_globals.hpp:327-339`声明MaxRAMPercentage25.0/MinRAMPercentage50.0/InitialRAMPercentage1.5625；`arguments.cpp:1578-1599`按是否显式MaxHeapSize、小内存、对齐/可分配内存等条件算heap。Linux `globals_linux.hpp:59`默认UseContainerSupport=true；`cgroupV2Subsystem_linux.cpp:192-208`读取memory.max。它不表示本次child的MaxHeapSize已测得256MiB，也不是多个JVM之间的总heap仲裁器。
- 同JDK `globals.hpp:1409-1411` 的MaxMetaspaceSize默认max_uintx；ThreadStackSize、ReservedCodeCacheSize为platform-dependent配置。原固定argv没有为这些native域加独立cap。reserved地址空间、committed、used、RSS必须区分；不能把某个虚拟reserve直接算成物理峰值。
- Linux v6.12 cgroup-v2文档 `:1226-1232,1296-1313,1373-1416,1420-1485`：memory.current含该组/子组使用；memory.max是hardlimit，events说明限额/oom事件但不定位哪类分配；memory.stat的file含tmpfs/shared-memory，kernel又含多个子项，不能把所有项相加。`:1812-1828`说明共享内存区域的charge ownership不能从使用者简单推断。
- 同版本tmpfs文档 `:7-17`：tmpfs随实际文件增长，页在内核缓存中，无swap时不能假设它能靠swap释放。`size=256m`是fs上限，不是常驻量，也不是另给app增加256MiB预算。

本候选 `read_cgroup:1265-1275`读取limits和memory.events，**没有**读取memory.current/peak/stat或各JVM的RSS/heap/native拆分。现有运行资料不能提供这些数时保持UNKNOWN；本包不公开原runtime资料，也不造一个执行过的NMT/heap报告。

还有两类不能漏算/误算：

- Generic initializer的 `ToolProvider.getSystemJavaCompiler():456-484` 在**同一Surefire进程**编译固定内容安全stub，可能占用该JVM的heap/metaspace；不是一个独立javac JVM。其scratch被finally删除不证明compiler相关heap/metaspace已经回收。
- 原16MiB repeated base64字符串和17MiB request limit（Transport:24-30,99-125）必须保留。provider接收已经streaming；client序列化/编码/重试保留对象的实际瞬时副本未知，不能将一个源字符串长度当成整条HTTP请求的heap峰值，也不缩payload/并发来过门禁。

对应实际生产发送链是 `OpenAiCompatibleLlmClient:171-181` 的`body.toJSONString()`→`HttpUtils:174`的UTF-8 byte[]→`:313-334`真实worker捕获body→`:511-520`的ByteArrayEntity。它证明发送侧存在完整JSON/byte表示及物理worker持有关系，不证明本次已到达该阶段，也不提供这些对象实际重叠/回收时刻的测量；不据此改生产发送语义或宣称找到OOM首因。

原configured executor数量见JSON；它们不是观测线程数。Spring原pool50/200、scheduled50、HTTP32/cleanup4和本case有限pool仍保留；不可把所有max slot同时当作已分配线程，也不猜每线程stack字节。App内任意child的堆/native/文件页/网络缓冲都受同一个1GiB cgroup约束，pids256不是内存预算。

## 直接控制 SOURCE 与交付边界

若root采用child候选，必要直接控制为：原精确组合selector才有一个固定`-Xmx64m`；standalone旧argv逐项保持；无任意heap/env参数入口；原两个存活child/三个总child的READY/PID/hash/锁和回执不变；失败仍保首非零并精确cleanup。随后仍必须跑原全场景，不能以纯argv控制通过称容量通过。

不改主Maven128MiB/Surefire512MiB，不绕原heap/booter/JVM identity，不删HTTP240、并发/故障/UNKNOWN/原资金事实，不把`forkCount=0`或少启动child当修复。文件复用建议不包含复制/删减/转存命令。所有本轮控制、parse/import、编译、Java/Maven、DB/Redis、Docker、HTTP业务及CI均 **NOT_RUN**；仅静态源/Git/摘要/秘密扫描，完整root统一验收之前不声称任何memory参数够用。
