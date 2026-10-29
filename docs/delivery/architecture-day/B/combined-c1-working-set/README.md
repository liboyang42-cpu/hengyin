# 固定组合 JVM 工作集候选 SOURCE

父提交：`92210eab634d81a8d4c1c550d37fd9e3dacb65f0`。本包仅两Python及本文，不含内部runtime资料。本次固定JVM参数变更交root裁决；本包未执行，不声称已消除OOM或完成组合业务。

## 精确改动和调用者

仅在原 `RUNTIME_MAVEN_OPTIONS` 和 `SUREFIRE_MEMORY_ARGUMENTS` 各追加一次 `-XX:TieredStopAtLevel=1`。`fixed_cache_command` 的ONLINE/OFFLINE两次真实provider fork继续使用同一组常量，ONLINE仍为probe+Config9+Photo23，OFFLINE仍只probe；package/compiler准备进程的原512MiB参数不改。`child_environment` 和 `fixed_maven_command` 分别把同一常量交实际Maven与Surefire。无新参数入口、POM、Java、依赖或workflow修改。

原Maven Xms16/Xmx128/direct16/code32/SerialGC、Surefire Xms16/Xmx384/direct64/code64/SerialGC、scheduler child原Xmx64全部保留。该候选不设置新的heap/metaspace/stack上限、不扩大cgroup/时间预算，不调整线程数/并发/payload。它限制两个并存runtime JVM的高阶JIT编译工作，避免C2优化及解释器profiling，而不是把尚未观测的native分类当作本次OOM根因。scheduler参数不改，其原默认编译策略仍明确未证明。

`jvm_memory_binding` 把原精确参数列表核验扩至tiered/编译模式冲突：缺少、重复、覆盖level，关闭tiered，显式其它CompilationMode/Xint/Xcomp、UseCompiler/ProfileInterpreter/JVMCI覆盖均拒绝；scheduler也不能顺带接受新增tiered参数。已接受MAVEN/SUREFIRE原PID证明新增 `tieredStopAtLevel=1`、`compilationMode=C1_SIMPLE`，`check_subject`继续用原同源binding精确比较，旧或漂移证明不能签当前候选。原booter/agent/实际class/JAR、source/run/attempt、全树与精确命令核验不变。

## 源码依据与明确限制

官方OpenJDK21参考源：`openjdk/jdk21u`，tag `jdk-21.0.9+10`（仅解释JDK21选项语义，不冒充原pinned镜像二进制版本或原运行内存归因）：

- [compiler_globals.hpp:251](https://github.com/openjdk/jdk21u/blob/jdk-21.0.9%2B10/src/hotspot/share/compiler/compiler_globals.hpp#L251)：TieredStopAtLevel默认4、取值0至4；blob `7f1f08b68ef65539ae79b016b366784ea1d5549a`。
- [compilerDefinitions.inline.hpp:49-80](https://github.com/openjdk/jdk21u/blob/jdk-21.0.9%2B10/src/hotspot/share/compiler/compilerDefinitions.inline.hpp#L49)：level1进入C1 simple，`is_c2_enabled`排除C1-only；blob `ed456a4215720ecc1e0b779f4241747860f7993d`。
- [compilerDefinitions.cpp:607-611](https://github.com/openjdk/jdk21u/blob/jdk-21.0.9%2B10/src/hotspot/share/compiler/compilerDefinitions.cpp#L607)：C1 simple关闭ProfileInterpreter；blob `54bd1cbc7fcdbd2771fe40386cbbd187a3d042ac`。
- [compilationPolicy.cpp:925-928](https://github.com/openjdk/jdk21u/blob/jdk-21.0.9%2B10/src/hotspot/share/compiler/compilationPolicy.cpp#L925)：无ProfileInterpreter时不创建该方法profiling数据；blob `dfa2a9d01b4a80bdd0783a8de2ea08eaf17d3429`。

本源未发现整target/JAR向tmpfs复制；目标仍为原宿主target bind。没有删除文件/缓存树，也没有把只读物料变可写。实际Maven仍走原reactor `test` 生命周期：不能仅改为initialize+direct Surefire goal并假设当前reactor依赖可用。Maven3.9.11 `ReactorReader.find` 仅本session经历compile或已有artifact文件时提供相应reactor产物；当前package不等于已install，本包不引入替代解析来源。官方源blob `2a3ac7dfcab5cc1ca2e97841b0bb46bd5b7a17ad`，路径 `maven-core/src/main/java/org/apache/maven/ReactorReader.java`，tag `maven-3.9.11`。

保原实际HTTP240、业务case/并发/故障、真实Spring/资源、21编译输入/40最低及完整class树、原14运行身份、生产reactor/JAR和双probe/Config9/Photo23。C1可能改变吞吐和启动耗时；不延时、不削减业务补偿，必须以后续同源真实退出/XML/HTTP/预算和清理证据判断。是否仍OOM、占用下降多少、堆/native细分和业务是否进入均不由本SOURCE推定。真实cgroup oom_kill仍无条件拒绝。

## 配套控制与验证

原114控制方法及全文保留，新增3个SOURCE控制：同一固定策略贯穿两次探针及原实际consumer且原分配/集合不变；缺失/重复/冲突编译选项拒绝且scheduler不被暗改；当前PID策略证明严格消费、旧策略拒绝、其它证据齐全仍不能忽略OOM。合计117声明，不代表执行通过。

仅源码读取/编辑、diff/apply-check/hash。Python解析/import/测试、Java/Maven/编译、Docker/DB/HTTP/CI全部 **NOT_RUN**。未改共享entry/registry、POM或其他owner源码；root决定是否接纳此固定JVM策略后统一冻结必要同源验收，原绿色不转签。
