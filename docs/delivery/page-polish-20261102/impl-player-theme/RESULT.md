# impl-player-theme: result (2026-11-02)

Branch `claude/page-polish-20261102-impl-player-theme`, based on `integrate/page-polish-20261102`. Audit: `player-theme` REPORT.md.
Scope: P0/P1 rows plus the spacing and radius rows. A5 and PL5 are open decisions, so I skipped them. No backend, SQL or kit files changed.

## Findings

| id | outcome | files | evidence |
|---|---|---|---|
| P-T1 | done | topic/index/index.{js,wxml,wxss} | The stagger `transition-delay` now applies only under a new `.tab-run` class. `switchTab` sets it with `tabPre`, and a sequence-guarded `_later(1400)` clears it once the entrance has played. Direct children with `.cy-pressed` use `--cy-motion-press`. This fixes both press and release. A press-only rule would still leave release up to 0.35 s late. The entrance order and timings are unchanged. Reason: code. |
| P-T2 | done | same | The inline chapter-card delay is emitted only while `tabRun` is set. `.story-cc.cy-pressed` uses the press duration for opacity and transform and keeps the `.45s` height value. No `!important` was needed. Reason: code. |
| P-T3 | done | topic/index/index.wxss | `.hero-desc` joins the `slide2--enter` rise at `.09s` (between title `.06s` and acts `.12s`) and the reduced-motion list. Not captured: the rig does not perform `pageScrollTo` to screen 2. `currentScreen` became 1 but the cover stayed in view. |
| P-T4 | done, differently | topic/index/index.wxss | Changing to `hover-class="none"` breaks the press ratchet (`press-feedback-coverage-contract`: 52 > 51, and topic/index is not a registered scrim file). So I kept `cy-pressed` and added `.bdim.cy-pressed{transform:none}`. `.bdim.is-in` already holds opacity at 1, so the full-screen dim no longer scales and the page edges no longer flash. |
| P-T5 | done | topic/index/index.js | `onSessionPicked` → baoming, and both reveal exits (`开始游戏` / `放进票夹`) in `_goAfterReveal`, now use the file's `if (this._navigating) return; this._navigating = true; … complete: () => { this._navigating = false; }` pattern. `_finishReveal` also returns while `_navigating` is true or `reveal.show` is already false. This covers the marketing-offer branch, where a second tap used to bypass the panel. |
| P-T6 | done | topic/index/index.js | The reveal and checkout are hidden in `navigateTo`'s `success`, so the theme page no longer shows between the reveal and the next page. If navigation fails, the reveal stays up and can be tapped again. The offer branch still hides first, because it opens the result panel. The flash itself is inferred and was not seen. |
| P-T7 | done | topic/index/index.wxml | Removed the loading block's `padding-top`, so the skeleton hero sits under the transparent top bar like the full-bleed cover. `.slide1` gets the global `cy-fade-in` (already imported via `style/components.wxss`). Not visually evidenced: the rig renders `cy-skeleton` bars invisible on this dark page, both before and after. |
| PL1 | done | play/index.wxss | `rolecard-in` now runs at `var(--cy-motion-slow) var(--cy-ease-out)`, with no overshoot. `.rolemask` fades in with the file's `fade` keyframe at `--cy-motion-standard`. |
| PL2 | done | play/index.wxss | `.fx-tile.is-dealt` also transitions `filter`. `.fx-tile__im` transitions opacity and filter (`--cy-motion-standard`). Completing a station and unlocking a station now fade instead of snapping. The deal-flight timings are untouched. |
| PL3 | done | play/index.{js,wxml}; tests/unit/{longrun-own-c01-39,play-overlay-spec-contract}.test.js | `_outThen` now takes `gmod` and `board` as well as `sheet` and `journal`, through a flag map and unchanged logic. `closeGameModule` and `closeBoard` route through it. `closeBoard` still bumps `_boardReadSequence` first. The openers reset `gmodOut` / `boardOut`. Two test edits follow from this: `closed board is never reopened` now waits 320 ms instead of `setImmediate` (the board now unmounts after its 280 ms exit, and the assertion is unchanged). The overlay-spec literal `class="sheet"` became `/class="sheet[ "]/`. |
| PL4 | done | play/index.{wxss,wxml} | `.play-loading` uses `align-items:flex-start` with `padding-top:{{chrome.contentTop || 0}}px`. Not visually evidenced, for the same invisible-skeleton reason as P-T7. |
| PL5 | skipped | — | Open decision per BRIEF. |
| PL16 | done | play/index.wxss | 8 × `border-radius:999rpx` → `var(--cy-radius-pill)` (token = 999rpx, no visual change). |
| PL17 | skipped | — | Values marked `ds-ok 用户裁决字面值` (owner ruling). |
| A2 | done | activity/list/index.js | `pageMotion.enter` runs only when `applyFilter` is not a keyword change (`!extra \|\| extra.keyword === undefined`). It still runs on load and tab change. |
| A5 | skipped | — | Open decision per BRIEF. |
| A6 | done | activity/baoming/baoming.wxml | During `pageState==='loading'` the page now renders `.bmbottom-spacer` plus a `.bmbottom` with a 20 px consent-row placeholder and a disabled, empty `cy-btn` (`accessibility-label="正在加载"`, `aria-hidden` bar). Shots `a6-baoming-loading-before/after.png`. Note: the page's `cy-skeleton type="card" count="{{skeletonItems.length}}"` also renders nothing in the rig before and after (it is not my line). Trade-off: for a gated or error result, the placeholder bar disappears with the load. Identity 9004 lands on `来自俱乐部的活动，加入后查看` (`a6-baoming-ready-after.png`). |
| A22 | done | activity/baoming/baoming.wxss | `.radio` `border-radius: 30rpx` → `50%`. |
| A18 | done | activity/official-mine/index.{wxml,json,wxss} | The bare `<button>` is now `<cy-btn size="sm" variant="secondary" class="om-recover" catchtap="recoverBroadcast">安全恢复原投递</cy-btn>`. `cy-btn` re-emits a bubbling `tap`, so `catchtap` still stops the card's `openBroadcastStats`. Not captured: no recoverable broadcast in the seed. |
| D1 | done | templatedetail/topic-preview/index.wxml | Delays: title `.45s → .25s`; ✓ rows `.6 + .12i → .35 + .08i`; `查看详情` `1.05s → .6s`. Not captured: no template preview in the seed. |
| S14 | done (partly false positive) | square/detail/index.wxml | `关注`, comment like, `回复`, `举报`, `删除` now have `hover-class="cy-pressed" hover-stay-time="80"`. The heart swap is a false positive: `.comment-like.is-on` already turns the icon `--cy-color-status-danger`, and `ann-a-ui-contract` A10 locks the comment icon to `name="heart"`. |
| S16 | done | square/detail/index.wxss | `12rpx → --cy-space-1-5` and `36rpx → --cy-space-4` (32). No 4 rpx token exists, so `4rpx → --cy-space-1` (8 rpx), a 4 rpx visual change on two gaps. Not captured: no post in the seed. |
| S3 | done | search2/result/index.{js,wxml,wxss} | Removed the `pageMotion.enter` call, its `require`, the container `--pm-*` style and the 11-line `pm20` block. `cy-fly-in` remains and only plays on newly mounted keys, so `加载更多内容` no longer replays existing cards. |
| S4 | done | search2/result/index.{js,wxml} | `onKeywordInput` no longer clears `resultGroups` / `visibleResults` / `resultTabs`. If the edit interrupts an in-flight search, a `keywordEditing` flag holds back `没有找到` until `runSearch` clears it. A finished empty result keeps its empty state while typing. Shots: `s4-typing-before.png` shows `没有找到` while 「探店日」 is being typed. `s4-typing-after.png` keeps the 4 merchant results. |
| T1 | done (gate only), APPLIED auto-ack reported | team/join, team/detail, activity/official-detail, activity/official-organizer (js+wxml); tests/unit/team-recovery-card-gate.test.js | Details below. |

