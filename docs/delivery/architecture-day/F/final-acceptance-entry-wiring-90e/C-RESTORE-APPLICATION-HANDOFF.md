# C 完整应用恢复启动：唯一保留接点（未接通）

本页只记录 root/C 已给出的固定合同，不能据此派发、生成假字段或当可运行入口。现有 `recovery` 和 PR 默认行为保留；7 cohort 不变、不代表完整应用启动。

已收到的真实合同：

- 复用 `.github/workflows/isolated-authenticated-redis-recovery.yml`、`ci/authenticated-redis-recovery.sh` 和原 authenticated resource owner；不得新写 provisioning/第二套 fullschema/app 启动器。
- 唯一新增手动 choice：`restore-application-startup`；仅此 choice 令 `CHENGYIN_CI_RESTORE_HTTP_STARTUP=true`，其余默认/PR 为 false。这个布尔量仅为测试入场，不能登记为 DDL/业务审批。
- C 已给 `ci/restore_application_http_probe.py run NAME SCHEMA OUT PRIVATE_CREDENTIALS`；finalize 沿同一身份并加 `ORIGINAL_EXIT`，最终 argv、handler、字段/安全输出名单仍待 C 源码确认。
- root 已交公共 preparation `9a99c208010147bf7227f078147c83f7ab4c3cc9`（parent `90e18550ae75e83641e85bf4bb218f08922c7d3e`，tree `2c243d923ee7747b48d0382df9336b73d7c22f44`）的原 owner helper 扩展；仅 SOURCE，非完整候选或运行证明。唯一 `<NAME>-restore-mysql` 的 exact label/internal network、1CPU/1GiB/tmpfs768MiB、独立 UUID/同版本、无 publish port 仍由 root 完成宿主接线；F 不编辑 resource owner。
- 原 Redis 留同一登录主体，原 app slot 逐次真实启动。父进程 `CHENGYIN_RESTORE_MODE` 必须 absent；C 在每个真实 app 子进程的 env 分别设 true/invalid/empty，不能设置 Spring `-D` 冒充 `System.getenv`，不能导出空串给整个 workflow。

最终原 workflow review.patch 必须原子包含：固定 choice、check/execution 两个 step 的同一布尔入场、真实 C check/runner/finalizer 调用、只有 C 确认安全 JSON 路径的上传。shell 在原 prepare/trace setup 完成后、原第一 app launch 前选择 C handler；不同时跑旧 exercise 或另 provision资源。执行原非零先保存；即使 run 失败也沿原 owner 清理并调用确认后的 finalize，原非零不能被成功 finalize 覆盖，UNKNOWN cleanup 不得 PASS。私有 credentials、原响应/SQL/日志控制符与秘密不可扩进公共路径。

当前未填：C 最终 public SHA、完整 handler/检查命令、finalize 精确 argv、JSON schema/path、安全 upload 白名单、source/JAR/run/attempt/PID/UUID/actor 的实际字段。收到真实 source 后，只在这一接点追加窄 review.patch；不预注册新的 Java class/count，不造 output JSON。不提供只加 choice 却仍运行旧 recovery 的半成品补丁，以免模式名称误签覆盖。此缺项不阻止本目录其余已完成 source/收据绑定增量交 root 应用。

## 已逐字回读的 root helper 接口（9a99，未执行）

实际文件 `ci/authenticated_redis_recovery_probe.py`，SHA256 `39a016ce87872a914b76183c4b383aa69774235fb17c2c69c23c31efa8c8a65e`；配套控制源码 `ci/test_authenticated_redis_recovery_probe.py`，SHA256 `e9d6fb8058faca12983438e3ab4d9c7ee5f33c4dc7ffabd2d0eca8139cbbda0e`。F 仅 `git show` 回读和摘要，没有 import/测试/启动，不复制覆盖这两个文件。

| 实际接口 | 固定调用合同 |
|---|---|
| `mysql_endpoint(name, suffix='mysql')` | suffix 只允许 `mysql` / `restore-mysql`，返回原 `id/networkId/ip/pid`。目标槽额外检查 pinned image；不能将任意 container/address 传入 |
| `mysql_relay(name, baseline, suffix='mysql')` | baseline 是对应 readiness 内的 `mysqlReadiness` 对象，消费其 `endpoint`；绑定前、每连接和关闭后继续核相同身份。不手填端口/IP，不绕原 loopback relay |
| `mysql_ready(name, schema, out, private, port, suffix='mysql')` | 目标 suffix 写 `OUT/restore-mysql-readiness.json` 的 `mysqlReadiness`，原 source 仍 `schema-summary.json`；目标 result 必须 `OWNED_MYSQL_TCP_READY`、与 source 同 version/network 且 UUID/CID 不同 |
| CLI `restore-mysql-ready NAME SCHEMA OUT PRIVATE_CREDENTIALS 0` | 仅 `workflow_dispatch` 且固定 restore flag true 入场；原固定 NAME/SCHEMA 校验及 local hosted daemon 检查保留。不能拿旧 source readiness 顶替目标回执 |
| 原 `cleanup(name, schema, out, private, port)` | restore flag true 精确处理 `app/restore-mysql/mysql/redis`，普通仍三槽。目标核 pinned image/label/network，并在 readiness 已记录 CID 时核同 CID；按 CID 和名称回读不存在。原异常/UNKNOWN 仍阻断 |

原 `validate_execution_env` 已拒绝 restore 模式与 capacity/gameplay 组合，并要求 parent 中恢复业务变量 absent。后续 workflow 只将新 choice 映射为固定 flag，其余三 workload flag 均 false；不为了新接点覆盖原 helper 或放松 complete verifier。root/C 的 private 描述和 app-launch/runtime-user 需求仍待最终来源，未假定 9a99 已实现 app handler/宿主目标 provisioning。
