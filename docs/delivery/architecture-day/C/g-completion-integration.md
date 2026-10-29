# C 接入 G 当前个人路线完成事实

结论：接线代码及 14 条 C 行为单测候选已完成；Node 四文件实际 125/125，通过且无跳过。Java/JUnit/MySQL 未运行。本补丁独立于 Photo 编排，依赖 G 公开候选 `051c1c108653eeb1cbc4f3b69f8ae25728c99e34`，不能单独作为可编译的完整候选集成。

## 基线、所有权与文件

本批父提交 `d5e1773a2a85f60bb2a2abfffeb7e5aea9298224`，工作树 `/workspace/hengyin-c`，分支 `codex/architecture-day-c-play-command`。恢复了 C 自有 G WIP stash，并与最新基础命令资格修复自动合并；未切换他人分支或覆盖根集成差异。

C 修改：ApiPlayProgressController、ApiPlayMyCompletedSelfPlayContractTest、TopicRouteFrozenSessionReadSideTest；新增 ApiPlayCompletedRouteSourcesTest 和本任务报告/证据。G 的 7 个独占源码/测试/Mapper、共享接口/schema/CI 工作流不修改。根先合 G reader，再取本批差异。

## 调用链与正确性合同

`POST /api/play/my-completed` 从认证主体取 memberId → 既有完成节点、原已付探索票，以及一次 `CompletedRouteFactReader.findCurrentCompletedRoutes(memberId)` 的窄投影 → 按 activity/topic 合并候选 → 当前主题/活动归属和可读性 → 既有批量 sessionStatuses 与原完成规则 → 原 dataType/dataId 投影及 publicReferenceOnly 过滤。

G 查询先按本人 activity/topic 找 latest 再筛完成，C 不启动/切换 runtime，不把它当完整 session 写回。后一次批量状态读到更新的 RUNNING/ENDED 时覆盖早先候选，不复活旧成功。节点只认本人 status=1，按 activity/topic/node 去重；route-only 完成对象的 doneCount 保留真实 0，不伪造进度行。

FREE_EXPLORE 无有效 paid pk3 探索票直接拒绝完成判定，不能借经典 done>=total 或一个 route 完成状态绕过票。合法 paid pure-content 仍交原 PerkLedgerService.isExploreCompleted，允许已有辅助 RUNNING 路线的合法已完成票；没有新增一刀切拒绝。付费/退款/actor/owner 均显式核对。

旧 selectExplorePassesByActivity 一场只留首张，会让首张未付/未完成吞掉后面的合法完成票。本批一次批量查当前 actor、ownerType=2、reg=2、paid=2、pk3 的完整列表并逐行核验，保留每场所有合法票，沿既有“任一有效票已完成”规则判断；不写权益、奖励、核销、结算或经济凭据。

## 失败机制、测试和真实边界

G 原负例：只有 status=0 到达或 route=COMPLETED 时不在旧 progress 驱动候选中；FREE_EXPLORE 无票可从 classic fallback 错报完成。C 14 条新增 Java 用例覆盖 route-only 自玩/活动、不同 scope、最新 RUNNING/ENDED、来源去重、actor/topic 隔离、治理状态、无票、合法 paid pure-content、任一有效完成票、无效/退款票及匿名零查询。尚未运行，不能声称原 G 红测已转绿。

实际运行四文件：play-my-completed-method-contract、play-my-completed-selfplay-client、square-activity-publish-mapping-contract、profile-first-run-state-contract，Node 24.19.0，125 tests /125 pass /0 fail /0 skipped；完整新 TAP 与汇总见 `evidence/g-completion-integration/`。静态检查明确 reader 调用 1 次，完整探索票批量查询 1 次，新增入口不写状态；Java 文件静态括号检查通过，不等于编译。

G 的 ArchitectureDayGCompletionFallbackTest 原来手动 new controller，须补 reader 的空集合夹具和新的全票列表空集合，保持两条业务断言不动。为保护 G 所有权，本树不修改该文件，提供 `g-fallback-fixture-addition.patch` 给根追加。对 G 051c 原文件复制到专属临时目录后实际 git apply --check 通过。

## 受控验收与迁移

根联合 G+C 后需实际编译并运行 C 的新增 14 条、已有 MyCompletedSelfPlay/FrozenSessionReadSide、G 的两条 fallback 保护测试、CompletedRouteFactReaderTest/CompletedRouteFactMapperMySqlIT，再统一全 suite。MySQL 仅用项目已批准 CI/VPS，未在本 VM 启动 Java/数据库。G IT 清理仍由其所有者/集成人按门禁核对；未清理他人资源。

无新 DDL、依赖、公开接口参数或资金规则。回退时先移除 C reader 调用再移除 G bean，不能删除历史完成、票、回执或审计；不可恢复无票 FREE_EXPLORE fallback。未部署、未正式合并、未上传微信。
