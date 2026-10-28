# C · 原 restored full schema 上完整 Spring HTTP 启动 SOURCE

结论：本包新增完整应用启动/真实 HTTP/实际 Quartz 唤醒的固定验收源码，不替换 C568b 七 cohort、原 37+35 控制或 C5b54 物理终止包。**全部 NOT_RUN**：没有编译、解析执行、Node/Python 控制、Java、DB、HTTP、容器、故障注入或 CI。

基线 parent `90e18550ae75e83641e85bf4bb218f08922c7d3e`，独立分支 `codex/architecture-day-c-restore-http-app`。原 owner 的 suffix 接缝依赖 root `9a99c208010147bf7227f078147c83f7ab4c3cc9`；`restore-runtime-user` 是 root 随后已写、尚待发布/组合的固定 phase，按 root 真实给出的 CLI 接入，不在 C 重建账户 helper。

## Owned 文件与原入口

| 文件 | 实际职责 |
|---|---|
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RestoreHttpApplicationChild.java` | 仅 test classpath 的固定主类，启动原 RuoYiApplication、安全过滤器、真实 MVC/服务/事务/MyBatis/Quartz；观察原调用/异常和 mapper statement，不替换业务结果 |
| `ci/restore_application_http_runtime.py` | 复用原 authenticated app 槽位、固定 JAR/JDK/strace/network/user/预算；仅更换这一个 app 子进程，不 provision MySQL/Redis/network，不开放端口 |
| `ci/restore_application_http_probe.py` | 两份原 readiness → 原 relay → 原 canonical backup/restore → 原 runtime-user phase → 固定五个真实 app 启动/SQL 前后像/原 HTTP → 脱敏收据 |
| `ci/test_restore_application_http_probe.py` | 25 个直接控制方法 SOURCE；原 owner gate 的单项测试明确隔离新增 gate，不能冒充真实资源/HTTP/guard 验收 |
| `host-call.review.patch` | 原 shared shell 唯一 root owner 的精确调用建议，C 未修改脚本、workflow、registry |

既有 Java/资金/SDK/guard/SQL/权限生产源码零修改，原测试声明和断言零修改。未新增运行依赖、权限或生产入口。

## 固定 runner 合同

只由现成 `.github/workflows/isolated-authenticated-redis-recovery.yml` / `ci/authenticated-redis-recovery.sh` owner 调用；manual 固定 `probe_mode=restore-application-startup`，`CHENGYIN_CI_RESTORE_HTTP_STARTUP=true`。原 `CHENGYIN_CI_AUTH_REDIS_RECOVERY=true` 及原 hosted/workflow/job/repository/source/run/attempt 围栏继续必需；新模式与旧 gameplay/capacity 模式互斥。默认/PR 不导出新模式空值。

```text
python3 -I ci/restore_application_http_probe.py run NAME SCHEMA OUT PRIVATE_CREDENTIALS
# 原 owner 四槽 cleanup 完成、原 originalExit 保留后：
python3 -I ci/restore_application_http_probe.py finalize NAME SCHEMA OUT ORIGINAL_EXIT
```

- NAME 原 `cy-auth-<32hex>`；SCHEMA 原 `cy_e2e_auth_<同32hex>`，不借库，不另建 schema 命名体系。
- 原 source MySQL/Redis/internal network/bootstrap/JAR/strace/lib 准备顺序不变。新增 `<NAME>-restore-mysql` 仅由 root 原 owner 用原 pinned MySQL image 创建，无公开端口、同 label/network、不同 UUID/CID、同版本、1 CPU/1 GiB、768 MiB tmpfs。原 source 也是 1 CPU/1 GiB，app 1 CPU/1 GiB/512 MiB heap，Redis 原 64 MiB。四槽并存固定约 3.1 GiB 上限；probe 1500 秒、建议 shell 1550 秒硬终止边界。
- root 原 `restore-mysql-ready NAME SCHEMA OUT PRIVATE_CREDENTIALS 0` 先完成。probe 读 `OUT/schema-summary.json` 与 `OUT/restore-mysql-readiness.json`，均按原真实 `mysqlReadiness` 及 endpoint 身份使用；所有连接仍是原 `mysql_relay(..., suffix='mysql'/'restore-mysql')`，连接前/每连接/后 CID/network/IP/PID 围栏不变。
- PRIVATE_CREDENTIALS 原 600 JSON 字段不变：password/admin 密码、source serverUuid、schema、redactions。仅往既有 redactions 追加本 fixture 的 token/临时签名 secret，供原日志清理使用；没有新私有资源描述格式。
- canonical dump 不含 mysql.user：import 严格验证后，probe 调用 root 原 `restore-runtime-user NAME SCHEMA OUT PRIVATE_CREDENTIALS 0`；再核 `restore-runtime-user.json` source/schema/UUID/CID/用户名/result，及实际 SQL 四 CRUD/无 GRANT OPTION/无全局额外权限。应用所有 boot 固定 `startup_probe` / 原 synthetic-local-only，不是 root。
- JAR 仍是原 `chengyinhub-admin/target/chengyinhub-admin.jar`，只读；读取当前 pom 对应 `BOOT-INF/classes/git.properties` 的真实 full SHA，禁止子类进入发布 JAR。只把本子类及其 nested `.class` 从同源 test-classes 复制到私有固定 loader path，逐文件摘要进入 provenance；不挂其它测试目录/Mockito/test runner，不换打包属性。
- 实际 app 每次 Docker `-e CHENGYIN_RESTORE_MODE=<精确值>`，并核 inspect.Env + child 实际静态 guard 状态、实际 `/proc/PID/cmdline`、不同 bootId/CID、同 JAR/SHA、真实 datasource UUID/schema。不是 `-D`，不是同 JVM 关 SpringContext。

## 固定五 boot、真实业务与判定

1. **source false**：任务自有 full fresh schema 中暂停原 sys_job cron（只这一隔离夹具），注册/复用两个现有白名单任务且保持暂停。所有 boot 明确 `chengyin.jobs.enabled=true`、`spring.quartz.auto-startup=true`；不能因原 startup 配置默认关闭任务而过门。
   - 通过原 UmsMemberMapper 建两名测试主体、AppLoginService 建原 JWT/Redis session，不注入 principal。admin 通过原 `/captchaImage`、原 Redis CAPTCHA oracle、原 `/login`；权限过滤器不替换。
   - 真实 `/api/club/lead/unlock-chapter` 提交一个原稳定 command，真实 `/unlock-chapter-receipt` 读 APPLIED。
   - 真实 `/api/wallet/withdraw` 发起固定 30.00，真实 `/business/cashwithdraw/requery/<原id>` 收敛原 SUCCESS。原 service/mapper/admission/事务产生实际 DEBIT/SUBMIT_INTENT/ACCEPTED/SUCCESS 和 master immutable V1；成员初态 100.00 为明确 fixture opening balance，实际一次扣至 70.00。
   - 仅 SDK transport 这一边界为受控：test-only observer 对**唯一 source seed boot、固定3000分、固定synthetic mch/app、实际 CashWithdrawTransferIntent**保存 fsync 的独立不可覆盖 provider 原 wire/编号/两种 V1 的原字段，首次接受一次，原号查询返回该原记录。不是微信 SDK/验签/真实渠道验收；所有其它 transport 调用记失败，不按 disabled 配置拒绝当 guard。private provider store 不在 DB dump 里。
2. source app 真正停止后，原 canonical 工具 v3 backup；不同 UUID、相同版本、显式空 target、原 schema SHOW CREATE、原 manifest/hash/definitions/rows/checksum/trigger/AUTO_INCREMENT 全部保持，经原 tool.restore 完成后再独立逐表 typed raw 行与高水位对照。
3. **target false**：只在上述 actual complete 原事实已被独立核对后显式新 boot。只读原 actor/原 chapter receipt、原提现列表，以及对已终态原提现 ID 的合法 requery；无新 economic intent。整行/编号高水位/独立 provider 文件全部不变。此为 `test_owned_readback`，不是生产解除恢复模式或全域放行。
4. **target true / invalid / 空串**：三个独立新 app boot，沿同原 actor/token/command/编号：
   - 原 receipt 可读；B 不能读 A；同 key 改 target 拒绝且 DB 零变。成员 JWT 不能访问 admin 观察入口。
   - 原钱包写入口和后台退款写入口真正经过原 actor/参数/权限并进入实际 service；必须观察到各原 owner 的真实 `RestoreDispatchGuard.Blocked`，不是仅 HTTP503 或“支付没配置”。所有业务/经济行及拒绝前后高水位不变。
   - 在恢复模式下通过原 CommittedOfficialNoticeService 原事务登记一个真实 PENDING 通知/原 IM intent，然后原 CommittedBusinessNotice wake 必须抛真实 Blocked。后续 boot 只唤醒该同一原 id，不反复 insert。
   - 真正调用 `/monitor/job/run` 手动运行原 `beforeStartNotifyTask.run()` 与 `committedOfficialNoticeTask.deliverPending()`；必须有实际任务/原 notice owner enter+return、真实 sys_job_log 成功记录。既有实际 PENDING 通知/所有经济行不变；真实 factory 的只观察 MyBatis interceptor 证明没有进入 pending selector。若 before-start guard 被删，其实际 TeamGroup sweep 会被原观察捕获并判失败，即使 sweep 恰为空。
   - 通过 test-classpath 限定、admin 鉴权的固定 fixture callback 入口，把一个明确 historical unpaid fixture 的受控原 SUCCESS 外部事实 fsync 到独立文件，再调用实际 WechatVerifiedPaymentFactService.recordVerified 和实际 CmsRegistrationService.handleWechatPayCallback。事实/原 WAIT inbox/immutable link 实际持久可由鉴权 HTTP 和独立 SQL 读回，原 outcome 必须 FAIL，报名/余额/消费事实不推进；重复仍原身份。没有仿造 SDK 验签或返回假渠道 ACK。
   - 一旦引入这个晚到事实差额，后面**不再有 false boot**，始终 gap blocked。不能回填资金意图、重发、改号或 READY。

原工具/7 cohort不动。这里的 positive 是 actual complete withdrawal 的 full-app 原 ID 读回；gap 是本隔离 fixture 的已核晚到事实未被业务消费。不是覆盖退款两V1、AI/voice、生产定时全域或真钱边界。

## 证据、清理、准确限制

公开仅固定三份 JSON：`restore-http-application.json` / `restore-http-provenance.json` / `restore-http-cleanup.json`。root/F artifact whitelist 应只包含它们（其它原 readiness/log/用户权限收据可供内部接线，不整体上传 OUT）。字段绑定 source/run/attempt/JAR/类后像、实际 UUID/CID/PID/boot、原业务 ID、原 command 摘要、typed rows 摘要/计数与原 AUTO_INCREMENT、实际 timeline/金额/一次 provider submit。没有密码/JWT/phone/openid/原 callback payload/raw dump。

只排除原确实应变的 `sys_oper_log` / `sys_job_log` / `sys_logininfor`；其它 canonical base table 整行与 high water 全比。晚 callback 只允许原事实/inbox/link 精确 append；原 `INSERT IGNORE` 可保留未用的 allocator 前进，因此仅这三个实际表在合法 callback replay 中允许原行完全不变且 allocator 单向前进，原 before/after 实值保留；**拒绝写、任务唤醒、normal 原号 readback不享受这个例外**。

run 末先停止最后一个确切 app CID，保留 stopped container 给原 owner 收日志，再删除自己唯一 700 私有子目录（失败不把 UNKNOWN 改绿）。原 PRIVATE_CREDENTIALS/redactions 留给原 cleanup；原四槽 cleanup 后再 finalize。finalize 不读私有文件，逐一核历次 CID/四固定槽位/network 的 exact NoSuch、root 原 cleanup 的精确四 known CID / independentNoSuch，原 originalExit 非0绝不变绿。未知 app stop/私有清理/资源 absence 均失败，原首因不被 cleanup 成功抹掉。

每次 strace 使用原 network verifier，仅本次选定 MySQL + 原 Redis + loopback 可达；没有 host 凭据挂载或可配置 URL/脚本/profile/image。所有 provider 从根本上被 internal network 隔离；受控 transport 若在预期之外被进入，不能当 production guard 生效。

源码现在不声称实际启动/业务/清理通过，不给生产 RPO/RTO/SLO。未来 run 的 backup/restore/readback 时间是真实观察值；productionDispatchRelease 始终 false，allDispatchDomainsFenced 明确 NOT_CLAIMED。共享 shell/workflow/原 runtime-user phase 需 root 串行组合；SOURCE 此刻不意味着独立 branch 已有这些 root 增量。
