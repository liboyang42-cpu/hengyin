# Round 12, lane k8-map-player: result

> `ROUND12-K8-RESULT.md` already held another lane's result (k8-play, j3 game items), so this lane's result is here, as k7-map did. A pointer line was added at the top of that file.

Branch `integ/launch-test-20261103`. I worked on a private local branch, ran `git pull --rebase` and `git show --stat` before each push, never force-pushed, and did not touch master, merge a PR or touch production. No keys in the diff. No migrations. No gated `@Test` was added or removed, so `ci/funds_it_gate.py` is unchanged.

## What happened to the items: three other lanes did most of them first

While I worked, three lanes running the same work list from `ROUND12K7.md` pushed first:
- **k7-map** (`9288094d2`, `278510daf`, `87dde3887`, `919e8bee7`, result `ROUND12-K7-MAP-RESULT.md`): all of item 4 (j4 N1, N2, N3, N4, N5, N6, N8, N9, N10, F14, F15, F16), plus the guest 「编辑 / 删除」 fix on club posts.
- **k9-pages** (`ROUND12-K9-RESULT.md`): p1 N-4 参与详情 for a non-owner, the 排行榜 double line, 队伍详情 guest wording, and item 6 (p1 N-6 to N-16, with N-12 not done and the home chip left for the owner).
- **k8-play** (`ROUND12-K8-RESULT.md`): j3 F13 and the rest of item 5 that is pages/play.

I had written and unit-tested my own versions of all of these, and walked several in the rig. When I rebased onto their pushes, I **dropped my duplicate commits** rather than land a second implementation of the same thing. My local copies are kept on the local branches `k8-backup` / `k8-backup2` only (not pushed). What I pushed is only what nobody else had:

