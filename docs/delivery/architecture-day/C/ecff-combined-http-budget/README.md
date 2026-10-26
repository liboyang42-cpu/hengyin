# controlled-combination 固定硬预算接线 SOURCE

Parent：`ecff37372bfae059faa38e9eb420894cde1bcee9`。独立分支：`codex/architecture-day-c-combined-http-budget`。

## 所有权与完整交付

新增窄 owner `ci/combined_http_budget.py`、直接纯控制 `ci/test_combined_http_budget.py`（18 方法）及 `ci/CombinedHttpNamespacePortProbe.java`。共享修改**仅**位于本目录 `budget.review.patch`，没有编辑实际 shell/workflow、F 的 Java/selector/计数/entry、E restore 或生产源码。

patch 只门控精确 `CHENGYIN_HTTP_E2E_TESTS=CrossDomainCombinedLoadMySqlIT`。helper 同时要求原 hosted/manual/repository/full SOURCE_SHA 和实际 `HTTP_SCOPE=controlled-combination`。原 all-three/Generic/Freeze/reuse 代码留在原 else 路径，不加入新白名单或新 scope 选择器。该 patch 必须由 root 与 F 的唯一 scope/白名单/一次 package 准备片**按 hunk 串行合成，不得整文件覆盖**。

## 精确调用合同

1. 原 workflow 的新 class 资源分支使用原 pinned MySQL，限制 1CPU/1GiB/no-swap/256 PIDs，原数据目录改为本任务 tmpfs768MiB；仅此分支创建同 label 的 internal network，记录 `HTTP_MYSQL_CONTAINER/HTTP_MYSQL_CID/HTTP_BUDGET_NETWORK/HTTP_BUDGET_NETWORK_ID`。旧分支保持原样。提前准备原 pinned JDK/Redis image；helper 不 pull。
2. 原 shell 仍创建原 fresh schema/report。F 在**调用 prepare 前**显式 export 原 gate `B_PAYMENT_FACT_DDL_APPROVED=1`、`CHENGYIN_AI_REDIS_IT_DATABASE=15`、`CHENGYIN_AI_REDIS_IT_PORT=$CHENGYIN_HTTP_E2E_REDIS_PORT`；C 仅校验/传递，不赋这些授权 selector。端口沿用原已验证显式值，不限定为 6387。
3. 仅新分支 `TEMP_DIR=$(mktemp -d /tmp/cy-http-budget.XXXXXXXX)`。在原 redis-start 调用 `python3 -I ci/combined_http_budget.py prepare "$TEMP_DIR"`；它只创建这个任务的私有 Redis，不建库/表/账号/应用 fixture。原 MySQL/schema owner 不变。
4. 在原 java-tests 调用 `python3 -I ci/combined_http_budget.py run "$TEMP_DIR"`，原 `JAVA_STATUS` 捕获真实 Maven exit。使用原前置同源 package/test-compile 输出及 cache，内部执行固定 `mvn -o ... -pl chengyinhub-admin -am test -Dtest=CrossDomainCombinedLoadMySqlIT`，不再次 package、不指定其它命令/class、不在线下载。原 admin POM 的 initialize dependency-properties 与 Mockito startup javaagent、Surefire fork=1 保持。新分支的 `OUT/run.log` 由 helper 专用保存原容器日志；F 的前置 package 输出须使用另一 private preparation log，不能混入此文件或重复覆盖。
5. EXIT cleanup **先**调用 `finalize "$TEMP_DIR" "$ORIGINAL"`，再做原 schema cleanup/finalizer。保留原 exit，budget/cleanup 拒绝不能改绿。新 Redis marker 从 `read_redis_cleanup_proof()` 导出真实内部 namespace 清理结论，**不调用 host socket.bind 冒签内部端口**。只有两个实际容器的独立删除均证实后，`read_private_cleanup_proof()` 才允许擦除私有 journal；未知保留私有材料、不 artifact。
6. 原 workflow 最后只对新 class 调用 `python3 -I ci/combined_http_budget.py cleanup-mysql`，重核实际 CID/name/source/run/attempt/image/network，再清其 MySQL 和已空的精确 network；保留旧类原 cleanup。create 中途只建 network 时也必须核真实 network ID/labels、MySQL 名确实不存在后才清，不能默认 UNKNOWN=absence。

## 两个地址空间与硬预算证据

