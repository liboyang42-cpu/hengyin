# Round 12, lane k2-player: result

Branch `integ/launch-test-20261103`. I worked on a private local branch, ran `git pull --rebase` and `git show --stat` before each push, and did not force-push, merge, touch master or touch production. No keys in the diff.

**Not a phone, and no rig walk this run.** The container had no running Docker daemon, so I could not bring up the rig (real jar + MySQL + Redis + headless mini-program). There are no new screenshots under `integ/fix-shots/` from this lane. Every item below is proven by unit / contract tests and static gates only. Each item says what still needs a walk.

## Commits

| Commit | Items |
|---|---|
| `b1616412e` | 1. Solved station on reopen |
| `1bcf8f78d` | 2. Guests on club pages, read-only SQL |
| `a86f47169` | 3. j6 N2, N3, N5, N6; j5 N1, N2; p1 N-3; p1 N-4 (square post) |
| `07294f66f` | 3. p1 N-5 and the guest deep-link list; 5. j1 N1; this file |
| `f95dac2fd` | Ledger test counts after rebasing onto k3 (5716 / 2767; my two cta changes cancel out) |
| next commit | p1 N-4 play/merchant; the read-only SQL moved to `.md` (inventory gate); ledger 5717 / 2768 |

## 1. Solved station counts as completed on reopen (j3 F6). Status: fixed, unit-proved, not walked.

- **Owner rule:** a solved station is completed on reopen; the player doesn't walk back; this replaces the arrival proof for these stations.
- **Server** (`PlayBasicCommandApplication.arriveOnce`): new read-only `IAdvancedGameRuntimeService.solvedForArrival`. It answers true when the player's own game session for that station is solved. It uses the same rule as the session view's `readyForBase` (`AdvancedGameRuntimeServiceImpl`). I moved that rule into one private method so the two can't drift. When it is true, `/api/play/arrive` skips the coordinate fence and the location guard. Everything else is unchanged: sign-up, linear unlock, `completeGate`, rewards (`RewardPolicy.FULL`, so rewards as for a normal completion; the existing C1 cap for self-reported evidence still applies). Unsolved stations keep the fence.
- **Routes:** the check sits before the shared branch, so it covers classic linear, free orienteering, the generic personal route (`themeRuntime`) and the shared team route. 自由收集 (mode 3) never used GPS and still needs the shop scan.
- **Client** (`pages/play/index.js`): when the game is solved (`_advancedReadyNodeId`), 「开始任务」 / 继续故事 completes the station directly. It no longer starts navigation (mode 2) and no longer asks for location first.
- **Tests:**
  - `ApiPlayGpsGeofenceValidationTest`: `solvedAdvancedGameCompletesStationWithoutArrivalFence` (no location at all → 200, points and progression awarded) and `unsolvedAdvancedGameStillNeedsArrivalFence` (far away → 「还没到这一站附近」, nothing written). 23/23.
  - `tests/unit/play-solved-station-reopen.test.js`: 3 tests; 2 fail on the old code.
- **Unverified:** rig walk of 990015 station 2 (answer, leave, walk away, reopen). Real-phone GPS.

## 2. Guests on club pages. Status: fixed, unit- and security-chain-proved, not walked.

- **Opened** (method-level `@Anonymous`, POST only, same as the page uses): `/api/club/topics`, `/api/club/post/list`, `/api/club/members`, `/api/club/leaderboard`. Siblings and writes stay 401: `post/create`, `post/like`, `post/comment/list`, `post/update`, `post/delete`, `member/remove`, `topic-registrations`, `join`, `quit`, `home`, and `…/extra` on each opened path.
- **Which clubs:** only live (`status=1`) and public (`public_visible` not 0) clubs. Members and leaderboard follow the same rule as a logged-in non-member: the club must be open to join (`join_policy=0`). A guest therefore never sees more than a logged-in non-member. A review-to-join club shows guests the same 「通过审核后可看」 line as today.
- **Guest projections** (`ApiClubController`):
  - members: nickname, avatar, role, isOwner, level, join time. No member id, join message, review fields or phone.
  - leaderboard: score columns and nickname; `memberId` removed.
  - posts: author and reposter member ids removed; `isLiked`, `viewerReposted`, `isFollowTheUser`, `viewerCanManage` removed. Comments stay closed.
  - topics: author member id and per-viewer flags removed. Only on-sale topics are listed (the existing `publicTopicListWhere`: `status=1 AND user_status=1`, not templates).
  - Posts have no unpublished state in `club_post` other than `del_flag`; deleted posts were already excluded.
