# qx-c5: hint ladder on activity-session first runs (UG-05/06)

- **Branch:** `claude/qx-c5-20261110`, stacked on `origin/claude/qx-c4-20261110` (`955069da`).
- **Draft PR:** #756 into `claude/qx-c4-20261110`. The owner can take c4 (#755) alone or both.
- **Why:** the owner decided on 2026-11-10 to "make hints work during self-play too". That decision assumes hints already worked in club and activity sessions. qx-c4 found they did not: activity-session first runs also carry `play_instance_id='default'`, so `/api/play/help` refused them. Only replays had the ladder.

## Design

- **One identity for every first run.**
  - Every new `default` run, self-play (`activity_id=0`) or activity session (`activity_id>0`), is given a frozen `helpRunInstance = "firstrun:<uuid>"` in its `config_snapshot_json`. It is written in the same insert that creates the run.
  - The progress and reward instance stays `default`. First-run rewards still return `firstTime:true`, as the rig shows.
- **Rename.**
  - `selfplay:` would be misleading on a club session, so `SelfPlayHelpRun` is now `FirstRunHelpIdentity` and new identities use `firstrun:`.
  - The `selfplay:` identities c4 already created on review rigs are still read, but only on self-play runs. A `selfplay:` identity on an activity run is refused.
  - Both prefixes are 9 characters, so every fact, quota and points key keeps its length.
- **Ownership** (`PlayHelpRunQuotaApplication.requireOwner`):
  - The quota layer looks up `selectByOwner(the scope's real activityId, topicId, memberId, 'default')`.
  - The row's activity and topic must equal the scope's, and the identity frozen in the row must equal the scope's identity.
  - Each of these is refused with the same message, 「原局不存在或无权访问」: another member's identity, an identity from a different activity, an activity identity reported as self-play, a forged identity, and an old run without an identity.
- **Team and shared runs.** Spec §25.1 and the cooperation section (§26.1) say:
  - Personal runs keep the per-player scope.
  - Cooperative team runs share one free-N pool for the whole team. That pool is keyed on the server-verified team game run (`/api/play/help/shared` → `shared-game:<id>`, owned by the team owner).
  - New cooperative runs must not fall back to a second personal path.

  **Implemented:**
  - A first run in a personal activity keeps quota per player's own run. Two members in the same session have two identities and two pools, and neither can spend the other's.
  - If the activity has a shared team route (`sharedRouteSnapshot != null`), the personal first-run pool is refused with the existing copy 「队伍共享局请使用本场共享命令」. The team pool stays the only free-N pool in that activity.
  - Points are still always charged to the member who uses the hint.
- **Unchanged.** `frozenResolved` (staging), route and lead access gates, `Scope` validation, quota arithmetic, payment (G5) and audit facts. Old activity runs without an identity are still refused with 「原局身份不可用，请重新进入本局」.
- **No schema change.** No migration, table, column or dependency.

## Items

| ID | Status | What changed | Files | Verified | Unverified on a real phone |
|---|---|---|---|---|---|
| BE activity first-run help identity | fixed | Activity first runs get a frozen `firstrun:` identity. Help, quota, facts, receipt and the completion `helpReceipt` are keyed by it. Progress stays under `default`. | `FirstRunHelpIdentity.java` (renamed from `SelfPlayHelpRun.java`), `TopicRouteRuntimeServiceImpl.java`, `PlayHelpApplication.java`, `PlayNodeCompletionApplication.java`, `PlayHelpRunQuotaApplication.java` | **Unit:**<br>• `FirstRunHelpIdentityTest` 4<br>• `PlayHelpActivityFirstRunTest` 6<br>• `PlayHelpRunQuotaApplicationTest` +8 (45 total): own run for that activity, re-entry keeps one pool, another member refused, wrong activity or self-play refused, old run refused, per-player pools in one activity, shared team route refused, self-play never consults the team route<br>• `TopicRouteRuntimeServiceImplTest`: activity run freezes one identity and re-entry keeps it (102 total)<br>• `PlayHelpSelfPlayRunTest` 9 (c4 `selfplay:` compatibility)<br>**Regression:** `mvn -pl chengyinhub-admin test` ran 13107 tests. The only code failure was `GameplayTemplateSlotRuntimeTest` (see below). It is now fixed, 29/29. The 35 errors are `*MyBatisTest` IT classes that need a dedicated MySQL IT database (`Communications link failure` / H2 alias). This change does not touch them.<br>**Rig:** see below. | — |
| Team scope rule | fixed (per spec §25.1) | Personal activity run: per player. Shared team route: the personal pool is refused, and the team pool (`/api/play/help/shared`) is the only one. | `PlayHelpRunQuotaApplication.java` | Unit tests `teamMembersInAPersonalActivityKeepSeparatePerPlayerPools` and `sharedTeamActivityRefusesThePersonalFirstRunPool` | Not run in the rig on a shared team route; this needs a cooperative TEAM theme |
| UG-05 hint ladder on an activity run | fixed (server); client unchanged | The play page in a club-opened session now gets `enabled:true` and shows the b1 ladder. Before this change the server answered 400 and the 「提示」 button was absent. | — | `UG-05-ladder-before.jpg`: the same activity run with the identity removed (rig DB only) shows no 「提示」 button, and the server answers 「原局身份不可用，请重新进入本局」. This is what every activity run looked like before.<br>`UG-05-ladder-open-after.jpg`: 「本局剩余免费 1 次」, 文字提示 shown as 免费.<br>`UG-05-hint-unlocked-after.jpg`: `POST /api/play/help/use` returned 「看站厅里的线路图」. | Real device rendering |
| UG-06 counter on an activity run | fixed (server); client unchanged | After 首字 (30 points, covered by the run quota) the counter reads 「本局剩余免费 0 次」, and 答案 then shows 「30 积分」. | — | `UG-06-counter-after.jpg`. DB: `play_help_run_quota(9004, 990015, activity 15, firstrun:2008…, total 1, used 1)` | — |
| `GameplayTemplateSlotRuntimeTest` | fixed | This test (a self-play run, red since c4) compared the whole run snapshot to the published freeze. It now asserts the identity is present, removes it, and compares the rest byte for byte. | `GameplayTemplateSlotRuntimeTest.java` | 29/29 | — |

