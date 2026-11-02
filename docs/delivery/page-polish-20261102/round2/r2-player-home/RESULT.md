# r2-player-home: result (2026-11-02)

Branch `claude/page-polish-r2-player-home`, cut from `integrate/page-polish-r2-20261102` (2f7bdab0).
Scope: `chengyinhub-xcx/` pages/index, pages/template (+ `pages/templatedetail/shelf`), pages/talent/list, pages/member/index,
pages/roam, subpackageRoam. Audit: `player-home` REPORT.md. Round 1: `impl-player-home` RESULT.md.

Overlap check: `claude/walk-fix-1102-l2-explore` touches none of these files. `walk-fix-1102-l3-sell` edits `pages/index`
(the nearby/upcoming fallback cover, `LIVE` → `进行中`, the badge colour, and the city-consent guard). I did not touch those lines. PRs #702, #709 and #710 change
no file in this scope.

Rig: `claude/cloud-walk-20261101-rig` with `SHA` = base, using the `mirror.gcr.io` registry mirror (Docker Hub returned 429).
`chains.py finance` ran platform, merchant_club, sale_close, player and finance, and all five passed (topic 990014 「E2E 探店日一期」). The before bundle is the base,
and the after bundle is this branch. Both run against the same backend. Latency, empty responses and 500s were injected only in the browser
(puppeteer request interception), never in product code. Every shot is a before | after pair. Drivers are in `tools/`.

## Regressions
None found. The unit suite has 15551 pass and 2 fail, and both failures are ledger drift (see below). Every other required gate exits 0.
Things I tried and reverted because a non-reserved test pins the current behaviour and I would not weaken it:
- **N-03** (scrims → `hover-class="none"`): `press-feedback-coverage-contract.test.js` counts `none` as "tappable without feedback".
  The ratchet goes 51 → 57, and nearby's per-file quota goes 4 → 10. The BRIEF convention and this test disagree. See "Shared requests".
- **S-02** (drop the global `loading.show('生成足迹卡')`): `roam-session-share-card.test.js` 「清理损坏记录时会收起仍在途的生成 loading」
  pins exactly one show and one hide. The overlap the audit inferred was not rendered (the share card needs the canvas path), so I left it.
- **MT-06 wording** (「轻点重试」 → 「点按重试」): `l6-team-event-player.test.js` pins 「加载失败 · 轻点重试」, and the phrase is used in 3 wxml app-wide.

## Row accounting