- 原 host schema/bootstrap/cleanup 仍用 **127.0.0.1:33080**。Maven/test 与私有 Redis 使用 `--network=container:<原 MySQLCID>`，内部 SQL 显式 **127.0.0.1:3306**。helper 通过两端真实 MySQL CLI 查询同 UUID/version/port，密码仅走私有 stdin；无 argv/公开 env。容器内部只收 3306，不把 host 端口误送给 Generic initializer 或 domain fixture。
- Redis 与应用处于同一个原 MySQL namespace，仍使用同原端口和 DB15。原 loopback-only Java 守卫不改；该 namespace 所属 network 必须真实 internal。没有 host network、LAN/provider 地址、Docker socket/host settings/host HOME/SDK secret 挂载。
- 原 pinned JDK image 中的 Maven 父进程和真实 Surefire/JUnit 子 JVM 共处一个 **1CPU/1GiB/no-swap/256 PIDs** cgroup。实际 HostConfig、host PID/startticks、namespace PID、cgroup v2 `memory.max/swap.max/cpu.max/pids.max`、OOM counter、实际 image ID 均核验；不使用 heap/ActiveProcessorCount/stats 冒充硬上限。Maven heap128MiB、test heap512MiB 是内部配置，**不是**预算证明。
- 收集真实 Maven 与 Surefire Java 进程，验证 booter 本地 classpath、原 Mockito agent jar、class trees/所有 reactor JAR/原 packaged admin JAR/git.properties/source/hash。所有预编译输入前后必须一致。它仍是原 HTTP test classpath，**不是 packaged production application 的容量验收**。
- 原 Maven 结束后保留原容器最多约 120 秒取证；不重启、不重跑。删除精确 Redis CID后，在**这个已执行真实 workload 的原容器**用 JDK single-source 调用小 probe，真实绑定同 namespace 的原 loopback 端口。probe 只接该已校验端口，无 provider/业务代码/新依赖，不算 JUnit subject PID。之后 gate 让原 shell 按原 Maven exit 退出，记录实际 exit/OOM，再删除原 CID/name 并独立查 absence。异常超时仅停止/删除该精确任务对象，不 kill 任意 host PID、不 prune。
- 任务 Redis 限64MiB/no-swap/1CPU/256 PIDs；不执行 FLUSHDB/FLUSHALL。原随机 MEMBER、exact key ownership 和实际业务断言都由原 F fixture 保留。

## F/root 的精确结果接缝（不猜 F JSON 嵌套）

原 entry **先**验证原 XML、schema/Redis cleanup、唯一有界安全 stdout projection/source/JAR/run/attempt/class。对新固定 class，**脚本内 entry finalize** 在原 verified receipt 目录调用本库 `require_subject_budget(...)`；这时 workflow 的 MySQL/network 外层删除尚未发生，不能提前要求 storage-cleanup JSON。

**workflow 清理结束并下载 artifact 后的 summary** 再使用下列同参数的完整入口：

```python
require_final_budget(
    receipt_directory,
    {"sourceSha": source_sha, "runId": run_id, "attempt": run_attempt, "class": "CrossDomainCombinedLoadMySqlIT"},
    java_pid,              # F 原安全 projection 的 ProcessHandle.current().pid()
    original_jar_sha256,   # F 已核原 JAR
    original_database,    # 原 receipt["database"]，不能来自另一局或另一个 schema
)
```

这是精确 ABI，不复制/编辑 F 的 parser。root/F 只需把**已核**标量传入（source/run/attempt/class 为原规范字符串，java_pid 为正整数）；该函数不解析原 raw stdout。它严格读取同目录两份有长度上限的 budget/storage JSON，匹配实际 Surefire **namespace PID**（不是 host PID），查实际 cgroup/source/JAR/cleanup/数据库。缺文件、错 source/run/attempt/PID/JAR/schema/未知 cleanup 一律失败。新证明从不把 PARTIAL、wholeSystemCapacityAccepted=false、q4q8Complete=false 翻成全系统通过。

公开只增加 `combined-http-budget.json`、`combined-http-storage-cleanup.json`，不含 inspect.Env/argv/rawlog/私有路径/密码。原 run.log 仍 private，供 F 原安全投影 verifier 使用；私有 env/settings/journal 不进 artifact。

## 限制与 NOT_RUN

原 approved Linux hosted Docker default unix socket/cgroup v2 和可读取的本任务 `/proc` 是必要前提；缺失不降级为 host Java。原前置 package/cache 必须已有 Surefire 3.5.6 runtime provider 与 Mockito 等实际依赖。`-DskipTests` 本身不保证动态 provider 冷缓存已经完整；本入口明确预检并在缺失时拒绝，**没有新增依赖/自动下载/fallback**。root 若冷 cache 未齐，应在原已批准准备 owner 内准备其现有依赖，不能把缺项冒充业务失败或取消 offline。

控制 SOURCE 覆盖固定 gate/原动态端口/DB15、私有 env/实际 mount、budget/cgroup/foreign/missing ID、原 Mockito fork、bad-budget 仍只清 own CID、foreign 不删除、daemon 错误不是 absence、原非零/OOM/未知不绿、host bind 不能代替 namespace、wrong source/RedisCID/subjectPID/JAR、private journal 未证清理不得删除。

仅做源码文字核对、Git apply --check/差分和 hash/有限秘密模式扫描。没有执行任何 parse、控制、Java/Maven、容器、DB/Redis/HTTP/故障/CI；**全部运行验收 NOT_RUN**。这份 SOURCE 不证明 Q4/Q8 或新组合实际通过。
