# x-journey-app — backend receipt (hengyin)

Branch `feature/r-x-journey-app` from `origin/roadmap/be-integ` (`cbbf7e3d0`). App side: chengyin-app `cloud/r-x-journey-app`.

## Issues

| ID | Status | Note |
|---|---|---|
| GAME-10 step 3 | Fixed | Play refusals carry a typed `ErrorCode`, so the v2 contract never infers "system failure" from the wording |
| GAME-1, 5, 6, 7, 17 | App only | No backend change needed (server already accepts answer location, skip actions and exact resends) |

## What changed

The app opts into `X-Error-Contract: 2`. Before this, play refusals were untyped, so `ErrorContract` guessed the class from the message. A refusal containing 稍后 / 不可用 / 失败 (for example 这次定位暂不计入,稍后再试 from location trust, or 请稍后再试) went out as HTTP 5xx, and the app could only treat it as an unknown result.

- `PlayCommandErrorContract`: `rejected` and `unresolvedEffect` (and so `retryable`, `retryableUncertain`, `arriveFirst`) now carry `ErrorCode.REFUSED`. `conflict` and `refresh` carry `ErrorCode.CONFLICT` (409, as before). `pending` and `unresolved` (520) are not refusals and stay unchanged. The class Javadoc lists the builders.
- `PlayNodeContext.playContext`: access failures (not registered, activity not open, pass required…) are typed `REFUSED`, keeping their original body code (500 or 402).
- `PlayBasicCommandApplication`: the wrong-answer reply is typed `REFUSED`. It still has no body `errorCode`, as the existing comment requires.
- Old-contract (mini-program) bodies are byte-for-byte unchanged: same `code`, `msg` and fields.
- New `PlayRefusalTypedContractTest`.

No SQL, no sys_job, no `@Scheduled`, no new tables, no player-message text changes.

## Verification

All commands run with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

- `mvn -B -q -pl chengyinhub-admin -am test -Dtest=PlayRefusalTypedContractTest,PlayArriveRecoveryContractTest,PlayIntentRecoveryContractTest,ErrorContractCompatibilityTest`: 45 tests, 0 failures.
- Every test class in `web/service/play` and `web/controller/api/Play*Test` plus `PlayerMessagePunctuationTest`: 282 tests, 0 failures, 0 errors.

## Owner / open

- Nothing to configure. The change shows only for v2 callers (the app): play refusals now come back as 4xx instead of 5xx when their wording mentions 稍后 and similar words.
- Overlap with the UI agent: none. `fix/registration-list-r3` did not exist at run time.
