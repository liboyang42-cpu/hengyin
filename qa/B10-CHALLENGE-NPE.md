# B10: `challenge/command` NullPointerException on d20 rolls 2-19 (acceptance-r3 wave 6, F09)

Branch `fix/challenge-npe-r3` from `origin/integration/backend-app-r3` @ 6c1389098.

## Root cause

`CityMonopolyService.challengeCommand` (chengyinhub-system, citygame) builds its reply with:

```java
out.put("nat", roll == 20 ? 20 : roll == 1 ? 1 : null);
```

The inner ternary `roll == 1 ? 1 : null` has type `Integer`. The outer ternary mixes `int` (`20`) with that
`Integer`, so under JLS 15.25 it is a numeric conditional of type `int`. The inner result is therefore unboxed,
and for every roll from 2 to 19 that means `null.intValue()`. The NPE is thrown after the roll, the fee split
and the takeover/defence writes. `@Transactional` rolls all of it back, and the global handler answers
HTTP 200 / code 500 "系统繁忙，请稍后重试". Only a natural 1 or 20 could resolve. That matches the TEST app.log:
`NullPointerException: Cannot invoke "java.lang.Integer.intValue()" at CityMonopolyService.challengeCommand(CityMonopolyService.java:1292)`.

## Fix

- `out.put("nat", natural(roll))`. `CityMonopolyService.natural(int)` returns `Integer.valueOf(20)`,
  `Integer.valueOf(1)` or `null`, boxed on every branch, so nothing is unboxed. The wire shape is unchanged:
  `nat` is 20, 1 or null.
- Search for copies: scanned all Java sources for the nested `? <primitive> : … ? <primitive> : null` pattern,
  and citygame/roam/dice/game code for single-level mixes of primitives and nullable boxed values. Only line 1292
  had the pattern. Similar-looking lines are safe: they box explicitly (`Integer.valueOf(...)` in
  TopicRouteRuntimeServiceImpl, GameSessionRuntimeServiceImpl, GameplayTaskEngine, GameplayCooperationEngine),
  return reference types (String, Boolean, Tier), or use a non-null `int` (`CityMonopolyConfig.integer`
  returns `int`). No other changes were needed.

## Tests

- New `chengyinhub-admin/src/test/java/com/chengyinhub/business/citygame/CityChallengeNaturalRollTest.java`:
  - `everyD20RollResolvesItsNaturalMarker`: parameterized over every roll 1-20. It checks that `natural(roll)`
    does not throw, returns 20 / 1 / null, and survives `out.put("nat", …)` in the reply map.
  - `noPrimitiveNullNestedTernaryInGameSources`: a source guard over `citygame`, `service/support` and
    `service/impl`. It fails if the `? <int> : … ? <int> : null` pattern comes back. It matches the original line.
  - Result: 21 run, 0 failures, 0 errors. The neighbouring `CityMonopolyServiceUnitTest` (7) and
    `CityMonopolyRulesTest` (43) also pass.
- Module: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin -am test -Dmaven.test.failure.ignore=true`
  → **Tests run 19930, Failures 0, Errors 34, Skipped 713**. BUILD SUCCESS.
- Full suite: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B test -Dmaven.test.failure.ignore=true`
  → **Tests run 19930, Failures 0, Errors 34, Skipped 713**. BUILD SUCCESS.
- All 34 errors are environmental: `java.net.ConnectException: Connection refused` → `Communications link
  failure`. These are the MyBatis tests in `com.chengyinhub.business.it` that need a live MySQL, which this
  container does not have (AuditReportLane, ClubCrmCheckinDetail, ClubDissolutionGate, ClubTopicManageStats,
  ComplianceF7, MerchantDashboardAggregation, MerchantNumbersAgree, O08MemberOnlyTopicLocation,
  PublicActivityScopeGate, PublicTopicFeedGate, PublicVisibility, RedemptionRewardClawback, TopicDraftVisibility,
  TopicSyncPostRestore). None of them touch citygame.
- Heap note: both runs added `-DargLine=-Xmx3g -Dspring.test.context.cache.maxSize=4`. The root POM's
  `-Xmx512m` made the surefire fork run out of memory (`OutOfMemoryError: Java heap space`) after about 12.7k
  tests. That is unrelated to this change, but without the flag the totals are partial.

## Not done

- Not deployed. To confirm, re-run F09 on TEST once a jar from this branch is deployed.
- `CityMonopolyServiceUnitTest` still injects a fixed d20 (`faces -> 20`). An end-to-end `challengeCommand` test
  with a mid roll needs the MySQL harness (`CityGameMysqlIntegrationTest`, skipped here).
