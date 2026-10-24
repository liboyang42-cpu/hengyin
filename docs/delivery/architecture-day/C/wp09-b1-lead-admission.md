# C：W09-B1 带领状态故障拒绝候选

结论：已消除 `leadGateError` 将 ClubLead 查询异常当“非带领场”的 fail-open；仅此一个生产 helper 有改动，未迁移14 helpers。故障返回安全的现有 `ServiceException`，Ajax code=503、data=`{outcome:NOT_RUN}`；不把 SQL、原异常、cause/detailMessage 下发。成功查询结果及自玩/新 runtime/集合/章节规则保持。**Java/JUnit/MySQL 未运行；preference 两条非事务路径仍有先建局后 lead 校验的独立决策缺口，本候选不能宣称 W09-B1 全链路零变化已闭合。**

## 基线、隔离与候选身份

- 公共仓库：`https://github.com/liboyang42-cpu/hengyin`，原参考基线2ec。
- 本轮精确 parent：`26f3764ce0d81594be02c0eeace04aacdd483e58`。
- 独立工作树 `/workspace/hengyin-c-w09-b1`；分支 `codex/architecture-day-c-w09-b1-lead-admission`。其余 C/公共 worktree、e190/918/9da/971 等冻结候选不改写。
- 候选身份为本报告所在提交；root 可只取 `git diff 26f3764c..HEAD`。没有 merge、部署、CI dispatch、生产访问、Java VM 执行或新依赖。

## 失败机制、最小负控与修复合同

原源码：`try { st=clubLeadMapper.selectByActivity(actId); } catch(Exception e) { return null; }`。null 是所有调用者的“准入允许”，因此缺表/数据库故障/缺 mandatory mapper 的 NPE 都可误放行。新负控按原源码必将无法取得预期 ServiceException；这是**源级失败机制与已编写测试**，未把它记作 Java 红测实跑。

最小修复保留原判断顺序，将同一 helper 内 runtime 状态读、ClubLead 读及必要章节读的普通 RuntimeException 转为固定安全错误；日志只记 activityId 和异常类名，不记原 SQL/消息/栈。已有 `ServiceException` 原 code/data 原样传播，403 不被改成503/UNKNOWN/NOT_RUN，也不误判为非带领。没有改变状态数字、角色/RBAC、积分/收费身份或请求参数。

故障用 ServiceException 而非返回 error string：它越过调用者的 IllegalStateException 局部 catch 和 basic intent lambda，令原写事务回滚；新 claim 不能被 `completeReceipt` 写成失败终态或成功。成功查询 null、status=null/3、自玩、route RUNNING/其他原状态以及原章节空值/顺序分支保持；不在机械批猜新业务规则。

## 全部真实 caller 与 catch/事务边界

parent 中10个直接调用点对应10个HTTP入口（photo有两点，preference helper有两个入口）：

| HTTP入口 | 实际到达 helper 的路径 | 外层事务 / 故障传播 |
| --- | --- | --- |
| POST checkin | executeBasicIntent→checkinOnce | RC/DeadlockRetry；故障越过 lambda，claim 与本地写回滚；原FREE_COLLECT提前返回分支不扩改 |
| POST answer | executeBasicIntent→answerOnce | RC/DeadlockRetry；同上 |
| POST arrive | executeBasicIntent→arriveOnce | RC/DeadlockRetry；同上 |
| POST puzzle/reveal | revealPuzzle | RC/DeadlockRetry；helper 在 recordReveal/完成链之前，ServiceException 不被后部 IllegalState catch 吞掉 |
| POST puzzle/hint | puzzleHint | RC；helper 在 requestHint 之前，异常不被其 IllegalArgument/IllegalState catch 吞掉 |
| GET preference/{nodeId} | resolvePreferencePlayCtx | 无外层事务；见下述未闭合决策 |
| POST preference/{nodeId}/submit | resolvePreferencePlayCtx | 无 Controller 外层事务；后续 preference service 写尚未进入；见下述决策 |
| POST sensor-result | sensorResult | RC/DeadlockRetry；route canPlay 在前但参与原事务，异常令原事务回滚；传感器 acquire 在后 |
| POST photo | 普通照片、FREE_COLLECT filter_shot 内部各一点 | RC/DeadlockRetry；ServiceException 越过内部 IllegalState catch；proof/奖励未进入；非filter既有分支不扩改 |
| POST hint/unlock | unlockHint | REQUIRED/default；定价/提示正文/ledger/debit 在 lead 检查之后 |

原新 runtime 状态查询本身是 mapper 读取（GameSessionRuntimeServiceImpl.routeSessionStatus），不需要新外部动作/nonce。全局异常处理器沿既有 code/msg/data 响应，不用403表示服务不可用，不用UNKNOWN表示已知未执行的本地准入失败。

## 生产冻结之外的具体决策缺口：preference

`resolvePreferencePlayCtx` 当前先 `unlockGateError` / `routeAccessError`，之后才 `leadGateError`。`routeAccessError` 在 LINEAR 主题调用带独立 REQUIRED 事务的 `TopicRouteRuntimeServiceImpl.canPlay`；其 `ensureBranchSession/ensureJourneySession` 可在无外层事务的两个 preference 入口创建并提交 route session。之后 lead DB 故障即使抛503，也无法回滚前一个已提交事务。原helper补丁仅能保证不进入 preference 读正文/提交动作，不能证明整请求业务零变化。

