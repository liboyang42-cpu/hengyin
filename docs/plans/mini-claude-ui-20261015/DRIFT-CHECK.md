# 基线漂移复跑（BL-172）

> 只读复跑，只提交本文档，**没有向任何 worker 分支推送**。快照 2026-10-16 03:40–04:30Z，基线 `claude/mini-ui-features-20261015` = `7587358c`。

## 1. 范围与方法

- 范围：所有合入本基线的 open PR 中，**最新一条** `<!-- supervisor sha=... -->` 结论为 `OK`、且改了 `chengyinhub-xcx/` 下非文档文件的 PR，共 **74 个**（CHANGES NEEDED 6 个、CLOSE 4 个、尚无监督结论的 10 个 #210–#219 不在内）。74 个分支都落后于当前基线。
- 每个 PR 在临时 worktree 里：检出 PR 头 → `git merge --no-ff` 当前基线（不推送）→ 在 `chengyinhub-xcx/` 下跑：
  1. 该 PR 改动/新增的 `tests/unit/*.test.js`（`node --test`）；
  2. `node scripts/ui-validity-gates.js`；
  3. `node tests/lint/ui-integrity.js --warn-as-error`。
- 合入后有任何一项不绿的 39 个 PR，再在**不合基线的 PR 头**上原样跑一遍，判断是不是基线前进造成的。
- 对照：基线本身 `ui-integrity --warn-as-error` 为 `UI-GATE-0:PASS`（U5 需逐项理由 28 / 已登记 28），`ui-validity-gates` 退出码 0。
- 没跑 `ci/xcx-check.sh`、全量单测、CI。

## 2. 结论

- **合并冲突：0 个。** 74 个 PR 与当前基线都能自动合并。
- **因基线前进而转红：0 个。** 39 个合入后有红项的 PR，在 PR 头上（不合基线）的单测计数、`ui-validity-gates` 失败项、`ui-integrity` 失败项与条数**完全一致**，红项都是 PR 自带的，不是漂移。
- **相关单测：74 个均 fail 0。** 带 todo 的是 #186、#188、#189、#192（各 1）与 #201（3，#213 转正）。
- 监督已标 OK 但门禁仍红的 PR 不少，主要是三类（都已有对应卡或 PR，未在此重复修）：
  - `ui-validity-gates` U5 白名单未同步：#73/#74/#75/#77/#95/#118（report-sheet）、#110/#189（merchant-correction-sheet）、#83/#113/#196（roam）、#84/#208、#109/#112/#206（profile）、#114、#122、#123、#141、#184、#202 —— 见 #207（BL-160）、#216（BL-168）；#83/#113（及继承的 #196）、#110（及 #189）、#112、#84（及 #208）、#184 不在 #207（覆盖 #122/#123/#128/#141/#114/#73/#109）范围内，仍缺补丁。
  - `ui-integrity` U5 名额 / roam 键漂移：#38/#39/#60/#68/#83/#113/#196（roam 既有 u5Silent/u4Dynamic 键行号漂移）、#73 系与 #110 系（+2）、#123、#132 —— 见 #219（BL-170 `U5-QUOTA.md`）、#218（BL-166）。
  - `ui-integrity` U1/U4 基线键漂移或新增：#43/#209（official-inbox U1-VARIABLE，#194 X-8）、#164/#166/#203（U1）、#104/#201（U4 零消费 131/130）、#183（U4 132/130）、#109/#112/#206（U1/U4）；Legacy error 1：#144/#199/#205 —— 归协调者 `ui-integrity-baseline.js` 或各 PR 正文补丁。

## 3. 逐 PR 结果（合入基线后）

