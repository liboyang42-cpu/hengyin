# C：W09-B1 preference 准入顺序独立增量

结论：按 root 明确批准，`resolvePreferencePlayCtx` 只将原 lead 检查两行前移到 unlock/route 检查之前，仍在原身份、报名和节点归属检查之后。故障先拒绝，避免非事务 preference GET/POST 在带领状态不可知时提前建局。**代码候选已完成；实际 Node 70/70、静态检查通过；Java 编译/JUnit/MySQL 尚未执行，不能宣称 B1 验收通过。**

## 候选与边界

- 公共仓库 `https://github.com/liboyang42-cpu/hengyin`；本增量精确 parent `dfe083a89cd5538b7df77c6d407da3908e9a1cc8`，其 parent 为 `26f3764ce0d81594be02c0eeace04aacdd483e58`。
- 工作目录 `/workspace/hengyin-c-w09-b1`，分支 `codex/architecture-day-c-w09-b1-lead-admission`；本报告所在提交为独立后续候选。root 已有 dfe 时只取 `git diff dfe083a8..本提交`，不重复覆盖原 helper/fixture 批。
- 先前 `wp09-b1-lead-admission.md` 与旧证据保留原样，准确记录当时尚未授权的缺口；本报告记录随后授权的补丁。没有修改 root hint10、Theme/APPLIED、Photo、台账、CI、共享接口或其他会话文件；没有开始 M1/M2 的14 helpers迁移。
- 唯一有意行为差异：两闸同时拒绝时，带领状态拒绝优先于路线/前置解锁拒绝。root 明确批准此技术错误优先级；成功资格、角色、收费、积分和正常玩法规则不改。GET/POST 原注解与事务边界完全保留；没有给 GET 增加事务或锁。

## 调用链与失败机制

搜索实际生产调用者：小程序 `pages/play/index.js` 的 `loadPreference` GET 和 `submitPreference` POST（由 nextPreferenceStep/useInheritedPreference 触发）；当前账号/页面上下文、原 routeActionId、expectedRouteVersion 与 choices 的封装未改。

后端两条 HTTP 入口 `preference` / `submitPreference` 共用本 helper，另无生产调用者：currentMemberId → 节点查询 → resolveCtx（原归属、当前报名、时间校验）→ **leadGateError** → unlockGateError → routeAccessError → 原模式/到店/模板/冻结判定 → preference load/submit。已有 ServiceException403 原样传播；普通带领依赖故障沿 dfe 的固定503/msg/`{outcome:NOT_RUN}`。

parent 顺序是 routeAccessError → leadGateError。真实 `TopicRouteRuntimeServiceImpl.canPlay` 有自己的写事务，可调用 ensureBranchSession/ensureJourneySession，并经 `TopicRouteSessionMapper.insertSession` 提交。Controller 无外层事务，后续 lead 503 无法撤销已提交的 route session。不能仅凭 preference mutation 未调用就认定请求零效果。

本批先编写/加强对应测试，再移动原两行。旧顺序与新断言的矛盾由源码检查确认；**未声称先实跑了 Java 红测**。生产差异由脚本逐字构造 parent 的预期移动后比较整个 Controller，其他内容必须完全相同。

## 文件与测试合同

| 文件 | 增量与证明范围 |
| --- | --- |
| `ApiPlayProgressController.java` | 仅原 lead 检查两行移动；原 leadGateError 本体不改 |
| `web/controller/api/PlayLeadAdmissionFailureTest.java` | 原11项保留，其中 GET/POST 故障各加强 routes 零调用；新增5项：GET恢复、POST原命令恢复、两入口403保留、双闸拒绝优先级、两入口节点归属拒绝优先。该类现16个普通@Test；沿原 reject helper 保留精确503和进度/积分/奖励/回执等零新增调用断言 |
| `business/it/PlayLeadAdmissionFailureMySqlIT.java` | 原2项业务@Test正文逐字不变；新增2个普通@Test分别覆盖 branch 与 linear journey，类现4项。原事务入口仍要求实际可写事务；preference actor 明确断言没有外层事务 |
| 本报告及 `evidence/wp09-b1-preference/` | 可重跑源检查、源hash、真实Node原始输出/计数 |

