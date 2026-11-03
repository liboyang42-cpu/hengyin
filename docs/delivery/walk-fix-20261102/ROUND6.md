# Round 6: merge every lane into one test branch

You are the integrator. The goal is one branch that holds all the launch work, with every lane's behaviour intact and CI green apart from the one known gate below. A later round walks the app on this branch, so a wrong conflict resolution here reaches the owner's final check.

Never push to master. Never merge a PR. Never change production. No word list, private code or secret in this public repo. Follow root `AGENTS.md` and `chengyinhub-xcx/AGENTS.md`. Don't run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`.

## Branch and order

Create `integ/launch-test-20261103` from master `c6eac3b8c` and merge with real merge commits, one at a time, in this order. Use these exact commits, not the branch tips:

1. `8712fa452` pay-gate (#718)
2. `14312f809` l1-play (#713)
3. `6fbde34a1` l2-explore (#714)
4. `6621c8672` l3-sell (#715)
5. `48bc8a920` l4-counter (#717)
6. `d0383fb43` l5-onboard (#712)
7. `ef03e7a3b` l6-club (#716)
8. `8ecb80e56` l7-launch (#720)
9. `bc86e52a6` l8-safety (#721)
10. `beec06ee8` club broadcast (#710)
11. `a7c3624cf` page polish (#719)

Push after each merge, so a usage limit loses nothing. Open one draft PR titled `[TEST ONLY, do not merge] launch integration 2026-11-03` so CI runs.

If #710 or #719 needs a decision that a lane's result doesn't settle, stop on that one, leave it out, and say so in the result. They were opened before the lanes and must not undo lane work.

## Conflict rules

- Expected real conflicts: `ApiClubLeadController.java` (keep both injected fields), `pages/play/index.wxml`, `pages/play/index.js`, `CmsRegistrationServiceImpl.java`, `pages/publish/activity/index.{js,wxml}`, `scene-member-order-detail/index.js`, `item6-nonrefundable-confirm.test.js`.
- For each one, read both sides and both lanes' `docs/delivery/walk-fix-20261102/<lane>/RESULT.md` entries. Keep both intents. Where git shows a moved block on one side and an edit on the other, carry the edit into the moved block. Never resolve code by taking one whole side.
- Generated files: `chengyinhub-xcx/scripts/uiaudit/action-ledger.json` is rebuilt with `build-action-ledger.js`, and the counts in `tests/unit/action-ledger.test.js` are set to the rebuilt values. `tests/lint/ui-integrity-baseline.js` keeps both sides' entries with digests recomputed. It gets no new exemption that wasn't already on a lane. `ci/migration-dependencies.json` keeps both sides' entries.
- Migrations keep their file names and contents. If two lanes' migrations touch the same table, check that they apply in dependency order on a clean MySQL.
- After all merges, list every migration new against master, in apply order, in the result.

## Known red gate

`Fast gates` will fail on the architecture gate until the owner registration from `docs/delivery/walk-fix-20261102/l8-safety/round5e/architecture-owner-registration.patch` lands on master. Do not add that patch to `ci/architecture/owners.json` on this branch and do not bypass the gate. Prove it locally instead: apply the patch to a throwaway copy, run the gate against it, and report the output. Any other failure in that job is yours to fix.

## One bug to fix on this branch

Self-play quote: `/api/registration/quote` with ownerType=1 returns the theme ticket price (39.99 in the pay-gate rig run), while `/api/registration/create` charges `selfPlayPrice` (29.99 in `createSelfPlayPassInternal`). The customer sees one price and pays another. See `pay-gate/RESULT.md` line 195. Make the quote return what create charges, including the refund amounts the quote states. Do not change what create charges. Add a test that fails on the old code, and prove it in the rig with HTTP. Commit it separately.

## Verify

- Every lane's own new tests still run and pass on the merged tree. Check that none was dropped or weakened in a conflict by comparing test file lists and test counts against each lane.
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`, `build-action-ledger.js --check`, full mini unit tests, backend tests for changed modules, Druid wall on new mapper SQL as l8 did, all CI jobs.
- Stand up the rig from `claude/cloud-walk-20261101-rig` plus the club lane's `harness-patches.diff` on the merged tree. Apply all new migrations in order on the clean rig DB and prove the jar boots. Log in once each as player, merchant and club lead. The full walk comes in the next round, so don't do it here.
- Main package size: report compressed and uncompressed main size, and the two `scripts/preflight-release.js` BLOCKs as they stand on the merged tree.

## Result

Write `docs/delivery/walk-fix-20261102/integ/RESULT.md` on the test branch: the merge commit per lane; each real conflict with what was kept from each side; the migration list in apply order; test counts per lane before and after; CI state; the architecture gate proof; what was left out and why; anything you could not verify. Plain words. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
