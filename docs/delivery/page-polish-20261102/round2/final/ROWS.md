# Page polish 2026-11-02: row accounting across both rounds

Read-only accounting. Sources: 5 audit REPORT.md (origin/claude/mini-polish-audit-20261102-<lane>), 5 round-1 RESULT.md (origin/claude/page-polish-20261102-impl-<lane>, identical to origin/master copies), round-1 preview RESULT.md and PR #711 body, 6 round-2 RESULT.md on claude/page-polish-r2-final, integration commit a7c3624c, and `git diff origin/master...HEAD -- chengyinhub-xcx` (merge-base c6eac3b8).

Row rules: every finding ID is a row; §-section items without IDs (bugs, redesign list, cross-page patterns that propose something not already an ID) are rows named `<lane> §N <title>`. Cross-page pattern lines that only restate existing IDs are NOT counted (listed at the end). Extra items added by round-1/round-2 lane prompts (H-03b, measure-merchant A1–A12, topic-detail gaps, etc.) are not audit rows and are not counted.

## Counts

| FINAL category | merchant | player-home | player-me | player-theme | club-publish | total |
|---|---|---|---|---|---|---|
| done (round 1) | 22 | 14 | 20 | 25 | 8 | **89** |
| done (round 2) | 6 | 14 | 5 | 16 | 14 | **55** |
| false positive | 2 | 5 | 0 | 3 | 0 | **10** |
| already fixed (before round 1) | 2 | 0 | 0 | 0 | 0 | **2** |
| needs owner | 9 | 10 | 2 | 20 | 1 | **42** |
| too big | 4 | 1 | 3 | 1 | 4 | **13** |
| held (walk-fix) | 1 | 0 | 0 | 6 | 1 | **8** |
| Owner's choice (approved/ds-ok) | 1 | 9 | 1 | 8 | 0 | **19** |
| no change proposed | 2 | 5 | 0 | 0 | 1 | **8** |
| NO ACCOUNTING | 1 | 2 | 0 | 11 | 0 | **14** |
| **all rows** | 50 | 60 | 31 | 90 | 29 | **260** |

### Subset: remaining after round 1 (rows not done in round 1)

| What happened (final) | merchant | player-home | player-me | player-theme | club-publish | total |
|---|---|---|---|---|---|---|
| done (round 2) | 6 | 14 | 5 | 16 | 14 | **55** |
| false positive | 2 | 5 | 0 | 3 | 0 | **10** |
| already fixed (before round 1) | 2 | 0 | 0 | 0 | 0 | **2** |
| needs owner | 9 | 10 | 2 | 20 | 1 | **42** |
| too big | 4 | 1 | 3 | 1 | 4 | **13** |
| held (walk-fix) | 1 | 0 | 0 | 6 | 1 | **8** |
| Owner's choice (approved/ds-ok) | 1 | 9 | 0 | 8 | 0 | **18** |
| no change proposed | 2 | 5 | 0 | 0 | 1 | **8** |
| NO ACCOUNTING | 1 | 2 | 0 | 11 | 0 | **14** |
| **remaining after R1** | 28 | 46 | 10 | 65 | 21 | **170** |

Narrower view, finding IDs only (no B-/X- rows, no § items): remaining after round 1 = **108** (merchant 19, player-home 37, player-me 5, player-theme 35, club-publish 12).
PM-08 is excluded from this subset (done in round 1, reverted later by a7c3624c).

Of the remaining rows, 4 were already resolved by round 1 without a code change (R-05 and home M-01 false positives; T-01/P-03 decided) and 2 were fixed by the round-1 kit base (M-33, X-2).

### The ~96 figure

Not found in the repo. Searched origin/master `docs/delivery/page-polish-20261102/**` (BRIEF, 5 impl RESULT, preview RESULT), the round-2 BRIEF and RESULTs, and the PR #711 body: none states a remaining count of ~96 (the only "96" hits are a 96-colour PNG note and a 96.4 px tab-bar measurement). The figure most likely came from a chat-side tally.
Closest reconstruction: ID-only rows not done in round 1 = 108; minus 4 settled by round 1 without code (R-05, home M-01, T-01, P-03) and M-33 (kit) = 103; minus the 6 audit no-change ID rows (M-26, M-27, R-03, C-04, T-03, home M-02) = 97. That is near 96 but not exact, so my count does not match it; with B-/X-/§ rows the true remaining set is 170.

## NO ACCOUNTING rows

