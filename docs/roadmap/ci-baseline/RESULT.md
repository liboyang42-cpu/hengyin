# Q4 ci-baseline — result (hengyin)

Branch `feature/r-ci-baseline`, based on `fix/r5-be-infra-stubs` (`32b425c33`).
App-side part, the baseline and the branch-protection settings are in
chengyin-app `cloud/r-ci-baseline`, `docs/roadmap/ci-baseline/`.

## Findings

IOS-02 (backend half): every push to `feature/**` and `fix/**`, and every pull
request, now gets an automatic backend check. It blocks merges only after
the owner marks `backend-tests` as required (chengyin-app
`docs/roadmap/ci-baseline/BRANCH-PROTECTION.md`).

## Changes

- `.github/workflows/backend-tests.yml` (new), job `backend-tests`:
  - GitHub-hosted `ubuntu-latest` only (never the self-hosted Mac); concurrency per branch/PR with cancel-in-progress.
  - Temurin Java 21 through `actions/setup-java@v4` with `cache: maven` (dependency cache).
  - `mvn -B test` over the whole reactor. Only `chengyinhub-admin` has tests today; other modules are compiled.
  - A throwaway `mysql:8.0.45` service container (root password `root`, a fixed test value for that container only, not a secret) and `CHENGYIN_MYBATIS_IT_*` variables, so the 14 `*MyBatisTest` classes run against a real database instead of erroring.
  - A Markdown summary on the job page (counts plus each failing test) and the surefire reports uploaded on failure.
  - Reads no repository secrets. `ci.yml` and every other workflow are unchanged.

## Verification

| Command | Result |
|---|---|
| `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B test` (no database) | 6 min; 12,661 run, 1 failure, 34 errors, 565 skipped. All 34 errors are the 14 `*MyBatisTest` classes with "Communications link failure" |
| same with local `mysql:8.0.45` (Docker) and the workflow's `CHENGYIN_MYBATIS_IT_*` values | 5 min; 12,571 run, **1 failure**, 0 errors, 559 skipped |
| the workflow's summary script on those reports | prints the table and the one failure |
| `python3 -m unittest` on the 17 `ci/test_*.py` files that mention workflows, with and without the new file | identical results (5 fail both ways, unrelated) |
| GitHub Actions run 43711073129 on this branch (`0c602f5d`) | ran on GitHub-hosted Ubuntu in 8 min, MySQL service healthy; summary: 11,598 run, **1 failed** (the known test below), 0 errors, 409 skipped; surefire reports uploaded. Red, as expected. (Run 43710560708 was cancelled by the next push: the concurrency group works) |

## Known failure (base, not caused by this package)

`ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode`
expects `IllegalStateException` but `CmsTopicServiceImpl.updateTopicByOwnerInternal`
(line ~3013) now throws `ServiceException`. So `backend-tests` is red on
`fix/r5-be-infra-stubs` today for this single test, an honest red. Fix it in
the topic/chapter package: change the test's expected exception or the
service's exception type. The workflow was not weakened to hide it.

## Not verified

- `mvn test` does not run the named `*IT` suites or download the Apple IAP fixtures. `ci.yml`'s `Backend tests` job (master and selected PR bases) still covers those through `ci/backend-test.sh`.

## Owner actions

- Make `backend-tests` a required check on the integration branches (see chengyin-app `BRANCH-PROTECTION.md`) once the known failure above is fixed.
