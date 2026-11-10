# 提示阶梯在自玩通行证局上的可用性（qx-c3 第 4 项，设计稿）

结论：**不是一处可以收住的后端改动**。本轮不改代码，按 BRIEF 列为 needs owner，默认值保持现状：服务端对
`play_instance_id='default'` 的局回 `enabled=false`/「原局身份不可用，请重新进入本局」，客户端照旧显示旧提示行
（staging RESULT §6 已实拍确认客户端是这样回落的）。

## 现状（源码事实）

- 自玩/路线局只在 `TopicRouteRuntimeServiceImpl` 建 `topic_route_session`，`play_instance_id` 恒为 `DEFAULT_INSTANCE = "default"`
  （建局、撞键回读、`selectByOwner(..., DEFAULT_INSTANCE)` 等 9 处）。
- 唯一键 `uk_topic_route_owner (activity_id, topic_id, member_id, play_instance_id)`（`migration_20260823_topic_route_graph.sql`）：
  同一会员同一主题的自玩局在库里**只有一行身份**，重开靠这一行的状态流转，不产生新身份。
- `PlayHelpApplication.resolve` 拒绝 `default`：帮助用量账 `play_help_run_quota` 的唯一键是
  `(topic_id, activity_id, play_instance_id, owner_member_id)`（`migration_20261108_play_help_run_quota.sql`）。
  若接受 `default`，同一会员在同一主题的**每一局都共用同一份免费次数与已揭示阶梯**——第二局开局就带着上一局的用量，
  或反过来让「整局 N 次」失去局的边界。这正是拒绝它的理由，不是多余的守卫。
- 同一个 `playInstance` 还被 `persistedCompletionMode`（`player_node_progress`，判「揭示答案」不扣满分）、
  `PlayNodeCompletionApplication`（`progressInstance`）、`PlayOriginalRun`、`OrientScoringServiceImpl`、
  `OrientTaskServiceImpl`、`PuzzlePlayServiceImpl`、`AdvancedGameRuntimeServiceImpl`、`PlayJourneyStoryServiceImpl`
  共同使用。这些写入对自玩局都落 `default`。

## 为什么不能只在帮助入口换一个实例名

最省事的做法是在 `PlayHelpApplication` 里把 `default` 映射成 `"route:" + session.id`。这样帮助用量能按局隔离，
但同一个 scope 还要去 `player_node_progress` 读本局完成方式——那里的行仍是 `default`，于是「揭示答案」判定读不到本局行，
计分会把揭示过答案的节点当成正常完成（满分）。要么帮助侧与进度侧口径分叉，要么得同时改进度写入；两者都超出「收得住」。
且 `session.id` 在同一行被重开时不变，并不能真正区分「第几局」。

## 若业主要开，建议的最小完整方案

1. 自玩建局时铸造真实实例：`play_instance_id = "self:" + <新局序号或随机 id>`，每次「重新开始」生成新行（新唯一键值），
   旧行保留终态墓碑；`selectByOwner(..., DEFAULT_INSTANCE)` 的 9 处改为「本会员本主题最新一行」。
2. `player_node_progress`、定向计分、帮助用量等所有按实例写的地方，统一取 `topic_route_session.play_instance_id`（已有
   `PlayOriginalRun.progressInstance()` 这个收口点可扩展），不再各自写字面量 `default`。
3. 历史 `default` 行不迁移：仍按旧语义读；帮助对它们继续回 `enabled=false`。
4. 测试：同会员同主题连开两局，第二局帮助用量从 0 计；揭示答案的节点在两局各自按本局行判分；`default` 老局仍拒绝。
5. 影响面：建局、重开、续玩列表、计分、帮助、进度六处，需要 Java 单测 + `GenericRouteRuntimeMySqlIT` 真库复跑，
   属跨模块改动（AGENTS.md「Ask first」）。

## 需要业主拍板

- A：提示阶梯只对俱乐部开场的活动局开放（现状，零改动）；或
- B：按上面 1–4 给自玩局铸造真实实例（跨模块，约 6 个服务 + 1 个查询口径变化，无新表、无迁移）。

默认：A。