| Row | Result | Evidence / reason |
|---|---|---|
| **H-03b** (extra) refresh turns empty sections into skeletons and re-mounts 即将上线 | **done**. New `listLoaded` map: `listLoaded.<list>` is set when a section's request settles (on an existing line). After that, the skeleton branch (`… && !listLoaded.X`), the empty branch (`… \|\| listLoaded.X && …`) and the 即将上线 `wx:if` no longer fall back to loading. A section that already shows an error keeps it while it refreshes. The in-flow 「正在更新编辑精选/附近活动/即将上线…」 rows get the kit class `cy-refresh-float` (`.v3-sec` is now `position: relative`), so they take no height. This is the same convention round 1 used for clubs. | Rig probe, sampling scrollHeight every 50 ms through `onRefresh()` with 2.5 s latency. Before: 1495 → 1473 → **3221** → 1495. After: **1495** throughout. `shots/h03b-home-refreshing.png`: before shows the skeleton; after keeps 「暂时没有精选主题」/「你附近暂时没有可点亮的节点」. `shots/home-full-syncing.png`: the sync row floats over the top of the card instead of pushing it down. Note that the floating text is plain tertiary text, so it has low contrast over a bright cover. |
| **Home with real content** (extra) | **measured + fixed**. The seed gives no featured or nearby data, so I set page data from the rig's own records (`bottomTopicList[0]` copied into 编辑精选 ×2, 附近活动 ×3 and 即将上线 ×3; steps in `tools/steps-full.json`). Measured with measure-player's `measure.js` / `annotate.py`. Section gaps were banner→精选 **32.3**, 精选→附近 **32.3**, 附近→即将上线 **38.2**, 即将上线→feed **46.5** px. Cause: `.v3-nearby-row` / `.v3-upcoming-row` are `inline-flex` rows on the text baseline inside the `nowrap` rail, which adds about 6 px of descender gap. Fix: `vertical-align: top` on both, in place. After: **32.3 / 32.3 / 32.3** / 46.5. | `shots/home-full-measured.png` (annotated full page, before \| after). `shots/home-full-1.png`. The remaining 46.5 to the feed is the `cy-tabs wide` label's own top padding. That is component geometry, so I left it. Cards: nearby 16.6 inset / 16.6 radius, upcoming 16.6 / 20.8, feed card 16.6 / 22.9 (media tier). Reco card is 20.8 left (swiper `previous-margin`, v13-style carousel), radius 10.4. Listed under Owner's choice. |
| H-01 / H-02 / H-03 | already fixed (round 1) | — |
| **H-04** feed first load shows 「正在加载更多」, no empty state | **done**. First load renders `cy-skeleton type="feed-card" count="2"` (host class `.v2-feed-sk` cancels the doubled page inset, so skeleton cards sit on the real cards' 16.6 px edge). A loaded empty list renders `cy-empty` 「暂时没有主题」/「暂时没有活动」. 「正在加载更多」 now shows only while a real next page loads (`isBottomLoading && hasMore…`), not on first load or refresh. | `shots/h04-feed-first-load.png` (4 s latency), `shots/h04-feed-empty.png` (`/api/topic/list` answered with 0 rows: before shows only the tabs; after shows 「暂时没有主题」). |
| H-05 即将上线 hidden when empty | **needs owner** (owner chose "hide when empty"). After H-03b it no longer re-mounts on refresh. It still renders its skeleton on the very first load and collapses if the result is empty. | — |
| H-06 「编辑精选 · 参加条件以详情为准」 | **false positive for rule 3**. P-02 (owner) kept this line as the source note, and `discovery-editorial-source.test.js` pins it. | — |
| R-01, R-02 | already fixed (round 1) | — |
| R-03 roam peek error has no retry | no change (the audit proposed none; `onShow` already re-reads) | — |
| R-04 passport tile captions | **Owner's choice**. The tiles are owner P-29 structure on the confirmed passport screen. | — |
| R-05 prompt-stack radii | **false positive** (round 1; the lane prompt says do not change it) | — |
| R-06 抵达 `r-floatUp .7s` | **Owner's choice**. Map run state, v13 approved. | — |
| R-07 「打开地图寻找附近活动」 | **Owner's choice**. v13 roam home peek. | — |
| B-1 「null 次已完成旅程」 | **false positive**. `utils/roam-passport-read.js:87` throws when `trips == null`, so the tile never receives null. | code |
| C-01 | already fixed (round 1) for clubs. **Extended to 帖文**: the `loadFeed` success uses `pageMotion.enter(…,'pmFeed',{ once: true })`, so pull-to-refresh no longer hides and replays posts. The inner tab switch (line 77) still re-rises them (C-04). 「正在更新帖文」 gets `cy-refresh-float`, the same as 「正在更新俱乐部」. | Not shot: the rig has 0 posts, so the refreshing branch renders nothing on either side. |
| **C-02** `cy-fade-in` keyframe and pm opacity transition on the same post card | **done** without deleting either. The pm entrance now uses `filter: opacity()` (the kit's documented convention in `utils/page-motion.js`, as home does), so the 200 ms opacity keyframe and the staggered rise compose instead of overriding each other. | Motion only. No still possible, and no posts in the rig. |
| **C-03** two 「创建俱乐部」 CTAs in the empty state | **done**. Dropped `cta` on the player `cy-empty`. The resident row and button stay. | `shots/c03-clubs-empty.png` |
| C-04 | no change, by design | — |
| P-01 | already fixed (round 1) | — |
| P-02 shelf skeleton shape | **not changed**. Round 1 made the placeholder `cy-skeleton` default `list` to match this skeleton, so the subpackage hand-off does not change shape. Switching only the shelf would break that pair. The loaded shelf was never rendered (no templates in the rig), so the reflow is unmeasured. | — |
| P-03 / T-01 tab-bar visibility | **decided, not reopened** (BRIEF: home tab bar stays) | — |
| T-02 tabBar `.32s ease` → tokens | **shared request** (the tab bar is shared by player and merchant tabs) | — |
| T-03 | not proposed by the audit | — |
| M-01 | false positive (round 1) | — |
| M-02 | no change (the audit says leave it) | — |
| N-01 | already fixed (round 1) | — |
| N-02 overshoot | **decided** (BRIEF: N-02 stays) | — |
| N-03 scrims `hover-class="none"` | **reverted, shared request**: it conflicts with the press-feedback ratchet test (see Regressions) | — |
| N-04 fade-in on list/strip/corners | **Owner's choice**. This would add motion to the v13 map screen. | — |
| N-05 error card vs map/list switch | **not changed**. The audit marked it inferred, and forcing the nearby error state while measuring the switch was not reached. | — |
| N-06 pill radii spelled 5 ways | **Owner's choice**. Every value carries `ds-ok` with the v13 prototype / owner 2026-10-23 ruling. | — |
| **N-07** dead rules | **done** (partly). `.mt-top`, `.mt-top__pill/__dot/__t`, `.mt-filter` and their two `.nb …` / `.nb.map-open …` rules are removed. grep finds no use in any wxml or js, and no test or selector names them. The first `.mt-chip` block is **not** fully dead (it still supplies `display`, `align-items` and `vertical-align`), so it stays. | grep |
| N-08 hint captions | **Owner's choice** (v13 card copy) | — |
| **N-09** 「我的队伍」 sheet is empty while loading | **done**. A `.mt-foot` line 「正在读取你的队伍…」 (same class and slot as 「暂无队伍」, same copy as the my-teams page) shows while `myLoading` and there are no rows. | `shots/n09-nearby-mine-loading.png` (4 s latency) |
| **B-4** `.pb__photo-note` uses undefined `var(--sub)` | **done** → `var(--cy-color-text-secondary)` (the comment's intended secondary grey). | Not shot: it needs a POI card with a photo credit, which the seed does not have. |
| B-5 | = N-01, already fixed | — |
| MT-01 / MT-02 | already fixed (round 1) | — |
| **MT-03** uncapped card stagger | **done**: `{{index < 8 ? index * 60 : 480}}ms` in the 3 lists, the same cap as official-routes | Motion only |
| **MT-04** bare loading text | **done** for the 我的 tab: `cy-skeleton type="list" count="2"` replaces 「正在读取你的队伍…」. 「正在加载…」 is pinned by a test, and 「正在找附近的队伍…」 sits in a section whose content shape varies, so both stay. | `shots/mt04-my-teams-loading.png` |
| **MT-05** local `.cy-pressed { opacity: .82 }` | **done**: deleted, so the global press applies | Press only |
| **MT-06** error rows | radius **done**: 27rpx → `var(--cy-radius-md)` (inline-card tier; 14 → 12.5 px, under the noise line). Wording **not changed** (test-pinned, see Regressions). | `shots/mt06-my-teams-error.png` |
| **MT-07** native buttons in the team recovery panel | **done**: `hover-class="cy-pressed"` on all 5, and `.team-command-card button::after { border: none; }` | Not shot: needs an unresolved team command record |
| S-01 shared-link loading text | **not changed**. Swapping one status line for another keeps the same shape problem. A real placeholder would need a session-shaped skeleton type, which is a new `cy-skeleton` variant (shared, and a new variant). | — |
| S-02 | **not changed** (test-pinned, see Regressions) | — |
| **S-03** 「… · —」 dangling separator | **done**: ` · time` is appended only when `time` is set and not 「—」 | Not shot: shared mode needs a shared snapshot |
| B-6 / B-7 | = MT-02 (fixed) / S-02 (not changed) | — |
| R-08 official route card without cover | **not changed**. An empty cover box would add a blank 160 px block with no artwork (rule 5). The mixed heights are truthful. | — |
| D-01 official-route skeleton count | **not changed**. Inferred, and official routes are not seeded, so it was not measured. | — |
| **D-02** official-route content has no entrance | **done**: kit `cy-fade-in` on `.orr__cover`, `.orr__sec`, `.orr-node` | Motion only. Not seeded. |
| B-2 refresher closes on a fixed 800 ms | **not changed**, mitigated. With H-03 and H-03b, content stays on screen during refresh, so an early close no longer reveals empty sections. | — |
| B-3 shelf `bindretry` on a load-failure veil | **needs owner** (load-failure recovery policy, audit §4) | — |
| measure-player row 4 (`.mt-sec` inset) / row 5 (home feed inset) | already fixed (round 1, b8e9fa00) | code |

Not in any table, but in scope and measured: home is above. talent/list and member were measured as even by measure-player. my-teams
was measured here only in its loading and error states (no teams seeded).

## Owner's choice (measured, not edited)
- 编辑精选 card: 20.8 px left edge (swiper `previous-margin` 40rpx) vs 16.6 px for every other home card, radius 10.4 px (scaled focus
  card). This is the v13-style carousel.
- Home 即将上线 → 「主题/活动」 tabs: 46.5 px vs 32.3 px between the other sections. The difference is the `cy-tabs wide` internal padding.
- Home floating 「正在更新…」 text (H-03b): plain tertiary caption with no pill, so contrast over a bright cover is low while it shows.
  `cy-progress-status` (as clubs use) would be a visible restyle.
- Roam R-04, R-06, R-07, N-04, N-06, N-08: approved v13 / P-29 screens, values as in the audit census.

## Shared requests (for r2-shared / integration)
1. **Scrim press convention vs `press-feedback-coverage-contract.test.js`**: the BRIEF says scrims use `hover-class="none"`, but the
   ratchet counts that as missing feedback (nearby has 6 scrims: `.pdim` ×5, `.mdd-mask`). The fix is to let the test exempt elements
   whose class is a scrim (`pdim`, `*-mask`, `*-scrim`), or to agree on the convention. Then N-03 is a 6-attribute edit.
2. **T-02**: `components/tabBar/index.wxss` `transition: transform .32s ease, opacity .32s ease` → `var(--cy-motion-slow) var(--cy-ease-out)`
   (350 ms, about the same speed). This is a shared component.

## Ledger and baseline drift
- `action-ledger.test.js`: 「合并后可达动作 variant 全部进入台账」 expected **5597**, actual **5596**. The −1 is the removed `cy-empty` CTA
  「创建俱乐部」 (C-03). 「release 前重建 inventory」 is red for the same reason, plus wxml line re-keys in `pages/index/index.wxml`
  (+4 lines: two feed skeleton/empty pairs; every other wxml edit stays on its own lines, so later controls keep their keys). The ledger is not regenerated or committed.
- `ui-integrity` U4/U5: pass (dynamic setData 63/63). The JS line counts of `pages/index/index.js` and `pages/talent/list/index.js` are
  unchanged (all JS edits sit on existing lines). No baseline change is needed.
- `nearby/index.wxss` lost 8 dead lines (N-07). That file is not line-keyed in any baseline.

## Checks (from `chengyinhub-xcx/`)
`node --test "tests/unit/**/*.test.js"`: 15551 pass, 2 fail (the ledger rows above). `ds-hardcode-gate.sh --ci`, `ui-integrity.js --warn-as-error`,
`state-visibility-gate.js`, `ui-validity-gates.js`, `ui-theme-unit-lint.js --theme` and `--units`, `setdata-dead-field-lint.js` and
`a11y-tap-target-lint.js` all exit 0. Since the base, master has gained only #709 (backend Java), so nothing in this scope moved.

## Not exercised
Motion-only changes (C-02, MT-03, D-02), press-only changes (MT-05, MT-07), B-4 (no POI photo credit in the seed), S-03 (no shared snapshot), the
帖文 refresh float (0 posts in the rig), the template shelf with content (no templates), official routes (not seeded), and anything on a real device.
The rig's `scroll-view` lacks WeChat's `position: relative`, so the shot driver injects it the way measure-player did.

STATUS: COMPLETE
