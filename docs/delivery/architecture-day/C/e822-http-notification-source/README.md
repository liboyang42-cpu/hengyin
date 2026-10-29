# e822 两项实际失败的 SOURCE 增量

父提交：`e82229ef85e58159078a42956560356074192541`。独立分支：`codex/architecture-day-c-http-notify-diagnostics`。

本包只补原 run 的失败诊断及真实通知夹具前提，不修改生产、共享 auth owner、F shell/workflow、迁移文件或 canonical backup 工具。

## 恢复 HTTP：诊断不足，未认定当前 ValueError 已解决

实际证据为 run `40228826843` / job `121275974779`：SOURCE_WORKLOAD、ValueError、attempted=true、原结果未完成。它不证明 Spring 启动失败，也不允许 no-start waiver。

- 原调用链保持 `run → start_application → ApplicationSlot.start/http/stop → 原 owner cleanup → finalize`。
- 在原原子 JSON 写入点记录有限 substage，逐一标出真实管理员登录、身份读取、原章节命令、提现、原号查证、SQL 完整事实、原回执和停止步骤。
- 只在 `start()` 实际返回后记 `readyBootObservations`。它不是 completeBoots/checks，也不参与放宽成功条件。
- `firstFailureDiagnostic` 仅导出固定 phase/substage、异常类型和有限 reasonCode；同样的安全字段进入 job stderr。原异常仍抛出，停止异常另记，不能覆盖首因。
- `lastHttpObservation` 只有已限定 route/method、整数 HTTP status/businessCode，不含 query 原 ID、body、msg、token 或参数。
- 停止条件不变；失败前保存实际 Running/Pid/ExitCode/OOMKilled/Restarting。退出码 137 等仍被原谓词拒绝。
- 由当前精确 CID/label/network 读取原容器末 160 行日志，只在已有 application JSON 内保存最多 1MiB 摘要的长度/hash、固定 marker 和固定异常类型。没有原文、SQL、异常 message/stack、凭据或新增 artifact。原 owner 继续持有原日志及四槽精确清理职责。
- 五个真实 boot、原 source/JAR/UUID、经济行/高水位、provider 原号、guard/UNKNOWN、停止及 finalize 接受谓词全部保留。诊断 UNKNOWN 不可替代这些证据。

现有 `ci/test_restore_application_http_probe.py` 的原 37 个方法逐字保留，新增 9 个直接控制 SOURCE（共 46）：安全 reason/type、持久 checkpoint、不把 ready 当完成、HTTP 有限诊断、异常响应、日志白名单与截断、异常停止状态、日志原 owner、无 CID 不代表不存在。未运行。

## 同窗故障隔离：真实 member reader 的 canonical 前提遗漏

实际证据为 run `40228802075` / job `121275986821`：原 case 第 57 行 `poisonEntered.await(5s)` 返回 false，票据前提已越过，资源 CLEAN。

SOURCE 可证明的路径为 `runDueScheduled → dispatch → deliverSubscribe → WxSubscribeMsgServiceImpl.notifyOfficialBroadcast → openidOf → UmsMemberServiceImpl.selectUmsMemberById → UmsMemberMapper.selectUmsMemberVo → post → loopback poison latch`。

当前 mapper 完整投影含 `app_open_id`，原 `base_schema_20260818.sql` 的 ums_member 无该列；夹具没有执行仓库现成 `migration_app_open_id.sql`。这会在 HTTP post/latch 前阻断真实 reader，是 SOURCE 发现的具体装配缺口；没有原内部日志，不宣称它是该 run 唯一原因。

- `CrossDomainStorageFixture.open()` 加入现成迁移，只用于其未来精确自有 schema，不改任何生产 DDL。
- 原真实 member service/mapper 注入不变，发送前显式读回当前 member、wx_open_id，并断言 app_open_id 没有猜测回填。
- 原 5 秒 latch 谓词保持，失败消息增加实际 provider post 次数、原 subscribe receipt 状态、dispatch 是否结束/异常类型和受控 server 异常类型；不打印异常正文，不以诊断代替正向。
- 原一个 case、150 秒总预算、同一调度队列、真实 HTTP timeout、healthyWhilePoisonHeld、原经济身份、UNKNOWN 不重发、恢复后新广播和精确 MySQL/Redis cleanup 均保留。没有新增 mock 成功、并行通知 dispatcher 或时间预算。

## 验证与交付

仅进行 Git 差分/文本核对、SHA-256 后像和新增内容的有限秘密模式扫描。没有执行 Node/Python 控制、parse/import、Java/编译、数据库、HTTP、容器、恢复、故障注入或 CI；全部运行验收 **NOT_RUN**，由 root 同源安排。

`postimages.sha256` 列出 5 个 SOURCE 文件与本说明；manifest 自身由提交/tree 绑定，不列自引用。完整补丁为该提交相对父提交的 Git diff。原生产和其它 owner 文件未改。
