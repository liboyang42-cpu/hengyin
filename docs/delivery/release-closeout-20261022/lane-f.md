# Lane F 收尾:排行榜 D-13 口径 + IM 屏蔽后未读角标(N-02 / N-04)

- 分支:`feature/lane-f-leaderboard-unread-20261022`(基于 master `063a6c3c`,2026-10-22 拉取时的 live master 即此 SHA)
- PR:https://github.com/liboyang42-cpu/hengyin/pull/458(draft,目标 master,未合并)
- 提交:`b0d0aced` test(完成写入证明) · `e7ef7f65` feat(排行榜) · `27bd6967` fix(IM 未读)

## 改了什么

### N-02 排行榜(P1)
1. **前置证明**(`ApiPlayProgressCompletionTruthTest` +4):扫码 / 拍照 / 答题 / 高级玩法门四种完成入口都经 `advanceProgress` 写 `player_node_progress` status=1。
2. **缺口**:个人玩法(gameplayRuntime PLAYER quests)扫码/答题只写 status=0 到店凭证,完成事实在 `topic_route_session` ⇒ 节点数榜恒 0。处理:这类主题与分支主题一起按会话排(不伪造 status=1 行,避免影响发奖幂等/通关判定)。
3. `LeaderboardRankPolicy`:BRANCH_GRAPH 或个人玩法主题 = ROUTE;其余 LINEAR(原 SQL 排序不变)。
4. ROUTE SQL `selectRouteLeaderboard`/`selectRouteLeaderboardRow`:每人本场最新一局;到达结局(COMPLETED)优先 → 结局用时升序 → 未到结局按节点数;ENDED 不算到达结局。
5. 同排序键并列同名次(1,1,3)。响应 `{list, me}` 不变,追加 `reachedEnding` / `elapsedSeconds` / `finishedAt` / `data.rankMode`。

### N-04 屏蔽后未读(P2)
- 根因:`AuditTaskServiceImpl` BIZ_IM 举报成立只改 `im_message.status`,未扣 `unread_count`。
- 修复(屏蔽时扣减):`ImMessageMapper.transitionStatus` 条件迁移命中 1 行才调 `ImConversationMemberMapper.adjustUnreadForModeratedMessage(±1)`;恢复对称 +1;重复结论不重复扣;下限 0;只扣真正计着这条未读的读者(与 markRead 重算同口径 + 晚于入会)。

## 验证证据
本地(`mvn -q test -pl chengyinhub-admin -Dtest=...`,surefire 报告):

| 测试类 | 结果 |
|---|---|
| ApiPlayProgressCompletionTruthTest | 31/31 通过(含 4 条新证明) |
| ApiPlayLeaderboardTest | 11/11 通过(+3 条 D-13) |
| PlayerRouteLeaderboardMyBatisTest(新,H2) | 2/2 通过 |
| LeaderboardRankPolicyTest(新) | 3/3 通过 |
| PlayerLeaderboardScopeMyBatisTest | 2/2 通过 |
| GameSessionRuntimeServiceImplTest | 127/127 通过 |
| ImUnreadAfterModerationBlockMyBatisTest(新,H2,屏蔽后角标) | 3/3 通过 |
| AuditTaskImModerationUnreadTest(新) | 3/3 通过 |
| ImMessageReviewVisibilityMyBatisTest | 3/3 通过 |

CI(PR #458,head `c41fb60`,35 个 check 全部 success / 按改动范围 skipped,0 failure):

| Workflow run | 关键 job | 结果 |
|---|---|---|
| 39757240789(ci.yml) | Backend tests(job 119753103271,真 MySQL 8.0.45,含全部 *MyBatisTest) | success |
| 39757240789 | Mini program static gates / static checks / unit shard 1–20 / Migration safety & layout / Secret scan / Fast gates | success |
| 39757240900 | Funds MySQL IT + gate self-test | success |
| 39757240957 | Followup 39 selected backend cases | success |
| 39757240761 | isolated-authenticated-redis-recovery | success |

注:旧 head `27bd696` 上的 "Mini program static checks" 红是因为推送 docs 提交后 unit/static 子 job 被 cancelled(汇总 job 判 cancelled≠success),非真实失败;新 head 已绿。
本地全量 `mvn test -pl chengyinhub-system,chengyinhub-admin`:12956 个用例,Failures 0;25 个 Error 全部是 `business.it.*` 真库测试在沙箱无 MySQL 时的 `Connection refused`(fail-closed 设计),CI 有 MySQL 已全绿。

## 验收清单
- [x] 先写测试证明各完成方式写 progress;不写的(个人玩法)先修口径
- [x] 分支主题:到达结局优先 → 总用时;线性不变;同用时并列
- [x] API 返回结构向后兼容(只追加字段)
- [x] 屏蔽后角标当场回落(含「badge after block」测试)
- [x] 无 DDL、无新依赖、未动 C00 保留文件、未动小程序

## 未决 / 需负责人确认
1. ENDED(失败结局)是否算「到达结局」:当前不算。
2. ROUTE 只看最新一局:重开一局后前一局成绩离榜(与线性榜 play_instance 口径一致)。
3. 小程序同行者榜(`pages/play/index.js` openBoard)仍只显示 score;ROUTE 模式建议显示 `reachedEnding`/`elapsedSeconds`(跨 lane,未改)。
4. 俱乐部共享路线(sharedRoute)与俱乐部控制台 `leaderboardRows` 未改(队伍级口径,另议)。

## 需负责人本地 WeChat DevTools 手测
- 分支主题活动:两名玩家先后到达结局,打开通关 sheet 的同行者榜,确认先通关/用时短者在前、名次正确。
- IM:A 给 B 发文字,B 未读角标 +1;后台把该消息举报审核为违规,B 刷新会话列表/tabBar 角标应立即 -1;判定举报不成立后恢复 +1。
