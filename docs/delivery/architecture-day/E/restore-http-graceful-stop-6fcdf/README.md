# E：完整恢复应用的受控优雅停止（SOURCE）

结论：修复真实应用已 ready、Docker stop 最终退出 137 的停止接线；交付四个源码后像与八项新增直接控制测试源码。全部测试、解析、编译、Java/MySQL/Redis/HTTP、故障和 CI 均 NOT_RUN。此包不宣称五次 boot 或恢复验收通过。

## 身份与范围

- 公共仓库：liboyang42-cpu/hengyin。
- 唯一 parent：6fcdf747e17cb5094121f609ae33f7734fda2ae7；parent tree：02e30bd0778ad2eff63b091429b89e7f17895f8f。
- 原工作区 /workspace/hengyin-e-final-reuse-coupons 保留，HEAD 037afa2eb546402460738e5767c8df86e246cf1f，未修改。
- 本包隔离源码目录 /workspace/hengyin-e-restore-stop-6fcdf；original 为精确公共 parent，candidate 仅四个 owned 文件。
- 不改 B authenticated_redis_recovery_probe.py 或 relay，不改其他 owner、workflow、registry、SQL、业务服务或生产恢复放行。
- owned.patch.base64 只含下述四个 code 文件；提交另外含此 README、manifest.json、owned.patch.base64。两个范围的 diff 不混称同一 hash。

## 已知失败与源码根因

root 已独立核验 run 40232928297/job 121288314564/artifact 12613549604（ZIP SHA256 17d7c79fca06332536a727f3fb74a01931cb6b2e41aa85951e2b4ef391954914）。应用真实启动、登录并读回原章回执后，在 SOURCE_QUIESCENT_STOP 得到 APP_STOP_EXIT_NOT_CONFIRMED；exit137/OOMKilled=false，不是业务静止证明。本包没有重新下载日志或执行环境。

原 launch 的 PID1 是 strace -f -o FILE，JVM 为子进程。strace 官方源码在 -o FILE PROG 模式默认 INTR_NEVER，fatal-signal handler 忽略 SIGTERM；Docker 默认停止信号发到 PID1，不能由此外推 Java/Spring 已正常退出。官方 -D 让 tracer 成为 tracee 的孙进程，原父进程 exec 原命令；startup_child 在忽略信号设置之前进行。沿原已有 SYS_PTRACE、原固定 trace 输出，不新增进程管理框架。

原测试 child 的 Spring shutdown hook 已存在，但 Quartz 默认不等待执行中的任务结束。新参数只在同一测试 child 设置 spring.quartz.wait-for-jobs-to-complete-on-shutdown=true，ready 前同时验证属性、实际已启动 Scheduler 和该 SchedulerFactoryBean 的真实等待字段。未关闭 Quartz、未增时间预算；如果任务不能在原停止窗口内退出，137 仍失败，不能继续快照。

官方一级参考（源码阅读，不是本地 binary 运行证明）：