| PR | 分支 | 合入基线 | 相关单测 pass/fail/todo | `ui-validity-gates` | `ui-integrity --warn-as-error` | PR 头上（不合基线） |
|---|---|---|---|---|---|---|
| #23 | `claude/cl3-club-badge-nickname-20261015` | 无冲突 | 97/0/0 | 通过 | PASS | 同 |
| #25 | `claude/cl2-report-transparency-20261015` | 无冲突 | 107/0/0 | 通过 | PASS | — |
| #27 | `claude/cl4-official-organizer-export-20261015` | 无冲突 | 108/0/0 | 通过 | PASS | — |
| #35 | `claude/cl6-official-coorganizers-20261015` | 无冲突 | 15/0/0 | 通过 | PASS | — |
| #37 | `claude/bl-01-roam-poi-filter` | 无冲突 | 64/0/0 | 通过 | PASS | — |
| #38 | `claude/bl-03-roam-offline-hint` | 无冲突 | 4/0/0 | 通过 | FAIL 30 项（U4/U5） | 同 |
| #39 | `claude/bl-02-roam-idle-hint` | 无冲突 | 4/0/0 | 通过 | FAIL 30 项（U4/U5） | 同 |
| #40 | `claude/bl-04-points-filter` | 无冲突 | 3/0/0 | 通过 | PASS | — |
| #42 | `claude/bl-05-stamp-received-detail` | 无冲突 | 3/0/0 | 通过 | PASS | — |
| #43 | `claude/bl-06-coorg-invite-card` | 无冲突 | 4/0/0 | 通过 | FAIL 3 项（U1） | 同 |
| #55 | `claude/bl-17-perks-back-guard` | 无冲突 | — | 通过 | PASS | — |
| #56 | `claude/bl-19-coupon-back-guard` | 无冲突 | 9/0/0 | 通过 | PASS | — |
| #57 | `claude/bl-20-club-media-preview` | 无冲突 | 109/0/0 | 通过 | PASS | — |
| #58 | `claude/bl-22-stable-wx-key` | 无冲突 | — | 通过 | PASS | — |
| #59 | `claude/bl-18-club-edit-back-guard` | 无冲突 | 4/0/0 | 通过 | PASS | — |
| #60 | `claude/bl-23-last-location` | 无冲突 | — | 通过 | FAIL 56 项（U4/U5） | 同 |
| #62 | `claude/bl-26-skeleton` | 无冲突 | — | 通过 | PASS | — |
| #65 | `claude/bl-27-honest-empty-copy` | 无冲突 | — | 通过 | PASS | — |
| #68 | `claude/bl-21-media-preview-rest` | 无冲突 | 108/0/0 | 通过 | FAIL 28 项（U1/U4/U5） | 同 |
| #73 | `claude/bl-32-report-member` | 无冲突 | 4/0/0 | U5 FAIL:未进白名单:components/cy/report-sheet/index.js(2 处) | FAIL 10 项（U1/U4/U5） | 同 |
| #74 | `claude/bl-33-report-merchant` | 无冲突 | 5/0/0 | U5 FAIL:未进白名单:components/cy/report-sheet/index.js(2 处) | FAIL 10 项（U1/U4/U5） | 同 |
| #75 | `claude/bl-34-report-club` | 无冲突 | 4/0/0 | U5 FAIL:未进白名单:components/cy/report-sheet/index.js(2 处) | FAIL 10 项（U1/U4/U5） | 同 |
| #77 | `claude/bl-36-report-im` | 无冲突 | 12/0/0 | U5 FAIL:未进白名单:components/cy/report-sheet/index.js(2 处) | FAIL 11 项（U1/U4/U5） | 同 |
| #83 | `claude/bl-41-roam-official-active` | 无冲突 | 3/0/0 | U5 FAIL:调用数变化:pages/roam/index.js(白名单 12,实际 13) | FAIL 31 项（U4/U5） | 同 |
| #84 | `claude/bl-42-node-favorites` | 无冲突 | 3/0/0 | U5 FAIL:未进白名单:subpackageRoam/node-favorites/index.js(1 处) | FAIL 2 项（--warn-as-error 下的 WARN，#208 修） | 同 |
| #88 | `claude/bl-43-npc-voice-reset` | 无冲突 | 15/0/0 | 通过 | PASS | — |
| #91 | `claude/bl-49-stamp-received-paging` | 无冲突 | 2/0/0 | 通过 | PASS | — |
| #95 | `claude/bl-51-report-reason-desc` | 无冲突 | 7/0/0 | U5 FAIL:未进白名单:components/cy/report-sheet/index.js(2 处) | FAIL 10 项（U1/U4/U5） | 同 |
| #98 | `claude/bl-54-report-tests` | 无冲突 | 7/0/0 | 通过 | PASS | — |
| #100 | `claude/bl-57-api-contract-tests` | 无冲突 | 8/0/0 | 通过 | PASS | — |
| #104 | `claude/bl-61-im-unblock` | 无冲突 | 12/0/0 | 通过 | FAIL 1 项（U4） | 同 |
| #109 | `claude/bl-66-merchant-safety` | 无冲突 | 4/0/0 | U1 FAIL:subpackageMember/components/cy/profile/index.js:720 /api/community-trust/safety/merchants 没有后端 @*Mapping；U5 FAIL:调用数变化:subpackageMember/components/cy/profile/index.js(白名单 1,实际 2) | FAIL 8 项（U1/U4） | 同 |
| #110 | `claude/bl-67-merchant-correction` | 无冲突 | 6/0/0 | U5 FAIL:未进白名单:components/cy/merchant-correction-sheet/index.js(2 处) | FAIL 10 项（U1/U4/U5） | 同 |
| #112 | `claude/bl-69-merchant-node-favorite` | 无冲突 | 6/0/0 | U5 FAIL:调用数变化:subpackageMember/components/cy/profile/index.js(白名单 1,实际 3) | FAIL 8 项（U1/U4） | 同 |
| #113 | `claude/bl-70-roam-shop-visitors` | 无冲突 | 5/0/0 | U5 FAIL:调用数变化:pages/roam/index.js(白名单 12,实际 13) | FAIL 32 项（U4/U5） | 同 |
| #114 | `claude/bl-71-player-code` | 无冲突 | 7/0/0 | U5 FAIL:未进白名单:components/cy/scene-share-invite/index.js(1 处) | PASS | 同 |
| #117 | `claude/bl-74-batch3-api-contract` | 无冲突 | 7/0/0 | 通过 | PASS | — |
| #118 | `claude/bl-75-im-menu-merge` | 无冲突 | 20/0/0 | U5 FAIL:未进白名单:components/cy/report-sheet/index.js(2 处) | FAIL 12 项（U1/U4/U5） | 同 |
| #122 | `claude/bl-79-object-card-rename` | 无冲突 | 4/0/0 | U5 FAIL:未进白名单:subpackageP3/pages/object-cards/index/index.js(1 处) | PASS | 同 |
| #123 | `claude/bl-80-broadcast-stats` | 无冲突 | 4/0/0 | U5 FAIL:调用数变化:pages/activity/official-mine/index.js(白名单 1,实际 2) | FAIL 7 项（U4/U5） | 同 |
| #125 | `claude/bl-82-batch4-api-contract` | 无冲突 | 4/0/0 | 通过 | PASS | — |
| #130 | `claude/bl-87-audit-detail` | 无冲突 | 6/0/0 | 通过 | PASS | — |
| #132 | `claude/bl-89-safety-rating` | 无冲突 | 4/0/0 | 通过 | FAIL 1 项（U5） | 同 |
| #134 | `claude/bl-91-batch5-api-contract` | 无冲突 | 3/0/0 | 通过 | PASS | — |
| #138 | `claude/bl-95-achievements` | 无冲突 | — | 通过 | PASS | — |
| #141 | `claude/bl-98-registration-trend` | 无冲突 | 4/0/0 | U5 FAIL:调用数变化:pages/club/topic-detail/index.js(白名单 6,实际 7) | PASS | 同 |
| #144 | `claude/bl-100-crm-campaigns` | 无冲突 | 6/0/0 | 通过 | FAIL 1 项（Legacy） | 同 |
| #147 | `claude/bl-103-coop-credit` | 无冲突 | 3/0/0 | 通过 | PASS | — |
| #150 | `claude/bl-106-batch8-tests` | 无冲突 | 3/0/0 | 通过 | PASS | — |
| #152 | `claude/bl-108-merchant-subscription` | 无冲突 | 3/0/0 | 通过 | PASS | — |
| #158 | `claude/bl-114-batch9-tests` | 无冲突 | 6/0/0 | 通过 | PASS | — |
| #164 | `claude/bl-120-save-as-template` | 无冲突 | 5/0/0 | 通过 | FAIL 2 项（U1） | 同 |
| #166 | `claude/bl-122-batch10-tests` | 无冲突 | 9/0/0 | 通过 | FAIL 2 项（U1） | 同 |
| #183 | `claude/bl-140-club-dissolve-blockers` | 无冲突 | 12/0/0 | 通过 | FAIL 5 项（U4） | 同 |
| #184 | `claude/bl-141-merchant-status-readback` | 无冲突 | 53/0/0 | U5 FAIL:调用数变化:pages/merchant/decor/index.js(白名单 2,实际 3)；U5 FAIL:调用数变化:pages/merchant/index/index.js(白名单 2,实际 3) | PASS | 同 |
| #186 | `claude/bl-143-roam-filter-tests` | 无冲突 | 68/0/1 | 通过 | PASS | — |
| #187 | `claude/bl-144-organizer-rules-tests` | 无冲突 | 115/0/0 | 通过 | PASS | — |
| #188 | `claude/bl-145-month-merchantid-tests` | 无冲突 | 7/0/1 | 通过 | PASS | — |
| #189 | `claude/bl-145-merchant-id-tests` | 无冲突 | 9/0/1 | U5 FAIL:未进白名单:components/cy/merchant-correction-sheet/index.js(2 处) | FAIL 10 项（U1/U4/U5） | 同 |
| #190 | `claude/bl-146-batch13-tests` | 无冲突 | 6/0/0 | 通过 | PASS | — |
| #192 | `claude/bl-148-club-badge-tests` | 无冲突 | 107/0/1 | 通过 | PASS | — |
| #193 | `claude/bl-149-nickname-tests` | 无冲突 | 107/0/0 | 通过 | PASS | — |
| #195 | `claude/bl-150-report-view-tests` | 无冲突 | 115/0/0 | 通过 | PASS | — |
| #196 | `claude/bl-152-roam-visitor-sourcekey-tests` | 无冲突 | 11/0/0 | U5 FAIL:调用数变化:pages/roam/index.js(白名单 12,实际 13) | FAIL 32 项（U4/U5） | 同 |
| #197 | `claude/bl-151-npc-export-points-tests` | 无冲突 | 117/0/0 | 通过 | PASS | — |
| #198 | `claude/bl-153-verify-record-shape-tests` | 无冲突 | 67/0/0 | 通过 | PASS | — |
| #199 | `claude/bl-153-crm-campaign-shape-tests` | 无冲突 | 11/0/0 | 通过 | FAIL 1 项（Legacy） | 同 |
| #201 | `claude/bl-154-im-unblock-handler-tests` | 无冲突 | 15/0/3 | 通过 | FAIL 1 项（U4） | 同 |
| #202 | `claude/bl-155-prereq-whitelist-tests` | 无冲突 | 10/0/0 | U5 FAIL:调用数变化:pages/merchant/index/index.js(白名单 2,实际 3) | PASS | 同 |
| #203 | `claude/bl-156-save-as-template-unknown-tests` | 无冲突 | 13/0/0 | 通过 | FAIL 2 项（U1） | 同 |
| #205 | `claude/bl-158-crm-wording` | 无冲突 | 6/0/0 | 通过 | FAIL 1 项（Legacy） | 同 |
| #206 | `claude/bl-159-safety-path` | 无冲突 | 4/0/0 | U5 FAIL:调用数变化:subpackageMember/components/cy/profile/index.js(白名单 1,实际 2) | FAIL 8 项（U1/U4） | 同 |
| #208 | `claude/bl-161-copy-node-fav` | 无冲突 | 3/0/0 | U5 FAIL:未进白名单:subpackageRoam/node-favorites/index.js(1 处) | PASS | 同 |
| #209 | `claude/bl-161-copy-official-inbox` | 无冲突 | 4/0/0 | 通过 | FAIL 2 项（U1） | 同 |

「PR 头上」一列：「同」= 不合基线时结果一致；「—」= 合入后全绿，未复跑。

## 4. 复现

```sh
git worktree add --detach /tmp/dw origin/<PR 分支> && cd /tmp/dw
git merge --no-ff --no-edit origin/claude/mini-ui-features-20261015
cd chengyinhub-xcx
node --test <该 PR 改动的 tests/unit/*.test.js>
node scripts/ui-validity-gates.js
node tests/lint/ui-integrity.js --warn-as-error   # 单次约 2 分钟
```

## 5. 未验证

- 只做了「单个 PR + 基线」的两方合并，没有把多个 PR 叠加（叠加冲突见 `CONFLICTS.md` BL-135、叠加计数见 #194）。
- 基线之后若再前进，本表需要重跑。
- 结论 OK 的判断取自监督评论的最新一条，没有核对评论之后是否又有新提交（74 个里 73 个 head 与被审 sha 相同，1 个已前进）。
