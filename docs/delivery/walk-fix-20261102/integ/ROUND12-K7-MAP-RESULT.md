# Round 12, lane k7-map: result

> `ROUND12-K7-RESULT.md` already held another lane's result (k7-game-fixes, j3 items), so this lane's result is here.

Branch `integ/launch-test-20261103`. I worked on a private local branch, ran `git pull --rebase` and `git show --stat` before each push, and did not force-push, merge, touch master or touch production. No keys in the diff.

**The rig ran this time, and it is still not a phone.** Docker was not running in the container. I started `dockerd` myself and brought up the rig (`claude/cloud-walk-20261101-rig` plus j4's `tools/rig-changes.diff`): the real jar, MySQL 8 and Redis 7, and the real mini-program code in headless Chrome at 390×844, with 2× screenshots. Tencent's map is the rig's canvas stand-in, so the map styles are not bound (the map styles are on hold anyway). Every screenshot named below is under `integ/fix-shots/k7/` and I looked at each one. `b-` is the integ head before my change and `a-` is after, on the same data. The scripts are in `fix-shots/k7/tools/`.

## Commits

| Commit | What |
|---|---|
| `0f5a383b` | j4 N10 (backend) |
| `dcca5236` | j4 N1, N2, N3, N4, N5, N6, N8, N9, F14, F15, F16 (mini-program) |
| `4dae9dc6` | Club theme list no longer sends the author's login name (a phone number), found while walking k2's guest work. Team detail title uses the same rule as the lists (F15). |
| `b52be81a` | Guest club page: no 「编辑 / 删除」 on other people's posts. Guest member list: the join time is formatted. Both found while walking k2's guest work. |
| next commit | This file, the shots, the read-backs and the walk scripts |

## Setup and fixtures (test DB only)

- **Rig.** `up.sh` was pinned to my commit. I used `RIG_SKIP_BUILD` with a fresh build each time the backend changed. Re-explode and restart kept the data.
- **Fee rules (HARNESS).** The seed has no `settlement_rule` rows. Since k1 item 7, a missing rule refuses, so `merchant_club` stopped at 「平台费率规则缺失」. I ran k6's `fix-shots/k6/tools/harness_rules.sh` (the three generic 3% rows production has). After that, all four chains PASS.
- **Photos (HARNESS).** `APP_MEDIA_PUBLIC_BASE_URL=http://127.0.0.1:8097/__api`. Without it, `/api/roam/pois` answers 「图片发布地址未配置」.
- **Map data.**
  - j4's `poi.sql`: 30 places around People's Square, 3 of them shops.
  - j4's `move.sql`: stations moved next to them.
  - `cms_topic.team_mode=1` for 990014.
- **Players.**
  - `walk-player-1..3` (990005–990007) were made by real login. Nicknames 阿青 (990006) and 小周 (990007) were set by SQL.
  - They and 9004 joined club 7002 through `/api/club/join`.
  - They bought session 9 through real quote and create, then the rig pay seam, which calls the real callback.
- **Teams.** 阿青 and 小周 made untitled public teams through `/api/team/create`. walk-player-1 applied to 阿青's team through `/api/team/apply`.
- **Session 9.** I moved it with `seed-walk.py shift-session` several times: started for play and N10, in the future for the team lists. 9008 started the trip through `/api/club/lead/start`.
- **No-game ticket.** Activity 20 is a copy of session 10 with `topic_id` NULL, plus ticket 90. 9009 joined and bought it for real (registration 79).
- **Club posts.** Two fictional posts in club 7002, made through `/api/club/post/create`.

## j4 items

| ID | Status | What changed | Files | Proof | Unverified |
|---|---|---|---|---|---|
| N1 | fixed | Tapping a shop's photo pin opens the swipe row on that shop's card. A shop past the first 8 is put into the row. The 「附近 N 个地点」 chip is tappable (88rpx tall) and opens the row. Other places still open the place card. | `pages/roam/index.js` `openShopStrip(focus)`, `onPinCountTap`, `onMarkerTap`; `index.wxml`; `index.wxss` | `b-/a-n1-shop-pin-tap`: before, the place card 画廊三号; after, the row on 画廊三号 (3/3). `b-/a-n1-chip-tap`: before, nothing; after, the row opens. The chip was a real DOM tap. The pin tap went through the page's own `onMarkerTap`, because the stand-in draws pins on a canvas. Tests: `k7-map.test.js` (4). | Real `customCallout` taps on a phone. The swiper following `current` (j4 M10). |
| F14 | fixed | Activity cards (tag, title, meta and the 附近活动 heading), the stat card's title and labels, and the map's shop stickers use `calc(Npx * var(--cy-type-scale, 1))`. The stat card's big numbers (62rpx) stay fixed, per the DS rule for data-xl sizes. | `pages/roam/index.wxss`, `components/cy/free-map/index.wxss` | `b-/a-f14-entry-font`, `b-/a-f14-map-font` at `--cy-type-scale` 1.3. After, the card text, the stat labels and the 弄堂口咖啡 / 画廊三号 stickers are bigger. | Real fonts at the largest size on a phone. |
| N10 | fixed | When the author's switch is off, any `state` / `consent` / `sync` call deletes the caller's own stored points before answering `available:false`. | `TeamTrailServiceImpl.eligibleTtl` | Real HTTP: `n10-readbacks.txt`. 990005 and 990006 stored points. The switch went off. 阿青's `finished:true` sync removed 990006's field (it used to stay). 990005's `GET state` removed theirs. The key is gone. Test `authorSwitchOffDeletesTheCallersStoredPoints` (13/13). | — |
| N2 | fixed | Off-screen edge pins are clamped to the visible map: below the search bar (`promptTop`), above the bottom card (512rpx plus the safe area), and at least 24px from each side. Without that box the old 6% inset is used. | `pages/roam/photo-pins.js` `placeInView(…, safe)`; `index.js` `_pinSafeArea()` | `b-n2-edge-pins`: before, pins under the clock and behind 更多 / 暂停 / 拍照. `a-n2-edge-pins`: after, all clamped pins are between the search bar and the card. Read-back: safe box `{top:171,bottom:300}`. | A phone with a real map region. |
| N3 | fixed | Snow is softer: alpha .34, radius 2–5px, overall .5–.8. Before it was a .72 white 1–2.6px dot. | `subpackageRoam/components/map-weather/index.js` | `b-/a-n3-map-snow` with a 0.9 snow reply. The reply is answered in the page (HARNESS: no Tencent key); the drawing code is real. | Real weather text. How it reads over the real dark style. |
| N4 | fixed | The 寻找组队 line shows only with a real count; empty otherwise. | `pages/roam/index.js` (default, reset and zero-team fallback) | Guest entry text: before 「寻找组队 / 附近正在组队」, after 「寻找组队」 alone (`b-/a-n4-guest-entry*`, `teams.mjs` output). | — |
| N5 | fixed | Session cards put the session's start time (Beijing time) before the distance. Theme cards are unchanged. | `utils/roam-entry-map.js` `sessionWhen` | `b-/a-n5-entry`. Read-back of the five cards. Before: five times 「距你 2m」. After: 「11月5日 14:42 · 距你 2m」, 「11月16日 10:00 · …」, 「11月17日 …」, 「11月18日 …」, 「11月5日 14:36 …」. | — |
| N6 | fixed | `pages/searchmap` gives the root `theme-merchant` when the app is in the merchant view, so back, search and filter are light. Players keep `theme-dark`. | `pages/searchmap/index.{js,wxml,wxss}` | `b-/a-n6-merchant-searchmap` (merchant 9002, reached from merchant home): light back and filter pills after. `a-n6-player-searchmap` is unchanged. | The base map in the rig is the stand-in, not the light style. |
| N8 | fixed | All five team confirms (reject, disband, kick, quit, quit-and-hand-over) use full-width 「，；？」. The disband subtitle that repeated the first bullet is gone. | `utils/danger-actions.js` | `b-/a-n8-reject-confirm`, `b-/a-n8-disband-confirm`. Test: no half-width `,;?` left in any team.* text. | — |
| N9 | fixed | The full-width 216rpx number is now only for 点亮坐标 / 玩法完成 (`fst--hero`). Before, whichever stat was first got it, so with no station done 「00:04」 became four giant digits under 继续. | `pages/play/index.{wxml,wxss}` | `b-n9-finish-sheet`: the round shapes are the tops of the giant 「00:04」. `a-n9-finish-sheet`: 「00:04 用时」 is a normal stat. | The first stat row still sits in the black fade above the fixed 继续 until you scroll. That is the design's fade, not an overlap. |
| F15 | fixed | An untitled team is called 「<leader nickname>的队伍」 in 我的队伍, 附近招募, the map plates and team detail. The old name is used only when the leader's name is unknown. | `subpackageRoam/my-teams/index.js`, `subpackageRoam/utils/map-team.js`, `pages/team/detail/index.{js,wxml}` | `b-f15-nearby-teams`: 「一起出发的玩家队伍」 twice. `a-f15-nearby-teams`: 「小周的队伍」 under 附近招募 and 「阿青的队伍」 under 审核中. `a-n8-team-detail`: 「阿青的队伍」. | The join page (`pages/team/join`) has no member names to use, so it keeps the old fallback. |
| F16 | fixed | When a team has no avatar, its map pin and rail item show the first character of the team or leader name, in the team colour. A team pin within 40m of a station moves 42px to the right, so the station number shows. | `pages/play/utils/team-trail.js`, `pages/play/index.{js,wxml,wxss}`, `components/cy/free-map/index.{js,wxml,wxss}` | Two logins in two browsers, real trail sync. `b-f16-storymap`: blank black discs; 阿青's pin covers station 1. `a-f16-storymap`: 「阿」 discs; station 「1」 visible next to the pin. | `customCallout` `anchorX` on a real map. |

## k2's unwalked work

| Item | Result | Proof |
|---|---|---|
| Guests on a club page: themes, posts, members, leaderboard | **Walked: 3 bugs found and fixed** | |
| | The four calls the page makes all return 200 for a guest. `post/create`, `like`, `comment/list`, `join`, `home` and `topic-registrations` return 401. | `k2-guest-http.txt` |
| | **Fixed: data leak.** `/api/club/topics` sent `createBy: "13900000003"` (RuoYi's login name is the author's phone number) to guests and to logged-in users. It now sends `null` for everyone; the page never read it. | `4dae9dc6`, `ApiClubTopicVisibilityTest`, re-read over HTTP after the restart |
| | **Fixed:** a guest saw 「编辑 / 删除」 under every post. The guest projection drops both ids, and `null == null` counted as "mine". This was a dead control; the server still answered 401. | `b-/a-k2-guest-club-posts`, `b52be81a` |
| | **Fixed:** the guest member list showed 「加入 2026-11-05T14:41:17.000+08:00」. It now shows 「2026-11-05 14:41:17」, the same format as the logged-in list. | `b-k2-guest-member-sheet`, `k2-guest-member-sheet`, `GuestBrowseAnonymousReadTest` |
| | No phone, member id, viewer flag or unpublished row in members, leaderboard or posts. The leaderboard is empty (no scores in the fixture). | `k2-guest-http.txt` "non-null private" scan |
| | **Not verified:** the floating 「加入」 button is in the DOM at (331, 765) but does not paint in the rig, for a guest or for a logged-in non-member. I can't tell whether this is the rig or the page. | — |
| Stale login state (j6 N2) | PASS | A guest taps a member, logs in through the page's own path, and the same page then reports `myMemberId 9004` with member controls (退出). `k2-stale-login-*`. |
| 我的参与 as a guest (j6 N3) | PASS | 「登录后查看 · 登录」 (`k2-guest-mycanyu`). It still fires `/api/registration/my-joined` in the background, which gets 401 and shows nothing. |
| No-game ticket (j1 N1) | PASS | 9009, registration 79. 订单详情 has no 开始游戏 (`k2-nogame-order-detail`). 票夹 lands on the ticket already turned to the QR, and `/api/verify/dyncode/issue` returns 200 (`k2-ticket-wallet-1`). |
| Solved station on reopen (j3 F6) | **NOT VERIFIED** | The rig seed has no theme with an advanced game station; 990013 and 990014 stations are shop visits. Building one through the editor was out of time. Only k2's unit tests prove it. |

## Gates

- **Node tests:**
  - New file `tests/unit/k7-map.test.js` (16 tests).
  - Updated for the new contract: `map-team.test.js`, `r11-g1-finish-sheet-label.test.js`, `roam-entry-map-contract.test.js`, `action-ledger.test.js` (tap 2507→2508, free-map recenter id :44→:45).
  - The related suites (roam, team, club detail, danger, entry map, finish) pass.
  - Full `tests/unit/**`: see "Full suite" below.
- **Java tests:**
  - `TeamTrailServiceImplTest` 13/13.
  - `ApiClub*Test`, `GuestBrowseAnonymousReadTest` and `PublicReadEndpointsSecurityChainTest`: 177 run, 0 failures.
  - No gated suite touched, so `ci/funds_it_gate.py` is unchanged.
- **`ui-integrity.js`:** `UI-GATE-0:PASS`, and `build-action-ledger.js --check` PASS (5719), on the tree rebased onto the latest `integ/launch-test-20261103` just before the push.
  - 20 existing U4-DYNAMIC / U5 anchors in roam and play moved by line shifts only. I remapped them by script from `git diff`; the call lines are identical.
  - The `u4Dynamic` and `u5Silent` digests were recomputed. No new entries.
- **`build-action-ledger.js --check`:** PASS at 5719 controls.
  - The integ head's ledger JSON said 5718, while `action-ledger.test.js` already expected 5719.
  - My chip adds 1, so file and test now agree.
- **Main package (preflight's count):** 3,120,796 bytes. That is +4,230 over the integ head (3,116,566), against a ceiling of 3,145,728.
- **Migrations:** none. **New endpoints:** none.

## Full suite

I ran `node --test "tests/unit/**/*.test.js"` on my tree before the rebase: 16,234 tests, 16,228 pass, 6 fail.

- **Mine, fixed in the follow-up commit:** these contract tests pinned what I changed. Both suites now pass.
  - `play-ui-contract` 「点亮坐标」 and its negative control: the regex now allows `fst--hero`.
  - `roam-goal-action-contract` 弱网长目标: it reads the base value out of `calc(21rpx * …)`.
- **Not from my files:**
  - `merchant-theme-route-contract`: `pages/topic/pricing/index` has no `theme-merchant` root. I did not touch that page.
  - `route-matrix-freshness`: the generated route matrix is stale against `app.json`. I did not change `app.json`.
  - `action-ledger` 「release 前重建 inventory」: the same file passed 46/46 when run alone earlier. This run overlapped two ledger rebuilds, so I count it as unconfirmed and it needs a rerun.
- **Not run:** the full suite after the rebase.

## CI

Not waited for, per the wrap-up notice. The pushed commit containing all of this lane's work is the one that adds this file.

## Where it stopped

- Done: every j4 item in the k7 list, with before/after rig shots. k2's guest club walk, stale login, 我的参与 as a guest and the no-game ticket, plus the three bugs the guest walk found.
- Not done:
  - The solved-station-on-reopen walk. There is no advanced-game station in the rig seed.
  - The full unit suite after the rebase. Also recheck `action-ledger.test.js` tap and control totals on the rebased tree, since other lanes changed the counts.
  - CI.
  - Checking whether the club page's floating 「加入」 button paints on a phone.

## What only a phone can prove

- Tapping real `customCallout` photo pins.
- The swiper following `current`.
- `anchorX` on team pins.
- The light map style for merchants.
- The real Tencent weather reply.
- Largest real system font.
- Polling on a cellular network.

STATUS: PARTIAL - cloud stopped for credit (full unit suite, action-ledger.test on the rebased tree, CI and the solved-station walk not done)
