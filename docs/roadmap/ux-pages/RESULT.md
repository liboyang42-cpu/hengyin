# T4 ux-pages: backend receipt (hengyin)

Branch `feature/r-ux-pages` from `origin/roadmap/be-integ` (`f0dc762c1`). App receipt: chengyin-app `cloud/r-ux-pages`, `docs/roadmap/ux-pages/RESULT.md`.

## Why

UX-11: the app's play verdict shows what a stop earned from the server answer. The node completion answer reported exploration points, badges, ranking medal, drop and conditional reward, but not the points `pointsAwardService.award(memberId, 12L, nodeId, "节点打卡")` actually granted. The screen must not estimate them.

## Change

- `chengyinhub-admin/.../web/service/play/PlayNodeCompletionApplication.java`: keeps the return value of `award(...)` (the points actually granted; 0 = no rule, rule 0 or already granted) and adds `pointsEarned` to the answer only when it is above 0. Additive: no route, field or behaviour removed; the grant itself is unchanged (still first time, `!shopDay`, `RewardPolicy.FULL`).
- `chengyinhub-admin/src/test/.../ApiPlayProgressIdempotencyTest.java`: two tests: the answer carries `pointsEarned` = the awarded amount; the key is absent when nothing was awarded.

No SQL, no configuration, no money path.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test -Dtest=ApiPlayProgressIdempotencyTest -Dsurefire.failIfNoSpecifiedTests=false
# Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
```

## Not verified
- A live TEST completion answer (no live services called).

## Owner actions
- None. Deploy with the app branch; an older server simply omits the key and the verdict lists the other rewards.
