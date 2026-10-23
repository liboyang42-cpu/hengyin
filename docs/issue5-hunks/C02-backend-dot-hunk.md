# C02 backend → dot hunk request

Base: hengyin `origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`

Exclusive files landed on `cursor/c02-rpg-adapters-e504` (this PR). Not applied to shared runtime.

## Needed hook (do not apply here)

File: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java` (dot-owned)

After environment refresh / before treating a check as success, call:

```java
EnvironmentExpiryAdapter.Decision expiry = environmentExpiryAdapter.evaluate(timedFact, Instant.now(clock), clientNow);
if (!environmentExpiryAdapter.grantsCheckSuccess(timedFact, Instant.now(clock), clientNow, state.getEnvironment().get("weather"))) {
    // fail the check; keep originalRequestId on the receipt; do not start a new request
}
```

Do not auto-resolve by taking this file from the Cursor branch. Wire the adapter as a collaborator; do not copy a second writer.

WAITING_SOURCE: unpushed `RpgAuthoringCaller` / `DynamicAttributeAuthoringService` SHAs `e16c2c5` `5b92889` `94a5d4a` `a829a09`.
