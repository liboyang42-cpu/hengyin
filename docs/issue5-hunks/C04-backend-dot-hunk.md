# C04-backend → dot hunk（未应用）

Base: `origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
Owner: Cursor C04-backend exclusive package  
Shared files: **do not apply from this lane**. Dot owns 12/35 lifecycle / public controllers.

## Why a hook is required

`OrientSessionServiceImpl.prepare` currently rejects team play:

```
String instance = routeRuntime.playInstance(memberId, topicId, act);
if (instance != null && instance.startsWith("game:")) {
    throw new OrientSessionException("ORIENT_TEAM_UNSUPPORTED", 409, "组队定向暂不支持该流程");
}
```

C04 does **not** write `TopicRouteRuntimeServiceImpl`, `TopicRouteSession*`, `CmsTopicServiceImpl`, or public Controllers/Mappers. TEAM / multi-club eligibility and frozen published-rule start live in:

- `com.chengyinhub.business.service.orienteering.PublishedRuleSelector`
- `com.chengyinhub.business.service.orienteering.TeamClubEligibilityAuthority`
- `com.chengyinhub.business.service.orienteering.OrdinaryCityTimezoneQualifier`
- `com.chengyinhub.business.service.orienteering.EventScoreVersioning`
- `com.chengyinhub.business.service.orienteering.EventResultDispute`

## Suggested call order (dot wiring, not applied here)

1. Register TEAM or SOLO through `TeamClubEligibilityAuthority.register` using `IClubService` / `IClubMemberService.selectByClubAndMember` / `IClubParticipationGuard.requireAllowed`. Empty roster must stay `EVENT_MATCHMAKING_CANCELLED`.
2. Select frozen rules with `PublishedRuleSelector.select(topic)` — ack `publishedConfigVersion`, never draft `configVersion`.
3. Qualify `OrdinaryCityTimezoneQualifier` (Asia/Shanghai) and per-item `confirm`.
4. `EventScoreVersioning.start(..., ackPublishedConfigVersion, requestId)` then evidence / signed scores / DNF|DQ|PENDING / `finish(requestId)`.
5. Readback **the same** `requestId`. UNKNOWN → `EVENT_NOT_FOUND`, do not mint a new request.
6. Dispute restore via `EventResultDispute.restoreOriginalReward` against the original finish request. Do not call Growth XP / personal leaderboard.

## Files this lane did not touch

- `TopicRouteRuntimeServiceImpl`
- `RouteEffects`
- `CmsTopicServiceImpl`
- `TopicRouteSession.java` / `TopicRouteSessionMapper.java` / `TopicRouteSessionScope.java`
- public Controllers / Mappers / SQL migrations
