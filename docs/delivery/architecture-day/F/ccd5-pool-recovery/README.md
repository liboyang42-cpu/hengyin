# ccd5 独立 SQL 池故障回读

原 run 已 SUCCESS；F 已直接回读原 job 日志，支持本次隔离 SQL 池超时及释放后恢复。artifact ZIP 下载 HTTP 403，尚不能签署原回执、实际 PID 数值及清理完成的独立读回。此交付只新增本目录，不修改源码、workflow、阈值或其他交付目录。

- source/parent：`ccd55005c75d105f8245b0591e63adcd0dd15280`；tree：`45c9c293ea96bf2b907f739a743d58037f22a9bf`。
- 独立工作树：`/workspace/hengyin-f-ccd5-pool-evidence`；分支：`codex/architecture-day-f-ccd5-pool-evidence`。
- [原 run](https://github.com/liboyang42-cpu/hengyin/actions/runs/40140513624) / attempt 1 / job `121011084232`，workflow `.github/workflows/isolated-pool-recovery.yml`。
- artifact：`12579050590` / `isolated-pool-recovery-1` / 11,255 bytes / 7 files。GitHub API 和上传日志一致报告 ZIP SHA256：`0c95af72b13821bf0268e56414f0bc3f5b1f75e5112dabc802ec2cdc0835d1aa`；F 未取得 ZIP bytes，未独立重算。
- GitHub 连接器返回文件引用成功；引用下载实际 HTTP 403。未更换凭据/网络绕路、未启动第二次 run。请 root 取同 artifact 原件补证。

| 核查项 | 原日志直接观察 | 固定源码规定、尚待原 JSON 逐值读回 |
| --- | --- | --- |
| native Druid 等待 | `5001 ms` | native GetConnectionTimeoutException 必须 active=maxActive=20，wait 5000–6000 ms；覆盖池借用等待，不是全链路 SLA |
| overflow | request 20，HTTP `200`，业务 code `500`，emptyData=false，`5.061 s` | 不可改写成 HTTP 503/500；业务拒绝不等于 HTTP 失败 |
| 饱和与释放 | workflow 对唯一 JSON 的 PASS result 校验通过并打印回执 | 20 个 nonce 定向真实 SQL 被表锁阻塞；原 holder 释放确认；20 请求恢复正常；3 次新读取正常；实际 samples/20 个耗时/3 个恢复耗时未读 |
| 同一进程 | 故障前后两次 health 输出 isolatedStartup/db/Redis/liveness/readiness 均 UP；aggregate DOWN 是禁用支付不可用 | 比较三个容器 ID 与 PID/Running/Paused/OOMKilled；PID 实值、前后 checksum/UUID 未读 |
| 隔离/网络 | `24` 个 socket 目标观察，external destinations/DNS attempts=`0`；三个容器 ports={} 且仅同一任务网络 | 源码要求 network Internal=true，nonce/容器/socket 都精确归属；原 network.trace/isolation.log 未读 |
| 任务资源清理 | entry exit=0，清理 trap 已执行到输出 | holder finally 成功才保持 PASS，结果暗示没有其未确认标志；仍须原 JSON 核对。外层 docker rm/network rm 吞错且没有删除后独立 inventory，不能据 exit=0 声称容器/网络删除已独立证明 |

源码顺序为：正常独立字典读 → nonce holder 锁住测试字典表 → 20 个真实阻塞读 → 第 21 个读触发 native 池等待超时 → 原 holder 正常释放 → 已阻塞请求完成 → 3 次新读 → 数据 UUID/两表 checksum 与进程状态比对 → 原进程 health → 网络校验 → finally 清理。入口仅为 `POST /api/common/dict` 的空结果只读路径；不是付款/业务写/恢复 worker 场景。

本 run 以 `mvn -B -DskipTests package` 新打 JAR，仅用于此故障模式。job 日志明确 Tests are skipped，不计作 Java 测试复跑。实际新 JAR SHA256 未出现在本次已读日志，workflow 上传名单也不含 JAR/build identity；source 相同不能证明与此前 4ef3 冻结制品字节相同。JDK 日志为 Temurin 21.0.12+1；测试数据库镜像固定 MySQL 8.0.45，Redis 镜像固定 7.4-alpine，health 实际显示 Redis 7.4.11。

关于配置，隔离命令精确覆盖 maxActive=20/maxWait=5000/socketTimeout=60000；生产 YAML 的 connectTimeout=30000，本探针的 report.profile 字面值写 connectTimeoutMs=5000，但没有相应命令覆盖。因此不将该字段或本场景当作 connectTimeout 的实际运行证明，也没有据此修改任何配置/判据。

F 本轮仅执行只读 Git/API/日志读取、文档与 JSON 格式检查、diff/秘密扫描；未执行 Java、DB、Redis、模型外呼、任何 CI dispatch 或重复标准/存储测试。原 hosted workflow 的 4 项 pure Python controls 在日志为 PASS；它们不替代故障运行回执。

剩余补证仅限同一原 run：下载并验证 ZIP digest；核 pool-recovery.json 的真实 blockedSelectCounts/holderReleaseAcknowledged/heldRequests/recovery/PID/UUID/checksum/finally 标志；核 7 个原文件。外层删除后的资源不存在性与本次 JAR 字节身份，现有上传证据不足则保持 UNKNOWN，不补造。结论最多覆盖局部 SQL 池等待超时及同进程恢复，不覆盖完整容量、所有业务恢复、其他通道隔离、生产负载或 Q4/Q8 完成。