- merchant · merchant §2 customer castCount · 共N位客户 0 is real (no-ID row) (audit itself proposed no change)
- player-home · player-home §2.2 999rpx · Roam 999rpx → pill token (optional) (audit: not proposed)
- player-home · player-home §4 roam sweep · Token sweep of roam wxss (audit: redesign, not proposing)
- player-theme · player-theme §4 reveal · Pass reveal buttons at 6.3s (audit: redesign, not proposing)
- player-theme · player-theme §4 node-detail · No player-facing node detail (audit: redesign, not proposing)
- player-theme · player-theme §4 topic-tokens · Tokenise topic wxss (audit: redesign, not proposing)
- player-theme · player-theme §4 od-dark · Official-detail dark conversion (audit: redesign, not proposing)
- player-theme · player-theme §4 cmd-state · In-place command busy/success (audit: redesign, not proposing)
- player-theme · player-theme §4 ring · Draw-on progress ring (audit: redesign, not proposing)
- player-theme · player-theme §4 woo-medal · Unify .woo with medal screen (audit: redesign, not proposing)
- player-theme · player-theme §4 finsheet · Staged chapter-finish reveal (audit: redesign, not proposing)
- player-theme · player-theme §4 play-wxml · Restructure play wxml (audit: redesign, not proposing)
- player-theme · player-theme §4 marker-sheet · searchmap marker sheet vs D5 (audit: redesign, not proposing)
- player-theme · player-theme §5 sc-rank · sc-rank overlay overshoot (P2 candidate)

Note: of these 14, 13 are items the audit itself did not propose (redesign lists, optional/no-change notes). Only `player-theme §5 sc-rank` (overshoot easing on `pages/play/components/play-overlays.wxss:66`, P2 candidate) is a proposed fix nobody accounted for.

## Claimed done, not present

Every round-2 'done' row was spot-checked in `git diff origin/master...HEAD -- chengyinhub-xcx`; every round-1 'done' row was spot-checked on HEAD (round 1 is on master). All present except:

- **PM-08** (player-me, round 1 done, on origin/master): reverted on this branch by a7c3624c (`subpackageMember/components/cy/profile/index.wxml` back to `userInfo.likeNum || 0` etc., UI-04 ruling "缺数显示 0"). Not walk-fix; categorised Owner's choice. The round-2 extra (profile 好友/关注/粉丝 "—" before load) was reverted in the same commit.
- Withdrawn by a7c3624c (lanes claim done, code absent by design): M-34, PL6, PL10, PL12, PL14, player-theme §3 play-overlays, CP-16. Also withdrawn but not audit rows: r2-merchant project-host/project-join `.qa`, `.pg`, `.dfil + .dg-sub`, `--btn` rows; r2-club-publish topic-detail gaps/radius and enroll `.sub`; r2-player-theme `.rolemask`/`.mask` press neutralisers.
- Partial by the lane's own account: MT-04, MT-06, N-07, PM-15 (monthly-recap left), P-T10 (reduced-motion only).

## Conflicts and unowned rows

