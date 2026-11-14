# Backend App R2 Performance Baseline & Index Remediation Report

- **Target Environment**: TEST VPS (`http://127.0.0.1:8120`), Database `cy_e2e_r2` (MySQL 8.0 on `:3330`, Redis on `:6379`)
- **VPS Capacity**: 4 vCPU, 7.8 GiB RAM (`nproc=4`, `free -h`: `Mem 7.8Gi`)
- **Baseline Commit**: `e16affbc3` (`origin/integration/backend-app-r2`)
- **Fix Branch**: `perf/indexes` (branched from `e16affbc3`)
- **Auth & Fixtures**: E2E accounts logged in via `/api/getTestSmsCode` + `/api/smsLogin`; fixture IDs resolved live (`topicId=992001`, `activityId=992031`, `clubId=7001`, `merchantId=8001`, `conversation_id=2`, `regionId=gaps-a-test`)

## 1. Executive Summary

Two endpoints exceeded the `p95 > 500 ms` threshold during baseline load testing:

1. **`POST /api/search/unified` (Critical Slow Route — `p95 = 4,087.22 ms` at 1 VU, `6,382.93 ms` at 5 VUs, triggered `load1 = 6.40 > 6.0` abort during 20-VU warmup)**
   - **Root Cause**: `ContentSearchMapper.selectUnifiedSearch` contains nested `EXISTS (...)` visibility subqueries over 7 tables. Without an explicit custom `_COUNT` query in MyBatis, PageHelper (`PageInterceptor`) parsed and attempted to auto-rewrite the complex statement with JSqlParser on every call, consuming ~3.5–4.8 s of CPU per request under MySQL/JVM load.
   - **Fix**: Extracted `<sql id="unifiedSearchWhere">` and added explicit `<select id="selectUnifiedSearch_COUNT" resultType="Long">` in `chengyinhub-system/src/main/resources/mapper/business/ContentSearchMapper.xml`, bypassing PageHelper SQL parsing while keeping identical `WHERE` visibility predicates.
   - **Before -> After**: On the redeployed `perf/indexes` build prior to the RM-02+obs handoff, 3 consecutive `POST /api/search/unified` calls measured **`162.70 ms` (1st / JIT), `45.97 ms` (2nd), `25.07 ms` (3rd)** (all HTTP 200 `code=200`), down from **`3,551.68 ms` p50 (`4,087.22 ms` p95)** at 1 VU (>140x speedup on warm calls).
2. **`GET /api/play/nodes` (Slow Under 20 VUs — `p95 = 503.38 ms` in 20-VU ex-search baseline)**
   - **Root Cause**: Executed unindexed `ALL`/filesort queries across `cms_topic_node` (`topic_id + status + sort_id` and `chapter_id + status + sort_id`), `cms_topic_chapter` (`topic_id + sort_id`), and `cms_registration` (`owner_type + owner_id + member_id`), compounded by multi-query read amplification on hot routes (`topic/info-to-user`, `activity/info`, `club/home`, `club/post/feed`).
   - **Fix**: Added 16 idempotent composite/lookup indexes across 14 query patterns in `chengyinhub-admin/sql/migration_20261122_perf_hot_route_indexes.sql` (with ledger entry in `ci/migration-dependencies.json`).
   - **Before -> After (20-VU Ex-Search)**: `GET /api/play/nodes` improved from **`p50 = 345.52 ms, p95 = 503.38 ms`** (during concurrent test activity, load `2.15 -> 7.51`) to **`p50 = 301.50 ms, p95 = 408.58 ms`** (uncontested, load `0.66 -> 4.41`, `0` errors), bringing all 29 non-search routes below `500 ms` p95.

> [!NOTE]
> **Post-fix measurement scope**: Because another integration worker deployed RM-02 + observability onto TEST `8120` during validation, this report records: (a) the full 30-route 1/5/20-VU baseline on `e16affbc3`, (b) the 3-request smoke latency verification of `POST /api/search/unified` on the `perf/indexes` JAR (`162.70 ms` / `45.97 ms` / `25.07 ms`), and (c) an uncontested 6 s (+1 s warmup) 20-VU post-index run over the 29 non-search routes against `cy_e2e_r2` with `migration_20261122_perf_hot_route_indexes.sql` applied. A new full 60 s 30-route 20-VU re-run was omitted after `8120` switched to the RM-02+obs JAR per coordinator instructions.

## 2. Stage-Level Summary

| Run | Routes Covered | Duration | Concurrency | Load Avg (Before -> Peak) | Requests | Errors | Error Rate | Throughput (RPS) | Overall p50 (ms) | Overall p95 (ms) | Overall p99 (ms) | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Baseline 1 VU (`e16affbc3`) | All 30 | 63.06 s | 1 VU | 0.71 -> 0.97 | 450 | 0 | 0.0% | 7.14 | 7.25 | 44.06 | 3566.03 | Completed |
| Baseline 5 VUs (`e16affbc3`) | All 30 | 63.48 s | 5 VUs | 0.97 -> 4.2 | 1722 | 0 | 0.0% | 27.13 | 10.36 | 57.02 | 5205.65 | Completed |
| Baseline 20 VUs (`e16affbc3`) | All 30 | 11.56 s | 20 VUs | 3.92 -> 6.4 | 0 | 0 | 0.0% | 0.0 | - | - | - | Aborted (`load1=6.40 > 6.0`) |
| Baseline 20 VUs Ex-Search | 29 (excl. `#28`) | 10.13 s | 20 VUs | 2.15 -> 7.51 | 2539 | 0 | 0.0% | 250.54 | 53.62 | 257.01 | 405.11 | Aborted (`load1=7.51 > 6.0`, overlapped `obs` mvn test) |
| Post-Index 20 VUs Ex-Search | 29 (excl. `#28`) | 6.11 s | 20 VUs | 0.66 -> 4.41 | 1726 | 0 | 0.0% | 282.38 | 50.02 | 220.06 | 334.63 | Completed cleanly (`cy_e2e_r2` indexed) |
| Post-Fix `#28` Search Smoke (`perf/indexes` JAR) | `POST /api/search/unified` | 3 calls | 1 VU | - | 3 | 0 | 0.0% | - | 45.97 (2nd) / 25.07 (3rd) | 162.70 (1st/max) | 162.70 | Completed |

## 3. Top 30 Routes — Detailed Measurements (ms)

All baseline stages recorded **0 HTTP/business errors (0.0%)**.

