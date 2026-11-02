# r2-club-publish: result

Branch `claude/page-polish-r2-club-publish`, cut from `integrate/page-polish-r2-20261102` (2f7bdab0). Scope: `chengyinhub-xcx/pages/club`,
`pages/publish`, `pages/crop`. Audit lane `club-publish` (REPORT.md), round 1 lane `impl-club-publish` (RESULT.md).

Overlap checked before editing: `claude/walk-fix-1102-l6-club` (touches club/topic-detail director area, enroll money rows,
event-ops, club/detail `id="manage-received-section"`), `claude/walk-fix-1102-l3-sell` (fabu full-screen `top`, topic-detail JS),
PR #709 (backend only: club event-ops cancellation), PR #710 (topic-detail `director.js`, broadcast sheet, one wxml line). None of
them touches the lines changed here: `.ctd-head`, `.ctd-sec`, `.ctd-topic`, the leaderboard rows, the stats note, `.slopes-node-row`,
`.pd-save-state`, `.pending-undo`, temp `addModule`, crop, join-requests cards, settlement amount. Textual merge conflicts are still
possible in `pages/club/topic-detail/index.wxml` (l6-club and #710 edit other hunks of the same file).

## How this was measured
- Rig `claude/cloud-walk-20261101-rig`, `SHA` set to the base. Docker Hub returned 429, so dockerd ran with `mirror.gcr.io` as a registry
  mirror. Data: `chains.py finance` (all four chains PASS: topic 990014, activities, club 7002 settlement).
- Before bundle = base worktree, after bundle = this branch (`CAND=… mpweb/build.sh`), 390×844. Measurements use
  `measure-player/tools/` (`run.mjs` + `measure.js` + `summ.py`) unchanged except the identity per page.
- Rig-only data changes (HARNESS, my own rig DB): `ums_member.role='club'` for fixture owners 9003 and 9008 (the fixture seeds them as
  `player`, so `/api/club/access/me` answered 403 「俱乐部身份无效或权限不足」 and every management page showed the access gate; round 1
  hit the same wall on join-requests); `club 7001.join_policy=1`; five dummy join requests to 7001 through the real `POST /api/club/join`
  (fixture players 9001/9004/9005/9006/9009).
- Motion is reported as state sequences or computed styles read in the page (I did not watch 30–300 ms motion by eye).

## Regressions
None found. All required gates pass (see the last section). The leaderboard gutter was checked for a member (9009 on 7002: no gutter,
the row keeps `--cy-space-3`) and for the owner (no float, no gutter).

## Row accounting

Round 1 did CP-01, CP-02, CP-03, CP-05, CP-10, CP-11. Every other row:

| Row | Outcome | What changed / why | Evidence |
|---|---|---|---|
| CP-04 「—报名」 | **done** | `pages/club/detail/index.wxml`: `'人报名'` moved inside the true branch; no data now shows a bare 「—」. | Code. The rig's projects all have a `signupCount`, so the 「—」 branch was not rendered. |
| CP-04b helper captions | **done** (lane prompt: remove) | Removed 「仅统计本俱乐部自办项目。」 (`.club-stats-note`) and 「每周一重置」 (`.ov-lb-note`) and their now-dead CSS (grep: no other user). Neither line carries data; the section title already says 「本周」, the stat label already says 「游玩人次」. **Test changed**: `club-detail-g1a-fixes.test.js` CU-C-51 pinned the caption; it now asserts the label 「游玩人次」 and that neither caption is present (owner rule 3). | `shots/03-club-detail-leaderboard-note.png`, `shots/04-club-manage-stats-note.png` |
| CP-06 fabu drag lift snaps | **done** | `.slopes-node-row` gets `transition: transform/box-shadow var(--cy-motion-press) var(--cy-ease-out)`. Reduced motion is covered by the global rule in app.wxss. | Code. The fresh rig draft has no nodes to drag (not exercised). |
| CP-07 fabu undo bar pops in | **done** | `.pending-undo` gets the global `cy-rise-in`. | Code (not exercised: needs a node to delete). |
| CP-08 fabu save chip flicker | **done** | `.pd-save-state` gets `transition: background-color/color var(--cy-motion-fast)`. | Code (not exercised). |
| CP-12 temp validation scroll teleports | **done** | `scroll-with-animation` on `scroll-view.cg-scroll`. | Rig, see CP-13. |
| CP-13 temp new module lands off-screen | **done** | `addModule` (same line, no line shift) scrolls to the new module's header: `sec-join` for 玩法, `sec-reward` for 完成奖励 (album has no reward header, so no scroll). Same clear-then-set pattern as `scrollToError`. | Rig, `pages/publish/temp/index` as 9003, remove then add 完成奖励. Before: `scrollTop` stays 0, `scrollIntoView` unchanged. After: `scrollIntoView:'sec-reward'` at +36 ms, then `scrollTop` 0 → 2 → 10 → 25 → … → 1255 over ~620 ms (eased). |
| CP-14 join-requests card vanishes | **done** | `leavingMemberId` marks the row (`.request-card--leaving`: opacity 0, `--cy-motion-fast`), the row is filtered out 160 ms later. The intro count updates at once. No line shift. | Rig, 7001 as 9003, approve 「玩家小城」 through the real confirm. Before: one `setData` removes the row (`[[0,'-',3]]`). After: `[[0, leaving=9004], [161 ms, removed, 4 left]]`. |
| CP-15 settlement money appears flat | **done** | `cnt.money(summary.settledAmountText, settledK)` plus `pageMotion.countMoney(this,'settledK')` on first ready only (same slow-fast-slow kit call as coop/merchant ledgers); skipped when the amount is 「金额待核验」. | Code. **Not exercised**: the rig's club 7002 has no platform profit-share rows (`state='empty'`, 「还没有平台分润记录」). |
| CP-16 topic-detail hard cut | **done** | `cy-fade-in` (opacity only, 200 ms) on `.ctd-head` and the four `.ctd-sec` blocks. Geometry untouched. | Rig, computed style. Before: `animationName none`. After: `cy-fade-in 0.2s` on head and sections. |
| CP-17 crop 还原 teleports | **done** | `settling` flag on reset only: `.crop__img--settling { transition: transform var(--cy-motion-standard) var(--cy-ease-out) }`, cleared after 260 ms or on the next touch start. Pan/pinch never carry the transition. | Rig: crop opened via `eventChannel cropInit`, image moved, 还原 tapped. Before: one `setData {tx:0,ty:0,scale:1}`, transition `all 0s`. After: `{…, settling:true}` then `{settling:false}` at +260 ms; the settling class resolves to `transform 0.22s cubic-bezier(.22,.61,.36,1)`. |
| CP-18 crop image pops in | **done** | `cy-fade-in` on `.crop__img`. | Rig computed style: `animationName cy-fade-in` (before `none`). |
| §4 crop 选取 a11y | **done** | 选取 gets `aria-role="button"`, a label matching its text, `aria-disabled="{{busy}}"`, and `hover-class` `none` while busy. | Code + a11y lint. |
| §4 `topicadd` shell still registered | **needs owner / integration** | Removing it means editing `app.json` (reserved). | — |
| §4 temp `this.data.lastSubmitAction =` | **not changed** | No visible effect (audit agrees); a logic-only change outside this lane's visual scope. | — |
| §5 redesign rows (fabu undo vs `cyToast.undo`, chapter-delete undo, temp row undo, full stale-while-refresh) | **too big** | Each needs new UI or cross-cutting logic; unchanged. | — |
| X-1 … X-4 cross-page patterns | covered by the rows above; the kit-level `enter(once)` option belongs to r2-shared. | — | — |

### Extra items from the lane prompt
| Item | Outcome | Measured before → after |
|---|---|---|
| topic-detail: action row → 「主题」 38.0 vs 「主题」 card → 「接下来」 11.4 | **done**. One rhythm for "gap before a section title": `.ctd-sec` `margin-bottom` `--cy-space-2-5` → `--cy-space-4`; `--ctd-head-h` 1044 → 1008 rpx (label bottom 976 + `--cy-space-4`). | label text → 「主题」 text **38.0 → 19.4**; topic card → 「接下来」 text **11.4 → 17.7** (difference 1.7 px, under the noise line). `shots/01-topic-detail-section-gaps.png` |
| topic-detail: `ctd-topic` radius 16.64 / padding 10.4 L, 12.5 R vs `ctd-next` radius 20.8 | **done**. `.ctd-topic` → `--cy-radius-xl` (same as 「接下来」 and 「未通过原因」, the page's section cards) and `padding: var(--cy-space-2-5)` on all four sides (cover radius 10 px = card radius 20 px − padding, concentric). | radius **16.6 → 20.8**, padding **(10.4, 12.5, 10.4, 10.4) → 10.4 all round** |
| club/detail: floating 「加入」 covers 「0.0 分」 on row 1 | **done**. The button is unchanged (position, size, label, hide-on-scroll). The page measures its width once after the club loads (`_measureJoinFloat`, appended at the end of the Page object) and, only while the float renders (`!isJoined && !isOwner`), the leaderboard rows reserve `width + 8 px` on the right via `--ov-lb-float-gutter`. Members and owners keep the old padding. | score right edge **361 → 306**, button left 315: no overlap. `shots/02-club-detail-join-float.png` |
| CP-04b helper captions | done (above) | |
| publish/temp and publish/fabu: feedback on add, delete, reorder, save only | **done** within that limit: fabu reorder (CP-06), delete (CP-07), save (CP-08); temp add (CP-13) and validation scroll (CP-12). Already present and left alone: fabu add toasts (「已添加到路线」, 「已放入…」), temp min/max toasts, story-module remove confirm (round 1). Not restructured. | see rows |
| Unmeasured club management pages | **measured and fixed** (next section) | |

### Unmeasured pages: measured (rig, 390 px)
Opened as owner 9008 on club 7002 (join-requests as 9003 on 7001, five pending requests).

| Page | Finding | Outcome |
|---|---|---|
| settlement | page title → first card **17.4** (convention ~9) | **done**: `cy-page-title + .section { margin-top: 0 }` (the coop/invite pattern) → **9.1**. `shots/05` |
| event-ops | page title → 「日期清单」 **16.4** | **done**, same rule → **8.1**. `shots/06` |
| badges | page title (with club subtitle) → 「创建勋章」 **28.3** | **done**, same rule (also when the absolute 「正在确认勋章列表…」 float sits between them, so the list does not jump while refreshing) → **11.6**. `shots/07` |
| enroll | title → subtitle **15.3** vs join-requests title → subtitle **11.1** (same role, same family) | **done**: `.sub` top padding 8rpx → 0 → **11.1**. `shots/08` |
| join-requests | cards: radius 16.6 (lg list cards), padding 12.5 even, gap 8.3 even, inset 16.6 | nothing to fix |
| roles, notify, governance | page title → first section **23.6** on all three (consistent with each other, not with the ~9 convention) | **Owner's choice** (below): `.section` margin is `40rpx /* ds-ok: Figma 区块间距 20px */`. |
| customers | title → count 11.1, chips/search/list rhythm 19.6 / 12.5 / 20.8 | not changed: different roles (count line, chip row, search, list) |
| members (club/detail 管理) | stats grid, project list, fold rows: radius 16.6, padding 12.5 even | nothing beyond CP-04b |

### Brand consistency (raw → token)
Every value changed here is a token (`--cy-space-4`, `--cy-space-2-5`, `--cy-radius-xl`, `--cy-motion-press/fast/standard`,
`--cy-ease-out`). No new literals. The only literal kept is `--ctd-head-h: 1008rpx` (page-local geometry, as before).

## Owner's choice
- **roles / notify / governance**: page title → first section **23.6 px** because the first `.section` carries the
  `40rpx /* ds-ok: Figma 区块间距 20px */` block gap. The settlement, event-ops and badges pages now sit at 8–12 px. Making these three
  match is the same one-line `cy-page-title + .section { margin-top: 0 }`, but it overrides a ds-ok Figma value, so I left it.
- **Helper captions not named by the lane prompt** (rule 3, no data): roles 「撤销后立即生效」 (`.page-note`), event-ops section notes
  (e.g. 「仅可修改无报名的未来场次」). They state rules, not data; not removed without a ruling.
- **club/detail leaderboard gutter while the float is hidden**: after scrolling down the float hides but the rows keep the gutter, so the
  layout does not jump when the float comes back. If the owner prefers the score to slide back, that is a transition on padding.

## Shared requests (for r2-shared)
- None. No `style/*`, shared `components/cy/*` or `utils/page-motion.js` / `utils/wxs/*` file was edited; the settlement count uses the
  existing `pageMotion.countMoney` and `count.wxs money`.

## Ledger and baseline drift
- `tests/unit/action-ledger.test.js` 「release 前重建 inventory，漏掉新增/修改控件必须变红」 is red, as expected. Exactly one control differs
  after a local rebuild (not committed): `pages/crop/index` `onConfirm` (line 61, 选取) gains `ariaRole: "button"`,
  `ariaLabelExpression: "{{busy ? '处理中' : (exportError ? '重试裁剪' : '选取')}}"`, `ariaDisabledExpression: "{{busy}}"`. **Integration:
  regenerate the ledger once.** Line numbers are otherwise stable: the removed caption lines in `club/detail/index.wxml` were replaced
  by comment lines of the same count, and the settlement `<wxs>` sits on the existing first line (a first draft shifted 114 controls;
  that was undone).
- JS line anchors: additions sit on existing lines or at the end of the Page object (club/detail `_measureJoinFloat`). U1/U3/U4/U5 pass.
- `tests/lint/ui-integrity-baseline.js`: no change needed (UI-GATE-0 PASS).
- **Test edited**: `tests/unit/club-detail-g1a-fixes.test.js` (CU-C-51) pinned the removed caption; it now pins the label and the
  absence of both caption elements. Not a reserved test.

## Checks (from `chengyinhub-xcx/`, base fetched)
- `node --test "tests/unit/**/*.test.js"`: 15553 tests, 15552 pass, 1 fail = the ledger drift above. (Run before the final line-count
  fix; the 4485 tests touching the changed pages were re-run after it: only the same ledger test fails.)
- `bash scripts/ds-hardcode-gate.sh --ci` 0 · `node tests/lint/ui-integrity.js --warn-as-error` 0 (UI-GATE-0 PASS) ·
  `node scripts/state-visibility-gate.js` 0 · `node scripts/ui-validity-gates.js` 0 · `ui-theme-unit-lint --theme` 0 · `--units` 0 ·
  `setdata-dead-field-lint` 0 (no new fields) · `a11y-tap-target-lint` 0 (no new small targets).

## Not exercised
- CP-15 settlement count: no profit-share rows in the rig (empty state).
- fabu CP-06/07/08: the fresh draft has no nodes; checked by code and gates only.
- CP-04 「—」 branch: every rig project has a `signupCount`.
- The join-float gutter for 「申请加入」/「等待主理人审核」 labels: on review-required club 7001 non-members cannot see the leaderboard
  (「通过审核后可看贡献榜」), so the wide labels never sit over scores; the gutter code is label-independent (measured width).
- Real-device rendering and native scroll-view animation: the rig is a browser runtime.

STATUS: COMPLETE