- **Page** (`pages/club/detail`): the 「登录后查看 · 登录」 row is gone; guests load members and leaderboard. A guest tapping a person gets the login prompt (no member id to open). The owner's 11-04 test `r10-f3-guest-login-gate` was rewritten to the 11-05 rule.
- **Tests:** `GuestBrowseAnonymousReadTest` (annotation scope now 8 handlers; projections; closed / private / review-join clubs refused; no member list or board read for them), `PublicReadEndpointsSecurityChainTest` (4 new anonymous 200s, 12 new sibling 401s), `ApiClubTopicVisibilityTest` fixtures marked live. Java: 555 run, 0 failures in the club/security set.
- **Read-only SQL for the owner:** `integ/k2-club-post-contact-scan.md` (a Markdown file holding the SQL: a `.sql` file outside `chengyinhub-admin/sql` fails the repo's SQL inventory gate, which is why the Redis-recovery workflow went red on `f95dac2fd`). One `SELECT`, counts live posts with phone numbers or WeChat ids, and how many of those guests would see. It was not run against any database here.
- **Club owner's name / nickname everywhere** is k3's item; I did not touch `applyOrganiserNames`.
- **Unverified:** rig walk as a guest on club 7002; the leaderboard's zero-fill for members with no score does not happen for guests (it joins on member id, which guests don't get), so a guest sees only members with a score.

## 3. Player pages and guest access

| ID | Status | What changed | Proof |
|---|---|---|---|
| j6 N2 stale login state | fixed | `pages/club/detail`: `myMemberId` refreshed from the app login on every detail read and on `onShow`. After the item-2 change the block is no longer login-gated at all. | club-detail tests 469/469 |
| j6 N3 我的参与 guest | fixed | `subpackageMember/mycanyu` gets the same `cy-access-gate kind="login"` as its family | `r10-f3-guest-login-gate` |
| j6 N5 trail | fixed | `TeamTrailServiceImpl.eligibleTtl` refuses before the session's start time (state, consent and sync). Other teams' progress now comes from `player_node_progress` (completed stations, server side, via `selectTrailParticipants`); the client's `d` is no longer shown to anyone. | `TeamTrailServiceImplTest` +2 |
| j6 N6 im read/delete | fixed | `ImServiceImpl.markRead` (both forms) and `deleteConversation` answer 403 「无权查看该会话」 for a non-member, same as `messages`; nothing written. `ApiImController` passes the code and message through. The old test that pinned the silent no-op was updated. | `ImLegacyReadLockTest` |
| j5 N1 editing a 广场 post | fixed | `CommunityPostServiceImpl.edit`: editing a **published** post runs the same `safetyGate` as a new post. Block → 400 「内容未通过安全检查，请修改后重试」, the whole edit rolls back and the old post stays live. Pass → the new content is live at once (`PUBLISHED`, search `VISIBLE`, audit level machine). Hold (e.g. WeChat busy) → the gate's own hold, no extra human task. Edits of posts already `LIMITED`, `HIDDEN` or `CHECKING` still go to a person: those states were set by moderation, and the code keeps a human on them on purpose. Drafts are unchanged. | `CommunityPostServiceImplTest` +3 |
| j5 N2 club feed | fixed | `cy/profile` sends `/api/club/post/feed` as JSON. **Sweep:** a scanner listed every client call to a backend `@RequestBody` POST/PUT/DELETE/PATCH endpoint (506 endpoints) with no JSON hint nearby: 93 call sites in 28 files. Each was traced to its final request. All others already go out as JSON through a wrapper, or are GETs with a matching `@GetMapping`. This was the only one. | `k2-club-feed-json.test.js` |
| p1 N-3 official event | fixed | `official-detail` renders 集合与签到 only for V2 events (V1 events got a permanent 「加载失败 · 重试」 from 「仅支持官方V2赛事」). No rewards → the 活动奖励 section is not drawn (no 「未配置奖励」). V1 「我的进度」 drops its 「已完成」 sub-label. The stats row is a horizontal scroll by design; not changed. | `official-detail-f22-contract` |
| p1 N-4 square post gone | fixed | `square/detail`: 内容不存在 / 已删除 / 审核中 / 数据不存在 / bad id → 「链接已失效 · 返回」 instead of a blank auto-back error. | `k2-square-detail-gone.test.js` |
| p1 N-4 play/merchant by link | fixed | `pages/play/merchant`: the stop is only known from the play page; opened by a link it now shows 「链接已失效 · 返回」 (back, or home when there is no page behind) instead of an untitled error. | `k2-square-detail-gone.test.js`, `play-merchant-a11y-state-contract` updated |
| p1 N-4 mycanyuinfo not owner | not done | — | — |
| p1 N-5 我的队伍 guest | fixed | `subpackageRoam/my-teams` gets the login gate. | `r10-f3-guest-login-gate` |
| p1 N-5 deep links | fixed for 7 pages | Login gate on 积分明细, 时光胶囊, 月度回顾, 导出, 探索目标, 那年今日, 自由探索权益. Not done: 排行榜 double line, 队伍详情 guest wording. | `r10-f3-guest-login-gate` |