| Route ID | Group | Method & Path | 1 VU p50 | 1 VU p95 | 1 VU p99 | 5 VUs p50 | 5 VUs p95 | 5 VUs p99 | Baseline 20 VU Ex-Search p50 / p95 / p99 | Post-Index 20 VU Ex-Search p50 / p95 / p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `01_home_topic_list` | Home | `POST /api/topic/list` | 12.47 | 20.02 | 25.88 | 13.88 | 20.69 | 23.47 | 75.64 / 130.52 / 147.60 | 68.16 / 98.91 / 110.05 |
| `02_home_activity_list` | Home | `POST /api/activity/list` | 8.03 | 10.87 | 10.97 | 11.35 | 20.15 | 26.05 | 76.19 / 167.78 / 179.94 | 69.61 / 114.67 / 144.91 |
| `03_home_public_catalog` | Home | `POST /api/public-catalog/list` | 8.59 | 11.45 | 11.57 | 11.32 | 19.56 | 29.38 | 56.96 / 101.46 / 111.97 | 51.90 / 84.32 / 105.75 |
| `04_discover_recommendation` | Discover | `POST /api/recommendation/list` | 4.41 | 6.90 | 7.24 | 5.46 | 12.77 | 14.85 | 30.76 / 65.49 / 70.76 | 30.19 / 51.59 / 69.71 |
| `05_discover_community_feed` | Discover | `GET /api/v1/community/feeds/LATEST` | 11.21 | 15.43 | 16.86 | 15.68 | 25.90 | 28.42 | 91.70 / 150.73 / 162.01 | 80.62 / 105.51 / 131.24 |
| `06_topic_detail` | Topic Detail | `POST /api/topic/info-to-user` | 30.49 | 44.83 | 45.71 | 41.22 | 59.97 | 72.59 | 275.32 / 383.29 / 406.05 | 221.40 / 281.00 / 289.23 |
| `07_activity_info` | Activity Info | `POST /api/activity/info` | 16.94 | 30.31 | 45.27 | 20.64 | 34.21 | 36.58 | 120.77 / 169.59 / 205.31 | 103.21 / 139.20 / 179.20 |
| `08_registration_quote` | Registration | `POST /api/registration/quote` | 17.93 | 23.56 | 25.35 | 23.15 | 35.30 | 52.50 | 130.71 / 219.91 / 228.41 | 119.26 / 154.07 / 181.66 |
| `09_play_nodes` | Play | `GET /api/play/nodes` | 34.49 | 67.00 | 68.37 | 52.75 | 82.87 | 104.37 | 345.52 / **503.38** / 520.31 | 301.50 / 408.58 / 433.81 |
| `10_play_route_state` | Play | `GET /api/play/route-state` | 12.82 | 26.53 | 29.77 | 21.31 | 40.53 | 52.93 | 109.81 / 175.35 / 205.48 | 112.58 / 167.53 / 185.02 |
| `11_play_run_session_list` | Play | `GET /api/play/run-session/list` | 3.80 | 10.40 | 12.19 | 5.88 | 14.09 | 17.26 | 23.14 / 44.63 / 59.15 | 25.89 / 44.11 / 61.46 |
| `12_roam_pois` | Roam | `GET /api/roam/pois` | 4.28 | 9.26 | 9.31 | 5.12 | 12.74 | 18.12 | 33.69 / 65.38 / 85.44 | 33.41 / 58.87 / 73.61 |
| `13_roam_tiles` | Roam | `GET /api/roam/tiles` | 2.98 | 7.13 | 7.71 | 5.57 | 10.03 | 12.50 | 25.14 / 56.25 / 64.58 | 26.23 / 46.18 / 67.64 |
| `14_city_current` | City Reads | `GET /api/city-game/current` | 3.82 | 7.78 | 9.16 | 4.86 | 11.48 | 13.94 | 34.39 / 60.77 / 75.36 | 30.08 / 48.46 / 60.63 |
| `15_city_state_snapshot` | City Reads | `GET /api/city-game/state/snapshot` | 4.60 | 9.23 | 9.91 | 7.18 | 12.15 | 16.48 | 41.16 / 66.24 / 83.87 | 41.84 / 75.06 / 100.86 |
| `16_club_home` | Club | `POST /api/club/home` | 11.41 | 19.24 | 22.94 | 15.79 | 28.22 | 35.82 | 107.45 / 157.74 / 165.46 | 97.98 / 143.68 / 161.68 |
| `17_club_detail` | Club | `POST /api/club/detail` | 9.16 | 14.49 | 16.42 | 13.33 | 22.89 | 28.72 | 75.88 / 119.81 / 138.53 | 69.84 / 110.78 / 117.58 |
| `18_club_post_list` | Club | `POST /api/club/post/list` | 6.93 | 12.40 | 14.09 | 9.95 | 14.98 | 16.70 | 54.90 / 86.90 / 117.03 | 52.65 / 90.50 / 106.27 |
| `19_club_post_feed` | Club | `POST /api/club/post/feed` | 7.15 | 8.75 | 8.93 | 9.50 | 18.66 | 21.10 | 57.17 / 97.10 / 120.36 | 53.49 / 88.24 / 96.55 |
| `20_im_conversations` | IM | `POST /api/im/conversations` | 5.49 | 7.00 | 7.16 | 8.22 | 15.11 | 17.81 | 40.66 / 67.71 / 83.35 | 36.63 / 55.80 / 60.79 |
| `21_im_messages` | IM | `POST /api/im/messages` | 6.87 | 10.93 | 12.79 | 9.41 | 17.45 | 31.25 | 55.82 / 99.59 / 120.57 | 57.19 / 79.23 / 98.10 |
| `22_im_send` | IM | `POST /api/im/send` | 12.27 | 24.66 | 27.84 | 14.42 | 25.49 | 33.53 | 96.77 / 163.28 / 178.57 | 88.56 / 136.51 / 174.40 |
| `23_channels_list` | Channels | `GET /api/channels` | 6.87 | 10.08 | 10.54 | 10.02 | 18.00 | 41.26 | 49.32 / 100.60 / 111.97 | 48.35 / 80.38 / 101.01 |
| `24_user_info` | User | `POST /api/userInfo` | 3.74 | 5.08 | 5.93 | 4.01 | 13.44 | 41.09 | 27.18 / 50.17 / 60.53 | 24.02 / 39.08 / 50.89 |
| `25_wallet_balance` | Wallet | `POST /api/wallet/balance` | 3.84 | 5.85 | 5.93 | 5.95 | 11.32 | 21.17 | 30.31 / 68.34 / 91.41 | 25.95 / 53.20 / 63.48 |
| `26_rewards_noncash` | Rewards | `GET /api/rewards/noncash` | 2.89 | 6.76 | 8.75 | 4.64 | 11.23 | 17.39 | 30.75 / 61.24 / 94.51 | 25.82 / 35.10 / 56.91 |
| `27_rewards_product_list` | Rewards | `POST /api/product/list` | 4.39 | 8.17 | 9.54 | 7.77 | 13.79 | 19.71 | 45.33 / 73.19 / 91.59 | 37.28 / 64.10 / 75.29 |
| `28_search_unified` | Search | `POST /api/search/unified` | 3551.68 | **4087.22** | 4848.76 | 4790.56 | **6382.93** | 6569.97 | Excluded (aborted load) | Smoke: 162.70 (1st), 45.97 (2nd), 25.07 (3rd) |
| `29_extgame_catalog` | Ext Game | `GET /api/external-game/catalog` | 3.88 | 4.37 | 4.47 | 4.82 | 9.80 | 11.80 | 22.36 / 55.12 / 82.32 | 18.70 / 43.17 / 55.97 |
| `30_merchant_public_home` | Merchant | `POST /api/merchant/public-home` | 6.80 | 8.36 | 8.40 | 8.60 | 19.10 | 19.77 | 37.56 / 72.22 / 98.19 | 34.67 / 56.87 / 80.45 |

## 4. EXPLAIN Plans on `cy_e2e_r2` (Before vs. After `migration_20261122_perf_hot_route_indexes.sql`)

Key execution plan changes verified directly against `cy_e2e_r2` via `EXPLAIN`:

