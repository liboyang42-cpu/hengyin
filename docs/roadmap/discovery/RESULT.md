# K2 discovery — backend receipt (hengyin `feature/r-discovery`)

Base: `origin/roadmap/be-integ` (current base merged into the branch on 2026-11-15, no conflicts).
App half: chengyin-app `cloud/r-discovery` (see its `docs/roadmap/discovery/RESULT.md`).
This run resumed an earlier run that stopped on a usage limit; its three pushed commits were kept, and tests and this
receipt were added on top.

## Findings

| ID | State | How |
|---|---|---|
| MKT-09 shop-visit posts cannot point at a shop | **Closed** (community v1 posts) | A post reference may be `POI` (roam place, existed) or `MERCHANT` (new). Composer picker `GET /api/v1/community/shop-options`; shop page `GET /api/v1/community/places/{POI\|MERCHANT}/{id}/posts`. The legacy `ums_creative_square` table named in the finding is retired (its routes answer 410), so nothing was added there. |
| MKT-10 recommendations ignore places and offers; scoring paused; no personal "near you" | **Closed** | `GET /api/discovery/near-you` mixes places, operator opted-in offers and events in a city-sized box, each with one reason (`NEAR_YOU`, `YOUR_CLUB`, `NEW`). Scoring resumes only behind `chengyin.discovery.scoring.enabled` (env `CHENGYIN_DISCOVERY_SCORING_ENABLED`), default `false` = nearest first, today's pin order. `RecommendationServiceImpl` itself is untouched. |

## What changed

- `chengyinhub-admin/sql/migration_20261115_discovery.sql` (new, ledger id `20261115_discovery` after `community_post_platform_20260909` in `ci/migration-dependencies.json`): widens `chk_community_post_reference_type` to allow `MERCHANT`, only if it does not already. Rollback note at the top. No data change.
- `CommunityPostServiceImpl` / `ICommunityPostService`: `MERCHANT` joins `REFERENCE_TYPES` (same validation path as every other reference); `shopOptions(...)` (signed in, keyword ≤ 40 chars, escaped for LIKE, optional point); `placePosts(...)` (internal `PLACE` feed mode, never reachable through `/feeds/{mode}`, newest first, cursor paging).
- `CommunityPostMapper.xml`: `PLACE` mode in `selectFeed` behind the same `lifecycle='PUBLISHED'` filter and `visibilityPolicy` as every feed (held, failed-review, removed, blocked, muted, not-interested posts never show); invalidated references are ignored. A merchant page also lists posts linked to its places and a place page posts linked to its merchant. `countReferenceTarget` / `selectReferenceSnapshot` / `selectReferenceOptions` accept `MERCHANT` with the public-shop rule (`status=1, account_status=1, del_flag=0`). `selectShopOptions`: approved published places plus public merchants without a place, nearest first in a ~25 km box when a point is given.
- `ApiCommunityPostController`: `GET /shop-options` (signed in), `GET /places/{type}/{id}/posts` (same feature gate and error contract as the feeds). No route removed or renamed.
- `business/discovery/*` (new): `DiscoveryNearYouService`, `DiscoveryScorer`, `DiscoveryItem`, `DiscoveryReason`, `DiscoveryMapper` (+ XML, read only). `ApiDiscoveryController` `GET /api/discovery/near-you` (signed in, rate limited 60/min).
  - No paid input exists: items carry only where, what, how new and whether it is your club's. Offers are re-sorted by distance (or score), never by the operator sort they arrive in. Any source row marked `sponsored` or carrying a `label` is dropped, so a sponsored item can never appear here unlabelled; sponsored quests stay in `GET /api/marketplace/sponsored-quests`.
  - Club events appear only to that club's members (`club_member.status=1, del_flag=0`).
- `application.yml`: `chengyin.discovery.scoring.enabled: ${CHENGYIN_DISCOVERY_SCORING_ENABLED:false}`.
- Tests (new): `DiscoveryShopLinkMyBatisTest` (H2 + real mapper XML), `DiscoveryNearYouServiceTest` (Mockito).

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='Discovery*Test,CommunityPost*Test,Marketplace*Test' -Dsurefire.failIfNoSpecifiedTests=false
  → 227 tests, 0 failures, 0 errors (9 new discovery tests; existing community and marketplace suites unchanged)
bash ci/migration-safety-check.sh → 迁移安全门禁:通过
ls chengyinhub-admin/sql | grep -c '^rollback_' → 0
```

Brief step 3 coverage:
- links survive moderation states: `shopPageShowsOnlyVisiblePostsAndLinksSurviveEveryModerationState` (held / review failed / rejected posts hidden for others, the author and anonymous viewers; approval later shows the post without re-linking; take-down hides it and the link row stays; invalidated links ignored; public feeds unchanged);
- the list never includes sponsored items unlabelled: `sponsoredRowsNeverEnterTheListSoNothingAppearsUnlabelled` (switch on and off);
- switch off keeps today's results: `switchOffKeepsTodaysNearestFirstOrderAndNeverReadsTheProfile`, `operatorOrderOfOffersNeverChangesTheList`; switch on: `switchOnScoresClubAndInterestButKeepsEveryItem`.

## Not verified

- Against MySQL 8 (the H2 tests run the real mapper XML in MySQL mode; the CHECK-constraint migration was only read, not executed).
- Load of the near-you query on production data (three bounded queries per call: 30 places, ≤100 offers via the marketplace read, 20 events).

## Owner actions and open questions

1. Run `20261115_discovery` through the ledger before deploying the jar.
2. Leave `CHENGYIN_DISCOVERY_SCORING_ENABLED` unset (false) until you want scored order. When on, the score is 0.5 closeness + 0.2 the player's own interest profile + 0.2 your club + 0.1 new (≤ 14 days); no paid term.
3. Mini-program: its composer still posts through the retired legacy route (`/api/creativesquare/action`), so adding a shop picker there is not a small change and was not done. The new routes are ready for it.
4. No hard-coded secrets were found in the files touched.
