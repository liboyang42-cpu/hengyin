# 固定宿主/资源合同（SOURCE，未执行）

本包基于公共 `fa5c263440573992701263d6bee60830844a49b8`，与旧 C 章节恢复 `5b54677db0c96af4f59070706984bcbb303919cc` 独立。不修改旧 probe、canonical 工具、生产源码、workflow 或 registry。

宿主入口为 `ci/restore_business_reconciliation_probe.py run --resources PRIVATE/resources.json --output OUT --private PRIVATE`。仅显式 GitHub-hosted/Linux、仓库 liboyang42-cpu/hengyin、workflow_dispatch、job `isolated-restore-business-reconciliation`、`CHENGYIN_CI_RESTORE_BUSINESS=true`；HEAD、GITHUB_SHA、资源 sourceSha、JAR git.properties 必须相同完整 40 SHA。此 job 尚未接入，root 单一持有共享 CI 接线。没有可选命令、URL、SQL、profile 或替代 child 参数。

## root 预分配；probe 不另建启动器

- 一个精确自有 internal Docker network：`cy-restore-<32hex>`，label `chengyin.restore-business=<32hex>`。
- 两个独立 MySQL container：`<network>-source` / `<network>-target`，相同 label、各自完整 CID，只接该 network。复用原 authenticated shell 的 pinned MySQL 镜像与本地 Docker wrapper；镜像固定 `mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff`。
- 每个 1 CPU、1 GiB、数据 tmpfs 上限 768 MiB；event_scheduler=OFF、skip-log-bin、performance-schema=OFF，独立 data directory，绝不复制 auto.cnf，因此 UUID 必须不同。禁止同实例两个 schema 冒充恢复演练。
- 两实例各有独立的随机 `127.0.0.1` TCP published port；仅 3306/tcp 可发布，禁止 0.0.0.0、host network 或远端数据库。启动命令/精确 CID、network 的创建/清理由 root 现有 owned shell 模式串行接入；本包只复用 `authenticated_redis_recovery_probe.command` / `require_absent`，不改其原三资源合同，不冒用其 mysql_relay。
- 测试 schema 两侧同名 `cy_restore_<32hex>_funds_core_it`，两侧开始均不存在。源库只由原 RegistrationFunds fixture 创建；目标只创建匹配 DATABASE charset 的空库，随后只准原 canonical restore 导入，禁止 seed/reset 目标。
- 不需 Redis、HTTP 或生产 app。host child 固定 heap 512 MiB，guard child 256 MiB（串行）；总预算 2 CPU/2 GiB MySQL + 一个最多 512 MiB JVM，加宿主 Python/工具开销；drill 内 600 秒 deadline、每个子命令 20–40 秒，外层预算建议 900 秒含最多 135 秒精确清理。资源预算是未来执行限制，未实测容量。
- PRIVATE 新建 owner-only 0700；OUT 独立新建、初始为空。`PRIVATE/provider/store.jsonl` 与 `PRIVATE/backup/backup.sql[.json]` 为不同目录，provider 不在任何 MySQL/SQL dump 域内，恢复从不覆盖它。journal 0600、独立 fsync/hash 链，单文件上限 1 MiB，保留至对账后；不得随目标恢复回滚。

资源 JSON 固定字段：

```json
{
  "scope": "<32hex>",
  "sourceSha": "<40hex>",
  "schema": "cy_restore_<32hex>_funds_core_it",
  "testClasspathFile": "<PRIVATE>/test-classpath.txt",
  "source": {"cid":"<64hex>","uuid":"<server_uuid>","port":33061,"defaultsFile":"<PRIVATE>/source.cnf"},
  "target": {"cid":"<64hex>","uuid":"<different_server_uuid>","port":33062,"defaultsFile":"<PRIVATE>/target.cnf"}
}
```

