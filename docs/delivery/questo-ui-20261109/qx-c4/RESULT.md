# qx-c4: hint ladder on self-play runs (UG-05/06/07)

- **Branch:** `claude/qx-c4-20261110`, based on `origin/integrate/questo-ui-20261110` (`2051ed43`).
- **Draft PR:** #755 into `integrate/questo-ui-20261110`.
- **Owner decision (2026-11-10):** "yes, make hints work during self-play too".
- **qx-c3:** `claude/qx-c3-20261110` exists, but its only commit (`36766b31`) updates the real-storage registry. It did not touch the help path, so this lane implements the item from scratch.

## Root cause (from source)

- Every first run gets `topic_route_session.play_instance_id = 'default'` when it is created (`ensureBranchSession` / `ensureJourneySession`). This is true for both self-play and activity sessions. Only replays get `replay:<id>`.
- `PlayHelpApplication` refuses `default`.
- The progress instance cannot be changed. First-run rewards, the preference idempotency key and older progress rows all recognise "this player's real run" by `default`. See `PlayNodeCompletionApplication` (`firstTime && default`), `PreferencePlayService` and `AdvancedGameRuntimeServiceImpl`.

## Design

- **Identity at run start.** In the same insert that creates a new self-play run (`activity_id = 0`), the server writes `helpRunInstance = "selfplay:<uuid>"` into that run's frozen `config_snapshot_json`.
- **Frozen with the run.** The identity is frozen together with the run's ladder and run-wide N. Re-entry reads the same row, so it is idempotent. The client cannot write or choose it.
- **What it keys.** Help quota, level facts, point records, audit facts and the score detail are all keyed by this identity. The progress and reward instance stays `default`.
- **Ownership.** For a `selfplay:` scope, the quota layer checks the player's own `default` self-play run. The identity frozen in that run's snapshot must match the scope.
- **What is still refused.** `default` without a valid identity, old self-play runs, and activity-session `default` runs are all still refused with 「原局身份不可用，请重新进入本局」. A forged identity, another player's run or an activity run is refused with the same message as a missing run.
- **No schema change.** No migration, no new table and no new column. The identity lives in the existing frozen snapshot, so no MySQL IT is needed. The real MySQL write and read path was exercised in the rig instead (see the read-backs below).

## Items

| ID | Status | What changed | Files | Verified | Unverified on a real phone |
|---|---|---|---|---|---|
| BE self-play help identity | fixed | New runs get a frozen `selfplay:` identity. `resolve` admits only runs that carry one. Quota ownership checks the player's own self-play run and the matching frozen identity. Progress and completion mode are still read under `default`. The completion response's `helpReceipt` uses the same identity. | `SelfPlayHelpRun.java` (new), `TopicRouteRuntimeServiceImpl.java`, `PlayHelpApplication.java`, `PlayHelpRunQuotaApplication.java`, `PlayNodeCompletionApplication.java` | **Unit:** `SelfPlayHelpRunTest` 4, `PlayHelpSelfPlayRunTest` 9, `PlayHelpRunQuotaApplicationTest` +4 (37), `TopicRouteRuntimeServiceImplTest` +3 (102).<br>**Regression:** 1565 tests, 0 failures.<br>**Rig:** see below. | — |
| UG-05 hint ladder on self-play | fixed (server); client unchanged | The server now answers `enabled:true` for a ticketed self-play run, so the b1 sheet appears. Before this change it was 400. The client needed no change. | — | Rig shots `UG-05-ladder-open-after.jpg` and `UG-05-hint-unlocked-after.jpg`. `POST /api/play/help/use` returned 「看站厅里的线路图」. | Real device rendering |
| UG-06 counter | fixed (server); client unchanged | 「本局剩余免费 1 次」 → after the first priced layer (首字, 30 points, covered by the run quota) → 「本局剩余免费 0 次」. 答案 then shows 「30 积分」. | — | `UG-06-counter-after.jpg`. DB: `play_help_run_quota(9004, 990016, 0, selfplay:84cb…, total 1, used 1)` | — |
| UG-07 score detail | fixed (server); client unchanged | `GET /api/play/help/receipt` on the self-play run returns `SCORED`, base 100, `helpDeduction` 0, net 100, and per-layer rows. | — | `UG-07-score-detail-after.jpg` | — |
| iOS | not done (not needed) | The API shape is unchanged. iOS runs the same server rule, so there is no iOS PR. | — | — | iOS on a real device |

