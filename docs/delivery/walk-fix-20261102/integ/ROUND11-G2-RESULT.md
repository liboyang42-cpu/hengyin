# Round 11 lane g2-team-map: result

The findings come from `docs/delivery/check-20261103/j4-roam-teams/REPORT.md` (tested at `ecef2f078`). I confirmed each one in the code at the current head before fixing it.

- **Commits:** `eade95a5` (backend), `76d23f56` (team pages and shared receipt layer), `78523f9f` (漫游 entry and captions), and a follow-up commit with this file (the backend join message, the walk shots and the proof).
- **Branch:** `integ/launch-test-20261103`. I rebased before every push and never force-pushed.

**Rig, not phone.** The screenshots come from the walkthrough rig: the real jar at `78523f9f`, MySQL 8 and Redis 7, and the real mini-program in headless Chrome at 390x844. I used j4's rig patch (`getRandomValues`, `locdeny`) and pulled Docker images through `mirror.gcr.io`.

**Fixture writes (test DB only):**
- `cms_topic` 990014 `team_mode` 0→1, as j4 did.
- Players 9004, 9009 and walk-player-1 (990005) joined club 7002 and signed up for session 9 through the real API. They paid through the rig pay seam, which calls the real callback.

The rig's MySQL runs in UTC, so 「8 小时前申请」 is off by 8 hours. That is a rig artefact.

Screenshots are in `integ/fix-shots/g2-*.jpg`. I looked at every one.

## Item 1: j4 F1, team results at the shared level. Status: fixed

**What changed**
- **Shared receipt layer.** `utils/team-event-command.js` has a new `settle` option:
  - APPLIED and CANCELLED receipts are archived silently.
  - A REJECTED receipt is archived and its backend reason goes to the page as `outcome`.
  - The recovery card (`card`) shows only for UNKNOWN or a storage error.
- **Callers.** The join page, team detail and `subpackageRoam/utils/roam-team-recovery.js` (map team sheet, 我的队伍) all use `settle`. The SIGNUP and EVENT callers keep the old behaviour, since the round names teams only.
- **No raw ids.** The card and panel no longer show the request number, team number or member number. The handle row says 「同意入队申请 / 拒绝入队申请」. 「确认结果 / 确认并归档结果」 is gone.
- **Where a rejection shows:**
  - Join page: red text above the button, and the button is disabled.
  - Team detail: next to the switch, the member list, the application rows, or the quit/disband buttons, depending on the action.
  - 我的队伍: under the card that sent the request.
  - Map sheet: the existing failure sheet, which now reads the receipt's `message`.
- **Backend reason text.**
  - `TeamEventCommandService`: for TEAM commands, the team service's readable Chinese reason now goes into the receipt instead of 「原请求被拒绝，请重新读取业务状态」.
  - `PlayTeamServiceImpl.join`: a removed member using the old link now gets 「暂时无法加入该队伍」, not 「你已在队伍中或不能重复加入」.

**Proof**
- **Mini tests:** `roam-team-command-recovery`, `w8-07-enrollment-recovery`, `team-detail-join-mode-contract`, `team-p1-contract` and `team-recovery-card-gate`.
- **Java tests:** `TeamEventCommandRejectionTextTest` and `PlayTeamServiceImplTest.join_removedMemberWithOldLinkGetsNeutralRefusalNotAlreadyInTeam`.
- **Rig:**
  - `g2-12` / `g2-13`: accepting the invite leaves no card, and the page shows 2/3 and 查看队伍.
  - `g2-17`: when a removed member reopens the link, the reason shows at the button and the button is disabled. That shot shows the old wording 「你已在队伍中或不能重复加入」. The wording fix landed after the rig jar was built, so only the unit test proves the new text.

**Tests that encoded old decisions (updated and commented in place)**
- The terminal receipt card stayed until the user confirmed it.
- Leaving a team re-read the team.
- 「重试加入」 after a failed join.
- Fixtures assumed the applications read did not take a request slot.

