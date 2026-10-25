# C 完整应用恢复启动：实际 shared 接线（SOURCE / NOT_RUN）

原占位接点已由 F `restore-http-entry-9496` 片填齐，本页同步实际合同，不再等待 argv。这是源码接通，不是启动、业务、清理或恢复验收通过。root 合成供体并冻结后唯一执行；F 没有运行测试/编译/语法解析/资源/HTTP/CI。

- F parent：公共 `9496e7028ef3793ac19a1687f9391fff9ff33e72`，tree `ec60ba4c3f8c327ec393e1d4344d3f43ed6e2b40`。
- C owned 包：`c382451bddbfdaaaa1231a544605685f8863f7bf` + `bec4054ca7e95b2f44a104cd3f39588d700f4f65`。后者仅修无参 SQL caller 并增一个直接控制，当前 26 控制 SOURCE 保原25；F 不复制覆盖它们，workflow 只调用原控制脚本一次，不硬编码计数。
- root 原 helper：9496 已含 target suffix/readiness/relay/四槽 cleanup 和 `restore-runtime-user`。root 另串行修 CREATE USER 参数中 `%` 转义；F 不修改 helper/账户合同。

## 固定入场与真实调用

复用 `.github/workflows/isolated-authenticated-redis-recovery.yml` / `ci/authenticated-redis-recovery.sh`。旧四 choice、PR/default recovery、hosted/repository/workflow/job、原 freshschema/bootstrap/JAR/strace/资源 owner 保留；不新增工作流/job/provisioner/注册类，不改七 cohort/M1。

唯一新增 choice `restore-application-startup`，仅 `workflow_dispatch` 该选择在既有 controls/execution 两个 step 设置 `CHENGYIN_CI_RESTORE_HTTP_STARTUP=true`，其它均 false。旧三个 workload flag 在新选择下均 false。parent **不设置** `CHENGYIN_RESTORE_MODE`，包括不设空值；业务模式只由 C 在每个真实 app 子进程 env 设置，禁止 `-D` 替代。

既有 controls 阶段只在新 flag true 时各一次调用 `ci/test_restore_application_http_probe.py`、`ci/test_restore_application_http_entry.py`。package 仍一次原 `mvn -B -DskipTests package`；无额外 JAR/重复旧测试。

shell 原 source MySQL/Redis/network → native readiness → prepare/fullschema/bootstrap → trace-libs 顺序不变。原第一 app launch 前，新分支使用原 pinned `$MYSQL_IMAGE` 在同 internal network/label 创建唯一 `<NAME>-restore-mysql`，1CPU/1GiB、memory-swap 同上限、768MiB tmpfs、无 publish port，再调用：

```text
python3 -B ci/authenticated_redis_recovery_probe.py restore-mysql-ready NAME SCHEMA OUT PRIVATE_CREDENTIALS 0
timeout --signal=TERM --kill-after=20s 1550s python3 -I ci/restore_application_http_probe.py run NAME SCHEMA OUT PRIVATE_CREDENTIALS
```

target readiness 写 `OUT/restore-mysql-readiness.json` 的 `mysqlReadiness`，不覆盖 source `schema-summary.json`；同版本/network、不同 UUID/CID 和原 relay 身份核对由 root helper 执行。C run 在 canonical import 后自行调用 root `restore-runtime-user NAME SCHEMA OUT PRIVATE_CREDENTIALS 0`；runtime user 只原 startup_probe 四 CRUD，不 root fallback，F 不重复创建。

C 内层1500秒，shell外层1550秒/TERM/KILL。旧25min小于 run 本身预算，仅此新手动模式上限55min，留一次构建/schema/readiness/cleanup总窗口；旧四模式25min不变。上限不是实测耗时/SLO，CPU/内存 profile 不变。

run 成功或失败均经原 EXIT trap：先原四槽 cleanup，再原 private 父目录清理，随后：

```text
python3 -I ci/restore_application_http_probe.py finalize NAME SCHEMA OUT ORIGINAL_EXIT
```

finalize **不带 private 参数**。C run 清自己的 PRIVATE/restore-http，最后 app 停止但留 container 给原 owner 日志及 cleanup。trap 保留最初非零；原状态零但 owner/private/finalize 未确认则转非零。finalize 再核历史 CID、四固定槽/network 缺席及原 owner 四 known CID/independentNoSuch；不以清理成功抹掉原失败。新分支结束，不执行旧 exercise/capacity/gameplay。

## 唯一公共输出与局限

新模式关闭旧 recovery 的日志/trace/schema artifact 上传，只允许新 artifact `isolated-restore-http-application-<attempt>` 内固定三路径，位于原唯一 `cy-auth.*` OUT 目录：

1. `restore-http-application.json`：实际 source/run/attempt/JAR/schema、五 boot/业务观察/原身份、首失败、private/app stop 状态。
2. `restore-http-provenance.json`：同源 JAR/child class postimages/canonical tool、source/target CID、受控 provider 边界。
3. `restore-http-cleanup.json`：原 originalExit、历史 CID/四槽/network absence、原 owner 清理，不授权生产 release。

失败仍保留实际存在的安全部分，缺文件不得补造。`if-no-files-found=error` 只保证至少有输入，不等于三份都已验证；真实通过须原 run/finalize 及 root 逐项回读。新模式不上传 entry/application log、whole OUT、private/dump/trace、runtime-user/原 readiness。旧四模式 artifact 名称/路径/行为保留。

C full-app 场景限 task-owned complete 提现原ID读回及晚到支付事实 gap；provider 为受控 transport，不是真渠道/验签。源码齐不证明执行成功，不签全域T20/Q4/Q8、生产 dispatch fence、RPO/RTO或解除恢复授权。全部等待 root 同一冻结 SHA 最终验证。
