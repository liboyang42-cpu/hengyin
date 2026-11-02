# Page polish round 2: final review (2026-11-02)

Branch `claude/page-polish-r2-final`, cut from `claude/page-polish-r2-preview` (a7c3624c). No product code changed: this branch adds only files under `round2/final/`.
Compared: BEFORE = `origin/master` c6eac3b8 (round 1 #711 + #702), AFTER = preview a7c3624c (the PR #719 content).

**Method.** I used the rig from `claude/cloud-walk-20261101-rig` with `SHA=a7c3624c` in a local `up.sh` copy. Docker ran with `mirror.gcr.io` as its registry mirror. Both sides used one backend jar built from this branch, on real MySQL and Redis.
- `chains.py finance`: platform, merchant_club, sale_close, player and finance all **PASS** (topic 990014).
- Bundles: the AFTER bundle was built with `up.sh` from a7c3624c and served on :8097. The BEFORE bundle was built with `CAND=<master worktree> mpweb/build.sh` and served by a second `server.mjs` on :8098.
- Rig-only data changes:
  - `ums_member.role='club'` for 9003 and 9008, as the club lane did.
  - `club 7001.join_policy=1`, then five real `POST /api/club/join` calls.
  - Theme 990013 was made merchant-hosted (`member_id=9002`).
- Pages the seed cannot reach were filled with dummy page data or a wrapped in-page `fetch`. Each case is listed in `tools/mk_pages2.py`.
- Measurements use measure-merchant's `measure.js` at 390 px wide (1 px = 1.923 rpx).
- Tools: `tools/fw.mjs` (walk, shoot, measure and probe), `tools/pair.py`, `tools/money.mjs`, `tools/brand.py` and `tools/pages*.json`. Data: `data/`. Full row table: `ROWS.md`.

**Checks on this tree** (from `chengyinhub-xcx/`): `node --test "tests/unit/**/*.test.js"` gives **15561 / 15561 pass**. Every gate exits 0: `ds-hardcode-gate --ci`, `ui-integrity --warn-as-error` (UI-GATE-0 PASS), `state-visibility-gate`, `ui-validity-gates` (U6 PASS), `ui-theme-unit-lint --theme` and `--units`, `setdata-dead-field-lint`, and `a11y-tap-target-lint` (0). The ledger drift the lanes reported was regenerated at integration, and `action-ledger.test.js` is green.

## 1. Regressions

**No visual regression found.** I opened 72 page states on both bundles: 56 routes from the diff list plus the main tabs, 13 fixture states and 3 extra states.
- **Page errors:** 0 on every page, on both sides.
- **Console errors:** the only one is `onReady pages/templatedetail/templatedetail` with id=1. It appears identically before and after, because id=1 does not exist in the rig.
- **Main tabs are pixel-identical before vs after (AE = 0):** player 漫游, 发布, 发现, 我的; merchant 工作台 and 我的.
- **Also identical:** My Projects (dark for 9009, light for 9002 `scope=MERCHANT`), official signup pages (light), baoming, topic cover and detail, square detail, and join-requests.
- **No raw text found:** `undefined`, `null`, `NaN` and `[object` appear in no rendered text. No clipped or overlapping content was found apart from the clip that was fixed.

Things a sceptical reviewer should still know. None of them is a regression, but the owner will see each one:

| # | What | Where | Verdict |
|---|---|---|---|
| R1 | 积分明细's empty state grew from the default size to `fill` + `size="lg"`. The illustration is about 1.5× and centred in the space below the title. No audit row named this. It came from r2-player-me's lane-prompt item ("sibling lists as one family"). | `shots/player-points-empty-state-fill.png` | Visible restyle. Accept if the owner wants the family match; otherwise revert 2 attributes. |
| R2 | 我的参与 cards grew from 174.7 to 228.5 px to fix the clipped 「查看报名凭证」. The cover art now scales with the taller card, so its crop changes. | `shots/player-mycanyu-voucher-button-clip.png` | Intended. The clip is fixed. |
| R3 | 我的订单: the first card moved down 4.2 px, because the tabs gap went from 24rpx to 32rpx. | `shots/player-orders-filter-to-card-gap.png` | Intended, under 5 px. |
| R4 | `merchant/aftercare` `.acl-search` changed from `var(--cy-space-5)` to a raw `40rpx /* ds-ok */`. This moves a token to a literal, the opposite of the brand rule, and the `ds-ok` was self-granted. The gap is visually right: 23.8 / 23.8. | `shots/merchant-aftercare-search-to-group-gap.png` | **Open**, minor. A half-step token, or reusing `.fin-group`'s own rule, would be cleaner. |
| R5 | `.cy-refresh-float--pill` (style/fade-in.wxss) shipped with **0 consumers**, which makes it dead CSS until the 8 page copies adopt it. `--cy-ease-page-rise` shipped, but the same round added a new literal copy (`subpackageMember/mycanyu/mycanyu.wxss`, last line), and about 45 page copies remain. | code | **Open**. Adopt both in one sweep, or drop the pill class. |
| R6 | Merchant reviews B-2: r2-merchant still lists it as "needs owner/backend", but HEAD `pages/merchant/reviews/index.wxml:81` already reads 「平均评分」. | code | Accounting is stale. B-2 is **already fixed (round 1)**. |
| R7 | Owner decision applied at integration: the public 成就 tab now shows **0** where master showed 「—」 (PM-08 reverted). | `shots/owner0-profile-achievements-fields-missing.png` | Correct per the owner's ruling. It is a visible change against master. |

Lane visual claims checked at readable size, before | after:
- **Merchant side:**
  - 客户 rows: name 2 px under the divider → padded. `merchant-customer-row-padding`
  - 退款售后: search → group. `merchant-aftercare-search-to-group-gap`
  - 确认终价: chip gap 9.1, then 10.4 even, plus the note above the button. `merchant-pricing-chip-and-note-gaps`
  - 商家报名: duplicate 「你的门店」 removed. `merchant-merchantapply-duplicate-store-title`
  - 结算: money landing. `merchant-ledger-money-landing-*`
- **Player side:** `player-mycanyu-voucher-button-clip`, `player-orders-filter-to-card-gap`, `player-anniversary-loading-skeleton`, `player-myteams-loading-skeleton`, `player-home-*-with-content`
- **Club side:**
  - 贡献榜 score no longer under the 「加入」 float (score right edge sits left of the button). `club-detail-join-float-vs-score`
  - Title gaps on 结算, 勋章 and 运营 (measured 9.1 / 11.6 / 8.1). `club-*-title-gap`

All match the lanes' claims.

Not re-verified by rendering: the game-node and batch-detail claims. The rig redirects game-node to the workbench (no prepared session), and the seed has no transfer batch. The lanes' own shots `r2-merchant/shots/05,06,08,12` are the only evidence. Motion-only items are also not re-verified (see §6).

## 2. Brand consistency (after, 390 px, px)

Columns:
- **Inset:** modal left edge of top-level blocks.
- **Card radii:** distinct radii of top-level surfaces on screen.
- **Section gaps:** ink-to-ink.
- **Title → first:** `cy-page-title` ink to the next block's ink.
- **Press:** static `hover-class` values in the page WXML.

Full raw table: `data/brand-after.md`.

| Page | Inset | Card radii | Section gaps | Title → first | Press | Note |
|---|---|---|---|---|---|---|
| player 首页 (content) | 16.6 | 10.4 / 16.6 / 20.8 / 22.9 | 32.3 / 32.3 / 32.3 / 46.5 | — (greeting header) | cy-pressed ×11 | reco 10.4 + 20.8 left = v13 carousel |
| player 发现 帖文/俱乐部 | 16.6 | 22.9 | 31.2 / 29.1 / 12.5 | — | cy-pressed ×6 | |
| player 我的 | 16.6 | 12.5 / 16.6 | 31.5 / 31.4 / 23.6 | — (hero) | component | |
| player 漫游 | 23.9 | 12 | — | — | cy-pressed ×122 | v13 approved |
| player 我的参与 | 16.6 | 16.6 | 31.2 | tabs | cy-pressed | |
| player 我的订单 | 16.6 | 16.6 | 16.6 | 9.1 | component | |
| player 积分明细 | 16.6 | rows | 15.6 | — (empty in rig; lane: chips at 0) | cy-pressed | |
| player 我的收藏 | 16.6 | 22.9 (2xl, r2) | — | tabs | cy-pressed ×2 | |
| player 我的项目 (dark) | 16.6 | — | 15.6 / 16.4 | tabs | cy-pressed ×18 | |
| player 我的队伍 | 16.1 | 12.5 | 22.7–43.9 | 21.9 (to stats) | cy-pressed ×11 | 31rpx prototype; under 2 px |
| player 附近 (roam) | 16.1 | 14 | — | — | cy-pressed ×42 + `mdd__i--press` | v13 |
| player 那年今日 / 胶囊 / 目标 / 月报 | 16.1–16.6 | — | — | 16.8 / 40.2 / 40.2 / 23.1 | cy-pressed | display hero numerals by design |
| player 主题封面/详情 | v13 | 14 / 16.6 / 23.9 | 27.6 / 49.8 / 40.3 / 24.8 (lane) | — | cy-pressed ×28 | v13 approved |
| player 报名 baoming (light) | 16.6 | 12.5 | 14.6–67.8 (mixed roles) | — | cy-pressed ×10 | |
| player 官方·我发起的 (light) | 16.6 | — | — | 9.1 | cy-pressed ×3 (r2) | |
| player 帖子详情 | 16.6 (head 12.5, lane) | 12.5 | even 20.6 (lane) | — | cy-pressed ×12 | Figma 379:2571 |
| player 模板详情 | 16.6 (r2, was 20.8; lane-measured) | 12.5 (lane) | — | — | cy-pressed ×10 | not reachable here (id=1 missing); lane shot `td-inset-stars` |
| club 主页 | 16.6 | 16.6 | — | — | cy-pressed ×81 | |
| club 结算 | 16.6 | 12.5 / 16.6 | 25 / 25 | **9.1** (r2) | cy-pressed | |
| club 运营 | 16.6 | 8.3 / 12.5 | 13.9–16 | **8.1** (r2) | cy-pressed + dyn | |
| club 勋章 | 16.6 | 12.5 | 11.6 / 25 | **11.6** (r2, after subtitle) | dyn | |
| club 入团审核 | 16.6 | 16.6 | 8.3 even | 11.1 (subtitle) | dyn | |
| club 角色 / 通知 / 治理 | 16.6 | 16.6 | 19.9 / 22.8 | **23.6** | cy-pressed / dyn | Owner's choice #3 |
| club 客户 | 16.6 | — | 19.6 / 12.5 / 20.8 | 11.1 | cy-pressed ×2 | |
| club 发布 temp | **13.5** cards, 16.6 hero, 17.7 titles | **8.3 / 14.6** | 25.9 / 14 | — | cy-pressed ×211 + 3 local | **Open** |
| club 发布 fabu | 16.6 | 16.6 / 20.8 | 25 / 16.6 / 19.6 | — | cy-pressed ×125 | |
| merchant 工作台 | 16.6 | 16.6 | 43.6 / 31 / 43.3 | — | cy-pressed ×19 + `rv-tap` ×7 | |
| merchant 结算 / 核销 (ledger) | 16.6 | 16.6 | 16.6 / 22.8 | 9.1 | **`fin-row--tap` / `ledger-action--tap` .72** | **Open** (press) |
| merchant 退款售后 | 16.6 | 16.6 | 23.8 / 23.8 | 9.1 | cy-pressed | |
| merchant 客户 | **20.8** title, **12.5** search | — | 15.4 / 12.5 | 11.1 | cy-pressed ×21 | **Open** (two insets) |
| merchant 营销 | 16.6 | — | 27.9 / 28 / 14 | — | **6 different presses: .6 / .7 / .88 / .9 / .92 + cy-pressed** | **Open** |
| merchant 确认终价 | 16.6 | 16.6 | 10.4 even (r2) | 9.1 (r2) | `pg-lineup-row--pressed` .6 | press **open** |
| merchant 商家报名 | 16.6 | 12.5 / 14.6 | 16.4 / 14.5 / 9.2 | — | `ma-row--pressed` scale .99, `ma-pgrid-add` .97 | press **open** |
| merchant 合作分润 / 核销记录 | 16.6 | 12.5 / 16.6 | — | 9.1 | — | |
| merchant 合作中心 | 12.5 | 0 (covers) | 22.9 / 12.5 | — | cy-pressed ×3 | **held for walk-fix** |
| merchant 我的项目 (light) | 16.6 | — | 15.6 / 16.4 | tabs | cy-pressed ×18 | |

**App-wide census** (static, on this tree):
- **Press:** 1940 of 2161 literal `hover-class` values are `cy-pressed` (89.8%; master 1926 / 2156), plus 4 `cy-pressed-bg`, 3 `none` and 142 dynamic. About 214 local press classes remain. Most sit in `pages/play/components/playkit-*` (held, v13 game kit), `subpackageMember/components/cy/profile` (12), `pages/merchant/index` (`rv-tap` ×7), merchantapply, marketing, ledger and crop.
- **Radius tokens:** `sm` 192, `md` 363, `lg` 320, `xl` 63, **`2xl` 7**, `pill` 636.
- **Raw rpx radii:** 429 in total, 381 of them on a `ds-ok` line. They concentrate in play (124), topic (66) and roam / subpackageRoam (80), all v13 approved screens.
- **Page inset:** `--cy-page-x` is used 486 times; 39 raw `32rpx` side paddings remain.

**Remaining outliers**

| Outlier | Status |
|---|---|
| Title → first block on club 结算 / 运营 / 勋章 | fixed (r2) |
| Merchant 确认终价 rhythm | fixed (r2) |
| 我的收藏 media radius | fixed (r2) |
| Template detail inset | fixed (r2) |
| 客户 row padding | fixed (r2) |
| Club 角色 / 通知 / 治理 title → section 23.6 | Owner's choice #3 |
| Home reco card 20.8 inset, 10.4 radius | approved (v13) |
| Roam, topic and play geometry | approved (v13 / ds-ok) |
| Merchant 合作中心 12.5 inset | held for walk-fix |
| `project-host` / `project-join` | held for walk-fix |
| Activity-list cover cards on `md` 12.5 (media tier is 22.9) | Owner's choice #7 |
| Merchant 客户 title 20.8 vs search 12.5 on one screen | **still open** |
| Club 发布 temp cards 13.5 inset / 14.6 radius (neither lg nor md) next to a 16.6 hero | **still open** |
| Press .6 / .7 / .72 / .9 / .92 on merchant ledger, marketing and pricing; scale-only on merchantapply | **still open** (no audit row named them, so no lane touched them) |
| Media tier `2xl` used only 7 times app-wide | **still open** |

**Does the app read as one system?** Mostly, yes:
- On the everyday surfaces (tabs, lists, ledgers, club management, the signup family), page inset is 16.6 px almost everywhere.
- Title → first block is about 9 px where a `cy-page-title` meets a block.
- List cards sit on `lg`, and press is `cy-pressed` for about 90% of controls.

It does **not** yet read as one system in three places:
1. **The v13 game and theme screens** (roam, topic detail, play) keep their own approved prototype geometry. This is by decision, but it is visibly a second dialect.
2. **The merchant secondary pages** (marketing, ledger, pricing, merchantapply) still use five different local press values.
3. **The media-card tier is inconsistent:** home and discover feed cards are 22.9, but the activity list and most other cover cards are 12.5–16.6.

The club publish form (`publish/temp`) is the single most off-grid page among the ones measured.

## 3. Row accounting (all five audits; full per-row table in `ROWS.md`)

**Row rule.** Every finding ID in the five audit reports is a row. Each § item without an ID is also a row, so redesign lists and bug lists count. That gives **260 rows**.

| Final category | merchant | player-home | player-me | player-theme | club-publish | total |
|---|---|---|---|---|---|---|
| done (round 1) | 22 | 14 | 20 | 25 | 8 | **89** |
| done (round 2) | 6 | 14 | 5 | 16 | 14 | **55** |
| false positive | 2 | 5 | 0 | 3 | 0 | **10** |
| already fixed (kit base, before round 1) | 2 | 0 | 0 | 0 | 0 | **2** |
| needs owner | 9 | 10 | 2 | 20 | 1 | **42** |
| too big | 4 | 1 | 3 | 1 | 4 | **13** |
| held (walk-fix) | 1 | 0 | 0 | 6 | 1 | **8** |
| Owner's choice (approved / ds-ok) | 1 | 9 | 1 | 8 | 0 | **19** |
| no change proposed by the audit | 2 | 5 | 0 | 0 | 1 | **8** |
| **no accounting** | 1 | 2 | 0 | 11 | 0 | **14** |
| total | 50 | 60 | 31 | 90 | 29 | **260** |

**What happened to the "about 96 remaining".** No document in the repo states 96. Rows not done in round 1 come to **170** with § rows included, or **108** counting finding IDs only. Of the 108 ID rows:
- 4 were settled in round 1 without code (R-05, home M-01, T-01, P-03).
- 1 was fixed by the kit (M-33).
- 6 are rows where the audit proposed no change.

That leaves **97**, which is the closest reconstruction of "about 96". In round 2 those rows went to: done (r2) 55 · false positive 10 · needs owner 42 · too big 13 · held 8 · Owner's choice 18 · no change 8 · no accounting 14. These are counted on the 170 set, so the numbers include § rows.

**Held (8):**
- Withdrawn by a7c3624c: M-34, PL6, PL10, PL12, PL14, the player-theme §3 play-overlays reduced-motion fix, and CP-16.
- Never edited: PL11 (walk-fix l2 moves that block).

**Rows with no accounting (14):**
- merchant §2: 「共N位客户」. The audit proposed no change.
- player-home §2.2: roam 999rpx → pill token.
- player-home §4: roam token sweep.
- player-theme §4: the 10 redesign items (reveal buttons at 6.3 s, player node detail, topic tokenisation, official-detail dark, in-place command state, draw-on ring, `.woo` vs medal, staged chapter-finish, play wxml restructure, searchmap marker sheet).
- player-theme §5: `.sc-rank` overshoot `cubic-bezier(.3,1.4,.5,1)` at `pages/play/play-overlays.wxss:66`. **This is the only proposed fix that nobody accounted for.** It is in the held play area, so it goes to the walk-fix follow-up.

**Unowned or conflicting:**
- **Unowned shared requests:** M-14 (duplicate status in `scene-merchant-profit`) and N-03 (scrims `hover-class="none"`) were sent to r2-shared, which never mentions them.
- **No owner (shelf):** D5 and the shelf `background .6s` on a gradient. player-theme scoped the shelf out, and player-home did not take these rows.
- **Lanes disagree:** X3 (shared `.cy-pm-row`: player-me asks, shared declines) and S21 (no chip-grid skeleton, according to player-theme, while shared says every shape exists).

## 4. Owner's choice: one list

Each item gives the measured value, one shot, and my recommendation.

1. **Tab-bar height token.** `--cy-tabbar-h` gives 88.1 px, but the rendered bar is 96.4 px (8.3 px short). Shot: `shots/player-tab-profile-unchanged.png`. **Keep the token.** Pages that must clear the whole bar add `+ var(--cy-space-2)`, as marketing already does.
2. **Scrim press vs the press-coverage test.** The brief says `hover-class="none"`. `press-feedback-coverage-contract` counts that as missing feedback (51 → 57). Shot: `r2-player-home` N-03 has no visible frame (press only). **Exempt scrim classes (`pdim`, `*-mask`, `*-scrim`) in the test, then do N-03 and `.rolemask`.**
3. **Club 角色 / 通知 / 治理.** Title → first section is 23.6 here against 9.1 on 结算 and 8.1 on 运营. The cause is the first `.section`'s `40rpx /* ds-ok: Figma 区块间距 20px */`. Shot: `shots/choice-club-roles-title-gap-vs-settlement.png`. **Align with the same one-line `cy-page-title + .section { margin-top: 0 }`.** The Figma 20 px is a block-to-block gap, not title-to-block.
4. **Home 编辑精选 carousel.** Left 20.8, radius 10.4, against 16.6 / 22.9 elsewhere on home. Shot: `shots/player-home-top-with-content.png`. **Keep** (v13 carousel).
5. **Home 即将上线 → 主题/活动 tabs.** 46.5 here against 32.3 between the other sections; the extra is `cy-tabs wide`'s internal padding. Shot: `shots/player-home-sections-with-content.png`. **Fix later** with a page-local negative margin, as pricing did (−14rpx).
6. **Home floating 「正在更新…」.** Plain tertiary text over the covers (low contrast). Shot: `r2-player-home/shots/home-full-syncing.png`. **Use the new `.cy-refresh-float--pill`.** This also gives that class its first consumer (R5).
7. **Media-card tier.** Activity-list cover cards are `cy-card` at 12.5; the home and discover feeds are at 22.9; `2xl` is used 7 times app-wide. Shot: `r2-player-theme` "activity list" (rows only). **Decide once, app-wide.** I recommend a `cy-card` media variant at `2xl` rather than per-page overrides.
8. **Sibling-list filter variant.** 我的参与 uses `cy-tabs fill`; 我的订单 and 积分明细 use `chip`. The surfaces also differ: `bg-surface` against `bg-card` + border. Shot: `shots/player-mycanyu-voucher-button-clip.png` against `shots/player-orders-filter-to-card-gap.png`. **Keep for now.** A variant change is a restyle (rule 2).
9. **Negative money format.** 「¥-15.44」 sits next to 「+¥1000.00」 in the ledger and batch detail. Shot: `r2-merchant/shots/12-batch-detail-rows-nonzero.png`. **Change family-wide to 「−¥15.44」** in one money-format helper.
10. **Buy-sheet underline overshoot (P-T9).** `.4s cubic-bezier(.34,1.32,.64,1)` on the v13 buy sheet conflicts with "no bounce on popups". No still frame shows it. **Replace with `--cy-ease-out`** (one value; v13 timing kept).
11. **`scrollToFirst` (P-T10).** 500 ms full motion; the audit suggests 350. **Take 350** ("a little fast").
12. **Shared-component press values.**
    - **Change to .88:** avatar `.av--press` .82 and error retry .85 / .98. These are one-liners.
    - **Keep as is:** proto-cam .72 / .96 (over video), roam task list .82 / .62 and album FAB .72 (v13), and toast undo .6 (approved).
13. **Merchant settlement copy (M-14, M-15).** The duplicate status text is in `scene-merchant-profit`, and `.fc-explainer` 「已结算：金额已核算 · 已入账：已到账」 sits next to it. Shot: `r2-merchant/shots/13-coop-finance-nonzero.png`. **M-14: approve** (hide the date line when unsettled). **M-15: drop the explainer** and update shot-matrix C60 at integration.
14. **Ghost `cy-btn` on white merchant cards.** The ghost background `rgba(255,255,255,.88)` is invisible on a white card, so the button reads as bare text. Shot: `r2-merchant/shots/01-merchantinfo-my-node.png` (held page). **Give the light-theme ghost a `--cy-bg-subtle` surface**; this is a shared token change.
15. **Customer drawer inset 20.8 against its 16.6 bottom button.** Shot: `r2-merchant/shots/04`. **Keep** (R07 定稿).
16. **Host 「进度」 and gate card radius.** `xl` 20.8 next to `lg` 16.6. Shot: `r2-merchant/shots/03`. **Keep** (R04).
17. **Helper captions that state rules.** 「撤销后立即生效」 (roles), event-ops section notes, PM-12 「收藏的帖文与主题」, and A9 settlement wording. **Keep.** They carry rules, not decoration.
18. **New motion requested by audits** (M-08, M-23, M-30, PL13, N-04) and **new artwork** (M-05 coupon icon). **Decline.** Rule 5 says fix timing and add nothing new.
19. **Per-station award `.woo`.** At `rgba(6,8,14,.92)` the page shows through. Shot: `r2-player-theme/shots/owner-woo-overlay.png`. **Keep** (held play area).
20. **Pre-existing fake zeros and mixed money formats** seen in this review (not in any audit):
    - 我的队伍 counters show 「0 0 0 0」 while loading. Shot: `shots/player-myteams-loading-skeleton.png`.
    - 我的 shows 「¥0」 before load and 「¥0.00」 after. Shot: `shots/owner0-profile-header-before-load.png`.
    - 确认终价 shows 「¥0」 next to 「¥0.00」 on one screen. Shot: `shots/merchant-pricing-chip-and-note-gaps.png`.

    **Use 「—」 before load for counters** (rule 6), and **always format money with 2 decimals.**

## 5. Owner decisions tested by rendering, and money landing

| Test | Before (master) | After (this tree) | Shot |
|---|---|---|---|
| Public 成就 tab, `/api/user/public-info` with `topicNum`, `activityNum`, `likeNum` and `fansNum` removed | 「—」 「—」 「—」 | **「0」 「0」 「0」** (发布内容 / 获赞 / 粉丝) | `owner0-profile-achievements-fields-missing.png` |
| Public 成就 tab, real data | 0 / 0 / 0 | 0 / 0 / 0 | `owner0-profile-achievements-real-data.png` |
| Own header, `/api/user/info` with `friendNum`, `followNum` and `fansNum` removed | 0 / 0 / 0 | **0 / 0 / 0** (好友 / 关注 / 粉丝) | `owner0-profile-header-fields-missing.png` |
| Own header before the API answers (response held 8 s) | 0 / 0 / 0 | **0 / 0 / 0** | `owner0-profile-header-before-load.png` |

Both owner "0" decisions hold. The round-2 「—」 pre-load idea was fully reverted, so the header matches master.

**Money landing with a non-zero amount.** I used merchant 9002 on the 结算 view of `pages/merchant/ledger/index?view=settlement`. The in-page `fetch` was wrapped so `/merchant/finance/overview` returns `personalArrivedThisMonthNet = 1234.56`. The page's own `pageMotion.countMoney('lgK')` with `count.wxs money()` was sampled every 25 ms (`data/money-samples-*.json`).

| Measure | After | Before |
|---|---|---|
| Count starts (after navigation) | 335 ms | 367 ms |
| Lands on 「¥1234.56」 | 1556 ms | 1566 ms |
| Duration | 1247 ms | 1225 ms |
| Reached at 25 % of the time | **9 %** | 9 % |
| Reached at 50 % of the time | **54 %** | 61 % |
| Reached at 75 % of the time | **95 %** | 93 % |

The curve is slow-fast-slow, and the last frame is the real value 「¥1234.56」. Before and after match: the landing was done in round 1 and round 2 did not change it. Shots: `merchant-ledger-money-landing-900ms.png` (mid-count 「¥893.82」) and `merchant-ledger-money-landing-final.png`.

## 6. Not exercised

- **Motion and press timing** (stills cannot show it):
  - tab-bar curve (T-02)
  - post-card and event-attendance press
  - M-06 revenue hold, M-31 check fill
  - C-02, MT-03, D-02, PM-13 staggers
  - CP-06, CP-07, CP-08 (fabu)
  - CP-13 temp scroll, CP-14 join-request fade, CP-17 and CP-18 crop
  - S17 popover, D2 and D3 templatedetail
- **Not reached in the rig:**
  - game-node (no prepared session), batch-detail (no transfer batch), team detail (no team)
  - templatedetail (id=1 missing)
  - club settlement count (no profit-share rows), official routes, the shared roam session, and the POI photo credit
- **Platform:** real device, real fonts, safe areas and native `<map>`.

STATUS: COMPLETE
