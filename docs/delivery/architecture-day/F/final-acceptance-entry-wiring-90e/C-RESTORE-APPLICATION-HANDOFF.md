# C 完整应用恢复启动：唯一保留接点（未接通）

本页只记录 root/C 已给出的固定合同，不能据此派发、生成假字段或当可运行入口。现有 `recovery` 和 PR 默认行为保留；7 cohort 不变、不代表完整应用启动。

已收到的真实合同：

- 复用 `.github/workflows/isolated-authenticated-redis-recovery.yml`、`ci/authenticated-redis-recovery.sh` 和原 authenticated resource owner；不得新写 provisioning/第二套 fullschema/app 启动器。
- 唯一新增手动 choice：`restore-application-startup`；仅此 choice 令 `CHENGYIN_CI_RESTORE_HTTP_STARTUP=true`，其余默认/PR 为 false。这个布尔量仅为测试入场，不能登记为 DDL/业务审批。
- C 已给 `ci/restore_application_http_probe.py run NAME SCHEMA OUT PRIVATE_CREDENTIALS`；finalize 沿同一身份并加 `ORIGINAL_EXIT`，最终 argv、handler、字段/安全输出名单仍待 C 源码确认。
- root 正在原 owner 内补唯一 `<NAME>-restore-mysql`，原 exact label/internal network、1CPU/1GiB/tmpfs768MiB、独立 UUID/同版本、无 publish port；loopback relay/private credentials/CID/精确 cleanup 均 root 拥有。F 不编辑它。
- 原 Redis 留同一登录主体，原 app slot 逐次真实启动。父进程 `CHENGYIN_RESTORE_MODE` 必须 absent；C 在每个真实 app 子进程的 env 分别设 true/invalid/empty，不能设置 Spring `-D` 冒充 `System.getenv`，不能导出空串给整个 workflow。

最终原 workflow review.patch 必须原子包含：固定 choice、check/execution 两个 step 的同一布尔入场、真实 C check/runner/finalizer 调用、只有 C 确认安全 JSON 路径的上传。shell 在原 prepare/trace setup 完成后、原第一 app launch 前选择 C handler；不同时跑旧 exercise 或另 provision资源。执行原非零先保存；即使 run 失败也沿原 owner 清理并调用确认后的 finalize，原非零不能被成功 finalize 覆盖，UNKNOWN cleanup 不得 PASS。私有 credentials、原响应/SQL/日志控制符与秘密不可扩进公共路径。

当前未填：C 最终 public SHA、完整 handler/检查命令、finalize 精确 argv、JSON schema/path、安全 upload 白名单、source/JAR/run/attempt/PID/UUID/actor 的实际字段。收到真实 source 后，只在这一接点追加窄 review.patch；不预注册新的 Java class/count，不造 output JSON。不提供只加 choice 却仍运行旧 recovery 的半成品补丁，以免模式名称误签覆盖。此缺项不阻止本目录其余已完成 source/收据绑定增量交 root 应用。