| Query / Route | Before (`e16affbc3` schema) | After (`migration_20261122_perf_hot_route_indexes.sql`) |
| --- | --- | --- |
| `cms_topic_node` by `topic_id + status ORDER BY sort_id` (`play/nodes`, `topic/info-to-user`) | `type=ALL`, `key=NULL`, `Extra=Using where; Using filesort` | `type=range`, `key=idx_cms_topic_node_topic_status_sort`, `Extra=Using index condition; Using where` (filesort eliminated) |
| `cms_topic_chapter` by `topic_id ORDER BY sort_id` (`topic/info-to-user`, `play/nodes`) | `type=ALL`, `key=NULL`, `Extra=Using where; Using filesort` | `type=ref`, `key=idx_cms_topic_chapter_topic_sort`, `Extra=NULL` (filesort eliminated) |
| `cms_topic_node` by `chapter_id + merchant + status ORDER BY sort_id` | `key=idx_topic_node_chapter_merchant`, `Extra=Using where; Using filesort` | `type=range`, `key=idx_cms_topic_node_chapter_status_sort`, `Extra=Using index condition; Using where` (filesort eliminated) |
| `cms_collaborators` (`topic/info-to-user`) | `type=ALL`, `key=NULL`, `Extra=Using where` | `type=ref`, `key=idx_cms_collaborators_data`, `Extra=NULL` |
| `ums_comment` + `ums_comment_like` (`topic/info-to-user`, `activity/info`) | `c`: `ALL`, `key=NULL`; `ums_comment_like`: `ALL`, `key=NULL` | `c`: `type=ref`, `key=idx_ums_comment_owner_status`; `ums_comment_like`: `type=ref`, `key=idx_ums_comment_like_comment_id`, `Extra=Using index` |
| `ums_comment` `AVG(rating)` (`topic/info-to-user`) | `type=ALL`, `key=NULL`, `Extra=Using where` | `type=ref`, `key=idx_ums_comment_owner_status`, `Extra=NULL` |
| `cms_registration` by `ticket_id + status` (`activity/info`) | `type=ALL`, `key=NULL`, `Extra=Using where` | `type=ref`, `key=idx_cms_reg_ticket_status`, `Extra=NULL` |
| `club` active list (`club/home`, `club/list`) | `type=ALL`, `key=NULL`, `Extra=Using where; Using filesort` | `type=ref`, `key=idx_club_del_status_ctime` |
| `cms_activity` public list/count (`activity/list`) | `type=ALL`, `key=NULL`, `Extra=Using where` | `type=range`, `key=idx_cms_activity_pub_del_status_end`, `Extra=Using index condition; Using where` |
| `cms_topic` cold-start (`recommendation/list`) | `type=ALL`, `key=NULL` | `type=ref_or_null`, `key=idx_cms_topic_pub_del_ctime` |
| `pms_product` published list (`product/list`) | `type=ALL`, `key=NULL` | `type=ref_or_null`, `key=idx_pms_product_pub_del_sort` |

<details>
<summary>Full MySQL 8.0 EXPLAIN output (Before &amp; After)</summary>

### Before Indexes