## Rig evidence

- **Candidate.** The ladder UI (b1) exists only on `staging/questo-review-20261110`. The rig therefore ran a **local, unpushed** merge of staging `6c7a2961` and this branch: `935037fa`.
  - One conflict: staging refactored `resolve` into `frozenResolved`. The resolution moves the same `SelfPlayHelpRun.helpInstance` check into `frozenResolved` (see Cross-lane).
  - The merged tree passes 299 related tests.
- **Rig setup.** `claude/cloud-walk-20261101-rig` with `rig-up-sha-param.diff`, `rig-app-oss-seam.diff` (placeholder `CHENGYIN_OSS_*` values, not credentials), qx-c1's `rig-app-media-seam.diff`, and the club branch's `harness-patches.diff`.
- **Fixture.** `staging/rig/free_orient_theme.py`. Topic 990015 used it unchanged. Topic 990016 used a rig-only copy whose question answer is 「Line 3」 instead of 「3」 (`rig/free_orient_theme-c4.diff`). With 「3」 the LETTER layer has no English first letter, so the server refuses it as 「该层内容未配置，未扣除任何代价」 and the counter never moves.
- **Player.** Fixture player 9004, on real `/api/play/*` endpoints, with GPS set to the authored station coordinates.
  - Station 1 check-in returned `firstTime:true`. First-run rewards still work because the progress instance is still `default`.
- **Read-backs** (`rig/readback.txt`):
  - Three self-play runs carry three distinct identities, and all keep `play_instance_id='default'`.
  - Audit facts in `play_attempt_log.result_json.playInstance` hold the run's `selfplay:` identity.
  - `/api/play/help` with another player's token returns 「原局不存在或无权访问」.
  - A simulated old in-flight run (rig DB only: `json_remove` of the key on run 1) returns 400 「原局身份不可用，请重新进入本局」.
- **Logs and steps.** `rig/walk-c4.txt`, `rig/walk-c4-receipt.txt`, `rig/fixture-run-c4.txt` and `rig/steps-*.json`. OSS signatures are redacted.

## Cross-lane

1. **Merging into staging, or any branch that has `frozenResolved`.**
   - `PlayHelpApplication.java` will conflict.
   - Resolution: keep `frozenResolved`, and replace its `default` check with `SelfPlayHelpRun.helpInstance(session, parseSnapshot(session.getConfigSnapshotJson()))`, refusing when the result is null. That is exactly what `935037fa` does.
   - `PlayHelpAuditController` then reads the same identity.
2. **b1 (UG-07 row wording).**
   - Layer 1 is author-priced at 0, and the score detail shows it as 「0 积分」. Layer 2 was covered by the run quota and shows as 「免费」.
   - This is the server facts rendered as given (`free:false, pointsCharged:0`). Whether a zero-priced layer should also read 「免费」 is b1's call.

## Needs owner

- **Activity-session first runs also carry `default`.**
  - The ladder is refused there as well, so the staging RESULT's note "the ladder needs a ticketed activity-session run" does not match the source.
  - Default implemented: unchanged. The owner decision covers self-play only. The same `stamp` call would extend it by dropping the `selfPlay(activityId)` condition.
- **`helpDeduction` is 0 for TEXT/LETTER layers.**
  - This is the existing `PlayHelpReceiptReadModel` and `PuzzleScorePolicy` rule, the same for every kind of run.
  - Default implemented: unchanged.
- **`ci/architecture/owners.json`.** Not changed. `SelfPlayHelpRun` is a static helper with no boundary role, so the gate does not require it to be registered. No owner review is requested.

STATUS: COMPLETE
