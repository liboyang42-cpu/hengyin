# E：未 ready 首因与受控停止闭环（SOURCE ONLY）

结论：完整源码包，5 code + 3 delivery files。未运行任何解析/import、测试、编译、Java、数据库、HTTP、故障或 CI。历史运行的具体 child 首因仍 UNKNOWN；不宣称旧137或本轮 readiness 已通过。

## 公共身份与已知证据

- parent f1fe75d646f8ae57864ee5603c68e70e7fabc854，tree e293cc5976c22de88731ee792e75d16e6544fc7b。旧 E 工作区/提交均保留。
- root 已核原 run40237890983/job121303282684/artifact12615468923，ZIP SHA256 15adbea84a7c4bac5fed985338ec453af5d06616bda916fac6d4eeb66d6402e7。此包只读取 root 公共读回，不重下/重跑原 artifact。
- 原 JAR a4b7d84f7db76e1686038526c01fd30102f1601171eb08078837cf9951aaa5c2，boot/ready/checks皆空；运行进程/IllegalStateException 不能证明具体失败条件，也不能由 marker=false 推断未启动。精确外部清理不是业务静止。
- [root 原三 JSON 读回](https://github.com/liboyang42-cpu/hengyin/blob/192a122f3f0716d132eb7075fc7dba303feb7f13/docs/delivery/architecture-day/COORDINATOR/f1fe75d64-restore-http-failure-readback.json) 是证据文档，192a不是本包运行/代码 parent。

## 确认的源码接缝与 UNKNOWN

1. 原 main 在 SpringApplication.run 返回后立即断言 Scheduler.isStarted。真实 MySqlSchedulerCoordinator.start 只在独立控制线程 scheduleWithFixedDelay(maintainLeadership,0,2秒)，由真实 session lock/refreshQuartz 才启动 Scheduler；线程完成不受 main 返回点保证。此时序缺口可由源码确认，但现有日志不能证明它正是本轮历史 IllegalStateException。
2. 真实 LeaderJobsQuartzConfig 的 customizer 只 setAutoStartup(false)；ScheduleConfig 全注释。没有虚构第二生产 factory。Boot4.1.1 QuartzAutoConfiguration 将 wait 属性先写到真实 SchedulerFactoryBean，再执行 ordered customizers。
3. 原 stop 仅在成功 ready 后才持有 process/boot proof，因此主线程断言失败、Spring线程仍在的真实路径必然没有可用停止身份；本轮 secondary APP_BOOT_IDENTITY_REJECTED 与此一致。
4. 其余历史条件（数据库、Redis、bean/factory、原 seed、leader是否实际获得、strace-D/PID1现场语义）仍 UNKNOWN；不能按异常类或本次源码补齐反推历史成功。

直接参考：[Boot4.1.1 QuartzAutoConfiguration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-quartz/src/main/java/org/springframework/boot/quartz/autoconfigure/QuartzAutoConfiguration.java)；本 parent 的 LeaderJobsQuartzConfig.java、MySqlSchedulerCoordinator.java 及原 child。Spring factory/getBeansOfType/DirectFieldAccessor 另按公开官方源码5651ee2f97edc64ca480c2764ca990516446e321核方法合同，非运行版本/通过证明。

## 实际生产测试 caller 闭环

- RestoreHttpApplicationChild：只测试 classpath 增加原 Boot SchedulerFactoryBeanCustomizer，设置 waitForJobsToCompleteOnShutdown=true，不创建 factory，不开 auto-start，不驱动/替换真实 leader。仍核真实 env + 被选中的真实 Scheduler/FactoryBean + 私有实际 wait 字段。原立即 isStarted 改为等待真实 Scheduler，原180秒上限和父进程预算不增加；不手动 start/maintainLeadership、不禁 Quartz。
- 同 child 写原私有目录 startup.json（原子替换、最大4096字节），只含固定 stage/status/reason/type 与 source/JAR/class manifest/scope/schema/mode/boot/javaPid。main 捕获原 Exception/Error 后记录固定分类并重抛原对象；没有异常正文、cause、SQL、stack、令牌/私密配置/环境转储。来源/JAR/test-classpath准入前无可信诊断就维持 UNKNOWN。
- ApplicationSlot 在原等待循环中捕获独立 launch proof（原CID/owner/network/镜像/命令、source/config/JAR哈希、固定PID1 argv/hostPID/kernel startTicks），无需 ready 或伪 boot。后续 pre-ready stop 必须对同一原 proof fresh复核；缺失/变化拒绝。不选择任意PID、不增加signal endpoint，不跨容器发信号。
- 有可信 FAILED startup 文档即抛 APP_CHILD_STARTUP_REJECTED，保原 typed stage；缺失/PROGRESS 不能代替 ready，仍180秒超时。ready 文件与原 startup READY+bootId均一致才进入原五 boot。
- stop 的原20秒/command30秒/exit0或143/137拒绝/OOM拒绝/独立移除保留。pre-ready成功仅为 PRE_READY_JVM_EXIT_CONFIRMED；完整ready成功为 READY_JVM_EXIT_CONFIRMED。首因不因停止或清理成功改绿，未捕获可信JVM即便容器已exit0也拒签。
- source_quiescent_snapshot 仍 stop→原SQL读取，并要求当前 launch 已真实 ready。pre-ready proof 不可触发静止SQL/备份。probe 将有界 startup 诊断及停止证明类型保存在原 JSON 中；没有新 public endpoint/artifact 通道。
- 原五 boot/mode true-invalid-empty-false、source/target UUID/schema、真实HTTP/money/receipt/原身份/UNKNOWN/gap拒release、网络/内存/总时间/清理全部保留。B/C/H/F 源、auth relay、workflow/registry/SQL和生产业务未改。

## 配套 SOURCE（全部 NOT_RUN）

ci/test_restore_application_http_probe.py 保留所有原用例/断言，只给已有 running-slot doubles 补真实新 launch/ready 前提；原 source快照两个控制显式 ready。新增8项：
1. 固定 startup 阶段和身份，无消息/cause输出。
2. foreign、超限、未知枚举、额外raw字段、symlink拒绝。
3. child失败在timeout前返回，不伪造boot。
4. 原未ready JVM可按捕获proof停止，但不成为boot。
5. starttime/hostPID/config/JAR/source/缺proof变化均不可stop。
6. pre-ready137及未捕获JVM的exit0均保持UNKNOWN。
7. progress/未ready保持原180秒超时。
8. ready文件单独不准入，pre-ready不stop-for-snapshot/不读SQL。

新增 RestoreHttpStartupContractTest 5项：
- 真实 Boot Quartz内存 factory + 原leader customizer + 测试wait customizer实际装配，保持 auto-start=false。
- 外部真实Scheduler不能替代所选factory。
- barrier控制初次未started/随后真实Scheduler启动，验证等待而非立即误拒。
- 过期deadline是typed timeout，不启动Scheduler。
- 固定阶段/原因/身份投影，message/cause/私密canary不进入文档。
这些纯内存控制不是MySQL领导、完整应用或真实恢复验收。测试新类刻意不采用 RestoreHttpApplicationChild* 前缀，避免进入原运行 child.class glob。原运行 child 新增必要innerclass由原glob/manifest如实绑定。

## 交付与剩余

owned.patch.base64 是完整5 code增量；提交还含本README、manifest.json和owned.patch.base64，共8文件。manifest逐项列原/后像hash。git/hash/applycheck/有限秘密扫描不算测试；gitleaks无现成可执行时root统一完整扫描。所有真实新运行证明仍 NOT_RUN，最终仅root统一获准环境验收。不升级137、不增budget/heap/宽限、不开放生产派发、不执行DDL/真实渠道。

Root transport note: the author raw code patch contains 11 literal blank-context whitespace lines. It is losslessly base64 encoded here; decoded SHA256 72caf2891a2194e70dbc7714226bb59150e328f4b0a877d74e2f83506de772a4. Author five code postimages and source semantics are unchanged; the author manifest/readme hash refers to the original donor, with root documentation projection recorded separately.