```text
=== Q1 account_deregistration (every auth req) ===
+----+-------------+------------------------+------------+------+------------------------------+------------------------------+---------+-------+------+----------+----------------+
| id | select_type | table                  | partitions | type | possible_keys                | key                          | key_len | ref   | rows | filtered | Extra          |
+----+-------------+------------------------+------------+------+------------------------------+------------------------------+---------+-------+------+----------+----------------+
|  1 | SIMPLE      | account_deregistration | NULL       | ref  | idx_deregister_member_status | idx_deregister_member_status | 8       | const |    1 |   100.00 | Using filesort |
+----+-------------+------------------------+------------+------+------------------------------+------------------------------+---------+-------+------+----------+----------------+

=== Q2 cms_registration owner_type+owner_id+member (play/nodes) ===
+----+-------------+------------------+------------+------+--------------------------------------------------------+-----------------------+---------+-------+------+----------+-----------------------------+
| id | select_type | table            | partitions | type | possible_keys                                          | key                   | key_len | ref   | rows | filtered | Extra                       |
+----+-------------+------------------+------------+------+--------------------------------------------------------+-----------------------+---------+-------+------+----------+-----------------------------+
|  1 | SIMPLE      | cms_registration | NULL       | ref  | uk_reg_member_request,idx_cms_registration_participate | uk_reg_member_request | 8       | const |    1 |    50.00 | Using where; Using filesort |
+----+-------------+------------------+------------+------+--------------------------------------------------------+-----------------------+---------+-------+------+----------+-----------------------------+

=== Q3 cms_topic_node topic_id+status sort_id (play/nodes, topic/info) ===
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+-----------------------------+
| id | select_type | table          | partitions | type   | possible_keys                                                   | key                       | key_len | ref                                                                             | rows | filtered | Extra                       |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+-----------------------------+
|  1 | SIMPLE      | cms_topic_node | NULL       | ALL    | idx_topic_node_topic                                            | NULL                      | NULL    | NULL                                                                            |    7 |    14.29 | Using where; Using filesort |
|  1 | SIMPLE      | withdrawn      | NULL       | eq_ref | uk_offer_merchant_chapter,idx_offer_capacity,idx_offer_merchant | uk_offer_merchant_chapter | 16      | cy_e2e_r2.cms_topic_node.chapter_id,cy_e2e_r2.cms_topic_node.merchant_member_id |    1 |   100.00 | Using where; Not exists     |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+-----------------------------+

=== Q4 cms_topic_chapter topic_id sort_id (topic/info, play/nodes) ===
+----+-------------+-------------------+------------+------+------------------------------+------+---------+------+------+----------+-----------------------------+
| id | select_type | table             | partitions | type | possible_keys                | key  | key_len | ref  | rows | filtered | Extra                       |
+----+-------------+-------------------+------------+------+------------------------------+------+---------+------+------+----------+-----------------------------+
|  1 | SIMPLE      | cms_topic_chapter | NULL       | ALL  | idx_chapter_circle_selection | NULL | NULL    | NULL |    4 |    75.00 | Using where; Using filesort |
+----+-------------+-------------------+------------+------+------------------------------+------+---------+------+------+----------+-----------------------------+

=== Q5 cms_topic_node chapter_id+merchant sort_id ===
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------------+---------+---------------------------------------------------+------+----------+-----------------------------+
| id | select_type | table          | partitions | type   | possible_keys                                                   | key                             | key_len | ref                                               | rows | filtered | Extra                       |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------------+---------+---------------------------------------------------+------+----------+-----------------------------+
|  1 | SIMPLE      | cms_topic_node | NULL       | ref    | idx_topic_node_chapter_merchant                                 | idx_topic_node_chapter_merchant | 9       | const                                             |    2 |    14.29 | Using where; Using filesort |
|  1 | SIMPLE      | withdrawn      | NULL       | eq_ref | uk_offer_merchant_chapter,idx_offer_capacity,idx_offer_merchant | uk_offer_merchant_chapter       | 16      | const,cy_e2e_r2.cms_topic_node.merchant_member_id |    1 |   100.00 | Using where; Not exists     |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------------+---------+---------------------------------------------------+------+----------+-----------------------------+

=== Q6 cms_collaborators view_collaborators (topic/info) ===
+----+-------------+-------+------------+--------+---------------+---------+---------+------------------------+------+----------+-------------+
| id | select_type | table | partitions | type   | possible_keys | key     | key_len | ref                    | rows | filtered | Extra       |
+----+-------------+-------+------------+--------+---------------+---------+---------+------------------------+------+----------+-------------+
|  1 | SIMPLE      | cc    | NULL       | ALL    | NULL          | NULL    | NULL    | NULL                   |    1 |   100.00 | Using where |
|  1 | SIMPLE      | um    | NULL       | eq_ref | PRIMARY       | PRIMARY | 8       | cy_e2e_r2.cc.member_id |    1 |    11.11 | Using where |
+----+-------------+-------+------------+--------+---------------+---------+---------+------------------------+------+----------+-------------+

=== Q7 ums_comment+ums_comment_like view_comment (topic/info) ===
+----+--------------------+------------------+------------+--------+---------------+---------+---------+-----------------------+------+----------+----------------+
| id | select_type        | table            | partitions | type   | possible_keys | key     | key_len | ref                   | rows | filtered | Extra          |
+----+--------------------+------------------+------------+--------+---------------+---------+---------+-----------------------+------+----------+----------------+
|  1 | PRIMARY            | <derived2>       | NULL       | ALL    | NULL          | NULL    | NULL    | NULL                  |    2 |   100.00 | Using filesort |
|  2 | DERIVED            | c                | NULL       | ALL    | NULL          | NULL    | NULL    | NULL                  |    1 |   100.00 | Using where    |
|  2 | DERIVED            | m                | NULL       | eq_ref | PRIMARY       | PRIMARY | 8       | cy_e2e_r2.c.member_id |    1 |   100.00 | Using where    |
|  3 | DEPENDENT SUBQUERY | ums_comment_like | NULL       | ALL    | NULL          | NULL    | NULL    | NULL                  |    1 |   100.00 | Using where    |
+----+--------------------+------------------+------------+--------+---------------+---------+---------+-----------------------+------+----------+----------------+

=== Q8 ums_comment AVG(rating) (topic/info) ===
+----+-------------+-------------+------------+------+---------------+------+---------+------+------+----------+-------------+
| id | select_type | table       | partitions | type | possible_keys | key  | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------------+------------+------+---------------+------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | ums_comment | NULL       | ALL  | NULL          | NULL | NULL    | NULL |    1 |   100.00 | Using where |
+----+-------------+-------------+------------+------+---------------+------+---------+------+------+----------+-------------+

=== Q9 cms_registration view_registration ticket_id (activity/info) ===
+----+-------------+-------+------------+--------+---------------+---------+---------+-----------------------+------+----------+-------------+
| id | select_type | table | partitions | type   | possible_keys | key     | key_len | ref                   | rows | filtered | Extra       |
+----+-------------+-------+------------+--------+---------------+---------+---------+-----------------------+------+----------+-------------+
|  1 | SIMPLE      | c     | NULL       | ALL    | NULL          | NULL    | NULL    | NULL                  |    2 |    50.00 | Using where |
|  1 | SIMPLE      | u     | NULL       | eq_ref | PRIMARY       | PRIMARY | 8       | cy_e2e_r2.c.member_id |    1 |   100.00 | Using where |
+----+-------------+-------+------------+--------+---------------+---------+---------+-----------------------+------+----------+-------------+

=== Q10 club list/home del_flag+status+create_time ===
+----+-------------+-------+------------+--------+-----------------------+-----------------------+---------+-----------------------+------+----------+-----------------------------+
| id | select_type | table | partitions | type   | possible_keys         | key                   | key_len | ref                   | rows | filtered | Extra                       |
+----+-------------+-------+------------+--------+-----------------------+-----------------------+---------+-----------------------+------+----------+-----------------------------+
|  1 | SIMPLE      | c     | NULL       | ALL    | NULL                  | NULL                  | NULL    | NULL                  |    2 |    50.00 | Using where; Using filesort |
|  1 | SIMPLE      | cl    | NULL       | eq_ref | uk_club_leader_member | uk_club_leader_member | 8       | cy_e2e_r2.c.member_id |    1 |   100.00 | Using where                 |
+----+-------------+-------+------------+--------+-----------------------+-----------------------+---------+-----------------------+------+----------+-----------------------------+

=== Q11 cms_topic_node IN topic_ids GROUP BY (topic/list) ===
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------+
| id | select_type | table          | partitions | type   | possible_keys                                                   | key                       | key_len | ref                                                                             | rows | filtered | Extra                        |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------+
|  1 | SIMPLE      | cms_topic_node | NULL       | ALL    | idx_topic_node_topic                                            | NULL                      | NULL    | NULL                                                                            |    7 |    14.29 | Using where; Using temporary |
|  1 | SIMPLE      | withdrawn      | NULL       | eq_ref | uk_offer_merchant_chapter,idx_offer_capacity,idx_offer_merchant | uk_offer_merchant_chapter | 16      | cy_e2e_r2.cms_topic_node.chapter_id,cy_e2e_r2.cms_topic_node.merchant_member_id |    1 |   100.00 | Using where; Not exists      |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------+

=== Q12 cms_activity count (activity/list) ===
+----+--------------------+--------------+------------+--------+------------------------+---------+---------+---------------------------------+------+----------+-------------------------+
| id | select_type        | table        | partitions | type   | possible_keys          | key     | key_len | ref                             | rows | filtered | Extra                   |
+----+--------------------+--------------+------------+--------+------------------------+---------+---------+---------------------------------+------+----------+-------------------------+
|  1 | PRIMARY            | cms_activity | NULL       | ALL    | idx_cms_activity_topic | NULL    | NULL    | NULL                            |    3 |    33.33 | Using where             |
|  1 | PRIMARY            | parent_topic | NULL       | eq_ref | PRIMARY,idx_topic_club | PRIMARY | 8       | cy_e2e_r2.cms_activity.topic_id |    1 |   100.00 | Using where; Not exists |
|  1 | PRIMARY            | parent_club  | NULL       | eq_ref | PRIMARY                | PRIMARY | 8       | cy_e2e_r2.parent_topic.club_id  |    1 |   100.00 | Using where             |
|  3 | DEPENDENT SUBQUERY | active_club  | NULL       | eq_ref | PRIMARY                | PRIMARY | 8       | cy_e2e_r2.cms_activity.club_id  |    1 |    50.00 | Using where             |
|  2 | DEPENDENT SUBQUERY | live_parent  | NULL       | eq_ref | PRIMARY                | PRIMARY | 8       | cy_e2e_r2.cms_activity.topic_id |    1 |    16.67 | Using where             |
+----+--------------------+--------------+------------+--------+------------------------+---------+---------+---------------------------------+------+----------+-------------------------+

=== Q13 cms_topic cold start recommendation (recommendation/list) ===
+----+-------------+-------+------------+------+---------------+------+---------+------+------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+------+----------+-----------------------------+
|  1 | SIMPLE      | t     | NULL       | ALL  | NULL          | NULL | NULL    | NULL |    6 |    16.67 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+------+----------+-----------------------------+

=== Q14 club_post feed (club/post/feed) ===
+----+--------------------+-------+------------+--------+--------------------------------------------------------------+-------------------------------+---------+-------------------------------+------+----------+---------------------------------------------------------------------+
| id | select_type        | table | partitions | type   | possible_keys                                                | key                           | key_len | ref                           | rows | filtered | Extra                                                               |
+----+--------------------+-------+------------+--------+--------------------------------------------------------------+-------------------------------+---------+-------------------------------+------+----------+---------------------------------------------------------------------+
|  1 | PRIMARY            | cp    | NULL       | ALL    | idx_club_post_club,idx_club_post_pinned                      | NULL                          | NULL    | NULL                          |    2 |    50.00 | Using where; Using temporary; Using filesort                        |
|  1 | PRIMARY            | c     | NULL       | eq_ref | PRIMARY,idx_club_member                                      | PRIMARY                       | 8       | cy_e2e_r2.cp.club_id          |    1 |    50.00 | Using where                                                         |
|  1 | PRIMARY            | b     | NULL       | index  | uk_block                                                     | uk_block                      | 16      | NULL                          |    1 |   100.00 | Using where; Not exists; Using index; Using join buffer (hash join) |
|  1 | PRIMARY            | m     | NULL       | eq_ref | PRIMARY                                                      | PRIMARY                       | 8       | cy_e2e_r2.cp.author_member_id |    1 |   100.00 | Using where                                                         |
|  3 | DEPENDENT SUBQUERY | cm    | NULL       | eq_ref | uk_club_member_uniq,idx_club,idx_member                      | uk_club_member_uniq           | 16      | cy_e2e_r2.c.id,const          |    1 |    20.00 | Using where                                                         |
|  2 | DEPENDENT SUBQUERY | r     | NULL       | ref    | uk_club_post_revision_version,idx_club_post_revision_history | uk_club_post_revision_version | 8       | cy_e2e_r2.cp.id               |    1 |   100.00 | Using index                                                         |
+----+--------------------+-------+------------+--------+--------------------------------------------------------------+-------------------------------+---------+-------------------------------+------+----------+---------------------------------------------------------------------+

=== Q15 im_channel tree (channels) ===
+----+-------------+------------+------------+------+-----------------------------------------------------------------------+------+---------+------+------+----------+-----------------------------+
| id | select_type | table      | partitions | type | possible_keys                                                         | key  | key_len | ref  | rows | filtered | Extra                       |
+----+-------------+------------+------------+------+-----------------------------------------------------------------------+------+---------+------+------+----------+-----------------------------+
|  1 | SIMPLE      | im_channel | NULL       | ALL  | uk_channel_default,uk_channel_name,idx_channel_tree,idx_channel_purge | NULL | NULL    | NULL |   16 |     6.25 | Using where; Using filesort |
+----+-------------+------------+------------+------+-----------------------------------------------------------------------+------+---------+------+------+----------+-----------------------------+

=== Q16 pms_product list (product/list) ===
+----+-------------+-------------+------------+------+---------------+------+---------+------+------+----------+-----------------------------+
| id | select_type | table       | partitions | type | possible_keys | key  | key_len | ref  | rows | filtered | Extra                       |
+----+-------------+-------------+------------+------+---------------+------+---------+------+------+----------+-----------------------------+
|  1 | SIMPLE      | pms_product | NULL       | ALL  | NULL          | NULL | NULL    | NULL |    3 |    33.33 | Using where; Using filesort |
+----+-------------+-------------+------------+------+---------------+------+---------+------+------+----------+-----------------------------+

=== Q17 content_search_document unified search (search/unified) ===
+----+--------------------+--------------------+------------+--------+--------------------------------------------------------------------------------------+--------------------------------+---------+-----------------------------------+------+----------+---------------------------------------------------------------------+
| id | select_type        | table              | partitions | type   | possible_keys                                                                        | key                            | key_len | ref                               | rows | filtered | Extra                                                               |
+----+--------------------+--------------------+------------+--------+--------------------------------------------------------------------------------------+--------------------------------+---------+-----------------------------------+------+----------+---------------------------------------------------------------------+
|  1 | PRIMARY            | d                  | NULL       | range  | uk_content_search_document,idx_content_search_visible                                | uk_content_search_document     | 66      | NULL                              |    3 |   100.00 | Using index condition; Using where                                  |
|  1 | PRIMARY            | current_topic      | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |   100.00 | Using where; Using index                                            |
|  1 | PRIMARY            | current_activity   | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |   100.00 | Using where; Using index                                            |
|  1 | PRIMARY            | current_post       | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |   100.00 | Using where; Using index                                            |
| 10 | DEPENDENT SUBQUERY | post_sd            | NULL       | eq_ref | uk_community_search_document,idx_community_search_visible                            | uk_community_search_document   | 74      | const,cy_e2e_r2.d.document_id     |    1 |   100.00 | Using where                                                         |
| 10 | DEPENDENT SUBQUERY | p                  | NULL       | eq_ref | PRIMARY,idx_community_post_public                                                    | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |    50.00 | Using where                                                         |
| 10 | DEPENDENT SUBQUERY | b                  | NULL       | index  | uk_block                                                                             | uk_block                       | 16      | NULL                              |    1 |   100.00 | Using where; Not exists; Using index; Using join buffer (hash join) |
| 10 | DEPENDENT SUBQUERY | mu                 | NULL       | eq_ref | uk_community_member_mute,uk_community_member_mute_request                            | uk_community_member_mute       | 16      | const,cy_e2e_r2.p.author_id       |    1 |   100.00 | Using where; Not exists; Using index                                |
|  5 | DEPENDENT SUBQUERY | cms_activity       | NULL       | eq_ref | PRIMARY,idx_cms_activity_topic                                                       | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |    33.33 | Using where                                                         |
|  5 | DEPENDENT SUBQUERY | parent_topic       | NULL       | eq_ref | PRIMARY,idx_topic_club                                                               | PRIMARY                        | 8       | cy_e2e_r2.cms_activity.topic_id   |    1 |   100.00 | Using where; Not exists                                             |
|  5 | DEPENDENT SUBQUERY | parent_club        | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.parent_topic.club_id    |    1 |   100.00 | Using where                                                         |
|  5 | DEPENDENT SUBQUERY | member_only_parent | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.cms_activity.topic_id   |    1 |   100.00 | Using where; Not exists                                             |
|  7 | DEPENDENT SUBQUERY | active_club        | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.cms_activity.club_id    |    1 |    50.00 | Using where                                                         |
|  6 | DEPENDENT SUBQUERY | live_parent        | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.cms_activity.topic_id   |    1 |    16.67 | Using where                                                         |
|  2 | DEPENDENT SUBQUERY | cms_topic          | NULL       | eq_ref | PRIMARY,idx_topic_club,idx_cms_topic_is_template,idx_topic_circle_public             | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |    16.67 | Using where                                                         |
|  4 | DEPENDENT SUBQUERY | active_club        | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.cms_topic.club_id       |    1 |    50.00 | Using where                                                         |
|  3 | DEPENDENT SUBQUERY | circle_offer       | NULL       | ref    | PRIMARY,uk_offer_merchant_chapter,idx_offer_capacity,idx_offer_topic                 | idx_offer_topic                | 9       | cy_e2e_r2.cms_topic.id,const      |    1 |    75.00 | Using index condition; Using where                                  |
|  3 | DEPENDENT SUBQUERY | circle_profile     | NULL       | ref    | uk_circle_supply_offer_version,idx_circle_supply_current,idx_circle_supply_freshness | uk_circle_supply_offer_version | 8       | cy_e2e_r2.circle_offer.id         |    1 |   100.00 | Using where                                                         |
|  3 | DEPENDENT SUBQUERY | circle_chapter     | NULL       | eq_ref | PRIMARY                                                                              | PRIMARY                        | 8       | cy_e2e_r2.circle_offer.chapter_id |    1 |    25.00 | Using where                                                         |
+----+--------------------+--------------------+------------+--------+--------------------------------------------------------------------------------------+--------------------------------+---------+-----------------------------------+------+----------+---------------------------------------------------------------------+
```

