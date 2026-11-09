# 第十一轮 h1-funds-gate 结果

Lane: h1-funds-gate. Branch: `integ/launch-test-20261103` (runs come from PR #723's `pull_request` event).
Workflow: `.github/workflows/funds-mysql-it.yml`.

## Cause

I read the failed run logs and reran the self-test locally (`python3 -m unittest ci/test_funds_it_gate.py`). The red runs have three separate causes:

| Commits / runs | Failing job, step | Cause | Class |
|---|---|---|---|
| `3c56a1cd` … `0928d192` (runs 41888605750 … 41895377689) | Self-test: `test_expected_counts_match_source_files` → `GrowthMissionProofMybatisIntegrationTest: EXPECTED=25 与源码实际 @Test 数=26 不符`. The main job then fails closed with `self-test = failure`, so real MySQL never ran. | g3's commit `4751ec46` (R11 g3 item 2, p1 F-4) added a new real-DB test, `leaderboardsHoldPlayerAccountsWithPointsOnly`, but did not update `EXPECTED` in `ci/funds_it_gate.py` or the self-test fixture. | The pinned count legitimately moved. This is **the current cause.** |
| `002f49a5` (run 41887598721) | Main job, Maven dependency resolution | `Could not transfer artifact com.fasterxml.jackson:jackson-parent:pom:2.18.1 … status code: 502, Bad Gateway`. Maven Central returned 502. | Flaky infrastructure. No code is at fault, and it had nothing to do with g3 (`002f49a5` does not yet contain `4751ec46`). |
| Before `ffc3a3ff` (for example run 41880235305 @ `5539681f`) | Main job, `ClubSettlementSummaryMybatisIntegrationTest` | `BadSqlGrammarException … near ''M1-3 社群活动所属俱乐部ID…'`: a fixture or DDL was broken by an earlier change. | Broken test or fixture. Another lane fixed it before `ffc3a3ff`, and it has been green ever since (also green at `3c056cf3` and `3245b215`). I made no change for it. |

**No real money bug.** The test that triggered the current red is about leaderboards (whether merchant accounts and 0-point accounts show up in the weekly leaderboard / my rank). It does not touch charging, refunds or settlement rules, and this lane changes no money rule.

## Change (counts only, gate not weakened)

- `ci/funds_it_gate.py`: `GrowthMissionProofMybatisIntegrationTest` 25 → 26. Added test: `leaderboardsHoldPlayerAccountsWithPointsOnly` (from `4751ec46`; the weekly leaderboard Top list and my rank only include accounts with `role=player` and points > 0, and a merchant account gets no rank; it runs against real XML and real MySQL). The reason is in a comment beside the line.
- `ci/test_funds_it_gate.py`: the self-test fixture follows the same count, 25 → 26 (the fixture must match `EXPECTED`).
- No test was removed, skipped or excluded. skipped=0, fail-closed and the structural contract are unchanged.

Commit: `a664bd4e fix: 资金 IT 门禁期望数跟随第十一轮 g3 新增的一条真库用例(GrowthMissionProof 25 → 26)`

## Evidence

- Local: `python3 -m unittest ci/test_funds_it_gate.py`: 45 tests OK. `python3 ci/funds_it_gate.py workflow …`: structural contract passes.
- CI green run **41895718489** @ `a664bd4e` (https://github.com/liboyang42-cpu/hengyin/actions/runs/41895718489):
  - `Funds IT gate self-test (skipped=0 negative controls)`: success
  - `Funds MySQL IT (50/9/30, skipped=0, fail-closed)`: success, on MySQL 8.0.45 scratch
  - Log: `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0 -- in …GrowthMissionProofMybatisIntegrationTest` (the new test passes on real MySQL)
  - Log: `Tests run: 728, Failures: 0, Errors: 0, Skipped: 0` / `✅ 资金 MySQL IT 门禁通过:所有已登记 IT 均真跑、skipped=0、无错无失败、用例数达标。`
  - The stack trace at line 419 in the log comes from the intentional failure-injection test `failedAwardRollsBackThenTaskRecordsFailureAndNextRunRecovers`. That test passes.

## For other lanes

Whenever you add or remove an `@Test` in a gated IT suite, update the entry in `ci/funds_it_gate.py` `EXPECTED` and the fixture in `ci/test_funds_it_gate.py` in the same commit, and write which test changed and why. Otherwise the self-test turns red and takes the main job down with it (fail-closed).

Not fixed by this lane, observation only: `4751ec46` also changed the rank query in `UmsMemberMapper.xml` to `role='player' AND point>0`. `UmsMemberRankMySqlIT` (in `real-storage-it.yml`, not this workflow) still asserts `total_users` against "every account with del_flag=0". If that workflow turns red, check there first. That belongs to g3 or the owner of that workflow.

STATUS: COMPLETE
