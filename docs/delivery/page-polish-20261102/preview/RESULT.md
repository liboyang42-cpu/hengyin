# Page polish preview (2026-11-02)

Lane: preview. Branch `claude/page-polish-20261102-preview`, created from `integrate/page-polish-20261102`. This is for the owner's review only. There is no PR, nothing was merged to master, and no product file was edited in this lane.
Rig: `claude/cloud-walk-20261101-rig` (390×844 browser phone). The backend jar is master a1d30fb, seeded with `chains.py finance`: platform, merchant_club, sale_close, player and finance all **PASS** (topic 990014 「E2E 探店日一期」, activity 13, registration 74 for player 9009).
**Before** is a bundle built from the base branch (port 8098). **After** is a bundle built from this preview branch (port 8097). Both ran against the same backend at the same time.

## Regressions (worse than before)

1. **verify-records and aftercare: the filter now sits on the page title.** The gap from the title's ink to the filter is **16.4 → 1.8 px**. The target is 9.1 px, the value the ledger page uses (measured with measure-merchant's `run.mjs`: `07-verify`, `08-aftercare`).
   Cause: two changes in impl-merchant work against each other. The first (`499c7ac8`, M-10/M-11) switched both filters to `cy-tabs ... shared-slab`. The second (`0792a89e`, A7) added `margin-top: -14rpx` to `.vrl-filter` and `.acl-filter`. That margin cancels the chip variant's transparent 14rpx hit band. But the shared-slab variant paints its grey slab from the top of its box, so nothing transparent is left to cancel, and the slab moves up against the title.
   Both edits come from the same lane, so this is not a merge conflict.
   Likely smallest fix (not applied, owner/impl call): drop the two `margin-top: -14rpx` declarations. The slab box would then start 8.3 px under the title, about the 9.1 px target.
   Shots: `m05-verify.png`, `m06-aftercare.png`.
2. **The same two filters changed look, not only behaviour.** Before, they were separate white pills (「已核销 0」「未核销 2」; 「待回应」「处理中」「已完成」). After, they are one full-width grey track with equal-width segments and a black sliding slab.
   impl-merchant's RESULT says verify-records has "no visual diff in the empty state". The rig shows that this is false. Under owner rule 1 (no restyle passes), the owner should confirm this look. Shots: `m05-verify.png`, `m06-aftercare.png`.
3. **Shared `cy-profile` `.pc-tags` on the player (dark) view: the spacing is now less even** (minor). The merchant view hits the measure-merchant A9 targets. On the player's own profile (「关于」 tab), the change behaves like this:
   - 「兴趣标签」 title → 「添加兴趣」 pill: **12.2 → 26.8 px**. The sibling 「个人介绍」 title → card is 16.0 px (both builds).
   - Pill → 「加入时间」 row: **8.3 → 24.9 px**.
   The group used to be about 4 px off its neighbour and is now about 11 px off. Shots: `q04-profile-player.png` (dark) and `m10-profile.png` (merchant).

No product behaviour was changed here to make anything pass.

## Integration (step 1)

The five lanes merged with `--no-ff` in this order: impl-merchant, impl-player-home, impl-player-theme, impl-player-me, impl-club-publish. There were **no conflicts**. No two lanes touched the same file (checked file by file against the base, so there was no overlap to resolve).

Scope notes for the owner. BRIEF lists these as "open, do not implement", but the lanes implemented them. This run's instructions ask me to verify them, so I did not revert anything:
- M-22, game-node run card order: implemented by impl-merchant (`0792a89e`). The block is now duplicated in the WXML, with one copy for `playable` and one for the other states.
- B1, My Projects theme: implemented by impl-player-me (`50bb3cdc`).
- PL5, per-station award: impl-player-theme added a 0.35 s `floatUp` entrance to `.woo__hero` (`4d5f894d`).

Two other changes the owner may want to look at:
- reviews: the stat label 「近 30 天均分」 became 「平均评分」 (impl-merchant). This rewords text. Check that it does not mirror a backend field.
- decor 「合作经营」: the 「衍生权益」 row moved into the 「常备权益」 card ("权益并卡"), so there is one fewer card (`m11-decor-coop.png`).

## Reserved generated files (step 2)