## Rig evidence (activity session)

- **Candidate.** A local, unpushed merge of `staging/questo-review-20261110` (`6c7a2961`) and this branch (`b66fcf59`), at `d1420d4b`. The ladder UI (b1) exists only on staging. The jar was built by the rig's `up.sh` from that merge.
- **Merge resolution for staging's `frozenResolved`.**
  - There is one conflict, in `PlayHelpApplication.java`.
  - Keep staging's `frozenResolved(...)` split. Inside it, replace the `default` check with `FirstRunHelpIdentity.helpInstance(session, parseSnapshot(session.getConfigSnapshotJson()))` and refuse 「原局身份不可用，请重新进入本局」 when it returns null.
  - This is c4's resolution with the class renamed. `PlayHelpAuditController` reads the same identity through `frozenResolved`.
- **Rig setup.** `claude/cloud-walk-20261101-rig` with:
  - staging's `rig-up-sha-param.diff` and `rig-app-oss-seam.diff` (placeholder `CHENGYIN_OSS_*` values, not credentials)
  - qx-c1's `rig-app-media-seam.diff`
  - the club branch's `tools/harness-patches.diff`
- **Fixture.** `rig/activity_orient_theme_c5.py` is derived from staging's `free_orient_theme.py`, with c4's 「Line 3」 answer. **All steps use real endpoints; no SQL writes:**
  1. Topic and QA template with `hintLadder`, `hintFreeUsesPerRun=1`. Club 7001, publisher 9003. Topic 990015.
  2. Admin `POST /business/ticket`: a zero-price `guided` ticket template on the topic. This is the same endpoint `chain_merchant_club` uses. `PUT` then moves its `signupDeadline` later, because `open-session` copies it verbatim.
  3. **`POST /api/club/open-session`** by 9003 for club 7001, which gives activity 15.
     - No cooperation contract was needed: `ClubSessionServiceImpl.open` accepts `clubOwned` (the topic belongs to the opening club) in place of an effective club order.
  4. Player 9004: consents, participant, `POST /api/club/join`, quote, create (`ownerType=2`). Registration 74 has status 2.
  5. `POST /api/club/lead/arrive` (player) and `POST /api/club/lead/start` (leader 9003), then `GET /api/play/nodes?activityId=15` returns 200 (`rig/lead_start.py`).
- **Attempts recorded.**
  - `fixture-run-c5.txt` is the run that hit the live-topic cap 「同时上架的主题已达上限（2）」. The earlier run that created topic 990015 and activity 12 was overwritten.
  - `fixture-run-c5-reuse.txt` is the successful run that reuses topic 990015 (`REUSE_TOPIC`) and opens activity 15.
- **Walk.** Real `/api/play/*` endpoints, GPS at the authored coordinates.
  - Station 1 `POST /api/play/arrive` returned `firstTime:true`. First-run rewards still work on the activity run.
  - At station 2: GET help (`enabled:true`), then use L1, then use L2 (`rig/walk-*.txt`).
- **Read-backs** (`rig/readback.txt`, `readback_c5.py`; SELECT only, apart from the step marked RIG-DB-ONLY):
  - `topic_route_session(1, member 9004, activity 15, 'default', firstrun:20089659-…)`.
  - Re-entry through `/api/play/nodes` keeps the same single row and the same identity.
  - `play_help_run_quota` row is keyed by activity 15 and the run identity.
  - `play_attempt_log.result_json.playInstance` holds the run identity (audit).
  - `player_node_progress` is still `default`.
  - Player 9005 on runId 1: GET and POST both return 「原局不存在或无权访问」.
  - Receipt: `SCORED`, base 100, net 100, per-layer rows.
- **Old run** (**RIG-DB-ONLY:** `JSON_REMOVE` of `helpRunInstance` on run 1, to simulate an activity run created before this change): GET help returns 400 「原局身份不可用，请重新进入本局」 (`rig/legacy-refusal.txt`). The before shot was taken in this state.

## Cross-lane

1. **Merging into staging, or any branch with `frozenResolved`:** use the resolution above (c4's, with the class renamed).
2. **c4 (#755):** if the owner takes c4 alone, `GameplayTemplateSlotRuntimeTest` stays red there for self-play runs. The one-file test fix in this PR (`618f3914`) applies to c4 unchanged, apart from the class name.
3. **b1 (UG-07 wording):** as c4 noted, a layer the author priced at 0 shows 「0 积分」 in the score detail (`free:false, pointsCharged:0`).

## Needs owner

- **Team scope.** Implemented the spec as written (§25.1 / §26.1): per-player pools in personal activity runs, and the team pool only on shared team routes, where the personal pool is refused. Confirm that a personal activity run in a club session (several players, no shared route) should keep a separate free N per player. Default implemented: yes, per player. No player can spend another player's quota or points.
- **Old in-flight activity runs.** Runs created before deploy have no identity and keep the refusal until the player starts a new run. Default implemented: no backfill. A backfill would need a data migration on production, which is out of scope.
- **Shared team route not rigged.** The refusal of the personal pool is unit-tested only. A rig run needs a cooperative TEAM theme (`cooperationVersion=1`).

STATUS: COMPLETE
