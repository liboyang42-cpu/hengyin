# 零调用接口增量复扫（BL-173，2026-10-16）

> 只登记，不立功能卡。底表：#177 `ZERO-CALL.md`（BL-133，基线 `d0307a78`，720 个映射 / 220 个零调用）。
> 复扫对象：① 当前基线 `claude/mini-ui-features-20261015`（`7587358c`）；② Codex 控制分支 `codex/mini-bugs-backend-cloud-plan-20261015`（`ba40af10`）；③ 附带：各 open Codex PR 分支、#177 快照之后新开的 `claude/bl-*` PR（#178 起）。

## 结论

**没有新增或变更的 `controller/api` 映射；零调用表仍为 220 条，归类不变。**

| 对象 | 映射数 | 零调用 | 与底表差异 |
|---|---|---|---|
| `d0307a78`（底表） | 720 | 220 | — |
| 当前基线 `7587358c` | 720 | 220 | **无**：`git diff d0307a78 7587358c -- chengyinhub-admin chengyinhub-xcx` 为空（其后 4 个提交只改 docs） |
| Codex 控制分支 `ba40af10` | 718 | （不可比，见下） | 少 2 条：`POST /api/im/send-receipt`、`POST /api/roam/session/records` |

### Codex 控制分支少的 2 条不是删除
- 该分支与基线的合并基 `9d4a4328`（2026-10-15 04:01）；`git diff 9d4a4328 ba40af10 -- …/controller/api` **为空**——Codex 控制分支自合并基以来**没改任何控制器**。
- 这 2 条是基线一侧在合并基之后新增的（`9d4a4328..d0307a78` 改了 `ApiImController`、`ApiRoamController` 等 8 个控制器，+197/−49），底表已包含。
- 控制分支的小程序树也停在合并基，所以它的零调用数（231）会把基线后来已接线的 11 条（`topic/state-keys` ×3、`merchant/claims/*` ×2、`preset/route/*` ×2、`ai/npc/chat/data/delete`、`points/list`、`roam/stamp/received`、`team/report`）误算为零调用，**不作为差异登记**。

### open Codex PR 分支（附带）
对 #18/#28/#29–#34/#36/#44/#72 的分支，按各自与 `d0307a78` 的合并基（均为 `d551c6ba`）取 `…/controller/api` 的 diff：

| 分支 | 改到的控制器 | 映射注解（`@*Mapping`）增删 |
|---|---|---|
| `mini-forward-source`、`mini-longrun-core` | `ApiAiNpcController`、`ApiTopicController` | 无 |
| `extra-six-rolling`、`extra-e06-regression` | `ApiRoamShareController` | 无 |
| `mini-bug-arch-followups` | `ApiTopicController` | 无 |
| 其余（e01–e05、`mini-bug-arch-integration`、控制分支） | — | 无 |

这些分支的小程序新增行（排除 tests/scripts）里的 `/api` 字面量共 50 个，**没有命中**底表 220 条中的任何一条。

### #177 快照之后的 claude PR（#178 起，附带）
新增行里的 `/api` 字面量命中零调用表的，全部是**随上游提交带入**（单测/修复类 follow-up 合了上游分支），接线 PR 仍记在底表 A 类原 PR 名下，没有新的接线：
- #187/#197 ← #27；#192/#193/#212 ← #23；#195 ← #25；#189 ← #110；#196 ← #113；#198 ← #119；#199/#205/#217 ← #144；#201/#213 ← #104；#202 ← #128；#206/#216 ← #109；#210 ← #88
- #183 `club/dissolution-blockers`、#184 `merchant/business-status` 已在底表 A 类。
- 注：`POST /api/merchant/crm/campaigns`（创建）只是路径与 GET 列表相同、被按路径匹配命中；#144 只做 GET，创建仍为零调用（底表已按方法人工剔除）。

## 复跑

```bash
S=/tmp/zc; for t in d0307a78 origin/claude/mini-ui-features-20261015 origin/codex/mini-bugs-backend-cloud-plan-20261015; do
  n=$(echo $t | tr / _); mkdir -p $S/$n
  git archive $t chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api chengyinhub-xcx | tar -x -C $S/$n
  ALL=1 node docs/plans/mini-claude-ui-20261015/ZERO-CALL-DELTA-scan.js $S/$n > $S/$n.all.tsv
done
diff <(cut -f1,2 $S/d0307a78.all.tsv | sort -u) <(cut -f1,2 $S/origin_claude_mini-ui-features-20261015.all.tsv | sort -u)
diff <(cut -f1,2 $S/d0307a78.all.tsv | sort -u) <(cut -f1,2 $S/origin_codex_mini-bugs-backend-cloud-plan-20261015.all.tsv | sort -u)
# Codex 分支映射注解增删：
git diff $(git merge-base d0307a78 origin/codex/<branch>) origin/codex/<branch> -- chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api | grep -E '^[+-].*@(Get|Post|Put|Delete|Patch|Request)Mapping'
```
