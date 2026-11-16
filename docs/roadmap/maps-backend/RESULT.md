# T2 maps-backend: result (backend, hengyin)

Branch `feature/r-maps-backend`. It is built on `origin/roadmap/be-integ`, merged twice in this run: first at
`87aaf3dd2` (hengyin master #830, the security line and the wave 5-7 packages), then at its later head (merge
`2dc282c1e`). Never force-pushed. Backend only. No app or mini-program change. The responses of `/api/roam/pois`,
`/api/config/features`, `/api/common/banner` and `/api/category/list` are unchanged.

## Findings

| ID | Status | Notes |
|---|---|---|
| PERF-17 `/api/roam/pois` cost grows with city density | **Fixed** (with #830) | #830 now limits the per-row look-ups to `limit + 1` rows. This package adds `idx_roam_poi_player_geo (status, lat, lng, application_type, audit_status)`. EXPLAIN of #830's inner query on a street-sized box shows an index range scan on it, where it was a table scan. My earlier two-phase SQL is replaced by #830's query, which is taken unchanged. |
| BE-18 almost no read caching | **Fixed, switch OFF by default** | Redis read-through cache for the shared parts of features, banners, categories and roam places by tile. Writes invalidate it after the transaction commits. It holds no per-member or money data. `CHENGYIN_READ_CACHE_ENABLED` defaults to **false**. |
| BE-30 load test only on a near-empty database | **Fixed** | Dense seed generator, k6 scenario and data-layer benchmark in `ci/perf/maps/`. Numbers measured here are below. |

## Merging #830 into the read cache

- `/api/roam/pois` with no filter: when the tile cache bean is present, `MapPoiTileCache.near(..., POIS_NEAR_LIMIT + 1)` serves it. The cache now orders like #830: `coalesce(node_level, 0)` descending, then the same box-centre distance, then id. The controller still does everything after the read: the `truncated` flag, the 3-picture gallery preview and the guest projection `publicPoi`. Every request gets fresh objects decoded from the cache entry, so cutting the gallery never changes a cached entry. Reads with a keyword, category or tag always go to MySQL. Rate limits and annotations on the route are unchanged.
- **Fixed a bug found by the new tests.** The marks query (`found` / `pendingRedeem`) was read by exact column label. Under H2 the labels came back lower-case, so the marks read as false. They are now read regardless of case.
- **Ranking matches MySQL exactly.** On MySQL 8.0.46, the Java distance matched the server's bit for bit for 239,801 of 240,000 place-and-box pairs. The rest were at most 2 ULP off, because the server's `cos` / `pow` come from libm. If two neighbouring places of the same level are within 1e-12 of each other (relative) and are not an exact mirror tie, the cache hands the request to MySQL. A `nearTies` counter records each hand-off; there were 0 in every run.
- The features cache key moved to `v2`, because #830's merge added `merchantReviewSla`.

## Changes by file (compared with be-integ)

- `chengyinhub-system/.../readcache/` (`ReadCache`, `ReadCacheStore`, `RedisReadCacheStore`, `ReadCacheNamespace`, `ReadCacheInvalidationInterceptor`, `ReadCacheConfiguration`, `MapPoiTileCache`): the cache, the invalidation on any MyBatis write to `roam_poi`, `npc_profile`, `sys_config`, `sys_banner` or `sys_category`, the tile cache and the near-tie guard.
- `RoamMapper.java/.xml`: #830's `selectPoisNearFiltered` taken unchanged. Adds `selectPoiBaseInBox` (the tile load: same columns and avatar, no member columns) and `selectPoiMemberMarks`.
- `RoamFogCellMapper.xml`: sargable prefix read for fog cells. Parity is checked on MySQL.
- `ApiRoamController`, `ApiConfigController`, `ApiCommonController`, `ApiCategoryController`: optional cache beans. When no bean is present, each route uses exactly the base code path.
- `application.yml`: `chengyin.read-cache.*`, with `enabled: ${CHENGYIN_READ_CACHE_ENABLED:false}`.
- `sql/migration_20261115_maps_backend.sql` (idempotent, rollback note at the top) and its entry in `ci/migration-dependencies.json`.
- `ci/perf/maps/` (README, `seed_dense_district.py`, `k6_map_reads.js`) and `ci/test_maps_seed.py`.
- Tests:
  - `MapBoxReadParityTest` (H2)
  - `RoamPoisReadCacheParityTest` (the real controller, cached and uncached, guest and member)
  - `PublicReadCacheTest`
  - `ReadCacheInvalidationInterceptorTest`
  - `MapBoxReadParityMySqlIT`
  - `MapReadLoadMySqlIT`
  - `LegacyMapReadsMapper.xml`, which holds the base SQL: #830's pois read and the old fog read.

## Verification (commands run in this session)

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile`: OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='RoamPoisReadCacheParityTest,PublicReadCacheTest,ReadCacheInvalidationInterceptorTest,MapBoxReadParityTest,RoamPresenceControllerTest,MemberPrivacyErasureServiceTest,TwoNodeSchedulingTest,ApiConfig*Test,*RoamPois*Test'`: all pass. That is 9 + 4 + 4 + 2 + 15 + 4 + 4 tests, plus the `ApiConfig*` tests.
- Local **MySQL 8.0.46**, installed with apt for this run, and Redis 7. `CHENGYIN_MYBATIS_IT_PORT=3307 … -Dtest=MapBoxReadParityMySqlIT`: **3/3 pass**. It covers:
  - 160 random boxes with limits 40, 500 and 501. #830's SQL, the production mapper and the tile cache (cold and warm) return identical JSON for guests and members.
  - Filtered reads, clubs, deals, sponsored quests and fog return the same before and after the migration. The migration was applied twice.
  - The controller returns byte-identical answers cached and uncached for the 3 km default, 1.2 km and 20 km boxes. The 20 km box sets `truncated: true` and the 500 places kept are the same.
  - The ULP bound, and EXPLAIN using `idx_roam_poi_player_geo` and `idx_cms_activity_club_start`.
- `bash ci/migration-safety-check.sh` ends with 迁移安全门禁:通过. `bash ci/migration-layout-check.sh` ends with 迁移布局门禁:通过. `python3 -m pytest -q ci/test_maps_seed.py`: 4 passed.
- Benchmark: `MapReadLoadMySqlIT` with 5,000 places, 8 threads, 10 s each, real Redis, and MySQL and the JVM on the same 4-vCPU / 15 GB container.

| Read | rps | p50 | p95 | p99 |
|---|---|---|---|---|
| pois, #830 SQL, uncached (3 km, limit 501) | 226 | 32.5 ms | 57.9 ms | 86.0 ms |
| pois, tile cache (Redis) | 170 | 43.8 ms | 85.5 ms | 126.0 ms |
| fog kept cells | 7,554 | 0.8 ms | 2.4 ms | 5.9 ms |
| clubs nearby | 549 | 14.2 ms | 23.7 ms | 30.6 ms |
| deals | 658 | 11.9 ms | 19.8 ms | 24.5 ms |

**Honest finding:** after #830, the `/pois` tile cache is *slower* than the uncached query on this box. It decodes whole tiles of JSON on every request, while #830's bounded query is already cheap. The cache only moves load from MySQL to the app server. Keep `CHENGYIN_READ_CACHE_ENABLED=false` for `/pois`. A follow-up could split the switch per namespace, or keep an in-process decoded-tile layer. The features, banner and category caches are simple key reads, but I did not run k6 against a running jar here (see below).

## Not verified here

- **H2 limits.**
  - On #830's derived table, H2 re-evaluates the box for every row's correlated sub-queries (about 1 s per row at 60 rows). The H2 tests therefore use boxes under 400 m.
  - H2 cannot parse MySQL's `interval 30 day`, so the fog parity test runs on MySQL only.
  - Large-box parity is proved on MySQL.
- **Not run.**
  - k6 against a running jar.
  - Production-sized hardware.
  - The full admin test suite. Only the tests listed above were run.
- **No Docker daemon.** MySQL was installed with apt into this container. A number from a container is not production capacity.
- **fog_map backfill on an empty schema.** `migration_20261115_fog_map.sql` fails its backfill on an empty schema under `ONLY_FULL_GROUP_BY` (ERROR 1055 at line 23). That belongs to the fog-map package, not this one. The IT creates the table it needs.

## Owner actions and open questions

- Decide whether to turn the cache on in TEST for features, banner and categories only. That needs a per-namespace switch, which is a small follow-up. Leave `/pois` caching off unless a k6 run on TEST shows MySQL is the bottleneck.
- Time the `ALTER TABLE roam_poi ADD INDEX … ALGORITHM=INPLACE, LOCK=NONE` on TEST before production.
- No secrets were added or found in the touched files. No production switch was changed. Money paths were not touched.
