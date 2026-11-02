# Player-home lane: polish audit report (2026-11-02)

Lane: player-home. Audience: the player (dark theme). Base: `claude/mini-polish-audit-20261102` (= master a1d30fb + brief).
Scope: `chengyinhub-xcx/` pages/index, pages/template (+ its content component `pages/templatedetail/shelf`), pages/talent/list,
pages/member/index (+ placeholder `components/cy/profile-loading`), pages/roam, subpackageRoam (all pages), components/tabBar,
plus the shared state components these pages use (`cy-error`, `cy-empty`, `cy-skeleton`, `utils/page-motion.js`).
Method: static reading only. Nothing was rendered. Evidence `code` = read directly; `inferred` = deduced from code paths.

## 1. Summary (top 5 by player impact)
1. **Roam "附近活动" shows a false empty state on first open.** There is no loading flag, so "附近暂无活动" renders until the request returns, then real cards replace it (R-01, P0). The same false-empty and fake-0 pattern recurs on the 附近 map (「附近暂无节点」, 「· 0 个」) and in my-teams stats after a partial failure (N-01, MT-01/02, P1).
2. **The tab bar behaves three ways across the five tabs.** Home hides it until the player scrolls 120px, 发布 hides it while scrolling down, and the other tabs keep it fixed. On a fresh home open the player sees no tab bar at all (T-01/T-02, P1).
3. **Returning to a tab replays loading.** 发现/俱乐部 refetches on every `onShow`, adds a "正在更新俱乐部" row and re-runs the card rise. Roam's 护照 blanks to bare text "正在读取漫游护照…" on every show or tab switch (C-01, R-02, P1).
4. **Home pull-to-refresh collapses the feed.** `loadPageData` empties both feed lists before refetching, so the page shortens and the scroll position jumps. Every section also re-runs its entrance (H-02/H-03, P1).
5. **Home entrance order follows network order, not top-to-bottom.** Each section rises whenever its own request lands. A small fixed stagger per section (first load only) would teach the hierarchy like reference A without new motion (H-01, P1).

## 2. Per page (ordered by expected traffic)

Finding row format: `ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence`

### 2.1 首页 pages/index (cold-start landing)
Already good: every section has separate loading, error and empty branches. Load-more appends rows by array path. The banner has a single 800ms entrance. Pull-to-refresh lives inside the scroll-view. Reduced motion is honoured.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H-01 | index | pages/index/index.js:1182; index.wxml:63,115,155,198 | section entrance (编辑精选 / 附近活动 / 即将上线 / feed) | `pageMotion.enter(that,'pm_'+listName)` fires when each request returns; all sections use the same `.32s` with no per-section offset | Reveal order is whichever request wins, so a lower rail can rise before 编辑精选. Hierarchy is not taught by order (ref A) | First load only: add a fixed section offset to the existing `--pm-*-d` style (reco 0, nearby +60ms, upcoming +120ms, feed +180ms), or delay a section's `enter` until the section above has entered. Keep curves and distances | motion-entry | S | low | P1 | code |
| H-02 | index | pages/index/index.js:1182, 1284, 1391 | all sections on pull-to-refresh | `enter` runs on every list arrival, including refresh | Content already on screen fades to 0 and rises again on each refresh, which reads as a flicker | Call `enter` only when the list was empty before (first paint), or skip it when `this.data.refreshing` | motion-transition | S | low | P1 | code |
| H-03 | index | pages/index/index.js:737-756 (`loadPageData`) | 主题/活动 feed | refresh sets `bottomTopicList: []` and `bottomActivityList: []` before refetching | The feed is removed during refresh, so page height drops and the scroll position jumps (breaks "page shape never changes while loading") | On refresh, keep the current rows and replace them when the response lands (`isRefresh` already replaces). Drop the pre-clear setData | loading-state | S | low | P1 | code |
| H-04 | index | index.wxml:198-232 | feed first load / empty | first load shows only "正在加载更多" under the tabs. A successful empty list shows nothing | Wrong copy on first paint (nothing is "more" yet), no shape placeholder, and no empty state for the 主题/活动 tabs | Reuse the section pattern: `cy-skeleton type="feed-card" count="2"` while `isBottomLoading && !list.length`, and `cy-empty` when loaded and empty | loading-state / empty-state | S | low | P2 | code |
| H-05 | index | index.wxml:155 | 即将上线 section | the whole section (title + skeleton) renders while loading, then is removed if the result is empty | Content below jumps up by a section height after load | Accept it (owner chose "hide when empty"), or reserve the space only when the last session had items. Recommend leaving it and noting it | structure | S | low | P2 | code |
| H-06 | index | index.wxml:105 | 编辑精选 card second line | "编辑精选 · 参加条件以详情为准" on every card | A static helper caption with no data (rule 3), repeating the section title | Remove the line (`metaLine` above already carries data) | structure | S | low | P2 | code |