## Item 2: F2, F3, F4, F6, F5, F13, F12. Status: fixed

**F2: wrong code or someone else's team showed a blank page.**
- `/api/team/info` refusals (IllegalArgumentException) now carry `errorCode: TEAM_INFO_REFUSED`.
- Both pages show the reason with a way back (`missing-param`). Other load failures stay blank, as the existing rule says.
- Proof: the `team-p1-contract` j4 F2 test, `ApiPlayTeamInfoRefusalTest`, rig shot `g2-11` (「邀请已失效」, then automatic back), and a read-back in `proof/g2-team-info-projection.txt`.

**F3: after leaving, the page said 「已满员 2/2」 above an empty list.**
- After a successful quit or disband, the page shows a toast and goes back (or to 我的队伍 if there is no previous page). It no longer re-reads a team the player can't see.
- Proof: `w8-07-team-membership-refresh`, `team-p1-contract`, rig shots `g2-15` / `g2-16` (back on the previous page).

**F4: a leader could only find applications through the map.**
- `utils/team-applicant-rows.js` is now the shared row builder for the map sheet and team detail. It moved out of `map-team.js`, which re-imports it.
- Team detail has an 入队申请 section for a recruiting leader: 拒绝 goes through the `team.reject` confirm, and 同意 uses the same `handle` command.
- 我的队伍 shows 「N 条入队申请」 on the leader's card, or 「申请数没读到」 if the read fails.
- Proof: tests in `team-p1-contract`, `roam-team-command-recovery` and `team-detail-orient-board`. Rig shots `g2-18` (card shows 1 条入队申请), `g2-19` (application row with message) and `g2-20` (after 同意: 2/3, 暂无申请).

**F6: every session card counted the whole theme's teams.**
- `utils/roam-entry-map.js`: a session row now counts only teams with its own `activityId`. Topic rows still total all sessions.
- Proof: `roam-entry-map` test, which fails on the old code. Rig shot `g2-22`: the second session card has no team count.

**F5: without location, cards said 「距你 0m」 from People's Square.**
- Without a stored real location, the entry no longer reads 附近 using the default coordinate. It reads public topics from any city, with no distance and no team pins.
- 「附近活动」 gets one 「开启定位」 row. Once location succeeds, the entry reloads with the real position.
- Proof: test in `roam-recovery-flow`. Rig shots `g2-21` (location refused: no 距你, no pin, 开启定位) and `g2-22` (after 开启定位: real distances).

**F13: after logging in from the 漫游 prompt, the entry still showed guest text.**
- A successful login from 「登录后继续」 now reloads the entry lists.
- Proof: `guest-browse-login-on-write` (source-level check). **Not walked** in the rig.

**F12: the guest place page showed 「暂无评分」 and a load failure together.**
- When reviews fail to load, the page now shows only the retry, without 「暂无评分，等你来体验」.
- Proof: `round11-g2-team-roam-copy`. **Not walked:** j4 thinks this state may only occur with fixture data.

## Item 3: F10, invite-code reads by non-members. Status: fixed

- `PlayTeamServiceImpl.info`: guests and players from other sessions now get only `id`, title, status, joinMode, counts, and members' names, avatars and roles.
- Members still get the full team.
- The team `id` stays because the join command freezes its target on it.
- Proof: `PlayTeamServiceImplTest` covers guest, other session and member. The rig read-back is in `proof/g2-team-info-projection.txt`.

## Item 4: F9 captions, quit dialog, 加入方式. Status: fixed, except the items listed for the owner

**Quit dialog**
- There are now two confirm keys:
  - `team.quit` (members): reversible. No 「你是队长的话…」 and no false 「此操作不可撤销」.
  - `team.quit-leader` (leaders): the handover/disband line plus 此操作不可撤销.