### T1 details

- **The gate.** `recoveryShown = !!(state \|\| busy) && !(fresh && busy)`. A page sets `_freshCommand = true` immediately before its own `start()` call. The flag clears on the first emit that is not busy, and `_openRecovery()` resets it for every new recovery instance (onLoad, onShow after hide, account change).
- **When the card is hidden.** Only while a command this page instance just sent is in flight. The button keeps its own busy state (`加入中…` / `joining`).
- **When the card still shows:**
  - The reply fails or is UNKNOWN.
  - The app crashes or reloads mid-flight (a new instance reads UNKNOWN from storage, with `fresh` false).
  - The page is hidden and shown again mid-flight.
  - The command is APPLIED, REJECTED or CANCELLED (the receipt stays until `确认结果`).
  - The player is querying, retrying or cancelling from the card (it stays visible while busy).
  - Storage errors occur.
- **Tests.** `team-recovery-card-gate.test.js` (7 tests) proves each of those cases, including that no new command is sent while a reloaded UNKNOWN is pending. All existing recovery tests (`w8-07-*`, `team-p1`, `bl217-*`, `official-organizer-console`, `team-detail-join-mode`, `apply-expire`, `guest-browse-login-on-write`) pass.
- **Not changed: auto-acknowledging APPLIED.** The report's suggestion to auto-acknowledge APPLIED and show `toast.success` conflicts with existing contracts. `bl217` asserts `commandRecovery.state === 'APPLIED'` after success, with the test titled `原请求已执行后业务刷新断网:保留回执…`. `w8-07-enrollment-recovery` asserts the same. The receipt is the proof of the outcome when the follow-up read fails. So after a normal join the receipt card still appears with `确认结果`. That is the owner's call.