| Commit | What |
|---|---|
| `2a01151af` | 广场 post edit: one write, version +1 (bug found walking k2's j5 N1 fix, see below) |
| `b6073e884` | route-matrix: `pages/searchmap` pinned as a player page (k7-map's N6 made the classifier read it as a merchant page) |
| `915df19e3` | j3 F18: fade under the pinned 画面风格 preview |
| next commit | this file and `fix-shots/k8-map-player/` |

## Environment

- **Docker: started.** No daemon was running. I started `dockerd` with `registry-mirrors: ["https://mirror.gcr.io"]`; MySQL 8.0 and Redis 7 pulled through the mirror.
- **Rig:** `claude/cloud-walk-20261101-rig` plus j4's `tools/rig-changes.diff`, real jar on real MySQL and Redis, real mini-program in headless Chrome at 390×844, 2× JPEG. **Not a phone.** The final walks ran on jar and bundle built from `9d1c8b864` (my post fix on top of the head of that moment); the guest-club re-walk ran on a bundle with k7-map's fix in it.
- **HARNESS / fixtures (test DB only):**
  - The seed has no `settlement_rule` rows. Since k1 item 7, a missing rule refuses pricing, so `chains.py player` stopped at merchant_club (「平台费率规则缺失」). I inserted two 3% platform rows (topic, activity). Then all four chains PASS. k6 and k7-map hit the same thing; CI's chain acceptance seed needs those rows too.
  - `APP_MEDIA_PUBLIC_BASE_URL=http://127.0.0.1:8097/__api`, without which `/api/roam/pois` answers 「图片发布地址未配置」.
  - j4's `poi.sql` (must be loaded with `--default-character-set=utf8mb4`, or names come out garbled).
  - API leg (labelled): players join club 7001 and post there; 9004 writes and publishes 广场 post 1; j3's `author2.py` / `approve.py` / `fx_onsale.py` / `buy_api2.py` for theme 990015 (two stations, 选项问答 and 文字问答, GPS arrival) and a pass for walk-player-2.
  - No-game ticket: a copy of session 13 as activity 900 with `topic_id` and `template_id` NULL, and a copy of 9009's registration as 900, unscanned. Direct test-DB writes.

## Walks of k2's fixes (screenshots under `integ/fix-shots/k8-map-player/`, each one viewed)

| k2 fix | Result | Shots | Read-back |
|---|---|---|---|
| **Solved station on reopen** (theme 990015) | **PASS.** At station 1, answered 梧桐 (`SUBMIT_QA` 200, 「答对了 · 继续故事」), left without tapping 继续故事. Reopened 80 m away: the station card shows 开始任务; tapping it completes the station with no walk back, and the card moves on (distance now 29 m to station 2). | `k2st-01-station1-game`, `k2st-02-answered`, `k2st-03-reopen-80m-away`, `k2st-04-after-tap-80m-away` | `POST /api/play/arrive` 200 「已到达，打卡成功」 with lat 31.2385 / lng 121.4906; `player_node_progress` (990006, node 75) status 1. Nothing on screen says the station was completed; the sheet just closes. |
| **Guests on a club page** (club 7001) | **PASS** for themes, posts, members, board. Found one bug (below), fixed by k7-map; re-walked. | `k2g-01-guest-club-top`, `k2g-06-guest-club-posts` (before), `k2g2-06-guest-club-posts` (after), `k2g-07-guest-club-themes` | Anonymous `topics`, `post/list`, `members`, `leaderboard` 200. Members: nickname, avatar, role, isOwner, level, joinTime only. Posts: `authorMemberId`, `isLiked`, `viewerReposted`, `viewerCanManage` null. Board: no `memberId`. `post/create`, `post/like`, `post/comment/list`, `home`, `join` → 401. **Leak not in k2's list:** the anonymous `topics` row still carried `createBy` (the author's phone number); k7-map removed it in `87dde3887`. |
| **Login state after a write prompt** | **PASS.** Guest taps 加入 → 「登录后继续 · 登录后即可加入俱乐部」 → 微信登录 → the join continues by itself, the page knows the new member (`myMemberId` 990009), shows 退出, 5 members and the welcome sheet. | `k2l-01-guest-taps-join-login-prompt`, `k2l-02-after-login` | `/api/login/code` 200, `/api/club/join` 200, `/api/club/access/me` 200 |
| **Club feed** (j5 N2) | **PASS.** 9004's 主页 → 帖文 lists both club posts with 「城瘾跑团 · 刚刚」. | `k2f-01-my-posts-club-feed` | `POST /api/club/post/feed` 200 (JSON) |
| **No-game ticket** (j1 N1) | **PASS.** 订单详情 has no 开始游戏. 票夹 turns the card to the entry code by itself, with no 开始游戏 bar. | `k2n-01-order-detail-no-game`, `k2n-02-wallet-no-game-ticket` | page data `flipped:true`, `qrState:"ready"`. The order title reads the copied registration's snapshot (「E2E 探店日一期」), a fixture artefact. |
| **广场 post edits** (j5 N1) | **FAIL, then fixed** (below). | `k2s-02-post-actions`, `k2s2-04-after-save` (before), `k2s3-04-after-save` (after) | see below |

### Found while walking: every edit of a published 广场 post said 「修改结果未确认」 (fixed, `2a01151af`)

- **Seen:** 9004 edits post 1 → 保存 → 同意发布. `PATCH /api/v1/community/posts/1` 200, the text is saved and stays `PUBLISHED` with machine audit, but the edit sheet never closes (`composeShow:true` 8 s later) and the detail keeps the old text. The database version jumped 3 → 5 for one edit.
- **Cause:** k2's `CommunityPostServiceImpl.edit` wrote the content as `CHECKING` (version +1), ran the machine check, then `CHECKING → PUBLISHED` (version +1 again). The composer (`utils/community-submit-progress.js`) checks `version === expected + 1` and throws 「修改结果未确认，请刷新后重试」. A retry also hits a stale version.
- **Fix:** relations first, machine check before the content write; block → error, whole edit rolls back; pass → one write straight to `PUBLISHED`; hold → one write to `CHECKING`. Same transaction.
- **After (rig, jar from my commit):** the sheet closes, the detail shows the new text, version 5 → 6, `lifecycle PUBLISHED`, `audit_level 1`, 0 community audit tasks (`k2s3-04-after-save`).
- **Tests:** `CommunityPostServiceImplTest` k2's three cases now assert one write (pass → `PUBLISHED` and no `transitionCas`; hold → `CHECKING`; block → no content write). 79/79.

## Per item

| Item | Status | Files (pushed) | Proof | Unverified |
|---|---|---|---|---|
| 4. Map j4 N1, N2, N3, N4, N5, N6, N8, N9, N10, F14, F15, F16 | **done by k7-map**; my duplicate dropped | — | k7-map's result and shots | — |
| 4. route-matrix after N6 | fixed | `scripts/uiaudit/build-route-matrix.js`, `route-matrix.json`, `action-ledger.json` (roles only, count 5728 unchanged) | `route-matrix-freshness` 5/5, `action-ledger --check` PASS | — |
| 5. j3 F13 | **done by k8-play** | — | — | — |
| 5. j3 F18 look sheet | fixed (fade); two sub-points not code | `pages/publish/fabu/look-sheet.wxss` | `k8-look-sheet-fade` 1 test, red on old code | Not walked in the rig (editor sheet scroll). 「第一章 江边1」 is the fixture author's own chapter name. 「还有 N 项未填」 on an on-sale theme is a p3 / k3 lead. |
| 5. i1 leftovers, j3 F16 / F17 | pages/play → k7 / k8-play | — | — | — |
| 6. p1 LOW list (N-6 … N-16) | **done by k9-pages** (N-12 not done there either; home chip needs owner); my duplicate dropped | — | k9's result | — |
| p1 N-4 play/merchant by link | **done by k2** (`94f753257`) | — | — | — |
| p1 N-4 mycanyuinfo non-owner, 排行榜, 队伍详情 guest | **done by k9-pages** | — | Walked on my local build of the same change: `p1-n4-mycanyuinfo-not-owner` 「链接已失效 · 返回」, `p1-n5-leaderboard-guest` one line, `p1-n8-character-no-attributes` | k9's own code was not re-walked by me |
| k2 walks | done, table above | — | shots and read-backs above | real phone, real GPS |
| 广场 edit version bug | fixed | `CommunityPostServiceImpl.java`, its test | rig before/after, 79/79 | — |

## Gates on the pushed tree

- `node chengyinhub-xcx/tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`.
- `build-action-ledger.js --check`: PASS, 5728 controls.
- Full `node --test "tests/unit/**/*.test.js"` on my tree just before the last rebase: 16258 pass, 4 fail; all 4 also fail on the integ head without my commits (`dailysign-save-poster-contract`, `merchant-theme-route-contract` on `pages/topic/pricing`, `playkit-fidelity-contract`, `route-matrix-freshness`; the last is fixed by `b6073e884`).
- Java: `CommunityPostServiceImplTest` 79/79 on the final base.

## CI

My code is in `915df19e3` (pushed); this doc's commit sits on top of it. At the time of writing, CI on PR #723 had not yet started for `915df19e3`; the last finished runs I read were on `6de347d40` (Mini followup regression: success; Redis recovery: success; GitHub CI pending; Funds MySQL IT in progress). I have not read a CI result on a commit containing my work.

## Not covered

- A phone: GPS, gestures, the real map, fonts, QR scan.
- My own map walks (N1 chip, N2 edge pins, F14 at largest font) were done on my implementation, which I then dropped for k7-map's; those shots are not included.
- p1 N-12, the home role chip (owner's P-01), 月度回顾 「路线」 counter, N-14's 去买票 walk: see k9-pages.

STATUS: PARTIAL - most of my items were landed first by k7-map, k9-pages and k8-play and I dropped my duplicates; F18 not walked; CI not yet read on a commit containing my work