新 IT 使用原专属 schema，增加从已有公开 migration **精确提取两张 route 表的 canonical CREATE**，装载真实 TopicRouteSessionMapper。Topic/node/template/progress 目录与只读进度输入明确是替身，**canPlay 本体不 mock**：真实实现 spy + Spring TransactionInterceptor + 同一真实 MySQL DataSource/Mapper。POST 的 preference service 为替身，以将表征限制在准入与 route 建局；不把它当偏好标签/全链路数据库验证。

每个新 IT 都执行两种 HTTP 方法：真实重命名本 suite 的 ClubLead 表制造故障 → 固定503/NOT_RUN → **真实 route 实现零进入**、preference 零调用、route session/action均0、原余额/流水/进度/回执/报名向量不变 → finally恢复表。之后同一有效非带领场 GET/POST 均按原 route 调用并实际提交一个原身份 session，验证 snapshot 有/无 edges 分别对应 branch/journey，原 actionId/version/choices 不变，route action无新增、原经济/进度向量不变。恢复正控用于证明夹具确实可走原建局链，避免把未装配服务的零调用误当正确性证据。

新源码增量为5 unit+2 MySQL，另加强旧2 unit。dfe 所写17个增量unit/2IT仍待根实跑，不能把这些源码数量加成实际通过数。root 运行本 MySQL 类时需以 **4个普通@Test**登记，原hint10仍10项且本批未改。

## 实际执行与未验证

实际执行：

- `git diff --check`：exit0；Python `check-source.py`：exit0。检查两行精确移动/原身份检查顺序/旧顺序源负控/原2条IT正文不变/hint10与Theme文件不变/3源文件括号/8 Mapper XML解析/两张canonical route DDL唯一匹配。XML解析不代表MyBatis加载，括号平衡不代表编译。
- Node v24.19.0：默认 process isolation 的三个文件结果全部成功，仅输出3个文件计数；另逐文件 `--experimental-test-isolation=none --test-reporter=tap` 实跑 GET身份矩阵29、journey请求11、request恢复30，合计 **70 tests /70 pass /0 fail /0 cancelled /0 skip**，exit均0。完整命令和原始输出见同目录证据。它们是相关既有客户端合同回归，不能证明 Java/数据库断言通过。
- 使用 gitleaks 8.24.3 与既有 `.gitleaks.toml/.gitleaksignore` 扫描本增量文件及原始diff，不新增忽略项；exit0 / no leaks found，记录见 `secret-scan.json`；提交前对最终候选复扫。

**未执行**：Java编译、所有新增/既有JUnit、真实MySQL4项及cleanup、并发/回滚实证、HTTP鉴权实机与完整CI。未在VM运行Java/数据库，未dispatch。root 已确认26f Theme58/Photo四阶段/AiCreator37通过与本补丁无同源B1证明关系。

## 迁移、回退、余项

没有生产 schema、数据、资金或接口迁移；DDL只供本 suite 自有隔离库初始化，尚未执行。沿原强制显式HOST/PORT/USER/PASSWORD/DB_SUFFIX、host127、suffix限制、精确本suite schema记录与AfterAll关池/删本库，不清理其他资源。

回退本增量会重新允许“先建局、后lead故障”窗口；不能将它作为已证明安全的回退。保留所有既有事实和回执，不删业务数据。正式闭合仍需 root 在同源受控环境编译、执行原/新unit与MySQL4及CLEAN清理，确认真实服务零进入与恢复建局断言；若失败按原断言修根因，不放松503/身份/权限。后续WP09生产迁移继续冻结。
