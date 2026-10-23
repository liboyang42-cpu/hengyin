# G 第二轮：只读候选实现与真实 Java 回归测试源码

结论：**已实现独立的批量路线完成事实查询，新增 14 个 Java 行为测试方法；Java/MySQL 均未运行。** 没有修改 C 的文件、资金入口、经济资格、共享接口、schema 或依赖。无票探索 fallback 的 1 个测试按基线源码预计失败，明确作为 C 的修复阻塞，不禁用、不把危险行为设为正确结果。Python 输出只是合成规格/静态检查，不是这些测试的通过证据。

## 独占文件与 C 接入合同

本轮新增且由 G 独占的 7 个源码文件：

| 文件（仓库相对路径） | 用途 |
| --- | --- |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/CompletedRouteFactReader.java` | `findCurrentCompletedRoutes(Long authenticatedMemberId)`，只读 Service，拒绝 null/非正 member |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CompletedRouteFactMapper.java` | 单一只读查询；沿既有 MapperScan 自动发现 |
| `chengyinhub-system/src/main/resources/mapper/business/CompletedRouteFactMapper.xml` | 一次查询，先按本人 activity/topic 取 max(id)，再筛 status=1；不连接 progress |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/CompletedRouteFactReaderTest.java` | 3 个单测：匿名查询拒绝、一次批量读取及来源版本、activity/topic 双维差集 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CompletedRouteFactMapperMySqlIT.java` | 4 个真 MySQL IT：精确来源集合、无节点行、主体隔离、重读不改事实/回执 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ArchitectureDayGSettlementGuardsTest.java` | 5 个真实业务方法单测：非合作拒结/reader拒绝/探索禁整单/context/claim重放冲突 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ArchitectureDayGCompletionFallbackTest.java` | 2 个 public myCompleted 行为测试：无票探索不得通关（预计红）；到达行不算完成 |

G 未修改 `ApiPlayProgressController`、`TopicRouteRuntimeServiceImpl`、`ApiClubLeadController`、`ClubLeadMapper`、现有 `TopicRouteSessionMapper`、共享 service interface。已读取 C 聊天 `01a16a22-c697-7637-ae29-cdff6341000b` 的独占范围；当前可用工具没有跨会话消息发送入口，**未声称 C 已确认接入**，本合同由协调者转交 C。

供 C 迁移 `/api/play/my-completed` 候选的具体规则：

1. 调用入口从已认证主体取 memberId，不能把客户端 memberId 直接传入。调用 reader 一次，和现有 progress/有效探索票候选按 `(activityId,topicId)` 求并集。
2. 结果是 `TopicRouteSession` 的窄投影，仅包含来源 sessionId、memberId、activityId、topicId、playInstanceId、configVersion、status、completedAt。不要将未查询的 state/configSnapshot 等字段写回；不要当完整 runtime session 使用。
3. 最新局的选择顺序与现有 selectCurrentByOwner/sessionStatuses 同源：先 latest，再 status=1。旧局成功而新局 RUNNING/ENDED 的 scope 不进入当前完成列表；历史列表需求另行处理。
4. 仍沿现有 Controller 检查主题可读性、activity→topic 关系、public 完成引用过滤和返回 dataType/dataId。activity=0 是自玩，不能伪造 activityId；路线事实仅补来源，不能授予探索票核销、奖励或现金资格。
5. 不将 `game_story_state` TEAM/WORLD 展开到个人，不把 `game_player_node_outcome` 复制成 progress=1。这个最小查询只补已存在的个人路线事实源，不改变其他玩法定义。

## 先有失败机制，再有补丁

**来源漏入：** 基线 myCompleted 先从 progress.status=1 与探索票构造候选，再查 route status。仅有 status=0 到达凭证的 generic 个人路线即使 route=1，也可能没进入待查 scope。新 mapper 直接由本人的 topic_route_session 驱动，不需要补造 progress/权益或奖励；也没有在 SQL 的 latest 子查询提前筛 status=1。

真 MySQL 夹具的期望 session ID 集为 `{3,4,9}`：id1 的成功被新局2 RUNNING 取代；id7 成功被新局8 ENDED 取代；id5 ENDED、id6 RUNNING 均排除；另一会员 id99 在相同 scope 不遮蔽本人 id3。id4 是 activity=0，自玩与 id9 的同主题另一场次必须并存。库中故意不建 player_node_progress；若新查询重新依赖节点进度，IT 应失败。重读前后按 id 排序比较状态/版本/state 和 action 回执行数。这些是**测试源码中的期望值，尚无 actual DB 输出**。

