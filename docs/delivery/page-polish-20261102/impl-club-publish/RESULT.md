# impl-club-publish: result

Branch `claude/page-polish-20261102-impl-club-publish`, from `integrate/page-polish-20261102`. Audit lane read: `club-publish`
(`claude/mini-polish-audit-20261102-club-publish`). Scope: `chengyinhub-xcx/pages/club`, `pages/publish`, `pages/crop`.

The audit has no `spacing` or `radius` rows. The P0/P1 rows are CP-01, CP-02, CP-03, CP-05 and CP-10. CP-11 was also requested.
I checked each row against the code before changing it. None was a false positive.

## Findings

| ID | Outcome | Files changed | Evidence |
|---|---|---|---|
| CP-01 (P0) feed blinks after every like/comment/edit/pin/delete | **done**. Both `pageMotion.enter(that, 'pmPosts')` calls in `loadPosts()` now pass `{ once: true }`. The entrance plays on first arrival only. | `pages/club/detail/index.js` (lines 1752, 1769) | Rig, owner 9003, club 7001, 帖子 tab, `loadPosts()` re-run the way every write action re-runs it. `pmPosts` sequence was `[0,1,2,0]` before and is `[0]` after. Before shot: the feed is blank (`cp01-feed-after-reload-before.png`). After shot: identical to `cp01-feed-ready.png` (`cp01-feed-after-reload-after.png`). |
| CP-02 (P1) Manage tab drops to skeletons on return | **done**. When a loader's state is already `'ready'`, it keeps its rows and state and replaces them on success. With no snapshot (first load, or after an error) it still goes to `'loading'` and clears, as before. Covered loaders: `loadOwnerManagement` (projects + owner stage card), `loadClubStats`, `loadShareEditions` (the empty-reason copy is only reset when there is no snapshot), `loadMessages` (messages + invite feedback) and `loadMembers`. The epoch/clubId guards on responses are unchanged. Error responses still switch to the error state. | `pages/club/detail/index.js` | Rig: 管理 tab, scrolled to the stats/projects, then `onShow()` (the owner return path) with a 1.5 s delay injected on `wx.request` (harness only). About 1.8 s into the refresh, before: `clubStatsState/ownerProjectState/membersState/shareEditionState = loading`; the stats grid and the project row are gone (`cp02-manage-refreshing-2-before.png`). After: all four stay `ready` and the grid and rows stay on screen (`cp02-manage-refreshing-2-after.png`; compare `cp02-manage-ready.png`). |
| CP-03 (P1) Events tab goes blank on refresh and has no skeleton on first load | **done**. `loadTopics` keeps `topicsLoaded: true, topicState: 'ready'` when a snapshot exists. Added `<cy-skeleton wx:if="{{topicState === 'loading' && !topicsLoaded}}" type="list" count="2" />` before the list block. It is mutually exclusive with the list, which requires `topicsLoaded`. | `pages/club/detail/index.js`, `pages/club/detail/index.wxml` | Rig: 活动 tab, then `onShow()` with the delay. Before: `topicsLoaded:false`, so the whole tab, including the "发布 +" header, is empty (`cp03-events-refreshing-before.png`). After: `topicsLoaded:true`, unchanged from `cp03-events-ready.png` (`cp03-events-refreshing-after.png`). I did **not** capture the first-load skeleton. The rig cannot delay requests before `onLoad`, so that branch is verified by reading the code only. |
| CP-05 (P1) "正在更新…" line pushes lists down | **done**. Added the kit class `.cy-refresh-float` to the three status lines. Each `.page` gets `position: relative`. The page-local class becomes a small pill (`--cy-space-1/3` padding, `--cy-radius-pill`, `--cy-bg-elevated`, nowrap) so it reads cleanly over content. join-requests and enroll are flex columns, so the float sits just under the nav bar. badges is not flex, and its static position overlapped the "创建勋章" button, so it gets an inline `top: {{statusBarHeight + navBarHeight}}px` to sit in the same place as the others. | `pages/club/{join-requests,enroll,badges}/index.wxml`, `index.wxss` | Rig, `onPullDownRefresh()` with the delay. enroll before: the "正在更新报名名册…" line pushes the card down about 26 px (`cp05-enroll-refreshing-before.png`). After: the card does not move and a pill floats in the gap under the nav bar (`cp05-enroll-refreshing-after.png`). badges: before / after (`cp05-badges-refreshing-*.png`). **join-requests could not be exercised**: with rig identity 9003 the page's access gate shows "暂时不能查看 / 当前账号无权进入该俱乐部". It is the same markup and the same change as enroll. |
| CP-10 (P1) 剧情故事 "移除" wipes beats and images with no confirm | **done**. The story `.cg-mod-del` now binds `confirmRemoveModule`. `filled` for `story` is true when any beat has non-blank text or images. The modal reads title "移除剧情故事？", content "剧情节点和配图会一起清空", confirm "移除". Empty story still removes immediately, the same rule as 玩法 / 完成奖励. | `pages/publish/temp/index.wxml`, `pages/publish/temp/r5-editor.js` | Rig, 9003, `pages/publish/temp/index`. Added the story module with beat text "第一幕:钟楼下的信" (`cp10-story-filled.png`), then called the bound handler. Before (`removeModule`): the module is gone and the beat text is cleared (`modules:["finish","reward"]`, `cp10-story-remove-tap-before.png`). After: the confirm modal shows and the data is intact (`cp10-story-remove-tap-after.png`). Note: the rig's `tapSel` click on the × icon did not dispatch in either build (HARNESS), so the handler was called directly via `eval`. |
| CP-11 (P2) 查看预览 spins while saving/publishing | **done**. 查看预览 now uses `loading="{{previewMode && submitting}}"`. In pure preview mode it is the only button, so it keeps the spinner there. `openPreview` returns early while `submitting` (same line), so a tap during a save does nothing. `disabled` is still `{{!canOpenPreview}}`, which the primary-action contract requires. | `pages/publish/temp/index.wxml`, `pages/publish/temp/index.js` (line 2847) | Rig with `submitting:true, submittingAction:'draft'` injected via `setData`. Before: "处理中" spins on both 保存草稿 and 查看预览 (`cp11-saving-draft-before.png`). After: only 保存草稿 spins (`cp11-saving-draft-after.png`). |
| CP-04 "—报名" | skipped: P2, not on a line I changed. | — | — |
| CP-04b helper captions | skipped: owner decision (marked so in the audit). | — | — |
| CP-06, CP-07, CP-08 (fabu), CP-12, CP-13 (temp), CP-14, CP-15, CP-16, CP-17, CP-18 | skipped: P2, not on lines I changed, so they fall outside this lane under the brief. | — | — |