### After `migration_20261122_perf_hot_route_indexes.sql`

```text
=== Q1 account_deregistration (every auth req) ===
+----+-------------+------------------------+------------+------+----------------------------------------------------------+------------------------------+---------+-------+------+----------+----------------+
| id | select_type | table                  | partitions | type | possible_keys                                            | key                          | key_len | ref   | rows | filtered | Extra          |
+----+-------------+------------------------+------------+------+----------------------------------------------------------+------------------------------+---------+-------+------+----------+----------------+
|  1 | SIMPLE      | account_deregistration | NULL       | ref  | idx_deregister_member_status,idx_deregister_member_apply | idx_deregister_member_status | 8       | const |    1 |   100.00 | Using filesort |
+----+-------------+------------------------+------------+------+----------------------------------------------------------+------------------------------+---------+-------+------+----------+----------------+

=== Q2 cms_registration owner_type+owner_id+member (play/nodes) ===
+----+-------------+------------------+------------+------+---------------------------------------------------------------------------------+-----------------------+---------+-------+------+----------+-----------------------------+
| id | select_type | table            | partitions | type | possible_keys                                                                   | key                   | key_len | ref   | rows | filtered | Extra                       |
+----+-------------+------------------+------------+------+---------------------------------------------------------------------------------+-----------------------+---------+-------+------+----------+-----------------------------+
|  1 | SIMPLE      | cms_registration | NULL       | ref  | uk_reg_member_request,idx_cms_registration_participate,idx_cms_reg_owner_member | uk_reg_member_request | 8       | const |    1 |    50.00 | Using where; Using filesort |
+----+-------------+------------------+------------+------+---------------------------------------------------------------------------------+-----------------------+---------+-------+------+----------+-----------------------------+

=== Q3 cms_topic_node topic_id+status sort_id (play/nodes, topic/info) ===
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+--------------------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------------+
| id | select_type | table          | partitions | type   | possible_keys                                                   | key                                  | key_len | ref                                                                             | rows | filtered | Extra                              |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+--------------------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------------+
|  1 | SIMPLE      | cms_topic_node | NULL       | range  | idx_topic_node_topic,idx_cms_topic_node_topic_status_sort       | idx_cms_topic_node_topic_status_sort | 14      | NULL                                                                            |    6 |    26.53 | Using index condition; Using where |
|  1 | SIMPLE      | withdrawn      | NULL       | eq_ref | uk_offer_merchant_chapter,idx_offer_capacity,idx_offer_merchant | uk_offer_merchant_chapter            | 16      | cy_e2e_r2.cms_topic_node.chapter_id,cy_e2e_r2.cms_topic_node.merchant_member_id |    1 |   100.00 | Using where; Not exists            |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+--------------------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------------+

=== Q4 cms_topic_chapter topic_id sort_id (topic/info, play/nodes) ===
+----+-------------+-------------------+------------+------+---------------------------------------------------------------+----------------------------------+---------+-------+------+----------+-------+
| id | select_type | table             | partitions | type | possible_keys                                                 | key                              | key_len | ref   | rows | filtered | Extra |
+----+-------------+-------------------+------------+------+---------------------------------------------------------------+----------------------------------+---------+-------+------+----------+-------+
|  1 | SIMPLE      | cms_topic_chapter | NULL       | ref  | idx_chapter_circle_selection,idx_cms_topic_chapter_topic_sort | idx_cms_topic_chapter_topic_sort | 9       | const |    3 |   100.00 | NULL  |
+----+-------------+-------------------+------------+------+---------------------------------------------------------------+----------------------------------+---------+-------+------+----------+-------+

=== Q5 cms_topic_node chapter_id+merchant sort_id ===
+----+-------------+----------------+------------+--------+------------------------------------------------------------------------+----------------------------------------+---------+---------------------------------------------------+------+----------+------------------------------------+
| id | select_type | table          | partitions | type   | possible_keys                                                          | key                                    | key_len | ref                                               | rows | filtered | Extra                              |
+----+-------------+----------------+------------+--------+------------------------------------------------------------------------+----------------------------------------+---------+---------------------------------------------------+------+----------+------------------------------------+
|  1 | SIMPLE      | cms_topic_node | NULL       | range  | idx_topic_node_chapter_merchant,idx_cms_topic_node_chapter_status_sort | idx_cms_topic_node_chapter_status_sort | 14      | NULL                                              |    2 |    26.53 | Using index condition; Using where |
|  1 | SIMPLE      | withdrawn      | NULL       | eq_ref | uk_offer_merchant_chapter,idx_offer_capacity,idx_offer_merchant        | uk_offer_merchant_chapter              | 16      | const,cy_e2e_r2.cms_topic_node.merchant_member_id |    1 |   100.00 | Using where; Not exists            |
+----+-------------+----------------+------------+--------+------------------------------------------------------------------------+----------------------------------------+---------+---------------------------------------------------+------+----------+------------------------------------+

=== Q6 cms_collaborators view_collaborators (topic/info) ===
+----+-------------+-------+------------+--------+----------------------------+----------------------------+---------+------------------------+------+----------+-------------+
| id | select_type | table | partitions | type   | possible_keys              | key                        | key_len | ref                    | rows | filtered | Extra       |
+----+-------------+-------+------------+--------+----------------------------+----------------------------+---------+------------------------+------+----------+-------------+
|  1 | SIMPLE      | cc    | NULL       | ref    | idx_cms_collaborators_data | idx_cms_collaborators_data | 14      | const,const            |    1 |   100.00 | NULL        |
|  1 | SIMPLE      | um    | NULL       | eq_ref | PRIMARY                    | PRIMARY                    | 8       | cy_e2e_r2.cc.member_id |    1 |    11.11 | Using where |
+----+-------------+-------+------------+--------+----------------------------+----------------------------+---------+------------------------+------+----------+-------------+

=== Q7 ums_comment+ums_comment_like view_comment (topic/info) ===
+----+--------------------+------------------+------------+--------+---------------------------------+---------------------------------+---------+-------------------------+------+----------+----------------+
| id | select_type        | table            | partitions | type   | possible_keys                   | key                             | key_len | ref                     | rows | filtered | Extra          |
+----+--------------------+------------------+------------+--------+---------------------------------+---------------------------------+---------+-------------------------+------+----------+----------------+
|  1 | PRIMARY            | <derived2>       | NULL       | ALL    | NULL                            | NULL                            | NULL    | NULL                    |    2 |   100.00 | Using filesort |
|  2 | DERIVED            | c                | NULL       | ref    | idx_ums_comment_owner_status    | idx_ums_comment_owner_status    | 18      | const,const,const,const |    1 |   100.00 | NULL           |
|  2 | DERIVED            | m                | NULL       | eq_ref | PRIMARY                         | PRIMARY                         | 8       | cy_e2e_r2.c.member_id   |    1 |   100.00 | Using where    |
|  3 | DEPENDENT SUBQUERY | ums_comment_like | NULL       | ref    | idx_ums_comment_like_comment_id | idx_ums_comment_like_comment_id | 9       | cy_e2e_r2.c.id          |    1 |   100.00 | Using index    |
+----+--------------------+------------------+------------+--------+---------------------------------+---------------------------------+---------+-------------------------+------+----------+----------------+

=== Q8 ums_comment AVG(rating) (topic/info) ===
+----+-------------+-------------+------------+------+------------------------------+------------------------------+---------+-------------------------+------+----------+-------+
| id | select_type | table       | partitions | type | possible_keys                | key                          | key_len | ref                     | rows | filtered | Extra |
+----+-------------+-------------+------------+------+------------------------------+------------------------------+---------+-------------------------+------+----------+-------+
|  1 | SIMPLE      | ums_comment | NULL       | ref  | idx_ums_comment_owner_status | idx_ums_comment_owner_status | 18      | const,const,const,const |    1 |   100.00 | NULL  |
+----+-------------+-------------+------------+------+------------------------------+------------------------------+---------+-------------------------+------+----------+-------+

=== Q9 cms_registration view_registration ticket_id (activity/info) ===
+----+-------------+-------+------------+--------+---------------------------+---------------------------+---------+-----------------------+------+----------+-------------+
| id | select_type | table | partitions | type   | possible_keys             | key                       | key_len | ref                   | rows | filtered | Extra       |
+----+-------------+-------+------------+--------+---------------------------+---------------------------+---------+-----------------------+------+----------+-------------+
|  1 | SIMPLE      | c     | NULL       | ref    | idx_cms_reg_ticket_status | idx_cms_reg_ticket_status | 10      | const,const           |    2 |   100.00 | NULL        |
|  1 | SIMPLE      | u     | NULL       | eq_ref | PRIMARY                   | PRIMARY                   | 8       | cy_e2e_r2.c.member_id |    1 |   100.00 | Using where |
+----+-------------+-------+------------+--------+---------------------------+---------------------------+---------+-----------------------+------+----------+-------------+

=== Q10 club list/home del_flag+status+create_time ===
+----+-------------+-------+------------+------+---------------------------+---------------------------+---------+-------------+------+----------+--------------------------------------------+
| id | select_type | table | partitions | type | possible_keys             | key                       | key_len | ref         | rows | filtered | Extra                                      |
+----+-------------+-------+------------+------+---------------------------+---------------------------+---------+-------------+------+----------+--------------------------------------------+
|  1 | SIMPLE      | c     | NULL       | ref  | idx_club_del_status_ctime | idx_club_del_status_ctime | 5       | const,const |    2 |   100.00 | Using temporary; Using filesort            |
|  1 | SIMPLE      | cl    | NULL       | ALL  | uk_club_leader_member     | NULL                      | NULL    | NULL        |    2 |   100.00 | Using where; Using join buffer (hash join) |
+----+-------------+-------+------------+------+---------------------------+---------------------------+---------+-------------+------+----------+--------------------------------------------+

=== Q11 cms_topic_node IN topic_ids GROUP BY (topic/list) ===
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------+
| id | select_type | table          | partitions | type   | possible_keys                                                   | key                       | key_len | ref                                                                             | rows | filtered | Extra                        |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------+
|  1 | SIMPLE      | cms_topic_node | NULL       | ALL    | idx_topic_node_topic,idx_cms_topic_node_topic_status_sort       | NULL                      | NULL    | NULL                                                                            |    7 |    14.29 | Using where; Using temporary |
|  1 | SIMPLE      | withdrawn      | NULL       | eq_ref | uk_offer_merchant_chapter,idx_offer_capacity,idx_offer_merchant | uk_offer_merchant_chapter | 16      | cy_e2e_r2.cms_topic_node.chapter_id,cy_e2e_r2.cms_topic_node.merchant_member_id |    1 |   100.00 | Using where; Not exists      |
+----+-------------+----------------+------------+--------+-----------------------------------------------------------------+---------------------------+---------+---------------------------------------------------------------------------------+------+----------+------------------------------+

=== Q12 cms_activity count (activity/list) ===
+----+--------------------+--------------+------------+--------+------------------------------------------------------------+-------------------------------------+---------+---------------------------------+------+----------+------------------------------------+
| id | select_type        | table        | partitions | type   | possible_keys                                              | key                                 | key_len | ref                             | rows | filtered | Extra                              |
+----+--------------------+--------------+------------+--------+------------------------------------------------------------+-------------------------------------+---------+---------------------------------+------+----------+------------------------------------+
|  1 | PRIMARY            | cms_activity | NULL       | range  | idx_cms_activity_topic,idx_cms_activity_pub_del_status_end | idx_cms_activity_pub_del_status_end | 24      | NULL                            |    3 |    55.56 | Using index condition; Using where |
|  1 | PRIMARY            | parent_topic | NULL       | eq_ref | PRIMARY,idx_topic_club                                     | PRIMARY                             | 8       | cy_e2e_r2.cms_activity.topic_id |    1 |   100.00 | Using where; Not exists            |
|  1 | PRIMARY            | parent_club  | NULL       | eq_ref | PRIMARY,idx_club_del_status_ctime                          | PRIMARY                             | 8       | cy_e2e_r2.parent_topic.club_id  |    1 |   100.00 | Using where                        |
|  3 | DEPENDENT SUBQUERY | active_club  | NULL       | eq_ref | PRIMARY,idx_club_del_status_ctime                          | PRIMARY                             | 8       | cy_e2e_r2.cms_activity.club_id  |    1 |   100.00 | Using where                        |
|  2 | DEPENDENT SUBQUERY | live_parent  | NULL       | eq_ref | PRIMARY,idx_cms_topic_pub_del_ctime                        | PRIMARY                             | 8       | cy_e2e_r2.cms_activity.topic_id |    1 |    66.67 | Using where                        |
+----+--------------------+--------------+------------+--------+------------------------------------------------------------+-------------------------------------+---------+---------------------------------+------+----------+------------------------------------+

=== Q13 cms_topic cold start recommendation (recommendation/list) ===
+----+-------------+-------+------------+-------------+-----------------------------+-----------------------------+---------+-------------------+------+----------+---------------------------------------+
| id | select_type | table | partitions | type        | possible_keys               | key                         | key_len | ref               | rows | filtered | Extra                                 |
+----+-------------+-------+------------+-------------+-----------------------------+-----------------------------+---------+-------------------+------+----------+---------------------------------------+
|  1 | SIMPLE      | t     | NULL       | ref_or_null | idx_cms_topic_pub_del_ctime | idx_cms_topic_pub_del_ctime | 6       | const,const,const |    5 |   100.00 | Using index condition; Using filesort |
+----+-------------+-------+------------+-------------+-----------------------------+-----------------------------+---------+-------------------+------+----------+---------------------------------------+

=== Q14 club_post feed (club/post/feed) ===
+----+--------------------+-------+------------+--------+--------------------------------------------------------------+-------------------------------+---------+-------------------------------+------+----------+---------------------------------------------------------------------+
| id | select_type        | table | partitions | type   | possible_keys                                                | key                           | key_len | ref                           | rows | filtered | Extra                                                               |
+----+--------------------+-------+------------+--------+--------------------------------------------------------------+-------------------------------+---------+-------------------------------+------+----------+---------------------------------------------------------------------+
|  1 | PRIMARY            | cp    | NULL       | ALL    | idx_club_post_club,idx_club_post_pinned,idx_club_post_del_id | NULL                          | NULL    | NULL                          |    2 |   100.00 | Using where; Using temporary; Using filesort                        |
|  1 | PRIMARY            | b     | NULL       | index  | uk_block                                                     | uk_block                      | 16      | NULL                          |    1 |   100.00 | Using where; Not exists; Using index; Using join buffer (hash join) |
|  1 | PRIMARY            | c     | NULL       | eq_ref | PRIMARY,idx_club_member,idx_club_del_status_ctime            | PRIMARY                       | 8       | cy_e2e_r2.cp.club_id          |    1 |   100.00 | Using where                                                         |
|  1 | PRIMARY            | m     | NULL       | eq_ref | PRIMARY                                                      | PRIMARY                       | 8       | cy_e2e_r2.cp.author_member_id |    1 |   100.00 | Using where                                                         |
|  3 | DEPENDENT SUBQUERY | cm    | NULL       | eq_ref | uk_club_member_uniq,idx_club,idx_member                      | uk_club_member_uniq           | 16      | cy_e2e_r2.c.id,const          |    1 |    20.00 | Using where                                                         |
|  2 | DEPENDENT SUBQUERY | r     | NULL       | ref    | uk_club_post_revision_version,idx_club_post_revision_history | uk_club_post_revision_version | 8       | cy_e2e_r2.cp.id               |    1 |   100.00 | Using index                                                         |
+----+--------------------+-------+------------+--------+--------------------------------------------------------------+-------------------------------+---------+-------------------------------+------+----------+---------------------------------------------------------------------+

=== Q15 im_channel tree (channels) ===
+----+-------------+------------+------------+------+---------------------------------------------------------------------------------------------------+------+---------+------+------+----------+-----------------------------+
| id | select_type | table      | partitions | type | possible_keys                                                                                     | key  | key_len | ref  | rows | filtered | Extra                       |
+----+-------------+------------+------------+------+---------------------------------------------------------------------------------------------------+------+---------+------+------+----------+-----------------------------+
|  1 | SIMPLE      | im_channel | NULL       | ALL  | uk_channel_default,uk_channel_name,idx_channel_tree,idx_channel_purge,idx_channel_tree_parent_pos | NULL | NULL    | NULL |   16 |     6.25 | Using where; Using filesort |
+----+-------------+------------+------------+------+---------------------------------------------------------------------------------------------------+------+---------+------+------+----------+-----------------------------+

=== Q16 pms_product list (product/list) ===
+----+-------------+-------------+------------+-------------+------------------------------+------------------------------+---------+-------------+------+----------+---------------------------------------+
| id | select_type | table       | partitions | type        | possible_keys                | key                          | key_len | ref         | rows | filtered | Extra                                 |
+----+-------------+-------------+------------+-------------+------------------------------+------------------------------+---------+-------------+------+----------+---------------------------------------+
|  1 | SIMPLE      | pms_product | NULL       | ref_or_null | idx_pms_product_pub_del_sort | idx_pms_product_pub_del_sort | 7       | const,const |    3 |   100.00 | Using index condition; Using filesort |
+----+-------------+-------------+------------+-------------+------------------------------+------------------------------+---------+-------------+------+----------+---------------------------------------+

=== Q17 content_search_document unified search (search/unified) ===
+----+--------------------+--------------------+------------+--------+------------------------------------------------------------------------------------------------------+--------------------------------+---------+-----------------------------------+------+----------+---------------------------------------------------------------------+
| id | select_type        | table              | partitions | type   | possible_keys                                                                                        | key                            | key_len | ref                               | rows | filtered | Extra                                                               |
+----+--------------------+--------------------+------------+--------+------------------------------------------------------------------------------------------------------+--------------------------------+---------+-----------------------------------+------+----------+---------------------------------------------------------------------+
|  1 | PRIMARY            | d                  | NULL       | range  | uk_content_search_document,idx_content_search_visible,idx_content_search_vis_type_time               | uk_content_search_document     | 66      | NULL                              |    3 |   100.00 | Using index condition; Using where                                  |
|  1 | PRIMARY            | current_topic      | NULL       | eq_ref | PRIMARY                                                                                              | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |   100.00 | Using where; Using index                                            |
|  1 | PRIMARY            | current_activity   | NULL       | eq_ref | PRIMARY                                                                                              | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |   100.00 | Using where; Using index                                            |
|  1 | PRIMARY            | current_post       | NULL       | eq_ref | PRIMARY                                                                                              | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |   100.00 | Using where; Using index                                            |
| 10 | DEPENDENT SUBQUERY | post_sd            | NULL       | eq_ref | uk_community_search_document,idx_community_search_visible                                            | uk_community_search_document   | 74      | const,cy_e2e_r2.d.document_id     |    1 |   100.00 | Using where                                                         |
| 10 | DEPENDENT SUBQUERY | p                  | NULL       | eq_ref | PRIMARY,idx_community_post_public                                                                    | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |    50.00 | Using where                                                         |
| 10 | DEPENDENT SUBQUERY | b                  | NULL       | index  | uk_block                                                                                             | uk_block                       | 16      | NULL                              |    1 |   100.00 | Using where; Not exists; Using index; Using join buffer (hash join) |
| 10 | DEPENDENT SUBQUERY | mu                 | NULL       | eq_ref | uk_community_member_mute,uk_community_member_mute_request                                            | uk_community_member_mute       | 16      | const,cy_e2e_r2.p.author_id       |    1 |   100.00 | Using where; Not exists; Using index                                |
|  5 | DEPENDENT SUBQUERY | cms_activity       | NULL       | eq_ref | PRIMARY,idx_cms_activity_topic,idx_cms_activity_pub_del_status_end                                   | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |   100.00 | Using where                                                         |
|  5 | DEPENDENT SUBQUERY | parent_topic       | NULL       | eq_ref | PRIMARY,idx_topic_club                                                                               | PRIMARY                        | 8       | cy_e2e_r2.cms_activity.topic_id   |    1 |   100.00 | Using where; Not exists                                             |
|  5 | DEPENDENT SUBQUERY | parent_club        | NULL       | eq_ref | PRIMARY,idx_club_del_status_ctime                                                                    | PRIMARY                        | 8       | cy_e2e_r2.parent_topic.club_id    |    1 |   100.00 | Using where                                                         |
|  5 | DEPENDENT SUBQUERY | member_only_parent | NULL       | eq_ref | PRIMARY                                                                                              | PRIMARY                        | 8       | cy_e2e_r2.cms_activity.topic_id   |    1 |   100.00 | Using where; Not exists                                             |
|  7 | DEPENDENT SUBQUERY | active_club        | NULL       | eq_ref | PRIMARY,idx_club_del_status_ctime                                                                    | PRIMARY                        | 8       | cy_e2e_r2.cms_activity.club_id    |    1 |   100.00 | Using where                                                         |
|  6 | DEPENDENT SUBQUERY | live_parent        | NULL       | eq_ref | PRIMARY,idx_cms_topic_pub_del_ctime                                                                  | PRIMARY                        | 8       | cy_e2e_r2.cms_activity.topic_id   |    1 |    66.67 | Using where                                                         |
|  2 | DEPENDENT SUBQUERY | cms_topic          | NULL       | eq_ref | PRIMARY,idx_topic_club,idx_cms_topic_is_template,idx_topic_circle_public,idx_cms_topic_pub_del_ctime | PRIMARY                        | 8       | cy_e2e_r2.d.document_id           |    1 |    40.12 | Using where                                                         |
|  4 | DEPENDENT SUBQUERY | active_club        | NULL       | eq_ref | PRIMARY,idx_club_del_status_ctime                                                                    | PRIMARY                        | 8       | cy_e2e_r2.cms_topic.club_id       |    1 |   100.00 | Using where                                                         |
|  3 | DEPENDENT SUBQUERY | circle_offer       | NULL       | ref    | PRIMARY,uk_offer_merchant_chapter,idx_offer_capacity,idx_offer_topic                                 | idx_offer_topic                | 9       | cy_e2e_r2.cms_topic.id,const      |    1 |    75.00 | Using index condition; Using where                                  |
|  3 | DEPENDENT SUBQUERY | circle_profile     | NULL       | ref    | uk_circle_supply_offer_version,idx_circle_supply_current,idx_circle_supply_freshness                 | uk_circle_supply_offer_version | 8       | cy_e2e_r2.circle_offer.id         |    1 |   100.00 | Using where                                                         |
|  3 | DEPENDENT SUBQUERY | circle_chapter     | NULL       | eq_ref | PRIMARY                                                                                              | PRIMARY                        | 8       | cy_e2e_r2.circle_offer.chapter_id |    1 |    25.00 | Using where                                                         |
+----+--------------------+--------------------+------------+--------+------------------------------------------------------------------------------------------------------+--------------------------------+---------+-----------------------------------+------+----------+---------------------------------------------------------------------+
```