**危险 fallback：** 在 FREE_EXPLORE、没有有效 explore 票、无 route status、仅已有一个完成节点时，基线 public `myCompleted()` 可通过 `isTopicCompletedFor(null)` 借用经典 total/done 判定成功。新增测试要求 data 为空；本分支不改 C Controller，因此预计该项红。不是“已运行失败”，更不是已修复。严格 pure-content 分支已有合法 paid 活动票/供给形状判定，不能和无票 fallback 混为一谈。

**原有资金保护：** 单测调用真实 executeTopicSettlement/prepare/recordPaidRegistration/claim 方法，分别断言指定拒绝原因、事务边界 rollback 被调用且 commit 未调用、现金/claim/奖励依赖零调用或规范化经济 key。Mock 的 rollback 方法调用不证明数据库事务已回滚；reader 返回 null 的测试也不能冒充结算主路径成功。

## 执行入口与实际状态

14 个新增 Java 方法：**NOT_RUN**。没有运行 Maven、Java、MySQL、Redis，也没有新建本地后端。没有 `@Disabled`、Assumptions、连不上即跳过或放宽原 baseline。将类名加入现有 CI 的修改由集成人单一处理，G 不改工作流/ci 脚本。

用户后续明确批准“仅为 CI 的隔离候选提交/推送”例外。按此授权可提交和推送 G codex 分支供协调者 fetch；不是编译/测试通过证明，预计红项仍保留，正式集成/合并/部署仍禁止。支付渠道首次发起锁定、确认原支付关闭后方可重新发起的决定已记录，G 不改支付路径。

获批 VPS/项目 GitHub-hosted CI 的单测命令（从仓库根执行）：

```sh
mvn -q -pl chengyinhub-admin -am -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CompletedRouteFactReaderTest,ArchitectureDayGSettlementGuardsTest,ArchitectureDayGCompletionFallbackTest test
python3 ci/assert_it_ran.py chengyinhub-admin/target/surefire-reports CompletedRouteFactReaderTest ArchitectureDayGSettlementGuardsTest ArchitectureDayGCompletionFallbackTest
```

上述命令中的两个 failIfNoTests 选项仅用于 Maven reactor 中没有这些测试类的依赖模块，沿仓库 ci/backend-test.sh 的约定；admin 的每个指定类必须实际生成非零 tests、零 skipped 的报告。必须单独记录 fallback 预计红项，不得通过过滤/改期望把它隐藏后报告整体通过。

受控 MySQL IT 使用项目已有 `MybatisItDatabase`/`ItSchema`/真实路线迁移，显式要求 `CHENGYIN_MYBATIS_IT_HOST` 为 localhost/127.0.0.1，不读取或申请新凭据。仅在批准实例上以 UUID 后缀的一次性 suite schema 建夹具，不操作共享/生产库，不清理别人资源。使用既有受控 CI 注入的连接配置运行：

```sh
mvn -q -pl chengyinhub-admin -am -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CompletedRouteFactMapperMySqlIT test
python3 ci/assert_it_ran.py chengyinhub-admin/target/surefire-reports CompletedRouteFactMapperMySqlIT
```

IT 名称沿仓库约定需点名执行；未接入统一 CI 清单前不声称自动覆盖。它验证真实查询/迁移形状，不验证结算、奖励或支付链路。

## 迁移、回退和剩余工作

无新 DDL/依赖，无生产数据变更；依赖既有 topic_route_session 迁移实际已安装。查询无 HTTP 入口，待 C 显式注入接线才能影响列表。未接线前可移除 3 个新实现文件；接线后先回退调用方再删 bean/mapper。历史投影回填、奖励、核销、结算入口不在本补丁内。

未完成：C 接线与危险 fallback 修复；获批环境编译/14项 Java 测试及真实 MySQL IT；资金恢复的 scope/回执/退款调整技术修复。业务问题从原六项收敛到 `approved-contract-review.md` 的一个具体多人票计量矛盾；其余既有规则不要求用户重选。
