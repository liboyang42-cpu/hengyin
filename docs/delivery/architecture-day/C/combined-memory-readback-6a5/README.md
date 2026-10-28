# 原组合一次有界内存读回 SOURCE

Parent `6a5c45c502a1cf6e7fcf11cf0c16c4596aee7365`，tree `4d9ca5f4cc54a6d38c55485c52323e483c33a994`。
原 74aa/compiler、原全部真实场景与护栏随此 parent 保留。本包不含运行材料，不是内存修复通过结论。
所有解析/import/测试/Java/Maven/DB/Redis/Docker/故障/CI 均 NOT_RUN；只交 root 一次原场景取证接线。

完整调用链：

1. root 应用 `budget-memory.review.patch` 的两个共享文件 hunk；本分支没有直接编辑这些文件。
2. 原 `run_owned_app` 在 cache/source/stub 证明之后、原 app create 之前，独占创建原 `OUT/run.log`，0600；已有文件拒绝，不采用旧日志。
3. 原 BEFORE/HELD/RECOVERED `sample()` 读取标准 JDK MXBean；同固定日志以 JDK FileChannel APPEND/NOFOLLOW 直接写完并关闭，不留到测试 finally。原 sample 计数/SQL/断言未删。
4. 仅原精确组合 scope 给两个原 child 增加 WP14_MEMORY_READBACK=1 和原 SOURCE_SHA/GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT。所有 JVM argv、原 stdout 业务事件、堆/GC/线程/协调器/Druid/Wall/SQL/协议不改。
5. child 原 event 紧跟一条 WP14 MEMORY 数字行；现有 parent reader 校验固定角色/阶段/source/run/attempt/真实 Process.pid 与数字边界后，同步写入上述 run.log。它不占业务事件队列，不经父 Surefire stdout 编码，也不等待业务方法完成。没有新增 reader 线程、子进程、日志文件或外部通道。
6. 原成功或失败清理在删除 exact app 之前，以原 owner/CID 校验后追加原 Docker logs，绝不截断已写数字；runLogCaptured 记录真实 capture 成功。未完整 capture 不能伪签完整日志。
7. 原 finalize 在清理前从固定同 owner/600/非 symlink/真实路径/inode 的原 run.log 严格解码，加入原 combined-http-budget.json 的 jvmMxBeanReadback。只有固定数字/角色/阶段/源身份/PID 被投影；raw Maven/业务/SQL/配置/路径/图像/prompt/凭据不输出。
8. 原 firstFailure/originalExit/OOM/accepted_outcome/资源退出与清理判据逐字保留。缺/坏/foreign 读回为 UNKNOWN；无唯一已接受 cgroup PID 的记录明确标记 UNKNOWN_NO_UNIQUE_ACCEPTED_PROCESS，不借另一个 JVM。它不是业务、真实 live heap 或容量验收。

固定数字行（原私有日志前缀 `CONTROLLED_COMBINATION_MEMORY `）：

`source run attempt role phase pid uptimeMillis monotonicNanos heap[init used committed max] nonheap[init used committed max] poolCount (kind init used committed max)* bufferCount (kind count memoryUsed totalCapacity)*`

payload <=4096 字符，最多 16 个 memory pool、4 个 buffer pool；collector 最多 128 行、原日志仍 <32MiB。
所有值是 signed JDK long；计数/usage 除明确 -1 外非负，nanoTime 允许 signed 原值。
-1 是 JDK undefined 或不可取得，不当 0；used/committed 不可取得使读回 PARTIAL_OR_UNKNOWN。
最后一次 kill 若只留下截断行，不接受该行，保留此前完整行并标 PARTIAL_OR_UNKNOWN；不补值、不补 PID。

pool kind 固定代码：0 未识别；1 Eden Space；2 Survivor Space；3 Tenured Gen；4 G1 Eden；5 G1 Survivor；6 G1 Old；7 PS Eden；8 PS Survivor；9 PS Old；10 Metaspace；11 Compressed Class Space；12 CodeCache；13 CodeHeap non-nmethods；14 CodeHeap profiled nmethods；15 CodeHeap non-profiled nmethods。
buffer kind：0 未识别；1 direct；2 mapped；3 mapped non-volatile memory。原 bean 名不写出；未知 pool 仍保数字并标 partial。
每条记录独立采样，heap used 不是存活/可回收量，pool 与 aggregate 不能相加，buffer 不是 RSS；各 PID/RSS 峰值不能当作同刻合计。没有 attach/GC/heapdump/NMT 或任何参数修改。

原配置仍 11 方法、原业务仍 1 方法。现 scheduler 配置方法追加数字格式/不可取得/拒绝 foreign/隐私控制，ordinary suite 不依赖预热成品或实际日志。共享 Python 保原 131 方法，新增 3 个 SOURCE：

- test_private_mxbean_rows_survive_raw_log_append_and_bind_only_exact_accepted_jvms
- test_mxbean_projection_rejects_foreign_malformed_unbounded_and_unowned_log_inputs
- test_oom_readback_precedes_cleanup_and_cannot_replace_failure_or_sign_business_success

两原 namespace-only 控制只补其已完成 log capture 的明确 fixture 标志；原断言不改。无资源执行证明。

实际 pinned Surefire 3.5.6 primary 依据（只读下载）：ConsoleOutputCapture.println 转 TestOutputReceiver；EventChannelEncoder.setOutErr 使用 write(frame,false)。本包不依赖该路径承诺即刻落盘，避免直接写 native stdout 破坏 Surefire 协议。

- https://raw.githubusercontent.com/apache/maven-surefire/surefire-3.5.6/surefire-api/src/main/java/org/apache/maven/surefire/api/report/ConsoleOutputCapture.java SHA256 `9fdc255a236ffd0c50a4d4abd9c915fd33ef24e6b86b8f7dd0e44f70fceaa541`
- https://raw.githubusercontent.com/apache/maven-surefire/surefire-3.5.6/surefire-booter/src/main/java/org/apache/maven/surefire/booter/spi/EventChannelEncoder.java SHA256 `35425cdeab82c615767fde2ea32a34de6ff1cf408a2b9bef929dd73163f29486`

JDK 标准接口为 java.lang.management.MemoryMXBean/MemoryPoolMXBean/BufferPoolMXBean/MemoryUsage；只调用只读 getter。完整运行及 OOM 分配归因仍 UNKNOWN，root 唯一集成后执行那一次已批准原场景，不自动安排第二次运行。