## 5. j1 N1: a no-game ticket shows its QR. Status: fixed, unit-proved, not walked.

- A session ticket whose activity has neither a source theme (`topicId`) nor a game template (`templateId`) has no game (owner made the game optional in Round 10). Rule in `utils/ticket-game.js`; when the activity can't be read, the ticket is treated as having a game, so nothing else changes.
- 订单详情 (`scene-member-order-detail`): no 「开始游戏」 for such tickets.
- 票夹 (`subpackageMember/signup`): no 「开始游戏」 bar; when the deck lands on such a ticket it turns to the QR side by itself and issues the entry code, using the existing flip and `_loadQr`. Same rule inlined there (the page is loaded by sandbox tests that allow only its current requires).
- Tests: `k2-no-game-ticket.test.js`; `signup-return-refresh` fixture's session ticket given a theme (it meant a playable ticket).
- Unverified: rig walk on a merchant activity published without a game; real-phone flip.

## 4, 5 (rest), 6. Not done this run

Map (j4 N1–N10, F14–F16), i1's leftovers (dead 提交 on a FAILED generic game, mic denial reason, re-walk of the hidden main button), j3 F13/F16/F17/F18, and p1's LOW list were not started. They need the rig for before/after shots anyway.

## Gates

- `node chengyinhub-xcx/tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`. Three baseline anchors moved by line shifts only (play/index.js U5 8444→8446, square/detail U4 1281→1285, signup U5 415→425); digests recomputed, no new entries.
- `build-action-ledger.js --check`: PASS after rebuild. After rebasing onto k3 (which removed 7 「清除」 keys): control count 5716, event contracts 2767. My changes net to zero (−1 club 「登录后查看」 cta, +1 square 「返回」 cta). `action-ledger.test.js` 46/46 on the merged tree. signup U5 anchor 415→425, line shift only.
- Java and node tests named above, run locally in this container.
- CI: see the CI section below.

## CI

Pending.

STATUS: PARTIAL - items 1–3 mostly fixed with tests and j1 N1 fixed; no rig walk or screenshots (no Docker daemon); item 4, most of 5, item 6 and parts of 3 (p1 N-4 mycanyuinfo, leaderboard and team-detail guest wording) not done.