### 2.2 漫游 pages/roam (entry/passport screens; map run-state out of scope except for the drift census)
Already good: the opening wipe, stamp and arrival motions are prototype-matched and gated. The run HUD uses one top prompt stack (`hud-slots.wxs`). Error versus empty is handled in the map run state. The tab bar only shows on the entry screens.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| R-01 | roam | index.wxml:153-168; index.js:307, 5796-5839 | 附近活动 peek | no loading flag; the `wx:else` branch renders "附近暂无活动 / 打开地图寻找附近活动" until `entryCards` arrive (and again during `_loadEntryFallback`) | A false empty state on the most-visited roam screen. The player is told there is nothing nearby, then cards appear | Add an `entryLoading` flag around `_loadEntryMap`/fallback and render one card-shaped skeleton (540×330, the existing `.entry-card` box with skeleton background) in that branch. The v13 card itself is untouched | loading-state | S | low | P0 | code |
| R-02 | roam | index.js:729-760, 1076 (`onShow` → `_loadIntroPassport`) | 护照 tab | every show or tab switch clears `passport` to empty arrays and renders bare text "正在读取漫游护照…" (index.wxml:173) | Content collapses to one unstyled line, then rebuilds. The page shape changes and a revisit looks like a cold load | Keep the previous `passport` while refreshing (set `passportLoading` only when `passport.stats` is empty). For the first load, swap the bare text for a skeleton with the `.pp-stats` + `.pp-row` geometry | loading-state | S | low | P1 | code |
| R-03 | roam | index.wxml:159 | 附近活动 error | `<cy-error load-failure="{{true}}" />` = blank veil, no retry (by `cy-error` design) | The roam entry has no pull-to-refresh, so after a failure the only recovery is leaving and returning | Do not add copy (load-failure policy). Retry `_loadEntryMap` once on the next `onShow`, which already happens, and say so in notes. No change proposed beyond R-01 | loading-state | — | — | P2 | code |
| R-04 | roam | index.js:771-772 | 护照 tiles 探索目标 / 时光胶囊 (also 勋章墙) | second lines "给自己定个小目标", "写给以后的自己", "我的徽章" | Helper captions with no data (rule 3). The neighbouring tiles carry counts ("N 次已完成旅程", "N 张本机邮票") | Show a real count when the passport summary has one, otherwise "—". Owner P-29 set the tile structure, so confirm first | structure | S | low | P2 | code |
| R-05 | roam | index.wxss:313 vs 317/339/609 | floating cards in the prompt stack | `.topcard`/`.roam-visit-card`/`.g3` 35rpx (prototype 18px); `.pace-card` 28rpx; `.zc-card` 36rpx; `.roam-prompt` 28rpx; `.dcard` 38rpx | Cards that swap in the same slot have four radii (28/35/36/38). Only 35 and 38 are prototype-annotated | Set `.pace-card`, `.roam-prompt` and `.zc-card` to 35rpx (the prototype's 18px value already used by `.topcard`). Leave `.dcard` (prototype 20px) | radius | S | low | P2 | code |
| R-06 | roam | index.wxss:461 | 抵达 body | `r-floatUp .7s` after `r-fade .4s` | The slowest non-celebration entrance on the page. The owner asked for "a little faster" | Shorten `r-floatUp` to ~.45s, keep the curve. Do not touch `r-stamp` (medal, v13 approved) | motion-entry | S | low | P2 | inferred (whether 抵达 belongs to the approved v13 set must be confirmed) |
| R-07 | roam | index.wxml:168 | 附近暂无活动 second line | "打开地图寻找附近活动" | A helper caption (rule 3). The whole row is already the tap target | Keep the single line "附近暂无活动", or leave it if the owner treats it as the action label | structure | S | low | P2 | code |

**Spacing/radius census for `pages/roam/index.wxss` (943 lines).**
- **v13 roam home overrides (lines 893-917).** 9 radius declarations: 12/19/23/27rpx, 50%, lg, xl+26. These are approved; do not touch.
- **Confirmed-HTML entry/passport rules (lines 58-133, 783-850).** These carry `ds-ok` plus owner rulings ("用户裁决不改", P-25..P-29); do not touch either.
- **Everything else (lines 1-892).** 29 distinct radius values. Card-like raw values: 15, 20, 22, 23, 25, 26, 27, 28, 31, 35, 36, 38, 42, 48rpx, plus 999rpx used next to `--cy-radius-pill`.
  - Spacing: roughly 60 distinct values. Tokens dominate (`--cy-space-2/3/4/1` ≈ 93 uses). The raw values cluster at 31rpx (18), 38rpx (13), 23rpx (12), 19rpx (9) and 15rpx (6), which are 1px = 1.923rpx prototype conversions (16/20/12/10/8px).
  - These raw values are prototype conversions, not random drift. A token sweep would fight the owner's "照原型" rulings.
- **Only proposed change: R-05**, the one place where same-slot cards disagree.
- **Optional, cosmetic only:** `999rpx` (lines 180, 186) could become `var(--cy-radius-pill)`. Not proposed; there is no visible change.

### 2.3 发现 / 俱乐部 pages/talent/list
Already good: full state machine (`loading/refreshing/ready/empty/error/stale-error`) for the feed, clubs and members. "Refreshing" keeps the existing content. The empty state has a real CTA.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| C-01 | talent/list | index.js:356 (`onShow` → `loadClubHome`), 439 | 俱乐部 grid on tab return | every return to the tab refetches, inserts `cy-progress-status` "正在更新俱乐部" above the grid, then `pageMotion.enter(that,'pmClubs')` hides and re-rises every card | Each tab switch shifts the grid down by a row, then replays the entrance. This is the most visible tab-switch jank in the lane | (a) Run `enter('pmClubs')` only when the grid was empty before. (b) For a background refresh with a snapshot, skip the progress row, or throttle `onShow` refetch to once per N seconds | motion-transition / loading-state | S | low | P1 | code |
| C-02 | talent/list | index.wxml:37 + index.wxss:310 | 帖文 cards | the card has both `class="cy-fade-in"` (200ms opacity keyframe) and the `--pm-p` opacity/translate transition | Two entrance systems overlap on the same node. The keyframe ends before the staggered transition, so later cards blink in and then rise | Remove `cy-fade-in` from `cy-post-card` (the pm stagger already covers the first paint). Load-more rows then appear without a fade, which is acceptable | motion-entry | S | low | P2 | code |
| C-03 | talent/list | index.wxml:50-53, 79 | 创建俱乐部 | a resident row "创建你自己的俱乐部" + button "创建俱乐部"; when the list is empty, `cy-empty` adds a second "创建俱乐部" CTA | Two identical CTAs stacked in the empty state. The resident row's text is a helper caption (rule 3) | Drop `cta` on the player `cy-empty` (line 79) since the resident row is always there. Keep the row | structure | S | low | P2 | code |
| C-04 | talent/list | index.wxml:30 + index.js:77 | 帖文/俱乐部 top-tab switch | `enter()` on every tab switch | Acceptable. The cards re-rise when the player switches the inner tabs, which matches the 2026-10-23 rule. Listed only so it is not "fixed" by C-01's change | — | motion-transition | — | — | — | code |

### 2.4 发布 pages/template (player 发布 tab; content = `pages/templatedetail/shelf`)
Already good: the shelf has a skeleton, error, empty and content branch per tab. The body uses `cy-rise-in`. The FAB open/close hand-off matches the overlay spec.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| P-01 | template | pages/template/index.json `componentPlaceholder` | whole shelf on first open | `"tpl-shelf": "view"`. While the subpackage downloads, only the top bar and bell render; tabs, search and body are blank | Cold first open of the 发布 tab shows an empty black page for the download time | Point the placeholder at the existing `cy-skeleton` (as pages/member does with `cy-profile-loading`), or a light placeholder that draws the tab row + search pill height | loading-state | S | low | P1 | code (download duration inferred) |
| P-02 | template | templatedetail/shelf/index.wxml (topic/game branches) | shelf skeleton | `cy-skeleton type="list" count="3"` | The loaded layout starts with a large horizontal feature card, then tags, then rails. The list skeleton has a different shape, so the page reflows on arrival | Use `type="feed-card" count="1"` (closest existing shape to the feature rail) | loading-state | S | low | P2 | code |
| P-03 | template | pages/template/index.js:179-197 | tab bar | hides while scrolling down past 50px, returns 300ms after the scroll stops | A third tab-bar behaviour in the lane (see T-01) | Covered by T-01 | structure | — | — | — | code |

### 2.5 我的 pages/member/index (wrapper; body is `subpackageMember/.../cy/profile`, owned by another lane)
Already good: the placeholder `cy-profile-loading` keeps the nav, title, skeleton and tab bar while the subpackage loads. Pull-to-refresh is bound to the real refresh promise.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-01 | member | pages/member/index/index.wxml:6; index.js:10-28 | logged-out empty state | `profileMounts` starts as `[]`. If the session is not restored by the first `onShow` (cold start), `cy-empty` "登录后查看我的主页" + "登录" renders, then flips to the profile once `waitForInitialSession` resolves | A logged-in player can see a login prompt flash on a cold open of 我的 | Render the `cy-profile-loading` skeleton until the initial session wait has settled once. Show `cy-empty` only after that | loading-state | S | low | P1 | inferred |
| M-02 | member | index.wxml:3-6; profile-loading/index.wxml:8 | loading copy | skeleton plus a separate "加载中" text line | Duplicate signal. Harmless | Leave it | loading-state | — | — | — | code |

### 2.6 Tab bar components/tabBar (shared by all five tabs)
Already good: the bar sits fixed at the bottom with no entrance animation (matches ref A: chrome is still). Every item has hover feedback. The selected state is one colour. Merchant mode is forced monochrome.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| T-01 | all tabs | pages/index/index.wxml:280 + index.js:252, 888-892; pages/template/index.js:179-197 | tab bar visibility | 首页: hidden until `scrollTop > 120` (`_navUp`). 发布: hides on scroll-down, returns 300ms after stop. 漫游/发现/我的: always shown | On a cold start (home) the player sees no navigation until they scroll. Switching back to home at the top hides the bar again. Three rules for one control | Recommend one rule: always raised on all player tabs (home passes `raised="{{true}}"`, template drops the scroll toggle). The bar code stays as is. **Reverses an explicit earlier decision** ("首页传 false→首屏隐藏"), so the owner must choose | structure | S | med | P1 | code |
| T-02 | all tabs | components/tabBar/index.wxss:86-93 | raise/lower transition | `transform .32s ease, opacity .32s ease` | If T-01 is kept as is, the curve is plain `ease`, not the kit's tokens | Switch to `var(--cy-motion-slow) var(--cy-ease-out)` | motion-transition | S | low | P2 | code |
| T-03 | all tabs | components/tabBar/index.wxml:7-12 | selected item | icon only, recoloured purple | Ref B (selected pill with label) would be a restyle; **not proposed** | — | — | — | — | — | code |

### 2.7 subpackageRoam pages
Census (wxml taps : hover-class · distinct radii · raw rpx : tokens): nearby 47:43 (4 are `catchtap="noop"`) · 21 · 359:42; session 3:2 · 3 · 14:70; official-routes 1:1 · 1 · 2:20; official-route 1:1 · 4 · 8:22; my-teams 11:11 · 6 · 56:50; citystamp 6:6 · 6 · 178:3; team-recovery-panel 5 native buttons : 0. All pages except citystamp (own nav row, prototype copy) and poi-detail (scene-sheet title) use `cy-nav-bar` (+ `cy-page-title` where titled).

**nearby** — Already good: hover-class on every real tap, proto-sheet with reduced-motion path, header avoids fake 0 for teams, error card with 「重试」/「去登录」, dark throughout.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| N-01 | nearby | subpackageRoam/nearby/index.wxml:46, 71-73 | bottom-strip empty card + list header | 「附近暂无节点」「点这里看看全部范围」 and 「… · {{listRows.length \|\| 0}} 个」 render whenever `listRows` is empty | No loaded flag: false empty + fake 0 on first open and during failure (contradicts the error card 「附近信息获取失败」) | Expose the existing `_nearbyTeamsLoaded` as data; keep the empty card shell but show its text only when loaded and no error/access state; header shows 「—」 until loaded | empty-state / loading-state | S | low | P1 | code |
| N-02 | nearby | nearby/index.wxss:109 (`.mdd`), 133 (`.pc.is-in`) | filter dropdown, POI card pop | `cubic-bezier(.34,1.32,.64,1)` (overshoot), `.pc` 0.5s | Bounce on popups (rule 6); comments say copied from v13 prototype | Retime to `var(--cy-ease-out)`, keep durations — owner decides whether nearby map counts as approved v13 roam motion | motion-transition | S | med | P1 if owner agrees | code |
| N-03 | nearby | nearby/index.wxml:94,117,143,167,189,199 | full-screen scrims `.pdim`/`.mdd-mask` | `hover-class="cy-pressed"` | Pressing the dim shrinks/fades the overlay before close | `hover-class="none"` on these 6 nodes | tap-feedback | S | low | P2 | code (visual inferred) |
| N-04 | nearby | nearby/index.wxml:45, 68, 84 | list view / bottom strip / corner buttons | plain `wx:if` mount | Pop in instantly while the sheet animates out | Add global `cy-fade-in` class to `.mt-list`, `.mt-strip`, `.mt-corners` | motion-transition | S | low | P2 | code |
| N-05 | nearby | nearby/index.wxml:60; wxss:272 | error card vs vertical map/list switch | card full-width at hudTop+100px z20; switch spans ~hudTop+56..139 z21 | Switch likely covers the card's right end | In map mode give `.nb-load-error` a right inset ≈140rpx (same as `.mt-list` padding-right) | spacing | S | low | P2 | inferred |
| N-06 | nearby | nearby/index.wxss | half-height pills | 44rpx (`.mt-corner`, `.pb__btn`), 36rpx (`.pb__send`), 27rpx (`.mt-strip__head`), 17rpx (`.mt-corner__n`), 999rpx | Pills spelled 5 ways, inflates the 21-radius count | Replace with `var(--cy-radius-pill)` (no visual change); leave prototype card radii 23/27/31/42 | radius | S | low | P2 | code |
| N-07 | nearby | nearby/index.wxss:25-29, 94, 95-98, 233-234 | dead rules | `.mt-top*`, `.mt-filter` not in wxml; first `.mt-chip` block fully overridden at 103-106; `.mt-mode` base radius overridden | Misleads future edits, inflates drift counts | Delete dead rules (separate cleanup ticket) | spacing | S | low | P2 | code |
| N-08 | nearby | nearby/index.wxml:72, 222, 237, 289 | hint captions | 「点这里看看全部范围」「点卡片看详情」「上滑看全部」「上滑展开」 | Captions without data (rule 3); part of v13 card | Drop where the gesture is already drawn (`.pb__up` arrow); owner call | structure | S | low | P2 | code |
| N-09 | nearby | nearby/index.wxml:142-161 | 「我的队伍」 sheet (deep link `?sheet=mine`) | only title while `myLoading` | Empty sheet during load | `cy-progress-status density="compact"` while `myLoading` | loading-state | S | low | P2 | code |

**my-teams** — Already good: `cy-rise-in` on stats and cards, reduced-motion class, hover on all taps, empty state CTA 「去地图找队伍」.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| MT-01 | my-teams | my-teams/index.wxml:13-15; index.js:37,112 | stats row | `stats: []` until loaded, then 4 numbers appear | ~85rpx row pops in and pushes segment + list down | Initialise `stats` with the 4 keys and `v:'—'` | loading-state | S | low | P1 | code |
| MT-02 | my-teams | my-teams/index.js:80-81 | stats after partial failure | `_render(joined \|\| [], apps \|\| [])` | Failed source counts show 0 (rule 7 fake 0) | Pass 「—」 for 已通过/带队 when `joined==null`, 审核中/未通过 when `apps==null` | empty-state | S | low | P1 | code |
| MT-03 | my-teams | my-teams/index.wxml:27, 54, 68 | card stagger | `animation-delay: index*60ms`, uncapped | Long lists: row 20 starts at 1.2s | Cap like official-routes: `{{index < 8 ? index*60 : 480}}ms` | motion-entry | S | low | P2 | code |
| MT-04 | my-teams | my-teams/index.wxml:22, 52, 66 | loading | bare text 「正在读取你的队伍…」「正在加载…」「正在找附近的队伍…」 | Shape change, unstyled | Existing `cy-skeleton` at card height | loading-state | S | low | P2 | code |
| MT-05 | my-teams | my-teams/index.wxss:40 | local `.cy-pressed{opacity:.82}` | overrides global .88 | Heavier press than every other page | Delete the line | tap-feedback | S | low | P2 | code |
| MT-06 | my-teams | my-teams/index.wxml:23, 53, 67; wxss:31 | error rows | custom red pill, 「队伍获取失败 · 点按重试」「加载失败 · 轻点重试」「{{openError}} · 点按重试」, 27rpx radius | Wording/radius drift; `cy-inline-error` is declared in json but unused | Unify the two non-backend strings on 「点按重试」; 27rpx → `var(--cy-radius-lg)` | structure / radius | S | low | P2 | code |
| MT-07 | my-teams + nearby | subpackageRoam/utils/team-recovery-panel.wxml:12-17 | 5 native `<button>` | no hover-class → WeChat default `button-hover` | Likely light-grey press flash and default `::after` border on a dark page | `hover-class="cy-pressed"` on each; `.team-command-card button::after{border:none}` | tap-feedback | S | low | P2 | inferred |

**session** — Already good: retry error, `gone` state via `cy-empty` with real CTA, tokens, share card avoids fake explore % (`'--'`).

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| S-01 | session | session/index.wxml:11 | shared-link loading | bare text 「正在打开朋友分享的足迹」 | Shape change on load | `cy-progress-status` / `cy-skeleton` in the scene-header position | loading-state | S | low | P2 | code |
| S-02 | session | session/index.wxml:23-26; index.js:365 | share-card generation | global `loading.show('生成足迹卡')` + inline 「正在生成足迹卡」 | Two loaders; mask likely covers the sheet | Drop `loading.show/hide` in `_prepareShareCard` | loading-state | S | low | P2 | code |
| S-03 | session | session/index.wxml:8 | page-title subtitle | `dateFull + ' · ' + s.time`; shared mode time = 「—」 → 「11月2日 周日 · —」 | Dangling separator | Append ` · time` only when time ≠ 「—」 (subtitle binding only, title untouched) | structure | S | low | P2 | code |

**official-routes** — Already good: skeleton, error 「重试」, `cy-empty` 「还没有上架的官方线路」, `pageMotion` stagger capped at 240ms, tokens only.
- R-08 | official-routes | official-routes/index.wxml:15 | card without cover | image omitted → ~100rpx card next to ~420rpx cards | mixed heights | in `wx:else` render the `.orl-card__cover` box with the elevated surface (no artwork) | structure | S | low | P2 | inferred

**official-route** — Already good: skeleton, retry error, not-found and no-nodes `cy-empty`, coordinate-less nodes correctly non-tappable.
- D-01 | official-route | official-route/index.wxml:9 | skeleton | `type="feed-card" count="2"` vs loaded 360rpx cover + label + compact rows | reflow on load | `count="1"` | loading-state | S | low | P2 | inferred
- D-02 | official-route | official-route/index.js:39 | content entry | no motion (sibling list uses `pageMotion.enter`) | inconsistent with its own list page | `cy-fade-in` on `.orr__cover`, `.orr__sec`, `.orr-node` | motion-entry | S | low | P2 | code

**poi-detail, history, citynode-code, citystamp** — already good, no findings. citystamp is a deliberate prototype copy (white paper is physical, not a theme leak; 178 raw rpx are annotated px conversions). `components/scene-roam-resume-bar` is not mounted anywhere (tests assert its absence), so not audited for players.

## 3. Real bugs noticed (out of scope to fix)
- **B-1 (roam):** `pages/roam/index.js:760` builds 漫游 as `v: summary.trips` with no `'—'` fallback, unlike its neighbours (`value()`). Tile line 767 shows `summary.trips + ' 次已完成旅程'`, so a null `trips` renders "null 次已完成旅程". Evidence: inferred (depends on whether the reader can return null).
- **B-2 (home):** `pages/index/index.js` `onRefresh` closes the refresher on a fixed 800ms timer, not on completion. A slow network shows "refreshed" while the sections are still empty, which compounds H-03. Evidence: code.
- **B-3 (template):** `pages/templatedetail/shelf/index.wxml` uses `cy-error load-failure ... bindretry="retry"`. With `loadFailure` the component renders a blank veil and never shows a retry button, so the binding is dead code. On 发布 a failed shelf has no retry path at all (the page has no pull-to-refresh). Evidence: code.
- B-4 (nearby): `subpackageRoam/nearby/index.wxss:171` `.pb__photo-note { color: var(--sub) }` — `--sub` is only defined in `pages/play` skins, so the variable is invalid here and the photo-licence note inherits white instead of secondary grey. Evidence: code.
- B-5 (nearby): strip says 「附近暂无节点」 while the load failed or is still running (N-01). Evidence: code.
- B-6 (my-teams): partial API failure shows fake zeros in stats (MT-02). Evidence: code.
- B-7 (session): two loaders during share-card generation (S-02). Evidence: code.

## 4. Would need a redesign (not proposing)
- **Recovery after `cy-error load-failure`.** The project-wide policy blanks failed loads with no copy or retry. On tabs without pull-to-refresh (漫游 entry, 发布 shelf) a failure is unrecoverable in place. Changing that is a policy decision, not a polish edit.
- **Tab-bar selected state as a labelled pill (ref B).** This is a visual restyle of shared chrome.
- **Token sweep of `pages/roam/index.wxss`.** Most raw values are prototype conversions under owner rulings. A sweep would re-open approved work.

## 5. Cross-page patterns (one shared fix, several pages)
1. **`pageMotion.enter` replays on refresh and tab return** (home H-02, clubs C-01, and any page that calls `enter` inside a success handler). Add an option to `utils/page-motion.js` that skips when the container already shows content, e.g. `enter(page, key, { onlyIfEmpty: list.length === 0 })`. Callers only pass the flag. **This one fix removes most of the tab-switch flicker.**
2. **Background refresh that inserts a status row** (`cy-progress-status` "正在更新…" in talent/list; `v3-sec-sync` on home). The row pushes content down mid-view. Option: render it as an overlay inside the existing header area, or not at all when the refresh comes from `onShow`.
3. **Cold-start shape placeholders.** `componentPlaceholder` and first-load branches should draw the page's own geometry: template P-01, roam passport R-02, roam peek R-01, home feed H-04. The member page already does this correctly and is the model.
4. **Fake 0 / false empty before data**: roam peek R-01, nearby N-01, my-teams MT-01/MT-02. One rule: gate empty copy on a loaded flag and show 「—」 until a source has loaded.
5. **hover-class misuse**: `cy-pressed` on full-screen scrims (nearby N-03), missing on native buttons (team-recovery-panel MT-07), page-level `.cy-pressed` overrides (my-teams MT-05). Scrims → `none`, native buttons → `cy-pressed`, no local overrides.
6. **Tab bar visibility** should be one rule for all player tabs (T-01).

STATUS: COMPLETE
