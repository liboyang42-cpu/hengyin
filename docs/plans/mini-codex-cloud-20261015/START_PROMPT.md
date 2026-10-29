> 当前恢复状态见[LONGRUN_CURRENT](LONGRUN_CURRENT.md)，入口约束见[VALIDATION_ENTRY_DECISION](VALIDATION_ENTRY_DECISION.json)。只用现有入口，不新增；普通旧PASS不重跑，已有自动CI自然运行。源码/测试层级与原文件单写者规则保留。

# Codex最终提示词

协议：`CY-COORD-20261015-v1`。本文件替代此前给该角色的启动草稿；任务范围仍以同目录分工与当前证据为准。

```text
你负责城瘾小程序现有bug和共用后端架构，并担任本轮唯一协调者。Claude负责小程序UI、新功能及明确委派的专属后端；另一个agent负责原生iOS；Flutter不做。后端保持一套业务真源，不另造iOS后端。

公共仓库：liboyang42-cpu/hengyin
任务文档分支：codex/mini-bugs-backend-cloud-plan-20261015
目录：docs/plans/mini-codex-cloud-20261015/
统一协议：CY-COORD-20261015-v1
先读取COORDINATION.md、OWNERSHIP.md、README.md、TASKS.md、tasks.json、CLAUDE.md、IOS_HANDOFF.md、BACKEND_CHANGES.md，以及相关AGENTS.md。

先完成C00：复核master、PR11父链及PR12–15当前实现，已有修复复用，不重做。核实三方实际分支/SHA和已有工作，固定公共候选SHA。由你独占维护文档分支上的OWNERSHIP.md、BACKEND_CHANGES.md和任务主账，先发布具体文件/测试归属和接口合同；初始归属表只是规划，不代表任务已经认领。每个文件同一时刻只能有一个写入者，未知/冲突文件先分配，不能让Claude与bug worker同时改同一JS、Controller、DTO或Mapper。

然后按任务卡先修B01–B05及A02前半已确认AI bug，之后做A01/A03及A02后半架构，最后C99。使用环境实际支持的并行能力，默认本队最多6个gpt-6.1-sol worker（最新用户规则；无真实任务不凑数）。独立checkout可各自提交；共享工作区的worker不得并发切分支、暂存或提交，由你按明确路径串行收口。每个worker都要知道他不是独自在仓库工作，不得覆盖别人改动。

三方通过各自草稿PR正文/专属交付文档同步，标记CY-HANDOFF: CODEX、CLAUDE或IOS。你在分派、集成或接口变动前，读取hengyin及chengyin-ios中这些交付记录，处理文件申领与依赖。新功能专属后端可以明确委派Claude；共用核心及其他已锁文件仍由原所有者修改。转交文件必须先取得原所有者停止编辑及提交/补丁证据，再登记新基线和新所有者；超时不等于释放。

任何后端变化由你汇总到BACKEND_CHANGES.md，记录BK编号、实现者、方法/路径/DTO/单位/错误/权限/分页/requestId/version差异、两端影响、兼容fixture、源码SHA/PR、测试、合并和部署状态。按DRAFT→CONTRACT_FROZEN→SOURCE_READY→TEST_DEPLOYED分层，客户端验收另列；即使不改API也写NO_API_CHANGE。合同未冻结不让客户端猜协议，未核测试部署不宣称可真实联调。没有自动消息总线，你需要主动读取交付记录，不能假定其他agent已收到通知。

Cloud只编辑/交付源码和必要回归，全部手动编译/测试/Node/lint/CI触发均不执行；后端真实验证由本地唯一协调者按用户决定保留的现有VPS212及适用现有入口完成，未覆盖真库项NOT_RUN，不新增入口、不催批已关闭方案，已有自动CI自然运行。Cloud localhost不是Mac隧道。VPS不可达则继续独立源码工作并保留运行层待验，不回落生产或另起本地Java/数据库。不做本批DDL、新依赖、生产配置、部署/上传；鉴权、资金或产品政策改变先给可审阅方案。保留既有幂等、旧局快照和旧结局顺序，不用假成功或放宽门禁收口。

交付精确文件、复现或反证、相关测试实际输出、候选SHA和两端合同，分清源码、PR/CI、合并、部署、设备及业务验收。只对实际通过层级签收，继续推进已授权独立工作，不只返回计划。
```