`resources.json`、两个 defaults 文件及 classpath 文件均为 0600。defaults 只允许 `[client]` 的 host=127.0.0.1、port、user=root、随机 password（24–64 URL-safe 字符）、protocol=tcp。密码只存在 private 文件与 child env，不进入 argv、公开输出、artifact。每侧实际 UUID/version/events 独立 SQL 核对，且 Docker CID/label/network/loopback port/预算独立 inspect。

classpath 由 root 在同源构建后写入：首项固定 `chengyinhub-admin/target/test-classes`，随后同 checkout 各模块 target/classes、test 资源和已有 Maven dependency JAR 的规范绝对路径；Maven 仓库只允许当前宿主 ~/.m2/repository，无新增依赖。不接任意 java flags，拒绝 JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS/CLASSPATH 注入。child 与所有依赖目录/JAR 都记内容摘要；正常发布 JAR 不得含 RestoreBusinessReconciliationFixture 类。root 需在批准 hosted 环境一次准备 test classpath/编译，本会话没有执行这些动作。

## 固定阶段与原工具调用

1. child 只运行 test-classpath 的 `com.chengyinhub.business.service.RestoreBusinessReconciliationFixture`，cwd=admin。它调用原资金 fixture 的 `createDatabaseAndService/resetSchema` 一次，只作用新源库。
2. `BACKUP_READY` 时 child 在 stdin 等待，不持有业务事务；host 独立抓取 typed rows 和高水位，调用原 `scripts/db-backup-restore.py` 的 `backup(args)`。完整 v3 manifest/sha/definitions/trigger/CHECK/AUTO_INCREMENT 原算法与失败条件保留。
3. host 只发送 `AFTER_BACKUP`。child 先保存原 late-success 的外部 SUCCESS，再经真实 order/beginPayment/freeze/fact/callback 提交备份后新订单的原支付与奖励，并重放一次验证不再奖励。`FAULT_READY` 后只发送 STOP，确认原 child 退出；不重启数据库、不修改 journal、不发 provider 请求。
4. host 对空的不同 UUID 目标调用原 `restore(args)`（confirm_test_target=true），不绕过任何原校验；随后独立读取同批完整 typed rows、高水位、receipt/fact/actor 引用、精确积分守恒与 provider journal。
5. 单独 test child 的 RESTORED_GUARD 分支只初始化原 WxPayConfig 默认关闭的真实 SDK gateway 并确认 create 拒绝。它不建库/seed，不注册成功 HTTP，不声称完整 restored app 已安全启动。provider journal 与目标 typed rows在此调用前后必须逐值一致。
6. 所有候选始终 `candidateState=BLOCKED, dispatchRelease=false`，包括本演练正常观察成功。缺失意图、外部成功但未消费、UNKNOWN/高水位风险只读报告，不回填、不调用退款/转账、不重派、不推进 AUTO_INCREMENT。

## 清理与首因

host 只删除本次确认成功创建的精确 schema，删除前重核相同 UUID，之后独立 SCHEMATA=0。创建结果不确定时不采用/删除该库，记录 cleanup UNKNOWN，交外层精确 owned container 清理；原 canonical restore 失败前后保留可取的 private 目标定义证据，不重试导入。不会清理原先存在的库、原断言表或未知资源。

root 外层原 trap 清理两个实际 CID 和 network 后，再运行同 probe `finalize --resources PRIVATE/resources.json --output OUT --original-exit ORIGINAL_EXIT`。须保留 resources.json 至 finalize 完成。finalize 用原 `require_absent` 独立查三个精确身份；网络/权限报错不是不存在，部分清理或原 exit!=0 不变绿。finalized 也只可 `SCOPED_OBSERVATION_COMPLETE_RELEASE_BLOCKED`，不能 READY。之后 root 只移除成功创建的 private 文件并验证目录不存在；private 清理失败须保留原非零 exit，不能上传 raw journal/SQL/child 日志或凭据。root 控制 private evidence 的保留/加密人工排查，公开仅输出摘要及合成 fixture 业务号。

未写 workflow/registry/shared shell；两个实例启动及精确清理属于 root 的唯一接线。此为可调用协议，并非已分配/已执行资源证明。