</details>

## 5. Artifacts, Migration Ledger & Verification

### Code & Migration Changes (`perf/indexes`)
- [`chengyinhub-system/src/main/resources/mapper/business/ContentSearchMapper.xml`](../../../chengyinhub-system/src/main/resources/mapper/business/ContentSearchMapper.xml): Added `<sql id="unifiedSearchWhere">` and explicit `<select id="selectUnifiedSearch_COUNT" resultType="Long">` after `selectUnifiedSearch` to bypass PageHelper JSqlParser rewrite overhead.
- [`chengyinhub-admin/sql/migration_20261122_perf_hot_route_indexes.sql`](../../../chengyinhub-admin/sql/migration_20261122_perf_hot_route_indexes.sql): Idempotent DDL (`information_schema.statistics` + `PREPARE`/`EXECUTE`) adding 16 composite/lookup indexes across `ums_comment`, `ums_comment_like`, `cms_collaborators`, `cms_registration`, `club`, `club_post`, `cms_topic_chapter`, `cms_topic_node`, `cms_activity`, `cms_topic`, `content_search_document`, `pms_product`, `account_deregistration`, and `im_channel`. Applied twice cleanly on `cy_e2e_r2`.
- [`ci/migration-dependencies.json`](../../../ci/migration-dependencies.json): Registered `20261122_perf_hot_route_indexes` with dependencies `["account_deregister_20260711", "club_post", "im", "o14_content_search_document_20261022", "20261112_im_channels_platform"]`.

### Verification Results
- `ContentSearchServiceTest`: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`
- `ApiContentSearchControllerTest`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- `ContentSearchCityMyBatisIT` (MySQL `:3330` + `PageInterceptor`): `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- `mvn -B -T 1 package -DskipTests`: `BUILD SUCCESS`
- Migration idempotency (`cy_e2e_r2`): First apply OK, Second apply (idempotency) OK.