- Action ledger, regenerated with `npm run audit:actions:update`: **controlCount 5579 → 5581**. These are **real control-instance changes, not just line numbers**:
  - `pages/merchant/game-node/index`: 37 → 41. The 「本站运行」 card is rendered in two mutually exclusive places, so `showLiveCheckin`, `openPauseSheet`, `openManualVerification` and `scanSubmission` each appear twice.
  - `pages/merchant/verify-records/index`: 5 → 4, and `pages/merchant/aftercare/index`: 9 → 8. The `switchBucket` tabs used to appear twice (empty branch and list branch) and are now one persistent instance.
  - Same count, changed identity: reviews native `button` → `cy-btn` (`recoverReply`, `copyReplyRequestId`); official-mine `button` → `cy-btn` (`recoverBroadcast`); publish/temp `removeModule` → `confirmRemoveModule`; nearby and capsule aria-label expressions.
  - Everything else in the 9983-line diff is line or digest re-keying.
- `tests/unit/action-ledger.test.js`, matching counts with dated comments:
  - `ledger.controlCount` 5579 → 5581.
  - local `tap` 2427 → 2430. game-node +3; `scanSubmission` counts as external.
  - local `event-contract` 2727 → 2725. `switchBucket` −1 on each of verify-records and aftercare.
- `tests/lint/ui-integrity-baseline.js`: dropped the `pages/merchant/index/index.js#revBars` debt entry, as impl-merchant asked. `revBars` is no longer computed.

## Check results (step 3)

Run from `chengyinhub-xcx/` on this branch, with `origin/master` fetched.

| Check | Result |
|---|---|
| `node --test "tests/unit/**/*.test.js"` | 15535 tests: **15534 pass, 1 fail**. The fail was `action-ledger.test.js` #116 (local tap 2430 vs pinned 2427), the reserved count. I updated it (and the event-contract count behind it, 2725 vs 2727, which the first assertion had masked). Re-run of `action-ledger.test.js` afterwards: see the line below. |
| `action-ledger.test.js` re-run after the count updates | **46/46 pass**, 0 fail |
| `scripts/ds-hardcode-gate.sh --ci` | exit 0 (no output). The file is checked in without the exec bit, so I ran it as `bash scripts/ds-hardcode-gate.sh --ci`. |
| `node scripts/ui-theme-unit-lint.js --theme` | pass (「页面根主题门禁:通过」) |
| `node scripts/ui-theme-unit-lint.js --units` | pass (「WXML 孤立单位门禁:通过」) |
| `node scripts/setdata-dead-field-lint.js` | pass (274 in the baseline, 0 new). Note: 7 baseline entries are now stale. |
| `node scripts/a11y-tap-target-lint.js` | pass: 0 too-small hit areas, 0 new, self-test 29/29 |
| WXML/WXSS compile | There is no WeChat compiler (`wcc`/`wcsc`) in the container. Instead, all 90 changed `.wxml`/`.wxss` files went through the rig's mini-program compiler (`mpweb-seed` `bundle.py` + `compile.mjs`): both bundles built with 410 templates and **0 errors or warnings** (base 13211 functions, preview 13233). |
| `ci/xcx-check.sh` | not run (forbidden) |

## Before / after (step 4)

Spacing values are px at 390 width. Most come from measure-merchant's `tools/run.mjs` on both bundles (ink-to-ink); the rest come from `getBoundingClientRect` probes. Every shot is a before | after pair in `shots/`.