### Bundled with T1
- The same in-flight gate on team/detail, official-detail and official-organizer. They share the identical `commandRecovery.state || commandRecovery.busy` condition, and the report names all four.

## Checks (run from `chengyinhub-xcx/`)
- Full `node --test "tests/unit/**/*.test.js"`: 15534 tests. 8 failed on the first pass, and every one except `action-ledger` was mine and is now fixed. The six affected files pass on the base worktree (76/76) and on this branch.
- `tests/unit/action-ledger.test.js` → `release 前重建 inventory` fails. This is inventory count drift from the added and changed tap elements (recovery button, placeholder `cy-btn`, sheet classes). The file is reserved and I did not regenerate the ledger. The integration step should regenerate it once.
- `scripts/ds-hardcode-gate.sh --ci` exit 0. `ui-theme-unit-lint --units`: pass. `setdata-dead-field-lint`: pass (no new entries; it notes 6 baseline entries now fixed, and I did not narrow the baseline). `a11y-tap-target-lint`: 0, no new findings.

## Rig
- Started with `up.sh`. The first run hit a Docker Hub `429 Too Many Requests` on the pinned redis image, and the retry with `RIG_SKIP_BUILD=1` succeeded. I ran `chains.py player` (topic 990014) for data.
- The backend is master a1d30fb. The browser bundle was built with `CAND=<worktree> build.sh` from the base branch (before) and this branch (after).
- Loading states were rendered by setting the page's own flag (`topicLoaded:false`, `pageState:'loading'`, `loading:true`), because real loads finish before the first frame.
- What I could not see:
  - Skeleton bars (invisible in the rig on dark pages).
  - Screen-2 entrance (`pageScrollTo` does nothing in the rig).
  - Press, transition and timing changes (a static shot cannot show them).
  - Team, official, square and template pages (no seeded invite, broadcast, post or template).
- Shots are in `shots/`: `s4-*`, `a6-*`.

## Notes for the owner
- `pages/play/index.wxml` `.rolemask` also has `hover-class="cy-pressed"` on a full-screen scrim (same edge flash as P-T4). I did not touch it.
- The baoming `cy-skeleton` renders empty in the rig in both builds (`count="{{skeletonItems.length}}"`). Worth checking on a device.

STATUS: PARTIAL — all in-scope P1, spacing and radius rows are implemented. Open items: the owner decides on T1 APPLIED auto-acknowledge; P-T3, P-T7, PL4, A18, D1 and S14/S16 have no rig shot (reasons above); the action ledger needs regenerating at integration.