## Tests and gates (run from `chengyinhub-xcx/`)
- `node --test` over every `tests/unit` file that references `club/detail|join-requests|enroll|badges`, `publish/temp` or `r5-editor`
  (174 files, 2189 tests). Before my test-related fixes there were 6 failures. After them:
  - `club-lifecycle-contract`: the regex requires the literal `members: [], membersState: 'loading'`. `loadMembers` now keeps that
    literal behind `if (this.data.membersState !== 'ready')`. Passes.
  - `primary-action-form-state-contract` (×2): it needs `disabled="{{!canOpenPreview}}"`. I kept that and moved the "no tap while
    saving" guard into `openPreview`. Passes.
  - `publish-creator-uiux-recovery-contract`: it asserted `loading="{{submitting}}"`, which matched only the 查看预览 button that
    CP-11 changes. **I edited this test** to assert the 保存草稿 spinner (`loading="{{submitting && submittingAction === 'draft'}}"`)
    instead, so it still proves a visible submit state. The test is not on the reserved list. Passes.
  - `sc-ui-date-count-probe.js`: **fails on the base branch too** (pre-existing, unrelated).
  - `action-ledger.test.js` "release 前重建 inventory": **count drift**, expected because the story × control now binds
    `confirmRemoveModule`. It passes on base. Per the brief I did not regenerate or commit the ledger. **Integration step: regenerate
    once.**
- After the final badges tweak: the 266 tests that reference badges / enroll / join-requests (excluding the ledger) pass.
- `scripts/ds-hardcode-gate.sh --ci`: exit 0. `ui-theme-unit-lint --units`: passed. `setdata-dead-field-lint`: passed, nothing new.
  `a11y-tap-target-lint`: nothing new.

## Assumptions and notes
- club/detail `clubId` does not change inside one page instance after the first load: the owner picker only runs before a club is
  resolved. So "state is `ready`" is enough to treat the snapshot as the current club's. If a later change lets the same instance
  switch clubs, the loaders should also compare against the club the snapshot was loaded for.
- CP-02 owner stage card: it keeps its previous stage only when projects, messages and invite feedback were all `ready`. Otherwise it
  shows "读取管理进度…" as before. In the rig `/api/coop/list` returned an error state before the refresh, so the stage card still
  showed "读取管理进度…" during refresh in both builds. That is correct: there was no snapshot.
- I changed no kit files. No kit change is requested.
- Rig: shots come from the real bundle of each branch (before = `integrate/page-polish-20261102`, after = this branch) against the
  seeded backend, 390×844. Harness-only manipulation: a `wx.request` delay shim to hold the refresh state long enough to screenshot,
  the `submitting` flag injected for CP-11, direct handler calls for CP-10, and two dummy club posts created through
  `/api/club/post/create` for CP-01. The walk scripts are in `walks/`. I did not see the 30–300 ms motion itself, only its state
  sequence and stills.

STATUS: COMPLETE