| Item | Before | After | Verdict | Shot |
|---|---|---|---|---|
| Merchant home: 「开通前置检查」 title → card (A1) | 20.6 | 31 (= 「项目」/「我的消息」 31) | fixed | `m01-home.png`, `m01-home-b.png` |
| Merchant home: card → next section title | 27 / 37.4 | 37.4 / 37.4 | fixed | `m01-home-b.png` |
| Ledger settlement: title → hero (A2) | 21.6 | 9.1 | fixed | `m02-ledger-settle.png` |
| Ledger settlement: hero→button / button→alert / alert→「收入明细」 (A3) | 20.8 / 12.5 / 27 | 20.8 / 20.8 / 22.8 | fixed | `m02-ledger-settle.png` |
| Coop finance: card left inset vs title (A4) | 33.3 vs 16.6 | 16.6 vs 16.6 | fixed | `m03-coop-finance.png` |
| Coop finance: 「可提现」 row → button (A5) | 0 | 12.4 | fixed | `m03-coop-finance.png` |
| Coop center: first tab label x vs cards (A6) | 37.4 vs 12.5 | 12.5 vs 12.5 | fixed | `m04-coop-center.png` |
| Verify-records: title → filter (A7) | 16.4 | **1.8** | **regression** (see 1) | `m05-verify.png` |
| Verify-records: filter → first block (A8, empty state) | 57.2 | 37.4 | changed, no list rows to judge | `m05-verify.png` |
| Aftercare: title → filter (A7) | 16.4 | **1.8** | **regression** (see 1) | `m06-aftercare.png` |
| Marketing: 「进入合作中心」 banner vs tab bar at full scroll (A11) | banner 8.3 under the bar | banner ends at the bar top (text bottom 739.2 → 731.2) | fixed | `m07-marketing-bottom.png`, `m07-marketing.png` |
| Customer: chips 「筛选」「管理」 left edge (A12) | 16.6 | 20.8 (= title) | fixed | `m08-customer.png` |
| Coop invite: step ③ title x / title → list (A10) | 20.8 / 23 | 16.6 / 13.6 | fixed | `m09-invite-b.png` |
| Profile (merchant): 「品类」 title → tag / tag → 「营业信息」 (A9) | 12.3 / 17.5 | 26.8 / 34.2 | fixed (others 26.7–27.1 / 32.6–32.9) | `m10-profile.png`, `m10-profile-b.png` |
| Profile (player, dark, shared `.pc-tags`) | 12.2 / 8.3 | 26.8 / 24.9 | **less even** (see 3) | `q04-profile-player.png`, `q04-profile-player-b.png` |
| Decor 「合作经营」 | 4 cards | 3 cards, 「衍生权益」 inside 「常备权益」 | structure change, owner to confirm | `m11-decor-coop.png` |
| Reviews `?mode=manage` empty | 「还没有真实到店评价」 | same | no change | `m12-reviews.png` |
| Reviews reply-pending recovery (forced `replyPending`, dummy id) | bare text and two full-width native buttons | white card with `cy-btn` primary/secondary | fixed | `m12-reviews-recovery.png` |
| game-node, `station.playable` (forced data) | 「本站运行」 after 「这一站怎么接待」 | 「本站运行」 directly after the hero, before the briefing | as specified | `m13-gamenode-playable.png` |
| game-node, not playable (forced data) | 「本站运行」 after the briefing | still after the briefing (text order: hero → 「这一站怎么接待」 → 「本站运行」 → 「最近操作」) | as specified | `m13-gamenode-notplay.png`, `m13-gamenode-notplay-b.png` |
| search2/result `?keyword=E2E` | info banner inset 29 (double padding), retry on its own row | inset 17, 「重试本页」 inline | improved | `p01-search.png` |
| club/detail 7001 (overview) | 「本团贡献榜」 title → card 40, member block → title 48 | 30 / 39 (matches 「成员」 title → avatar 30) | improved | `p02-club.png`, `p02-club-b.png` |
| mycanyu (9009) | card inset 8 | card inset 17 (page margin) | improved | `p03-mycanyu.png` |
| my-teams (9009) | 「附近在招募」 x 20 | x 17 | improved | `p04-myteams.png` |
| Home (9004) | — | no static difference (changes are entrance-once and refresh-keeps-feed) | n/a | `p05-home.png`, `p05-home-b.png` |
| Roam 附近活动 loading (all API +6 s) | false empty 「附近暂无活动 / 打开地图寻找附近活动」 | two card-shaped placeholders | fixed | `p06-roam-loading.png` |
| Roam settled | 「E2E 探店日一期」 card | same | no change | `p06-roam-settled.png` |
| Topic detail 990014 | cover | same (changes are motion/press) | no static change | `p07-topic.png` |
| Baoming loading (all API +6 s) | 3 skeleton cards, no bottom bar | 3 skeleton cards with the bottom bar placeholder held (empty dark pill); the third card runs under the bar | as specified (A6) | `p08-baoming-loading.png` |
| Baoming settled (9004) | 「来自俱乐部的活动，加入后查看」 | same | no change | `p08-baoming-settled.png` |
| My Projects, player 9004, no scope | light page | **dark**: title (248,248,248) on black, tabs (183,183,183), white status bar text, empty state 「还没有主题」 with a light 「去发布」 pill | fixed (B1) | `q01-myproject-player.png` |
| My Projects, player: toast 「已复制」 | — | readable on dark | ok | `q01-myproject-player-toast.png` |
| My Projects, club 9003, no scope: list | light cards | dark cards; 「未开始」 blue-on-navy and 「已下架」 chips readable; 「长按卡片管理」 grey caption readable | fixed | `q02-myproject-club.png` |
| My Projects, club: long-press ops sheet | **white sheet** | dark sheet, rows 「收到的申请」「下架」「另存为模板」 readable | fixed | `q02-myproject-club-sheet.png` |
| My Projects, club: refreshing state (forced `loading && loaded`) | text row pushes the list down | floating pill 「正在同步最新项目…」 on dark | fixed | `q02-myproject-club-refreshing.png` |
| My Projects, club: 「活动」 / 「模板」 empty | light | dark, 「还没有活动」 / 「还没有节点玩法」 readable | fixed | `q02-myproject-club-activity.png`, `q02-myproject-club-tpl.png` |
| My Projects `?scope=MERCHANT` (9002) | light | **light**, black status bar text, toast readable, 「发布单场活动」 empty CTA | unchanged (correct) | `q03-myproject-merchant.png`, `q03-myproject-merchant-toast.png`, `q03-myproject-merchant-activity.png` |
| Play `.woo` per-station award (forced `medal.show`) | renders; hero static | renders; hero `floatUp` 0.35 s (opacity 0.13 → 1.00, translateY 16.3 → 0 px by 355 ms) | renders | `q05-play-woo.png`, `q05-play-woo-150ms.png` |