具体复现条件：有效报名、合法目标、需要建 branch/journey session 且原局尚无记录的主题；canPlay 创建并提交后，ClubLead 读取故障。禁止把 route mock 返回 true 的 unit 结果冒充这条真实建局链已无变化。

已向 root 提出独立差异：只把 `resolvePreferencePlayCtx` 内原 leadGateError 检查移到 unlockGateError/routeAccessError 之前。它能在故障时先拒绝建局，但会改变“两闸同时拒绝”时错误消息优先级。用户要求“若最小fix需要改变正常业务/共享契约，先给root具体差距”，因此**本候选未改该 helper、未自行批准这一顺序变化**。需 root 明确此有限差异后单独提交/验证；不靠给整个 GET 加事务或锁跨业务调用掩盖问题。

## 文件清单、测试与旧合法夹具

生产：

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java`：只改 leadGateError；静态证据逐字比较其前/后源码，均与 parent 相同。

测试：

- `web/controller/api/ClubLeadGateTest.java`：原4个正控方法保留；加6个负控/边界方法（DB故障、mandatory mapper 缺失、runtime故障、章节依赖故障、403原样、自玩零依赖），合计源码10方法。
- `web/controller/api/PlayLeadAdmissionFailureTest.java`：新11个普通@Test，合法原命令进入所有10个HTTP入口，另覆盖照片filter内层catch；精确503/安全msg/NOT_RUN，lead mapper确实被调用，提示/偏好/传感器/proof/进度/积分/扣分服务零调用、零terminal receipt。basic claim 的实际回滚另由下述IT验证；preference 的先前建局效应未被此 mock 证明消除。
- `web/controller/api/CurrentPlayControllerFixtures.java`：缺 mapper 的旧合法业务夹具显式装“查询成功返回无 lead 行”的 mock，仅限测试，保留已有 fault/lead mapper。生产无空值 fallback。
- `web/controller/api/ApiPlayCurrentUseBoundaryTest.java`：不覆盖其专属 old/current registration 桩，仅显式注入同样的 no-lead fixture；原6个业务测试正文和断言不改。
- 新 `business/it/PlayLeadAdmissionFailureMySqlIT.java`：独立2个普通@Test。专属schema真实暂改 ClubLead 表名制造缺表，原Controller代理+真实CurrentPlayRegistration/ClubLead/积分/会员/回执Mapper SQL；验证提示故障后的余额/流水/进度/报名/回执向量不变，恢复表后原非带领合法收费；第二条证明**实际 INSERT 一条 basic claim 后** helper503，使真实事务回滚 claim、无terminal receipt，向量不变。

IT从已有公共 hint10 setup 复用 canonical DDL/装配方式，未改 root-owned `PlayHintUnlockMySqlIT`（仍10项，两普通入口适配保留）。新类只登记2项供 root 独立接 CI，不改 registry/workflow。其 node/template/topic 目录输入仍明确是替身；没有伪称 HTTP/JWT 或全部奖励 SQL 真库验收。新源码方法增量6+11+2=19，**不是实际执行19项通过**。

## 实际执行、未执行和受控运行要求

- 实际执行：git diff --check；Python 六个 Java 源的字面量/注释剥离后括号平衡、只改原 helper 的前后字节比较、旧fail-open源负控确认、7 mapper XML解析和3 canonical DDL唯一提取检查；源hash和方法数见 `evidence/wp09-b1/source-proof.json`。
- 秘密扫描：使用既有 `.gitleaks.toml/.gitleaksignore`，扫描本候选全部源/交付材料和原始diff；不扩忽略项，结果见静态证据。
- **未执行**：Java编译、ClubLead10/调用者11/相关旧6单测、MySQL新2项、原hint10回归、真实并发/rollback/cleanup、HTTP/小程序。XML解析不等于MyBatis加载；源码数量不等于Surefire计数。
- root已确认原hint10在 ad7/run40039899867 实际10/10、0fail/error/skip/CLEAN，只能用作原表征基线，不证明本候选B1或preference分支通过。当前26f完整CI40041038593当时仍运行，不挪用其未来结果。
- 新IT要求显式HOST/PORT/USER/PASSWORD/DB_SUFFIX，host127.0.0.1、有效数字端口、suffix `_[a-z0-9_]{1,20}`。只初始化并记录 `chengyin_mybatis_c_lead_admission_failure<suffix>`；异常也尝试恢复本表名，AfterAll关本pool后只DROP精确suite库。不使用缺环境skip，不访问生产。DDL仅测试编写，未在VM执行。

## 迁移、回退与验收限制

没有生产schema/数据迁移、资金回填、状态编码或新依赖。未碰A/B/G资金、Theme/APPLIED、root共享PhotoFixture、ledger/UI baseline/根pom/CI。回退此行为补丁会恢复已知lead fail-open，不应把恢复缺陷当可接受回退；需要时应停止该范围新命令，保留原事实/回执读取，而非清空数据。

正式验收仍需：root决定preference有限顺序差异；同源编译/完整单测；新IT2及原hint10真实SQL/清理；全部caller无异常吞噬和受拒零新增效果。以上未满足前不报W09-B1完成，更不开始M1/M2机械迁移。
