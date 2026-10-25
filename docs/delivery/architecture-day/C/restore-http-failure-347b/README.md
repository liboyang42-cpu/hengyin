# C · fullrestoreHTTP 347b 失败生命周期 SOURCE

parent `347bcd0226c3bed71616e38ea7c1391fc76efe5c`。仅 probe、runtime 的原 atomic writer 接线、同文件直接控制 SOURCE 及本 C 交付目录；F authowner/workflow/entrycontrol、Java/业务/驱动依赖不改。原 26 个控制方法及断言保留，新增 11 个生命周期控制方法 SOURCE；全部 NOT_RUN，未解析、导入、编译、测试、启动资源或发起 CI。

实际触发：root 给出的 run40220857316 在首次 boot 前 ModuleNotFoundError，原业务 report boots/checks/ownedAppCids 全空；旧 finalize 未分支直接取 `ownedAppCids[-1]` 再次 IndexError。此次没有通过捕获 IndexError 修饰结果，也不依据 ModuleNotFoundError 名字认定没启动。驱动与受控解释器由 F 持有，`-I` 与原 pin 不变。

## 与 F 原 owner 的唯一无启动判据

应用 report 在现有 slot 准备完成后先独占创建，初始 `applicationStartAttempted=false`。唯一固定 wrapper 在**调用 slot.start 之前**将 `true` 和本次固定 phase 原子持久写回；参数检查、create 失败、Docker 回包未知均不把它改回 false。run 内三个原 start 调用全部走该 wrapper；没有新 CLI/资源/启动器。

F 的 no-log 特例需要同时成立：

- sourceSha/schema/runId/runAttempt 绑定当前执行，原 jarSha256 存在；不是读到同名文件就可信。
- `applicationStartAttempted` 必须是显式 boolean false，缺失不成立。
- `result=NOT_COMPLETED`，`firstFailurePhase` 为 `PRE_APPLICATION_PREPARATION` 或 `SOURCE_DATABASE_PREPARATION`，并有稳定异常类型字段 `firstFailureType`；不匹配异常名字白名单。
- `boots=[]`、`completeBoots=[]`、`checks=[]`、`ownedAppCids=[]`、`removedAppCids=[]` 全部明确为空。
- `applicationStop=NO_ACTIVE_APP_SLOT`、`privateCleanup=EXACT_OWN_SUBTREE_REMOVED`、productionDispatchRelease=false。
- **原 owner 另行独立确认精确 app 槽位 NoSuch**；不能只信上述 JSON 或“没有 CID”。

probe 的 `confirmed_pre_start_failure` 对应上述 report 部分；F 不必导入 C probe，可沿原 owner 的固定 JSON 读口消费相同合同。原 source readiness/target readiness/label/CID/network/UUID/精确清理仍归 F/root，不在 C 重建。

## finalize 失败与成功分别保留

所有失败先持久写 `restore-http-cleanup.json`，`result=NOT_ACCEPTED` 且 originalExit 原值保留，然后返回失败。合法 report 的原 firstFailurePhase/Type 原样留下；错源、缺失/损坏 report 只记固定读阶段与异常 class，不抄原内容/inner exception/SQL/token/provider payload。即使 report 损坏仍核精确四槽名称与 network absence；合法原 owner 清理记录可继续提供其三或四个实际创建 CID 的独立 absence。

零 app 场景实际只创建三个 backing 容器，原 owner 必须证实这三个精确 CID 移除及 app 槽位缺失；不能捏造第四个 app CID。尝试过 create 但没拿到 CID/没 ready 时，NOT_STARTED 日志特例不成立；PRESERVED 必须有原日志证据，否则显式保留日志未知/失败。

失败时已证实的资源删除标记为 `CONFIRMED_EXACT_OWNED_REMOVAL`，不使用业务成功的 CLEAN；资源 absence 未知则 UNKNOWN。两者均不改变 NOT_ACCEPTED 或原非0退出。原 owner 删除事实和安全的独立检查项可读，不把其中任意额外 JSON 字段转发到公开 artifact。

成功仍须原 5 个独立 boot（source false、target false、true、invalid、空串），2+3 分组、原五 CID/四已移除 app、最终 stopped app、同 source/JAR/schema、原两个不同 UUID/network、原 actor/workload/rows/highwater/provider/guard 断言全部保留；原 owner 四个**实际** CID 一一匹配，日志 PRESERVED 且所有独立 absence 成立。必须 originalExit=0、无源失败/清理失败/日志失败，才沿用原成功结果与 CLEAN。生产放行始终 false。

新控制覆盖：真实零 boot 形状、原非0/错误0 exit、不成功 create 前持久 attempt、attempt=true 不能套免日志、日志 UNAVAILABLE、错 source/run/attempt/schema、损坏/缺 report、伪成功零 boot、absence 权限故障不泄露内层细节、完整五 boot 正向及少 boot/异 JAR/异 CID 拒绝。它们是明确 transport/host gate doubles 下的生命周期控制源码，不是 Docker/MySQL/HTTP/guard 业务通过证据。
