# r2-player-theme: result (2026-11-02)

Branch `claude/page-polish-r2-player-theme`, from `integrate/page-polish-r2-20261102` (2f7bdab). Audit lane `player-theme`; round 1 lane `impl-player-theme`.
Scope: `pages/topic` (not merchantinfo, merchantapply, pricing, project-host/join/drawer), `pages/play`, `pages/activity`, `pages/templatedetail` (not shelf), `pages/square`, `pages/search2`, `pages/searchmap`, `pages/team`.
No backend, SQL, `style/*`, shared `components/cy/*` or reserved files were edited.

Overlap check, done first:
- `walk-fix-1102-l1-play` (#713) and `l6-club` edit `pages/play/index.{js,wxml,wxss}` and `baoming`.
- `l2-explore` moves the whole story/tools block in `pages/play/index.wxml` (P-28, roughly lines 146–400).
- `l3-sell` edits baoming lines 135 and 225–238.
- None of my edits sit on their lines. PL11 is skipped because l2-explore moves that exact block.

Rig: `claude/cloud-walk-20261101-rig` with `SHA` set to the base in a local `up.sh` copy. Docker Hub returned 429, so I used `mirror.gcr.io` as the registry mirror. I ran `chains.py player` (all four chains PASS). The before bundle is the base and the after bundle is this branch (`CAND=<worktree> mpweb/build.sh`). Pages without seed data were reached by setting page data in the rig, as the brief allows. I started the team run for real: 9009 checked in with 「我到了」 and the leader 9008 pressed 「开始行程」.
Measurements use measure-player's `tools/measure.js` at 390 px, where 1 px = 1.923 rpx.

## Regressions
None found. Every shot pair below changes only the intended element.

Two side effects:
- **Removing dead CSS surfaced a real small tap target.** On square/detail, the comment-list follow badge `.jia` is 31rpx (16 px). The a11y lint used to skip it, because the last `.jia` rule had the dead `.row1` ancestor. I gave it the page's existing transparent `::after` hit area (88rpx, same as `.post-head .gz`) plus `overflow: visible`. The badge looks the same before and after (`shots/square-detail-deadcss-unchanged.png`, before | after identical).
- **Team board loading is taller now.** Its skeleton is taller than the old `正在加载…` line, so in the after shot the page sits further down. This is intended (T12). The section's loaded height is close to the skeleton's.

## Row accounting

R1 = done in round 1 (`impl-player-theme`), checked present on base.

| id | outcome | what / why | evidence |
|---|---|---|---|
| P-T1 | R1 | — | code |
| P-T2 | R1 | — | code |
| P-T3 | R1 | — | code |
| P-T4 | R1 (differently) | `.bdim.cy-pressed{transform:none}` | code |
| P-T5 | R1 | — | code |
| P-T6 | R1 | — | code |
| P-T7 | R1 | — | code |
| P-T8 | Owner's choice | Buy sheet → session picker / checkout swaps layers in one frame. That is the approved overlay "exit handoff": a programmatic close that opens the next layer does not play the exit (see `_outThen` comment in play). | — |
| P-T9 | Owner's choice | `.bseg__u` overshoot `.4s cubic-bezier(.34,1.32,.64,1)` sits on the v13 buy sheet, `ds-ok`. | — |
| P-T10 | done (reduced only) | `_scrollToSecondPlain` (reduced-motion only) `500 → 0`. `scrollToFirst` uses `reducedMotion ? 0 : 500`. The full-motion 500→350 change is under Owner's choice (v13 cover/detail). | code; the rig does not run `pageScrollTo` |
| P-T11 | done | `info.averageRating \|\| 0` → `\|\| '—'`. This only renders when `commentCount > 0`, so the seed cannot reach it. | code |
| P-T12 | done | Node duration: `--分钟` → `—` (with no unit); `0` → `0分钟`. The seed always has a duration. | code |
| P-T13 | Owner's choice | `.topic .topic-teams__card` 35rpx vs sibling cards 27rpx, `ds-ok` v13. | — |
| PL1 | R1 | — | — |
| PL2 | R1 | — | — |
| PL3 | R1 | — | — |
| PL4 | R1 | — | — |
| PL5 | already fixed | `.woo` has `fade --cy-motion-standard`, and `.woo__hero` has `floatUp --cy-motion-slow` (brief: "Per-station award fade is done"). | code |
| PL6 | done | `选择已记录` and `任务已提交`/`证据已记录`/`核验编号` toasts now fire only when the clue sheet is closed. While it is open, the in-sheet `.gmod__write` line is the only feedback. The status-in entrance for that line was not added, because it would need a new animation. `player-game-session-entry-contract` still passes, since its toast path runs with the sheet closed. | code + tests |
| PL7 | Owner's choice | The milestone rail sits on the chapter-finish page (`.finsheet`, approved). | — |
| PL8 | Owner's choice | Finish stats count-in: same page. | — |
| PL9 | not done | The advanced-game sheet has no entrance. Adding one needs either a new `@keyframes` in this isolated component, which hits the keyframes ratchet, or importing proto-sheet, and a `reducedMotion` prop the component does not have. The block also remounts every time `hide-panel` toggles (inline kit), so an entrance would replay. That is bigger than an S fix. | code |
| PL10 | done | `正在准备玩法…` → `<cy-skeleton type="list" count="2" loading-label="正在准备玩法">`. It shows only on first load (`state:null`). | code; not opened in rig |
| PL11 | skipped (overlap) | `walk-fix-1102-l2-explore` P-28 moves the whole tools block that holds these rows. | — |
| PL12 | done | `.st__tries-dot` transitions `background-color var(--cy-motion-fast) var(--cy-ease-out)`. | code |
| PL13 | needs owner | A `doneCount` pulse would be new motion, not a timing fix (rule 5). | — |
| PL14 | done | `continueNext` → `_outThen('sheet')`, the same exit as ✕ and the mask. | code |
| PL15 | Owner's choice | `.full` `fade .3s ease`, `ds-ok Figma 实测(用户裁决字面值)`. The nearest token is 220ms. | — |
| PL16 | R1 | — | — |
| PL17 | Owner's choice | 23rpx and 31rpx, `ds-ok 用户裁决字面值`. | — |
| A2 | R1 | — | — |
| A5 | decided: keep light | Owner decision: official pages stay light for everyone. | — |
| A6 | R1 | Plus the extra item below (third skeleton card). | — |
| A7 | needs owner | The full-screen `cyLoading` duplicates the button's `提交中...`. It is also the only thing that blocks back/navigation while the order is being created. Removing it changes behaviour, not just looks. | code |
| A8 | done | Removed the `（点击切换）` helper caption (rule 3). The arrow and `aria-label="切换票种…"` remain. It only renders with more than one ticket type, and the seed has one, so there is no shot. | code |
| A9 | needs owner | `完成活动任务后按上方配置发放` / `实际发放结果以活动结算为准` reads as settlement disclaimer copy. Keep or drop is the owner's call. | — |
| A10 | done | `--%` → `—`; null `current` / `threshold` → `—`. | `shots/a10-official-progress-before-after.png` |
| A18 | R1 | — | — |
| A22 | R1 | — | — |
| D1 | R1 | — | — |
| D2 | done | Success state now fades in on mount. The class string `xb-scroll xb-scroll--bleed` is pinned by `ada-p0-nav-state-contract`, so the fade sits on `.xb-scroll--bleed` in `templatedetail.wxss` (global `cy-fade-in`, opacity only). This covers templatedetail and topic-detail. | code |
| D3 | done | `#xbScroll` mounts only after load, so the `onReady` call never attached. The `loading:false` callback now calls `onReady()` once, guarded by `_heroRecedeApplied` and `!loadError`. The recede values are unchanged. | code; scroll-timeline not visible in rig |
| D4 | done | Stars fill by rating: 4.2 → ★★★★☆. Before, all five were outlines. | `shots/td-inset-stars-before-after.png` |
| D5, D6 | out of scope | `templatedetail/shelf` is excluded from this lane. | — |
| S3 | R1 | — | — |
| S4 | R1 | — | — |
| S14 | R1 | — | — |
| S16 | R1 | — | — |
| S15 | false positive | `cy-error load-failure` wins over `auto-back` (`components/cy/error/index.wxml:3`). It renders a blank veil with no sheet and no back, so the ghost `返回上一页` is the only exit. | code |
| S17 | done | The popover panel fades in with `cy-fade-in var(--cy-motion-fast) var(--cy-ease-out)`. The keyframes come via `@import` of `style/fade-in.wxss` (the documented way for isolated components), so the keyframe count does not change. | code |
| S21 | not done | No skeleton type matches a chip grid. `type="list"` (rows) would still change shape. → shared request. | — |
| M1 | false positive | The pill suffix shows on the map. The `N 个活动无位置，仅列表显示` tip is inside the full-height results sheet, so the two are never on screen together. `ui-copy-states-contract` also pins the pill copy. | code |
| T1 | R1 (gate) | APPLIED auto-ack remains the owner's call (round 1 note). | — |
| T11 | done | `joinedCount / maxMembers` (detail and join) and `totalTasks` now fall back to `—`. A real `0` stays `0`. | `shots/t11-t12-team-before-after.png` |
| T12 | done | Board and ranking `正在加载…` → `<cy-skeleton type="list" count="2">` with a labelled status. | same shot |
| T13 | needs owner | Dropping the constant `邀请制 / 加入方式` cell would leave a one-cell stats row: a layout change. | — |
| T19 | done | The `邀请队友` share `<button>` gets `hover-class="cy-pressed" hover-stay-time="80"` instead of the native grey wash. | code |

Section 3 ("real bugs") rows:

| item | outcome |
|---|---|
| topic:1662 raw `res.errMsg` toast | needs owner: it mirrors a WeChat message, and the brief says not to reword |
| topic:886 node card → creator-only templatedetail `scope=my` | needs owner: routing / product, not polish |
| topic checkout `loading` bound only to `selfPlayConfirmLoading` | needs owner: binding `_selfPlaySubmitting` would need a new data field |
| `_retrySelfPlayPayment` dead | not removed: deleting JS lines shifts line-keyed baselines (U1/U4/U5) |
| shelf `background .6s` on a gradient | out of scope (shelf) |
| `app.json` darkmode | reserved file |
| official-inbox hover precedence | **done**: `… ? true : false ? 'none' : 'cy-pressed'` → `… ? 'none' : 'cy-pressed'` (×3). The busy state now really yields `none`; no visible change |
| searchmap `hover-class` on `<map>` | not done: the map has `bindtap`, so removing it adds one bare-tap entry to the press ratchet (51) |
| searchmap `最热` sort | needs owner: needs a backend check (out of scope) |
| baoming dead `.paysuc__*` CSS | **done**: grep shows no users. Removed 71 lines, which also removes the `paysuc-spin` keyframes. The `KEYFRAMES_EXEMPT` entry in `motion-property-ratchet.test.js` was deleted, because that test requires every exempt name to exist (pinned structure removed) |
| square/detail dead `.row1–.row4`, `.row3-*`, `.sport-block` | **done**: grep shows none in the wxml. 44 rules removed or trimmed; `.gbox1` and `.item-link .comment-report` kept from the shared selector lists. Shot pair identical |
| official-detail `.od-refresh-state` top 16rpx | not done: the page has no status-bar data, and the rig cannot show safe areas |
| play reduced-motion misses role card / sheets | **done** (extra item below) |
| play `flashLock()` no callers | not removed (JS line baseline) |
| play/circle json light background | not changed: the page root has no `theme-dark` class, so the page colour depends on the global token. Check on a device |
| play `vibrateShort` ignores reducedMotion | needs owner (haptics policy, not motion) |
| play correct-answer `r.msg` toast | not changed: it mirrors the backend message |

## Extra items (lane prompt)

- **`.rolemask` scrim press.** `hover-class="none"` raises the press ratchet: `press-feedback-coverage-contract` 51 → 52, and `pages/play/index.wxml` is registered at 15 swallowers. So I did what round 1 did for `.bdim`: kept `cy-pressed` and added `.rolemask.cy-pressed{transform:none;opacity:1}`. The full-screen scrim no longer scales or dims when pressed. The same neutraliser is on the play `.mask` scrims (node, clue and board sheets), placed before `.mask.is-out`, so the exit still wins.
- **Reduced motion outside the root.** The role card, role mask, clue-sheet mask/sheet and board mask/sheet now carry `{{reducedMotion ? ' play--reduced-motion' : ''}}`. The existing kill-switch (`index.wxss:44-55`) matches the element itself and its descendants. The nodes were not moved. The missing `--cy-psheet-max-h` on those two sheets (inferred notch risk) is not changed, because it would change sheet height without a device to check.
- **Payment loading (`p08-baoming-loading`).** `skeletonItems` is now `[1, 2]` (it only feeds the loading skeleton count), so two whole cards sit above the bottom-bar placeholder instead of a third cut in half. The settled page is unchanged. Shots: `p08-baoming-loading-before-after.png`, `p08-baoming-settled-unchanged.png` (before | after, settled page identical).
- **`pages/activity/official-*` stay light.** Not touched.
- **Press styles.** `.team-action--pressed` (.72) and `.om-card--pressed` (.78) → `cy-pressed` with `hover-stay-time="80"` (team/detail ×3, team/join ×2, official-mine ×3). Both page rules were deleted. A static shot cannot show this.
- **`.woo` overlay.** See Owner's choice.

## Newly measured pages (390 px)

| page | how reached | result |
|---|---|---|
| template detail | `templatedetail?id=1` + page data | **Fixed:** page inset was 20.8 px (40rpx, `ds-ok` "no half-step token", no owner ruling) on every block, vs the 16.6 px convention → `--cy-page-x` (8 rules). **Fixed:** `.xb-edition-card` radius 8.3 px (`--cy-radius-sm`) next to four sibling info cards at 12.5 px → `--cy-radius-md`. Title→content 15.4 (cards) vs 17.4 (text) is text half-leading noise. Block gaps 45.8–51.5 come from text vs card edges, not from rules. Shot `td-inset-stars-before-after.png` |
| activity list with data | page data (3 events) | Inset 16.6, gap between cards 12.5, card-internal gaps even. **Needs owner:** the cover card is `cy-card` (shared, `--cy-radius-md`, 12.5 px), but it is a media card; the convention tier is `--cy-radius-2xl` (22.9 px) as on the home feed. Changing it would make this one `cy-card` differ from every other use, so it is the owner's call |
| post detail | `square/detail?id=13` + page data | Header avatar inset 12.5 px; body column at 64pt (Figma 379:2571, cited in the wxss) → Owner's choice. Comment rows even (20.6 / 20.6). Before and after identical (`square-detail-deadcss-unchanged.png`) |
| topic detail tabs (v13) | real topic 990014; host scrolled to screen 2, `switchTab` | See Owner's choice. Shot `measure-topic-tabs.png` |
| play during a run | real lead run (9008 leader view, pack screen) | `.leadbar` inset 14.5 px vs 16.6 (2.1 px, game screen, `ds-ok` Figma values) → Owner's choice. The 9009 view shows `本轮探索已完成`: correct, because the seed chain completed all 4 nodes for 9009. Shot `measure-play-run.png` |

## Owner's choice (approved or `ds-ok` owner/Figma values; not edited)

| item | measured / value | standard |
|---|---|---|
| Per-station award `.woo` | `rgba(6,8,14,.92)`: the page shows through (`队伍集合中` / `等队长开始行程…` visible behind the trophy) | scrims elsewhere fully hide the page. `shots/owner-woo-overlay.png` |
| Theme detail block rhythm `.tabcont` (v13) | det3 → det4 → det3 → lineup → det6 = 27.6 / 49.8 / 40.3 / 24.8 px | equal same-role gaps |
| Theme detail cards (v13) | `.sess` inset 29.1, r 16.6; `.det6-empty` inset 16.1, r 14.0; `.rp` r 18.2; `.rmap` / `.route-note` r 14.0 | 16.6 inset; `-lg` / `-md` tiers |
| `.topic-teams__card` (P-T13) | 35rpx vs 27rpx siblings | one tier |
| Buy-sheet underline (P-T9) | `.4s cubic-bezier(.34,1.32,.64,1)` (overshoot) | no bounce on popups |
| Buy sheet → next layer (P-T8) | one-frame swap | approved exit handoff |
| `scrollToFirst` full motion (P-T10) | 500ms | audit suggests 350ms |
| play `.full` (PL15) | `fade .3s ease` | `--cy-motion-standard` 220ms |
| play cards (PL17) | 23rpx / 31rpx | `-md` 24 / `-lg` 32 |
| play `.leadbar` | inset 14.5 px | 16.6 |
| chapter-finish rail / stats (PL7, PL8) | static width / plain numbers | — |
| post detail head | 12.5 px inset, body at 64pt (Figma 379:2571) | 16.6 |

## Shared requests (for r2-shared)
- `cy-skeleton` has no chip/tag-grid type. search2 `类别` loading (S21) shows two big cards that turn into a chip grid.
- `cy-card` media variant: if the owner wants the activity-list cover cards on the media tier (`--cy-radius-2xl`), add a variant rather than per-page overrides.

## Ledger and baseline drift
- `tests/unit/action-ledger.test.js` → `release 前重建 inventory…` fails. This is ledger drift: wxml lines shifted (templatedetail stars 5 → 1 line, team skeletons) and hover-class values changed (team/official-mine). The ledger was not regenerated (reserved).
- No other red. Full run: 15552 pass, 1 fail (the ledger test above).
- `ds-hardcode-gate --ci`, `ui-integrity --warn-as-error`, `state-visibility-gate`, `ui-validity-gates`, `ui-theme-unit-lint --theme` / `--units`, `setdata-dead-field-lint` and `a11y-tap-target-lint` all exit 0.
- JS line counts are unchanged in every edited JS file (all edits on existing lines).
- Test edit: `motion-property-ratchet.test.js` drops the `paysuc-spin` exemption, because those keyframes were deleted with the dead CSS.

## Not exercised
- Press, timing and transition changes cannot be seen in static shots:
  - scrim press neutralisers
  - `cy-pressed` convergence
  - PL6, PL12, PL14
  - S17 popover fade
  - D2 mount fade
  - D3 scroll-linked recede
  - P-T10 scroll durations
  - reduced-motion classes on the out-of-root play overlays
- Not reachable with the seed:
  - P-T11 and P-T12 (need a theme with comments but no average, or a node without a duration)
  - A8 (needs more than one ticket type)
  - PL10 (needs the advanced-game panel to open)
- The rig has no safe areas or notch, so the `--cy-psheet-max-h` question on the two outside-root sheets is unverified.

STATUS: COMPLETE