- M-22, B-2 (merchant), PL5, B1 (player-me): round-1 RESULT.md says skipped / not changed, but the code was changed in round 1 (preview RESULT 'scope notes'; PR #711 lists the owner decisions). Round 2 lists M-22/PL5/B1 as already fixed; r2-merchant still calls B-2 'needs owner/backend', which is stale: HEAD `pages/merchant/reviews/index.wxml:81` reads 「平均评分」.
- PM-08: done in round 1, reverted in integration (a7c3624c) under the UI-04 0-rule; r2-player-me listed the same 0-vs-— question under Owner's choice.
- X3 (player-me): r2-player-me requests a shared `.cy-pm-row`; r2-shared declines, saying the ladders were converted in round 1 (true for merchant, not for points-history / growthcenter / stamp-album).
- S21 (player-theme): r2-player-theme says no chip-grid skeleton exists (shared request); r2-shared says every requested shape already exists.
- M-14 (merchant) and N-03 (player-home): sent as shared requests; r2-shared does not mention either, so nobody owns them.
- D5, D6 and player-theme §3 shelf-bg: player-theme says `templatedetail/shelf` is out of its scope; player-home owns the shelf but only accounted P-02 (not changed). D5 and shelf-bg are unowned.
- M-33 / X-2: round 1 says 'already done on base' (kit), round 2 lists X-1..X-5 as 'already fixed (round 1)'. Counted as already fixed before round 1 (kit base).

Cross-page pattern lines not counted (pure aliases of counted IDs): player-home §5.3 (P-01/R-02/R-01/H-04), §5.4 (R-01/N-01/MT-01/02), §5.5 (N-03/MT-07/MT-05), §5.6 (T-01); player-theme §5 nav guard (P-T5), skeleton match (P-T7/A6/D6/S21/T12), recovery gate (T1), close via exit (PL3/PL14/P-T8), no-overshoot (PL1/P-T9; its sc-rank part is counted); club-publish §4 'CP-01 is effectively a bug'.

## Full table

| audit lane | row | short title | round-1 outcome | round-2 outcome | FINAL | note |
|---|---|---|---|---|---|---|
| merchant | M-01 | Aftercare alert entrance order | done | already fixed (R1) | done (round 1) |  |
| merchant | M-02 | nth-child section delays → --pmi | done (X-3) | already fixed (R1) | done (round 1) |  |
| merchant | M-03 | Dead rvK counter | done | already fixed (R1) | done (round 1) |  |
| merchant | M-04 | Unrendered revBars setData | done | already fixed (R1) | done (round 1) |  |
| merchant | M-05 | Coupon/refund share icon | not touched | needs owner (no icon; new artwork) | needs owner |  |
| merchant | M-06 | Revenue count waits for hero rise | not touched | done (index.js:990) | done (round 2) | verified in diff |
| merchant | M-07 | Ledger hero money count | done (X-1) | already fixed, verified | done (round 1) |  |
| merchant | M-08 | Redemptions view entrance | not touched | needs owner (new motion) | needs owner |  |
| merchant | M-09 | Ledger overview skeleton height hold | not touched | done | done (round 2) | verified in diff (ledger wxml/wxss) |
| merchant | M-10 | Aftercare tabs vanish on switch | done (X-5) | already fixed (R1) | done (round 1) |  |
| merchant | M-11 | Verify-records double cy-tabs | done (X-5) | already fixed (R1) | done (round 1) |  |
| merchant | M-12 | Order/batch detail money count | done (X-1) | already fixed, verified | done (round 1) |  |
| merchant | M-13 | Coop finance stat-card count | done (X-1) | already fixed, verified | done (round 1) |  |
| merchant | M-14 | Duplicate status text on profit card | not touched | shared request to r2-shared | needs owner | r2-shared never picked it up: unowned |
| merchant | M-15 | Profit explainer helper caption | not touched | needs owner (pinned by shot-matrix) | needs owner |  |
| merchant | M-16 | Settlement-detail money count | done (X-1) | already fixed, verified | done (round 1) |  |
| merchant | M-18 | Customer double entrance | not touched | needs owner (test pins cy-fade-in) | needs owner |  |
| merchant | M-19 | Customer select-dot fill | not touched | false positive | false positive |  |
| merchant | M-20 | Decor coop perks regroup | done | already fixed (R1) | done (round 1) |  |
| merchant | M-21 | Duplicate 承接设置 tap targets | not touched | needs owner | needs owner |  |
| merchant | M-22 | Game-node run card first when live | RESULT: skipped (open decision); actually implemented (0792a89e, PR #711 owner decision) | already fixed (base) | done (round 1) | CONFLICT: R1 RESULT says skipped |
| merchant | M-23 | Reviews staged entrance | not touched | needs owner (new motion) | needs owner |  |
| merchant | M-24 | Rating shown twice | not touched | needs owner (tied to B-2) | needs owner |  |
| merchant | M-25 | Verify group stagger --pmi | done (X-3) | already fixed (R1) | done (round 1) |  |
| merchant | M-26 | Marketing hero count (keep default) | not touched | no change (audit proposed none) | no change proposed |  |
| merchant | M-27 | Marketing content tiles | not touched | no change (audit proposed none) | no change proposed |  |
| merchant | M-28 | coop/list stagger --pmi | done (X-3) | already fixed (R1) | done (round 1) |  |
| merchant | M-29 | Decor home static cell captions | not touched | Owner's choice (Figma 31:147) | Owner's choice (approved/ds-ok) |  |
| merchant | M-30 | Decor view-switch entrance | not touched | needs owner (new motion) | needs owner |  |
| merchant | M-31 | Game-node checklist fill transition | not touched | done | done (round 2) | verified in diff |
| merchant | M-32 | Game-node helper captions removed | not touched | done | done (round 2) | verified in diff |
| merchant | M-33 | coop-center fly-in kills press scale | already done by X-2 on base | already fixed (X-2 in base) | already fixed (before round 1) | fixed by round-1 kit on integrate base |
| merchant | M-34 | Official-event cards fly-in | not touched | done (coop-center wxml:63) | held (walk-fix) | withdrawn by a7c3624c (merchant coop-center) |
| merchant | merchant §2 customer castCount | 共N位客户 0 is real (no-ID row) | — | — | NO ACCOUNTING | audit itself proposed no change |
| merchant | X-1 | Money count helper | done | already fixed, verified on 5 pages | done (round 1) |  |
| merchant | X-2 | cy-fly-in without transform | already on base | already fixed | already fixed (before round 1) | round-1 kit base |
| merchant | X-3 | Index-driven stagger | done | already fixed (R1) | done (round 1) |  |
| merchant | X-4 | Native button press feedback | done | already fixed (R1) | done (round 1) |  |
| merchant | X-5 | Chip tabs outside state chain | done | already fixed (R1) | done (round 1) |  |
| merchant | merchant §3 ease token | --cy-ease-page-rise token | — | r2-shared done (token + 2 component copies) | done (round 2) | ~45 page-file copies left for page lanes |
| merchant | B-1 | Decor empty card without profile-write | done | already fixed (R1) | done (round 1) |  |
| merchant | B-2 | 「近 30 天均分」 shows all-time avg | RESULT: reported, not changed; actually label → 「平均评分」 (PR #711 owner decision) | needs owner / backend | done (round 1) | CONFLICT: R1 RESULT + R2 both stale; HEAD reviews/index.wxml:81 = 平均评分 |
| merchant | B-3 | Reviews recovery block unstyled | done | already fixed (R1) | done (round 1) |  |
| merchant | B-4 | Dead rvK / revBars | done | already fixed (R1) | done (round 1) |  |
| merchant | B-5 | Withdraw records double title | — | false positive (nav title not drawn) | false positive |  |
| merchant | B-6 | role=switch not aria-role | — | done (aria-role added) | done (round 2) | verified in diff |
| merchant | merchant §5 home | Home 7-day sparkline | — | too big | too big |  |
| merchant | merchant §5 decor | Decor 升级权益 dropdown → sheet | — | too big | too big |  |
| merchant | merchant §5 game-node | Game-node setup/live modes | — | too big | too big |  |
| merchant | merchant §5 search | Search dimming | — | too big | too big |  |
| player-home | H-01 | Home section entrance order | done | already fixed (R1) | done (round 1) |  |
| player-home | H-02 | Sections re-enter on refresh | done | already fixed (R1) | done (round 1) |  |
| player-home | H-03 | Refresh empties feed | done | already fixed (R1) | done (round 1) |  |
| player-home | H-04 | Feed first-load / empty state | skipped (P2) | done | done (round 2) | verified in diff |
| player-home | H-05 | 即将上线 hidden when empty | skipped (P2) | needs owner | needs owner |  |
| player-home | H-06 | 编辑精选 helper caption | skipped (P2) | false positive (owner P-02 kept) | false positive |  |
| player-home | R-01 | Roam peek false empty (P0) | done | already fixed (R1) | done (round 1) |  |
| player-home | R-02 | Passport blanks on show | done | already fixed (R1) | done (round 1) |  |
| player-home | R-03 | Peek error has no retry | skipped (P2) | no change (audit proposed none) | no change proposed |  |
| player-home | R-04 | Passport tile captions | skipped (P2) | Owner's choice (P-29) | Owner's choice (approved/ds-ok) |  |
| player-home | R-05 | Prompt-stack card radii | false positive | false positive | false positive | decided in R1 |
| player-home | R-06 | 抵达 floatUp .7s | skipped (P2) | Owner's choice (v13) | Owner's choice (approved/ds-ok) |  |
| player-home | R-07 | 附近暂无活动 second line | skipped (P2) | Owner's choice (v13) | Owner's choice (approved/ds-ok) |  |
| player-home | C-01 | Clubs refetch/replay on tab return | done | already fixed (R1); extended to 帖文 | done (round 1) |  |
| player-home | C-02 | Post card double entrance | skipped (P2) | done (filter:opacity) | done (round 2) | verified in diff |
| player-home | C-03 | Duplicate 创建俱乐部 CTA | skipped (P2) | done | done (round 2) | verified in diff |
| player-home | C-04 | Inner tab re-rise (keep) | skipped | no change by design | no change proposed |  |
| player-home | P-01 | 发布 placeholder → skeleton | done | already fixed (R1) | done (round 1) |  |
| player-home | P-02 | Shelf skeleton shape | skipped (P2) | not changed (pairs with R1 placeholder; unmeasured) | needs owner | left open; overlaps player-theme D6 |
| player-home | P-03 | 发布 tab-bar hide toggle | skipped (=T-01) | decided, not reopened | Owner's choice (approved/ds-ok) | BRIEF: home tab bar stays |
| player-home | M-01 (home) | 我的 logged-out flash | false positive | false positive (R1) | false positive | decided in R1 |
| player-home | M-02 (home) | Duplicate 加载中 text | skipped | no change (audit: leave it) | no change proposed |  |
| player-home | T-01 | Tab bar visibility one rule | skipped (open decision) | decided, not reopened | Owner's choice (approved/ds-ok) | BRIEF: home tab bar stays |
| player-home | T-02 | Tab bar curve → tokens | skipped (P2) | shared request → r2-shared done | done (round 2) | verified in diff (tabBar wxss) |
| player-home | T-03 | Selected pill with label | skipped | not proposed by audit | no change proposed |  |
| player-home | N-01 | Nearby false empty / fake 0 | done | already fixed (R1) | done (round 1) |  |
| player-home | N-02 | Dropdown/POI overshoot | skipped (owner) | decided (BRIEF: N-02 stays) | Owner's choice (approved/ds-ok) |  |
| player-home | N-03 | Scrims hover-class none | skipped (P2) | reverted; shared request (press ratchet test) | needs owner | r2-shared did not address: unowned |
| player-home | N-04 | Fade-in list/strip/corners | skipped (P2) | Owner's choice (v13 map) | Owner's choice (approved/ds-ok) |  |
| player-home | N-05 | Error card vs map switch | skipped | not changed (inferred, not reached) | needs owner | unverified |
| player-home | N-06 | Pill radii spelled 5 ways | skipped | Owner's choice (ds-ok v13) | Owner's choice (approved/ds-ok) |  |
| player-home | N-07 | Nearby dead rules | skipped | done (partly; .mt-chip block kept) | done (round 2) | verified in diff |
| player-home | N-08 | Nearby hint captions | skipped (P2) | Owner's choice (v13 copy) | Owner's choice (approved/ds-ok) |  |
| player-home | N-09 | 我的队伍 sheet empty while loading | skipped (P2) | done | done (round 2) | verified in diff |
| player-home | MT-01 | Stats row pops in | done | already fixed (R1) | done (round 1) |  |
| player-home | MT-02 | Fake zeros on partial failure | done | already fixed (R1) | done (round 1) |  |
| player-home | MT-03 | Uncapped card stagger | skipped (P2) | done | done (round 2) | verified in diff |
| player-home | MT-04 | Bare loading text | skipped (P2) | done (我的 tab only) | done (round 2) | partial; verified in diff |
| player-home | MT-05 | Local .cy-pressed .82 | skipped (P2) | done | done (round 2) | verified in diff |
| player-home | MT-06 | Error row radius/wording | skipped | radius done; wording test-pinned | done (round 2) | partial; verified in diff |
| player-home | MT-07 | Recovery panel native buttons | skipped (P2) | done | done (round 2) | verified in diff |
| player-home | S-01 | Shared-link loading text | skipped (P2) | not changed (needs new skeleton variant) | too big |  |
| player-home | S-02 | Two loaders on share card | skipped (P2) | not changed (test-pinned) | needs owner |  |
| player-home | S-03 | Dangling ' · —' separator | skipped (P2) | done | done (round 2) | verified in diff |
| player-home | R-08 | Official route card without cover | skipped (P2) | not changed (blank box = rule 5) | false positive |  |
| player-home | D-01 (roam) | Official-route skeleton count | skipped (P2) | not changed (inferred, not seeded) | needs owner | unverified |
| player-home | D-02 (roam) | Official-route content entrance | skipped (P2) | done | done (round 2) | verified in diff |
| player-home | B-1 (home) | 「null 次已完成旅程」 | — | false positive (reader throws) | false positive |  |
| player-home | B-2 (home) | Refresher closes on 800ms timer | — | not changed, mitigated by H-03/H-03b | needs owner |  |
| player-home | B-3 (home) | Shelf bindretry on load-failure veil | — | needs owner (policy) | needs owner |  |
| player-home | B-4 (home) | undefined var(--sub) | — | done | done (round 2) | verified in diff |
| player-home | B-5 (home) | Nearby false empty (=N-01) | done (N-01) | = N-01 | done (round 1) |  |
| player-home | B-6 (home) | my-teams fake zeros (=MT-02) | done (MT-02) | = MT-02 | done (round 1) |  |
| player-home | B-7 (home) | Two loaders (=S-02) | — | = S-02 not changed | needs owner |  |
| player-home | player-home §2.2 999rpx | Roam 999rpx → pill token (optional) | — | — | NO ACCOUNTING | audit: not proposed |
| player-home | player-home §4 load-failure | Recovery after cy-error load-failure | — | needs owner (via B-3 note) | needs owner |  |
| player-home | player-home §4 tab pill | Tab-bar labelled pill (=T-03) | — | = T-03 | no change proposed |  |
| player-home | player-home §4 roam sweep | Token sweep of roam wxss | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-home | player-home §5.1 | enter() replays on refresh/tab | done (kit once) | r2-shared: already fixed | done (round 1) |  |
| player-home | player-home §5.2 | Refresh status row pushes content | done (cy-refresh-float) | r2-shared: already fixed + pill class | done (round 1) |  |
| player-me | PM-01 | Refresh rows (address/im/myproject/gerenziliao) | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-01a | mycanyu refresh row | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-01b | mylike empty refresh node | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-02a | points-history skeleton type | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-02b | appeal-mine skeleton type | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-02c | report-mine skeleton type | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-04 | mycanyu card inset | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-05 | Earnings money count timing | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-07 | IM route card fake 0 | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-11 | order 换个筛选试试 sub | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-11b | points-history 换个筛选试试 | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-11c | mycanyu 换个筛选试试 | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-14 | Anniversary 0 年前 | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-16 | appeal/report row rhythm | done | already fixed (R1) | done (round 1) |  |
| player-me | PM-03 | mycanyu list entrance | skipped (P2) | done | done (round 2) | verified in diff |
| player-me | PM-08 | Public stats fake 0 | done | already fixed (R1) | Owner's choice (approved/ds-ok) | R1 fix REVERTED by a7c3624c (UI-04 ruling: missing → 0); not present on branch |
| player-me | PM-10 | Order empty illustration swap | skipped (P2) | done | done (round 2) | verified in diff |
| player-me | PM-12 | shezhi 我的收藏 caption | skipped (P2) | needs owner (F-L5C-06 pinned) | needs owner |  |
| player-me | PM-13 | Uncapped tools stagger | skipped (P2) | done (4 pages) | done (round 2) | verified in diff |
| player-me | PM-15 | Tools first-load text line | skipped (P2) | anniversary done; monthly-recap too big | done (round 2) | partial |
| player-me | B1 (me) | myproject always light | RESULT: skipped (open decision); actually implemented (50bb3cdc) | already fixed (50bb3cdc) | done (round 1) | CONFLICT: R1 RESULT says skipped |
| player-me | B2 (me) | Capsule 0 天 fallback | done | already fixed (R1) | done (round 1) |  |
| player-me | player-me §4 free-explore/orient-score | free-explore/orient-score button walls | — | too big | too big |  |
| player-me | player-me §4 im/chat | im/chat radii de-slop | — | too big | too big |  |
| player-me | player-me §4 mycanyu | mycanyu vs order card surfaces | — | too big | too big |  |
| player-me | X1 (me) | Refresh float class | done | already fixed | done (round 1) |  |
| player-me | X2 (me) | Skeleton shape mismatch | done | already fixed | done (round 1) |  |
| player-me | X3 (me) | Shared .cy-pm-row stagger rule | skipped | shared request; r2-shared declined | needs owner | CONFLICT: r2-player-me requests it, r2-shared says not needed |
| player-me | X4 (me) | 换个筛选试试 subs | done | already fixed | done (round 1) |  |
| player-me | X5 (me) | num.wxs — helper | done | already fixed | done (round 1) | PM-08 part reverted by a7c3624c |
| player-me | X6 (me) | Uncapped index*60 stagger | skipped | done (=PM-13) | done (round 2) |  |
| player-theme | P-T1 | Late press under tab stagger | done | R1 (present) | done (round 1) |  |
| player-theme | P-T2 | Chapter card inline delays | done | R1 (present) | done (round 1) |  |
| player-theme | P-T3 | hero-desc missing from rise | done | R1 (present) | done (round 1) |  |
| player-theme | P-T4 | Buy-sheet dim press scales | done | R1 (present) | done (round 1) |  |
| player-theme | P-T5 | Double-tap nav guard | done | R1 (present) | done (round 1) |  |
| player-theme | P-T6 | Reveal exit flash | done | R1 (present) | done (round 1) |  |
| player-theme | P-T7 | Topic skeleton shape | done | R1 (present) | done (round 1) |  |
| player-theme | PL1 | Role card overshoot | done | R1 (present) | done (round 1) |  |
| player-theme | PL2 | Pack tile done/unlock fade | done | R1 (present) | done (round 1) |  |
| player-theme | PL3 | Clue/board sheet exit | done | R1 (present) | done (round 1) |  |
| player-theme | PL4 | Play skeleton alignment | done | R1 (present) | done (round 1) |  |
| player-theme | PL5 | Per-station .woo fade | RESULT: skipped (open decision); actually implemented (4d5f894d) | already fixed | done (round 1) | CONFLICT: R1 RESULT says skipped |
| player-theme | PL16 | 999rpx → pill token ×8 | done | R1 | done (round 1) |  |
| player-theme | A2 | activity/list enter per keystroke | done | R1 | done (round 1) |  |
| player-theme | A6 | baoming pay bar during loading | done | R1 | done (round 1) |  |
| player-theme | A18 | official-mine bare button | done | R1 | done (round 1) |  |
| player-theme | A22 | .radio radius 50% | done | R1 | done (round 1) |  |
| player-theme | D1 | topic-preview delays | done | R1 | done (round 1) |  |
| player-theme | S3 | search2 double rise | done | R1 | done (round 1) |  |
| player-theme | S4 | search2 clears on typing | done | R1 | done (round 1) |  |
| player-theme | S14 | square/detail press feedback | done | R1 | done (round 1) |  |
| player-theme | S16 | square/detail raw gaps | done | R1 | done (round 1) |  |
| player-theme | T1 | Team join recovery card gate | done | R1 | done (round 1) |  |
| player-theme | P-T8 | Buy sheet → next layer swap | — | Owner's choice (exit handoff) | Owner's choice (approved/ds-ok) |  |
| player-theme | P-T9 | Buy-sheet underline overshoot | — | Owner's choice (v13 ds-ok) | Owner's choice (approved/ds-ok) |  |
| player-theme | P-T10 | pageScrollTo 500ms reduced-motion | — | done (reduced only) | done (round 2) | verified in diff; full-motion part Owner's choice |
| player-theme | P-T11 | 评价 fake 0 | — | done | done (round 2) | verified in diff |
| player-theme | P-T12 | Node duration --分钟 | — | done | done (round 2) | verified in diff |
| player-theme | P-T13 | topic-teams card radius | — | Owner's choice (v13) | Owner's choice (approved/ds-ok) |  |
| player-theme | PL6 | Toast + in-sheet double feedback | — | done | held (walk-fix) | withdrawn by a7c3624c (pages/play/index.js) |
| player-theme | PL7 | Finish rail dead transition | — | Owner's choice (finsheet) | Owner's choice (approved/ds-ok) |  |
| player-theme | PL8 | Finish stats count-in | — | Owner's choice (finsheet) | Owner's choice (approved/ds-ok) |  |
| player-theme | PL9 | Advanced-game sheet entrance | — | not done (bigger than S) | too big |  |
| player-theme | PL10 | Advanced-game loading skeleton | — | done | held (walk-fix) | withdrawn by a7c3624c (advanced-game) |
| player-theme | PL11 | Tools rows merge while loading | — | skipped (walk-fix l2-explore moves block) | held (walk-fix) | never edited; deferred to walk-fix |
| player-theme | PL12 | 还能错 dot transition | — | done | held (walk-fix) | withdrawn by a7c3624c (play-stage) |
| player-theme | PL13 | doneCount pulse | — | needs owner (new motion) | needs owner |  |
| player-theme | PL14 | 前往下一站 skips exit | — | done | held (walk-fix) | withdrawn by a7c3624c (play/index.js) |
| player-theme | PL15 | .full fade .3s literal | — | Owner's choice (ds-ok) | Owner's choice (approved/ds-ok) |  |
| player-theme | PL17 | 23/31rpx card radii | skipped (ds-ok) | Owner's choice | Owner's choice (approved/ds-ok) |  |
| player-theme | A5 | Official-detail light theme | skipped (open decision) | decided: keep light | Owner's choice (approved/ds-ok) |  |
| player-theme | A7 | Double submit loader | — | needs owner (blocks nav) | needs owner |  |
| player-theme | A8 | （点击切换） caption | — | done | done (round 2) | verified in diff |
| player-theme | A9 | Reward tip captions | — | needs owner | needs owner |  |
| player-theme | A10 | Collective progress --% | — | done | done (round 2) | verified in diff |
| player-theme | D2 | templatedetail mount fade | — | done | done (round 2) | verified in diff |
| player-theme | D3 | Hero recede never attaches | — | done | done (round 2) | verified in diff |
| player-theme | D4 | Stars ignore rating | — | done | done (round 2) | verified in diff |
| player-theme | D5 | Shelf dot width snaps | — | out of scope (shelf) | needs owner | UNOWNED: player-theme says shelf out of scope; player-home never mentions D5 |
| player-theme | D6 | Shelf skeleton shape | — | out of scope (shelf) | needs owner | overlaps player-home P-02 (not changed) |
| player-theme | S15 | Redundant 返回上一页 | — | false positive | false positive |  |
| player-theme | S17 | Popover fade-in | — | done | done (round 2) | verified in diff |
| player-theme | S21 | search2 category skeleton | — | not done; shared request | needs owner | CONFLICT: r2-shared says every requested shape exists |
| player-theme | M1 (theme) | searchmap duplicate no-coord notice | — | false positive | false positive |  |
| player-theme | T11 | Team counts fake 0 | — | done | done (round 2) | verified in diff |
| player-theme | T12 | Team board loading text | — | done | done (round 2) | verified in diff |
| player-theme | T13 | 邀请制 constant cell | — | needs owner | needs owner |  |
| player-theme | T19 | 邀请队友 share button hover | — | done | done (round 2) | verified in diff |
| player-theme | player-theme §3 errmsg | topic raw res.errMsg toast | — | not changed / needs owner | needs owner |  |
| player-theme | player-theme §3 node-card | node card → creator-only scope=my | — | not changed / needs owner | needs owner |  |
| player-theme | player-theme §3 checkout-loading | checkout loading binding | — | not changed / needs owner | needs owner |  |
| player-theme | player-theme §3 retry-dead | _retrySelfPlayPayment dead | — | not changed / needs owner | needs owner | kept for line-keyed baselines |
| player-theme | player-theme §3 shelf-bg | shelf background .6s gradient | — | not changed / needs owner | needs owner | UNOWNED: shelf out of player-theme scope |
| player-theme | player-theme §3 darkmode | app.json darkmode | — | not changed / needs owner | needs owner | reserved file |
| player-theme | player-theme §3 inbox-hover | official-inbox hover precedence | — | done | done (round 2) | verified in diff |
| player-theme | player-theme §3 map-hover | searchmap hover on <map> | — | not changed / needs owner | needs owner | press ratchet |
| player-theme | player-theme §3 最热 | searchmap 最热 sort | — | not changed / needs owner | needs owner | backend check |
| player-theme | player-theme §3 paysuc | baoming dead .paysuc CSS | — | done | done (round 2) | verified in diff |
| player-theme | player-theme §3 square-dead | square/detail dead selectors | — | done | done (round 2) | verified in diff |
| player-theme | player-theme §3 od-refresh | official-detail refresh state top | — | not changed / needs owner | needs owner | unverified |
| player-theme | player-theme §3 play-overlays | play overlays miss reduced-motion | — | done | held (walk-fix) | withdrawn by a7c3624c (play/index.wxml) |
| player-theme | player-theme §3 flashLock | play flashLock no callers | — | not changed / needs owner | needs owner | kept for JS baseline |
| player-theme | player-theme §3 circle-json | play/circle json light bg | — | not changed / needs owner | needs owner | device check |
| player-theme | player-theme §3 vibrate | vibrateShort ignores reducedMotion | — | not changed / needs owner | needs owner |  |
| player-theme | player-theme §3 r.msg | correct-answer r.msg toast | — | not changed / needs owner | needs owner | mirrors backend message |
| player-theme | player-theme §4 reveal | Pass reveal buttons at 6.3s | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 node-detail | No player-facing node detail | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 topic-tokens | Tokenise topic wxss | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 od-dark | Official-detail dark conversion | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 cmd-state | In-place command busy/success | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 ring | Draw-on progress ring | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 woo-medal | Unify .woo with medal screen | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 finsheet | Staged chapter-finish reveal | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 play-wxml | Restructure play wxml | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §4 marker-sheet | searchmap marker sheet vs D5 | — | — | NO ACCOUNTING | audit: redesign, not proposing |
| player-theme | player-theme §5 late-press | Global .cy-pressed delay 0 | — | r2-shared: false positive for kit | false positive |  |
| player-theme | player-theme §5 enter-once | enter() only on first load | — | r2-shared: already fixed (kit once) | done (round 1) |  |
| player-theme | player-theme §5 dash-helper | — for null counts helper | — | r2-shared: already fixed (num.wxs) | done (round 1) |  |
| player-theme | player-theme §5 pressed-styles | team/om pressed → cy-pressed | — | r2-player-theme done (extra) | done (round 2) | verified in diff (team.wxss, official-mine) |
| player-theme | player-theme §5 sc-rank | sc-rank overlay overshoot (P2 candidate) | — | — | NO ACCOUNTING |  |
| club-publish | CP-01 | Feed blinks after every write (P0) | done | R1 | done (round 1) |  |
| club-publish | CP-02 | Manage tab skeletons on return | done | R1 | done (round 1) |  |
| club-publish | CP-03 | Events tab blank on refresh | done | R1 | done (round 1) |  |
| club-publish | CP-05 | Refresh lines push lists | done | R1 | done (round 1) |  |
| club-publish | CP-10 | Story remove without confirm | done | R1 | done (round 1) |  |
| club-publish | CP-11 | 查看预览 spins while saving | done | R1 | done (round 1) |  |
| club-publish | CP-04 | 「—报名」 glued dash | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-04b | Stats/leaderboard helper captions | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-06 | fabu drag lift snaps | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-07 | fabu undo bar pops in | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-08 | fabu save chip flicker | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-12 | temp scroll-with-animation | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-13 | temp new module off-screen | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-14 | join-requests card vanishes | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-15 | settlement money count | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-17 | crop 还原 teleports | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-18 | crop image pops in | skipped (P2) | done | done (round 2) | verified in diff |
| club-publish | CP-16 | topic-detail hard cut | skipped (P2) | done | held (walk-fix) | withdrawn by a7c3624c (club/topic-detail) |
| club-publish | club-publish §4 topicadd | topicadd shell still registered | — | needs owner (app.json reserved) | needs owner |  |
| club-publish | club-publish §4 lastSubmitAction | this.data direct assignment | — | not changed (no visible effect) | no change proposed |  |
| club-publish | club-publish §4 crop-a11y | crop 选取 aria / busy hover | — | done | done (round 2) | verified in diff |
| club-publish | club-publish §5 fabu | fabu undo vs cyToast.undo | — | too big | too big |  |
| club-publish | club-publish §5 chapter-delete | chapter-delete undo | — | too big | too big |  |
| club-publish | club-publish §5 temp | temp row-level undo | — | too big | too big |  |
| club-publish | club-publish §5 full | full stale-while-refresh | — | too big | too big |  |
| club-publish | X-1 (club) | enter on first data only | done (CP-01) | covered; kit once exists | done (round 1) |  |
| club-publish | X-2 (club) | Stable shape while refreshing | done (CP-02/03/05) | covered | done (round 1) |  |
| club-publish | X-3 (club) | Removal feedback fade | — | covered by CP-14 | done (round 2) |  |
| club-publish | X-4 (club) | State-change transitions | — | covered by CP-06/08/17 | done (round 2) |  |