- Punctuation is full-width.
- The danger registry now registers the leader key. `danger-confirm-gate` passes.
- Rig shot `g2-15` shows the member dialog.

**Captions and labels**
- 加入方式 was already bound to `joinMode` by f3 (`8873acff`). I rechecked it in rig shots `g2-12` / `g2-13`.
- Team detail: a recruiting leader no longer sees the duplicate 「公开招募」 subtitle. Everyone else still sees it.
- 漫游 entry: removed 「走到哪，雾散到哪」, 「已加入 · 申请中」 and 「城瘾 · CITY ROAM」. The live line still shows when there is real data. See `g2-21`.
- City list: removed 「切换城市 · N 处」 and the per-row reason line. The button now says 「复制地址」.
- Place page: removed 「完成据点挑战后可以评分和评论。」.

**Owner decisions**
- **Order detail team card, 「组队与否不影响活动举行，也不改变订单与退款规则。」** I kept this line because it states order and refund consequences, and `team-p1-contract` pins it as a required disclosure. **Recommended default:** keep it. If you want it gone, the change is one line plus that test.
- **Roam map 「继续走 · 点亮这片街区」** (`utils/play-state-contract.js`, the map's target state line). I left it: it is the state text the map shows for that target state, not a caption, and other states depend on the same contract. **Recommended default:** leave it.
- Already done by other lanes: the join page captions and team detail's 「陌生人可在附近…」 and 「退队不退款…」.

## Item 5: walking what j4 section 3 left undone. Status: partly done

- **Walked in the rig, all passing:**
  - Accept, quit, removal, a removed member reopening the link.
  - Public application → leader sees it on 我的队伍 and team detail → approve.
- **Covered by HTTP or unit tests only:**
  - Reject and withdraw (unit tests and the `handle` / `withdraw` command paths).
  - Disband (the leave-the-page path is the same as quit).
- **Not walked:**
  - Following a team from the rail.
  - The author's 同场路线 off switch.
  - Swipe cards and fly-to.
  - Drag clouds and off-screen pins.
  - Rain, snow and fog with a stubbed weather reply.

  That means nothing was found to fix in those areas because they weren't driven.

## Gates

- **Mini unit suite:** 16082 pass on the rebased tree. The only failure, `std-g2-wizard-shell-contract` negative control, also failed on the base; f4 fixed it in `1b78a536`, and it passes after rebase.
- **`ui-integrity.js`:** `UI-GATE-0:PASS`, with no baseline entries added.
  - The new topic read lets failures show (the error area plus the client's toast).
  - The 我的队伍 count shows 「申请数没读到」 when its read fails.
- **`build-action-ledger.js --check`:** PASS, 5703 controls on the merged tree. The action-ledger test counts are updated with a note: tap −1, event contracts +1, controls +1.
- **`danger-confirm-gate.js`:** PASS.
- **Backend tests:** `PlayTeamServiceImplTest` (26), `TeamEventCommandRejectionTextTest` and `ApiPlayTeamInfoRefusalTest` all green.
- **Main package:** 2.97 MB by preflight's count, under the 3.00 MB ratchet. `utils/team-applicant-rows.js` added about 1.5 KB to the main package.
- **CI:**
  - On `78523f9f`: Mini followup regression and Isolated Redis recovery are green.
  - Funds MySQL IT failed. It also fails on the three earlier commits, before my work.
  - The main CI run kept being cancelled by other lanes' pushes. **No green full CI on a commit containing all my work yet.**
- **Migrations:** none.

## Only a phone proves

- The real location permission prompt and 开启定位 → settings.
- Toasts on the real WeChat runtime.
- Font scaling.
- Map gestures.

STATUS: PARTIAL - item 5 map walks (rail follow, 同场路线 off switch, swipe/fly-to, clouds, off-screen pins, weather) not done; F12/F13 not walked in the rig; full CI not green on a commit with all g2 work because other lanes' pushes keep cancelling it, and Funds MySQL IT was already failing before my commits.
