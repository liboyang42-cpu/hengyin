# Map reads load test (T2 maps-backend, BE-30)

Repeatable load test for the reads that feed the maps: roam places (`/api/roam/pois`), kept fog cells
(`/api/roam/fog/cells`), deals, sponsored quests, clubs nearby, plus the cached public reads
(`/api/config/features`, `/api/common/banner`, `/api/category/list`).
Local or TEST only. Nothing here may point at production; the seed refuses the production schema name and the
k6 script refuses the production hosts (as `ci/perf/perf_harness.py` does).

## 1. Database

A MySQL 8 schema from `sql/base_schema_20260818.sql` plus, in ledger order, the migrations the map reads use
(`migration_club_open_settings_20260909.sql`, `migration_20261115_fog_map.sql`, `migration_20261115_marketplace.sql`,
`migration_20261115_sponsored_quests.sql`, `migration_20261115_club_ops.sql`, `migration_20261115_maps_backend.sql`).
On a TEST server the schema already has them through the migration runner.

## 2. Dense seed

```bash
python3 ci/perf/maps/seed_dense_district.py --database cy_maps --places 5000 > /tmp/maps-seed.sql
mysql -uUSER -p cy_maps < /tmp/maps-seed.sql
```

Defaults: 5,000 places in one 0.06 x 0.07 degree district (about 6.7 x 6.7 km, West Lake, Hangzhou) with every
visibility case (unpublished, pending / rejected / approved merchant applications, claims), places on 0.02-degree
tile edges and places sharing a point; 1,000 merchants (half with shop avatars), 50 load-test members
(ids 9000000-9000049) with found / reward rows, 400 kept fog cells each and 30-day fog tiles; 300 clubs (a third with a
pinned point) with 4 located events each; 200 deals; 20 sponsored quests. `--places 20000` makes a denser district.
All rows have ids from `--id-base` (default 9,000,000) and names starting with `loadseed`; running the script again
first deletes those rows, so it is repeatable. It prints SQL and never connects to a database.

## 3. Data-layer benchmark (no app server needed)

Runs the SQL and the tile cache directly with N threads and prints p50 / p95 / p99 and requests per second:

```bash
LANG=C.UTF-8 LC_ALL=C.UTF-8 \
CHENGYIN_MYBATIS_IT_PORT=3306 CHENGYIN_MYBATIS_IT_USER=cy CHENGYIN_MYBATIS_IT_PASSWORD=... \
CHENGYIN_MAPS_BENCH_SCHEMA=cy_maps CHENGYIN_REDIS_IT_PORT=6379 \
CHENGYIN_MAPS_BENCH_THREADS=16 CHENGYIN_MAPS_BENCH_SECONDS=10 \
mvn -B -q -pl chengyinhub-admin -am test -Dtest=MapReadLoadMySqlIT -Dsurefire.failIfNoSpecifiedTests=false
grep MAPBENCH chengyinhub-admin/target/surefire-reports/*MapReadLoadMySqlIT-output.txt
```

Without `CHENGYIN_REDIS_IT_PORT` the tile cache runs in memory (measures the code path, not Redis).
The parity proof on MySQL (same rows, same order before and after, EXPLAIN uses the new indexes, Java distance equals
MySQL's bit for bit) is `MapBoxReadParityMySqlIT`; it builds its own schema and seed:

```bash
CHENGYIN_MYBATIS_IT_PORT=3306 CHENGYIN_MYBATIS_IT_USER=cy CHENGYIN_MYBATIS_IT_PASSWORD=... \
mvn -B -q -pl chengyinhub-admin -am test -Dtest=MapBoxReadParityMySqlIT -Dsurefire.failIfNoSpecifiedTests=false
```

## 4. HTTP load test (k6)

Against a local jar or the TEST server running on the seeded schema, with Redis:

```bash
PERF_BASE_URL=http://127.0.0.1:8080 PERF_DRY_RUN=false MAPS_VUS=32 MAPS_DURATION=60s \
  PERF_TOKEN=<app token of a seeded member, optional> k6 run ci/perf/maps/k6_map_reads.js
```

Mix: 45 % pins, 10 % fog cells and 7 % deals (only with `PERF_TOKEN`), 6 % sponsored quests, 7 % clubs nearby,
12 % features, 7 % banner, 6 % categories. Thresholds are the read thresholds of `ci/perf/manifest.json`
(P95 300 ms, P99 800 ms, errors under 0.5 %). Without `PERF_DRY_RUN=false` the script does nothing.

To compare with caching off, restart with `CHENGYIN_READ_CACHE_ENABLED=false` and run the same command.
Record the box (vCPU, RAM), MySQL and Redis versions, seed size, VUs and duration with any number you publish; a
number from a laptop or a container is not production capacity (see `ci/perf/README.md`).