- [strace 手册 -D/interruptible](https://github.com/strace/strace/blob/1ade336eaf14669405b330f3c6d355bdce17dc8c/doc/strace.1.in)
- [strace 启动及信号实现](https://github.com/strace/strace/blob/1ade336eaf14669405b330f3c6d355bdce17dc8c/src/strace.c)
- [Boot Quartz 属性实际接线](https://github.com/spring-projects/spring-boot/blob/11778bdc24f72f208f532eba8185311473908019/module/spring-boot-quartz/src/main/java/org/springframework/boot/quartz/autoconfigure/QuartzAutoConfiguration.java)
- [Spring SchedulerFactoryBean shutdown 等待合同](https://github.com/spring-projects/spring-framework/blob/5651ee2f97edc64ca480c2764ca990516446e321/spring-context-support/src/main/java/org/springframework/scheduling/quartz/SchedulerFactoryBean.java)

## 实际 caller 和修改

1. ci/restore_application_http_runtime.py：原固定 launch 添加 strace -D；ready 的真实 javaPid 必须严格 int 1。固定读取原 CID 内 /proc/1/cmdline、/proc/1/stat，核 JVM main/classpath、PPID0、kernel startTicks，并绑定 CID、hostPID、原 boot/source/JAR、私有 ready/config 哈希。stop 在发出原 docker stop --time 20 前再次核对全组；不发现/选择可变 PID、不发任意 PID 信号。原 command timeout30、资源预算与 strict exit0/143 保留；137、OOM、重启、身份变化、超时都不可移除或接受。停止命令失败仍观察原实际状态，首个异常保留。
2. ci/restore_application_http_probe.py：原 run 的 stop→SQL snapshot 提取为 source_quiescent_snapshot，顺序及原数据库参数不变。stop 抛错即不读取最终快照/备份、不接受恢复。原五 boot 生命周期增加 PID1 身份约束，其余 money/receipt/授权/UNKNOWN/原号/网络断言及清理不变。
3. chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RestoreHttpApplicationChild.java：仅测试 child 的 Quartz wait 参数及实际 FactoryBean 核验；使用现有 Spring DirectFieldAccessor，无依赖/bean/endpoint/生产配置变更。
4. ci/test_restore_application_http_probe.py：所有原用例和断言保留；原成功 boot fixture 真实 PID 改为1，五 boot 负控加入非 PID1；新增下列八个直接控制源码（subTest 分支不是额外 JUnit/case 数）。

## 新增八项直接控制（全部 NOT_RUN）

- test_daemonized_tracer_keeps_original_budget_and_real_shutdown_contract
- test_proc_proof_is_fixed_to_original_java_pid1_not_a_pid_selector
- test_exact_original_stop_accepts_only_graceful_exit_and_preserves_log_slot
- test_exact_stop_removes_only_its_original_cid_after_graceful_exit
- test_changed_cid_owner_boot_source_jar_or_proc_never_receives_stop
- test_forced_exit_or_timeout_remains_unknown_without_removal
- test_source_stop_failure_prevents_quiescent_sql_snapshot_and_propagates_original_error
- test_source_snapshot_uses_original_database_arguments_only_after_stop

这些直接控制仅提供受控 proc/Docker 返回值与 stop/snapshot 次序断言，不执行 Java、Docker、信号、SQL 或备份，不能单独证明真实信号或 Quartz drain。既有完整五 boot 入口仍由 root 在获准 GitHub-hosted 环境统一实际运行；本包不登记/启动第二 runner。

## 保留围栏、限制与回退

原 immutable image/JAR、class manifest、网络、原 owner/CID、内存1g/JVM512m/CPU1、1500秒总预算、180秒 ready、20秒 stop/30秒 command、五 boot、原数据库/Redis身份与独立 absence 均保留；失败不能用外容器 gone 替代 quiescence。进程指纹只保存在私有 slot 内存；不把命令行/配置/令牌写入报告。日志原有限分类保持。已有 source/target SQL、原 money、恢复模式及 UNKNOWN 规则不变。

真实 host strace 版本、容器 PID/ptrace 实际行为、Spring/Quartz/JVM 退出、真实 stop0/143、五 boot/备份恢复/cleanup 仍 NOT_RUN；新固定 PID1/实际 FactoryBean 核验会 fail closed，不按官方参考版本推断现场通过。需 root 同源统一验收。无 schema/数据迁移；仅回退此源码 delta 会恢复已知信号缺口，不能据回退放行恢复。

manifest.json 记录四个原/后像 SHA256、Git blob SHA1、code patch hash 及实际有限静态检查。git diff/applycheck/hash/有限 secret 扫描不是测试通过。无现成 gitleaks 可用时，不新增依赖，由 root 做完整扫描。

Root transport only: literal diff context is losslessly base64 encoded. Decoded SHA256 `c2996e6a95587555431fe7cef7959ba9be3aa2133148264a503cc650b19cf35b`; all four source postimages remain exact. Runtime verification NOT_RUN.