My Projects in the dark view: I found **no element left light on dark or unreadable** in the states I reached: list, tabs, empty states, ops sheet, toast, refresh pill and status bar. The empty-state illustration keeps its white blob; it is shared artwork and unchanged. Tapping a club card opens a different page, 「当前账号无权查看」, in light (`q02-myproject-club-tap.png`). That page is outside My Projects and identical before and after.

## Page problems that are not regressions (same before and after)

- mycanyu: the participation card clips its 「查看报名凭证」 button at the bottom edge (`p03-mycanyu.png`).
- club/detail: the floating 「加入」 button covers the right end of the first contribution row (the 「5.0 分」 score) until you scroll (`p02-club.png`).
- play `.woo`: the overlay is `rgba(6,8,14,0.92)`, so the play page's 「我到了」 button and line text show through behind 「探店日徽章」 and the coupon pill (`q05-play-woo.png`).
- search2/result: the category chip row is cut at the right edge (「玩…」). It is a horizontal scroll strip.

## Rig artifacts (not page problems)

- Docker Hub returned 429 for the pinned MySQL/Redis digests. I pulled the same digests through `mirror.gcr.io` (as a dockerd registry mirror). The image digests are unchanged.
- Home: the real privacy gate and 「首页城市定位」 dialog cover the lower part of the page in the rig. After 「暂不定位」, the privacy sheet still shows (`p05-home*.png`).
- game-node: the seeded node returns 「本场游戏尚未准备」 (`business-error`), and the page auto-backs. I stubbed `navigateBack` and forced `viewState: 'ready'` and the station data. The data is dummy, for example 「探店日样板咖啡」 and 「在店内找到菜单上的隐藏图案并拍照。」.
- `m09-invite-b.png` and `p02-club-b.png` are taken at max scroll. The after page is shorter, so the frame offsets differ by 7 px and 19 px. Compare the elements, not the frame position.
- The merchant profile route redirects to `pages/userinfo/userinfo` (same as measure-merchant).

## Could not be exercised

- **Money landing** (ledger, order and batch detail, coop finance, earnings): every seeded amount is ¥0.00, so there was no count to see.
- **Lists that need data I could not seed:**
  - reviews list and reply blocks
  - aftercare rows
  - customer rows
  - batch-detail
  - verify-records list rows (so A8's list gap is only measured against the empty art)
- **game-node with real station data** (forced through page data instead).
- **Motion-only changes**, which need a frame-by-frame review I did not do: home entrance order, club tab return, topic tab press, search no-result delay, recovery-card gate. The only motion I timed was `.woo`.
- **My Projects:** the danger-confirm modal (`cy-modal-host`) and the error state in dark were not opened.
- **WeChat devtools compile**: not available in the container.

STATUS: PARTIAL — preview built, checked and shot. Open items: regressions 1–3 need an impl or owner decision; money landing, data-dependent lists and motion-only changes were not exercised.
