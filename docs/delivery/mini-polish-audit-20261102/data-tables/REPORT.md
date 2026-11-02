# Data-tables lane: database facts behind the mini program (2026-11-02)

Analysis only. Base: branch `claude/mini-polish-audit-20261102` (master a1d30fb). No database was contacted and no
code or SQL file was changed. Any DDL below is **text only, awaiting owner approval**. It must go through the
`chengyin-db-migrate` read-only precheck before anything touches the production database.

## Summary
1. The biggest wins are in the code, not the indexes. Four load paths fan out per row (N+1): theme detail
   `/api/topic/info-to-user` (chapter, then node, then merchant), merchant home `/api/merchant/upcoming-runs`, ledger
   `/api/merchant/finance/redemptions`, and roam `/api/medal/wall`. All four grow with history, not with page size.
2. Pages refetch everything on every `onShow`, with no cache. Merchant home sends 14 calls. Each one re-runs the
   `MerchantAccessServiceImpl.resolve` access check (2–5 queries). Roam sends 5 calls, play re-runs `/api/play/nodes`, and
   player home calls `/api/user/info` (about 14 queries) twice per show.
3. Missing indexes on hot paths: `cms_topic_node.topic_id`, `cms_registration_merchant.member_id` and `topic_id`/`node_id`,
   `cms_registration_scan` (only a primary key), `ums_comment(owner_type, owner_id)`, `ums_comment_like.comment_id`,
   `oms_order_item.order_id`, `oms_order.mms_merchant_id`, `ums_member_follow.follow_member_id`.
4. Oversized reads: `selectCmsTopicVo` reads about 10 large JSON columns for every topic list row. Theme detail asks
   for `like_list` with size 200 just to learn whether one topic is liked. Several merchant lists have no LIMIT and are
   paged in Java.
5. Tidying: 15 manual `*_bak_*` tables are still undropped, `pay_transaction` has no references, `member_badge` was
   replaced by `player_badge`, and a decommission DROP for `celebration_board` (3 tables) is still pending. None of
   these is visible to users.
6. Row counts are unknown. The 2026-08-18 snapshot's AUTO_INCREMENT values suggest that many tables were still small
   then (for example `oms_order` 154 and `cms_topic_node` 71). So the indexes matter as data grows, and the N+1 and
   refetch fixes matter now.

## Assumptions and method
- "Home" means `pages/index/index` (feed) plus `pages/roam/index` (map). "Theme detail" means `pages/topic/index/index`,
  "play" means `pages/play/index`, "merchant home" means `pages/merchant/index/index`, "ledger" means
  `pages/merchant/ledger/index`, and "customers" means `pages/merchant/customer/index` and its `detail`.
- Index truth comes from `sql/base_schema_20260818.sql` (a production schema-only dump, MySQL 8.0.45) unioned with all
  615 `chengyinhub-admin/sql/*.sql` files. Migrations dated after 2026-08-18 may or may not be applied in production;
  this audit cannot tell. A missing index here means that no repository file creates it.
- AUTO_INCREMENT is the next id as of 2026-08-18. It is **not** a row count, because ids jump (for example `cms_topic`
  990013 comes from seeded offsets). Use it only as a rough size hint.
- Evidence tag: `code` means I read it directly. `inferred` means it is reasoned from code, not observed.
- The mapper XML lives in `chengyinhub-system/src/main/resources/mapper/business/` (written as `XML/` below), and
  the API controllers live in `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/` (written as
  `Ctl/`).

## 1. Tables behind the most-used pages

| Page | Load-time endpoints (xcx file:line) | Main tables |
|---|---|---|
| Home feed `pages/index/index.js` | `/api/user/info` :1033 (and :568 on onShow), `/api/topic/list` :727/:1232, `/api/activity/list` :733/:760/:1326, `/api/compliance/consents/latest` :1489, `/api/map/reverse-geocode` :1573 | cms_topic, cms_activity, club/club_member, cms_registration, merchant_chapter_offer, ums_member, ums_member_follow, cms_topic_like (and other like tables), ums_member_balance_detail, player_growth |
| Roam `pages/roam/index.js` | `/api/roam/entry/nearby` + `/api/team/nearby` :5822/:5829, `/api/roam/passport/summary` :739, `/api/roam/badge/shop-streak` :4713, `/api/official/active-for-roam` :816, `/api/medal/wall` :787 | cms_activity, cms_topic, cms_topic_node, cms_registration, play team tables, roam_session, official_event (+signup), player_node_progress, cms_member_template, player_badge |
| Theme detail `pages/topic/index/index.js` | `/api/topic/info-to-user` :2056, `/api/user/info` :1571, `/api/team/nearby` :668, `/api/play/setup` :685, `/api/topic/like_list` :615, `/api/ai/npc/profile` ×≤4 :595 | cms_topic, cms_topic_chapter, cms_topic_node, cms_member_template, cms_registration_merchant, mms_merchant, sms_coupon, ums_comment (via view_comment), view_registration, oms_ticket, cms_topic_like |
| Play `pages/play/index.js` | `/api/play/nodes` :4141, then casebook, run-session, character, orient scoring, game session view, team-progress (polled), `/api/play/theme/state` (10–30 s timer) | cms_topic_node, player_node_progress, cms_topic, topic_route_session, npc_profile, cms_member_template, sms_coupon, cms_topic_chapter, game_runtime_session, ums_member_points_detail |
| Merchant home `pages/merchant/index/index.js` | `access/me` :791, then up to 13 in parallel (:537–595): merchant/info, ops/prerequisites, user/info, game entries, upcoming-runs, dashboard, todo-summary, im/conversations, coop/list, events, registration/merchant/list, chapter-application/mine, project/my | mms_merchant, ums_member, merchant_operator, cms_registration_merchant, cms_registration, cms_registration_scan, cms_topic, cms_topic_node, cms_activity, oms_ticket, oms_order, ums_member_balance_detail, refund_application, coop_invite, im_conversation(_member)/im_message, game_station_run, merchant_chapter_application, tms_browse_record |
| Ledger `pages/merchant/ledger/index.js` | `access/me` :185; redemptions :284 + 3× aftercare/list :312–314; settlement: overview :437, settlement-entries :439, public-transfer-batches :443; messages: events :258 | redemption, merchant_payable, merchant_payable_batch, ums_member_balance_detail, coop_settlement, merchant_clawback_application, refund_application, merchant_aftercare_response |
| Customers `pages/merchant/customer/index.js` | `access/me` :182, `crm/customers/list` :450, `crm/segments` :753; detail `crm/customers/{id}/detail` (detail/index.js:95) | cms_registration ⋈ ums_member, cms_topic, cms_registration_merchant, cms_activity, coop_invite, play_team*, player_activity_role, merchant_customer_note, merchant_crm_segment, merchant_customer_* |

## 2. Queries whose WHERE / JOIN / ORDER BY columns have no index

| ID | table or endpoint | file:line | now | problem | smallest change | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|
| IX-01 | cms_topic_node / `info-to-user`, `play/nodes`, medal wall | XML/CmsTopicNodeMapper.xml:43 (WHERE `topic_id`, `chapter_id`, …); schema `sql/base_schema_20260818.sql:1607-1608` | Indexes: PK, (chapter_id, merchant_member_id), unique checkin_code | Theme detail and play look up nodes by `topic_id`, which has no index, so every page open scans the whole table | Text only, awaiting owner approval: `ALTER TABLE cms_topic_node ADD INDEX idx_topic_node_topic (topic_id, sort_id);` | S | Low (online INPLACE add) | P1 | code |
| IX-02 | cms_registration_merchant / `upcoming-runs`, `info-to-user`, `todo-summary`, `registration/merchant/list` | XML/CmsRegistrationMerchantMapper.xml:56 (WHERE `member_id`, `topic_id`, `node_id`, …) | Only PK and unique `win_key` | Merchant-home and theme-detail lookups by `member_id` and by `topic_id`/`node_id` have no supporting index | Text only, awaiting owner approval: `ADD INDEX idx_crm_member (member_id, audit_status)`, `ADD INDEX idx_crm_topic_node (topic_id, node_id)` | S | Low | P1 | code |
| IX-03 | cms_registration_scan / `events`, `todo-summary` | XML/CmsRegistrationScanMapper.xml:42 (WHERE `member_id`, `registration_type`, `status`, `registration_id`) | Only PK. `member_id` and `registration_id` are `int` (schema :1217-1218) while ids elsewhere are bigint | Every merchant-home load filters this table by merchant with no index | Text only, awaiting owner approval: `ADD INDEX idx_scan_member (member_id, registration_type, status)`. Leave the int type alone (out of scope) | S | Low | P1 | code |
| IX-04 | ums_comment via `view_comment` / `info-to-user` | `sql/base_schema_20260818.sql:6446-6460` (view); XML/ViewCommentMapper.xml:33 | ums_comment: no secondary index. ums_comment_like: PK only | Theme-detail comments filter on `owner_type`/`owner_id`, and the view runs a per-comment `like_count` subquery on `ums_comment_like.comment_id`. Neither is indexed | Text only, awaiting owner approval: `ums_comment ADD INDEX (owner_type, owner_id, status, del_flag)`; `ums_comment_like ADD INDEX (comment_id)` | S | Low | P1 | code |
| IX-05 | ums_member_follow / `user/info` fans count | XML/UmsMemberFollowMapper.xml:25 (`follow_member_id = ?`) | Unique (member_id, follow_member_id) only | Counting followers by `follow_member_id` cannot use the leftmost prefix | Text only, awaiting owner approval: `ADD INDEX idx_follow_target (follow_member_id)` | S | Low | P2 | code |
| IX-06 | oms_order / merchant dashboard and todo order counts | XML/OmsOrder (selectOmsOrderList :67); Ctl/ApiMerchantController.java:1358, :1489 | Composite (member_id, mms_merchant_id, biz_type, commerce_request_id), plus (biz_type, order_sn) and phone hash | A filter on merchant (`mms_merchant_id`) without `member_id` cannot use the composite | Text only, awaiting owner approval: `ADD INDEX idx_order_merchant (mms_merchant_id, status, create_time)`. Better still, see N-08 (COUNT instead of a full load) | S | Low | P2 | code (index); inferred (exact WHERE mix) |
| IX-07 | oms_order_item | `sql/base_schema_20260818.sql:3662` | Only PK. `order_id` has no index | Any order-detail item lookup by `order_id` scans the table | Text only, awaiting owner approval: `ADD INDEX idx_item_order (order_id)` | S | Low | P2 | code |
| IX-08 | game_station_run / `game/session/merchant/entries` | XML/GameSessionRuntimeMapper.xml:368 (WHERE `r.merchant_member_id`) | Indexes lead with `game_session_id` or `activity_id` | A filter on merchant only does not match the leftmost prefix | Text only, awaiting owner approval: `ADD INDEX idx_gsr_merchant (merchant_member_id)` | S | Low | P2 | code |
| IX-09 | cms_topic / `user/info` topic count, `project/my` | XML/CmsTopicMapper.xml:304, :1210 (WHERE `member_id`) | No `member_id` index (schema :1390-1396) | "My topics" and the per-user topic count scan cms_topic | Text only, awaiting owner approval: `ADD INDEX idx_topic_member (member_id, del_flag)` | S | Low | P2 | code |
| IX-10 | cms_topic / `topic/list` | XML/CmsTopicMapper.xml:761-807 (status, user_status, del_flag, is_template, is_recommend; ORDER BY id desc; `FIND_IN_SET(category_ids)`) | Single-column is_draft / is_template indexes | The public list filter has no composite index. `FIND_IN_SET` can never use an index | Defer until rows grow. Candidate (awaiting owner approval): `(status, user_status, del_flag, id)` | S | Low | P2 | code; benefit inferred |
| IX-11 | cms_activity / `activity/list`, `roam/entry/nearby` | XML/CmsActivityMapper.xml:168, :217; XML/RoamHangoutMapper.xml:122-123 | Indexes only on topic_id and city_code | The status/date filter is unindexed, and the map box uses `cast(latitude as decimal)` on string columns, which no plain index can serve | Index text awaiting owner approval: `(status, publish_status, del_flag, start_date)`. The geo cast needs a typed column, which is a redesign (see section 5) | M | Medium | P2 | code; benefit inferred |
| IX-12 | official_event / `active-for-roam` | XML/OfficialEventMapper.xml:69 (`status >= 1 and <> 9`, `city LIKE`, ORDER BY sort, id) | (city, status), (status) | A leading-wildcard LIKE cannot use the index. The table is probably small | Leave as is | – | – | P2 | code; size inferred |

Checked and **adequately indexed** for their hot filters: `player_node_progress` (member/topic/node composites),
`redemption (merchant_id, create_time)`, `merchant_payable (merchant_member_id, create_time)`,
`merchant_payable_batch (merchant_member_id, period_ym)`, `ums_member_balance_detail (member_id, event_type, change_type, create_time)`,
`im_conversation_member (member_id, deleted)`, `coop_invite (from/to type, id, status)`, `merchant_chapter_application (merchant_id, status)`,
`refund_application (merchant_id, status)`, `merchant_operator (active_operator_member_id)`, `mms_merchant (member_id)`,
`cms_topic_chapter (topic_id, …)`, `roam_session (member_id)`, `cms_registration (owner_id, participate_date)` (code).

## 3. N+1, repeated requests, oversized responses (these decide how fast a page appears)

| ID | table or endpoint | file:line | now | problem | smallest change | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|
| N-01 | `/api/topic/info-to-user` (theme detail) | Ctl/ApiTopicController.java:1952-2014 (node list per chapter :1958; template per node :1968; merchant list per node :1978; merchant :1982, template :1985, coupon :1990 per merchant) | Three nested loops of single-row queries | Query count ≈ chapters × nodes × merchants. Theme detail is the most-opened conversion page | Collect node ids, template ids and merchant ids, then run one `IN (...)` query each and join in memory | M | Medium (anti-cheat `TemplateSecrets.strip` must stay applied) | P0 | code |
| N-02 | `/api/topic/info-to-user` comments and counts | Ctl/ApiTopicController.java:2057, :2072, :2066, :2047, :2081-2087 | Loads all comments to count them and keep 5; loads all registration-merchant rows to count them; runs a registration summary per ticket | Payload and query time grow with comment and merchant history | `COUNT(*)` plus `LIMIT 5`; one batched summary for all ticket ids | S | Low | P0 | code |
| N-03 | `/api/merchant/upcoming-runs` (merchant home) | chengyinhub-system/.../MerchantFulfillmentReadServiceImpl.java:64 → buildRunsForTopic :82/:87/:95/:113/:117/:129; club sessions :178/:193/:202 | For every won row in the merchant's whole history: topic (full JSON row), club, node list, activity list, tickets and paid counts | Unbounded, with no dedup by topic. Ended topics are dropped only after their queries have run | Filter to non-ended topics in SQL first, dedup topic ids, and batch the follow-ups | M | Medium | P0 | code |
| N-04 | `/api/merchant/finance/redemptions` (ledger) | chengyinhub-system/.../MerchantFinanceQueryService.java:125 → toRedemptionView :355/:357/:361; paging at :142 | About 3 queries per lifetime redemption, then a 20-row slice is taken in Java | Cost grows with lifetime volume, not page size | Page in SQL (`LIMIT`, using the existing (merchant_id, create_time) index) and batch the topic/chapter/offer lookups by id set | M | Medium (money view; keep totals identical) | P0 | code |
| N-05 | `/api/medal/wall` (roam, every onShow) | Ctl/ApiMedalController.java:65-70; roam/index.js:787-792 | Per progress row: node by id plus template by id. Roam uses only the newest badge image | Grows with every node a player has ever cleared | A dedicated "latest badge" query (one row) for roam; batch by id set for the full wall | S | Low | P1 | code |
| N-06 | `/api/official/active-for-roam` | chengyinhub-system/.../OfficialEventServiceImpl.java:177-186 (`collective` :402, :410-412); XML/OfficialEventMapper.xml:48-51, :69 | Per public event: a count plus a signup lookup. Reads `content_json`/`reward_json`/`story`; no LIMIT | Per-event queries plus big columns on every roam show | Select only list columns; batch signups by event-id set | S | Low | P1 | code |
| N-07 | `/api/play/nodes` | PlayEntryApplication.java:72, :177-239; PlayNodeQueryApplication :282/:313/:398/:518/:608/:613; TopicRouteRuntimeServiceImpl.java:3130 | Per node: template, merchant override, coupon, NPC (free mode), runtime config (re-parses `config_snapshot_json`). Full cms_topic row read 3 times | Play opens slower as themes get more nodes, and this repeats on every onShow (R-04) | Read the topic once per request; batch templates and coupons by id set | M | Medium (game logic; anti-cheat) | P1 | code; JSON cost inferred |
| N-08 | merchant `dashboard` and `todo-summary` order counts | Ctl/ApiMerchantController.java:1358, :1489; XML/OmsOrderMapper.xml:27-31 (PII TypeHandler) | Loads full `oms_order` rows and decrypts PII just to call `.size()` | Wasted decryption and transfer | A `COUNT(*)` mapper statement | S | Low | P1 | code |
| N-09 | merchant `events` | Ctl/ApiMerchantController.java:1654, :1684, :1698, :1701-1707; XML/CmsRegistrationScanMapper.xml:42 | Reads all income rows and all scans, sorts in Java, keeps 10, then looks up a registration per row | Full history read for 10 rows | `ORDER BY create_time DESC LIMIT 10` on each source (needs IX-03) | S | Low | P1 | code |
| N-10 | `/api/merchant/access/me` + `resolve()` on every merchant endpoint | chengyinhub-system/.../MerchantAccessServiceImpl.java:39-78 (MmsMerchant:88, UmsMember:87, MerchantOperator:28) | 2–5 queries per request; merchant home makes ~14 requests, so about 30–70 access queries per show | Same answer recomputed many times in one burst | Request-scoped or short-TTL (a few seconds) cache keyed by member id, invalidated on role change | M | Medium (touches access/RBAC, an "Ask first" item in AGENTS.md) | P1 | code; totals inferred |
| N-11 | `/api/user/info` | Ctl/ApiUmsMemberController.java:530, :563-640 | About 12–14 sequential queries (counts, likes, level, a self-follow check at :595) | Used by home twice per show, by merchant home only for `role` (index.js:948-951), and by theme detail only for name and phone | Callers that need only role/name/phone use a light endpoint or the cached login profile | S–M | Low | P1 | code |
| N-12 | `/api/topic/like_list` size 200 on theme detail | topic/index/index.js:615-620; Ctl/ApiTopicController.java:2423 | Fetches up to 200 full liked topics (each with 6 decoration queries) to test one id | Oversized for a yes/no | Return `isLike` in info-to-user, or a `like/exists?topicId=` check on the existing `(member_id, data_id)` unique key | S | Low | P1 | code |
| N-13 | `selectCmsTopicVo` used by lists | XML/CmsTopicMapper.xml:128 (list :807, detail :860) | Every list row reads about 10 large JSON columns (route_graph_json, game_module_config_json, journey_* …) | The DB reads and the server hydrates columns the list VO later drops | Add a slim `selectCmsTopicListVo` column list for `/topic/list` and `like_list` | S | Low | P1 | code |
| N-14 | `project/my`, `registration/merchant/list`, `settlement-entries`, `public-transfer-batches`, `im/conversations`, merchant entries, `coop/list` | ApiProjectController ~:231-310; ApiRegistrationMerchantController.java:136-138; MerchantFinanceQueryService.java:165-169, :197-202; XML/ImConversationMember.xml:89; XML/GameSessionRuntime.xml:368; XML/CoopInvite.xml:93 | No SQL LIMIT; paged or sliced in Java, or not at all. registration/merchant/list sends big text columns while home shows ≤2 rows (index.js:1037, :1062) | Payloads and work grow with history | Use the existing `startPage()` / `LIMIT`. For home previews, pass `size=2` | S each | Low | P1 | code |
| N-15 | `crm/customers/list` | XML/MerchantCrmAppMapper.xml:106-128 (scope via EXISTS), :145-150, :212/:220, :282, :328, :348 | Three GROUP BY `r.member_id` aggregations per request, scoped only through EXISTS, plus correlated team/role subqueries | Probably scans paid registrations platform-wide on each filter change | Pre-filter the merchant's owner ids first (`owner_id IN`, which uses the (owner_id, participate_date) index), then aggregate | M | Medium | P1 | code; scan inferred |
| N-16 | ImServiceImpl conversation loop | ImServiceImpl.java:119, ~:253, ~:1151-1156 | Per conversation: team access check; route-card topic detail | Grows with conversation count | Batch team ids; LIMIT the list | S | Low | P2 | code |
| N-17 | `coop/list` decoration | ApiCoopController.java:1469, :1493, :1522, :1534, :1617, :1633, :1643 | Per invite: partner club/merchant, topic; per game: full invite list counted in Java | N+1; home uses only `received` (index.js:524-526) | Batch partners by id; add a `received`-only mode | S | Low | P2 | code |

Repeated requests on page load (client side):

| ID | page | file:line | now | problem | smallest change | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|
| R-01 | Merchant home | pages/merchant/index/index.js:702-752 (onShow), :537-595 | All ~14 calls are re-sent on every onShow, including returns from ledger and customers | Each return redraws from the network | Skip the reload when the last load is under ~30 s old and nothing is marked dirty; keep pull-to-refresh forcing it | S | Low | P0 | code |
| R-02 | Player home | pages/index/index.js:568, :590 (onShow), :720 | `/api/user/info` twice per onShow (role check, then getUserData 100 ms later); the consent → location → reverse-geocode chain re-runs after hide (:536, :591) | Two copies of a ~14-query call | Use the first response for both purposes; re-geocode only after a TTL | S | Low | P0 | code |
| R-03 | Roam | pages/roam/index.js:939 (onLoad) and :1077 (onShow); :1041 | `passport/summary` sent twice on first open (first result discarded); every onShow refetches summary, active-for-roam, medal/wall, entry/nearby, team/nearby | Duplicate first-open call plus a full refetch | Skip the first onShow after onLoad (the pattern ledger already uses at ledger/index.js:99); TTL later shows | S | Low | P1 | code |
| R-04 | Play | pages/play/index.js:730-747 | Every onShow (for example back from the camera) re-runs `/api/play/nodes` and all of its follow-ups | Heavy refetch exactly when the player returns to play | Refetch only node progress, or mark dirty when the sub-page reports a change | S–M | Medium (state freshness after check-in) | P1 | code |
| R-05 | Player home | pages/index/index.js:760 vs :1326; Ctl/ApiActivityController.java:182; XML/CmsActivityMapper.xml:217 | The nearby (size 4) and bottom activity lists run the same SQL when no coordinates are sent. The `status` the client sends is not a controller parameter | A duplicate query | Reuse the first 4 rows of the bottom list when there are no coordinates | S | Low | P2 | code |
| R-06 | Ledger | ledger/index.js:312-314, :343-345 | 3× `aftercare/list` (one per bucket, each with count + list + access check); `access/me` re-sent on every view switch | 3× requests where one would do | One call returning the three bucket counts and the active bucket's list | S | Low | P2 | code |
| R-07 | Merchant home | index.js:897, :922 plus access/me | `mms_merchant` is read 3 times on one load (access/me, merchant/info, ops/prerequisites) | Redundant | Fold `name`/`businessStatus` into access/me's existing response | S | Low | P2 | code |

## 4. Duplicate or apparently unused tables and columns

| ID | table | file:line | now | problem | smallest change | effort | risk | priority | evidence / confidence |
|---|---|---|---|---|---|---|---|---|---|
| U-01 | 15 manual backups: `_bak_20260705_member_capability`, `_bak_20260708_cms_topic`, `_bak_20260708_member_capability`, `club_member_bak_20260809`, `cms_topic_bak_20260811_w`, `coop_order_bak_20260811_w`, `coop_settlement_bak_20260811_w`, `merchant_payable_bak_20260811_w`, `settlement_detail_bak_20260811_w`, `settlement_rule_bak_20260805`, `sys_menu_bak_p2_4`, `sys_menu_bak_t1_20260726`, `sys_role_menu_bak_p2_4`, `sys_role_menu_bak_t1_20260726`, `ums_member_balance_detail_bak_20260809` | sql/base_schema_20260818.sql; scope note in chengyinhub-admin/sql/migration_20261013_drop_member_pii_backup_tables.sql:15-17 | Present in the snapshot; no Java or XML reference; no repository DROP | Disk and backup clutter only. The 20261013 migration explicitly left these "for the business to decide" | Owner decides; export offline per retention policy, then DROP (text only, awaiting owner approval) | S | Low (financial backups: keep an offline export) | P2 (tidy) | code; high confidence they are unused by the app |
| U-02 | `pay_transaction` | sql/base_schema_20260818.sql:3723-3728 | No reference anywhere in main code, tests or migrations | Looks orphaned (payment facts live in other tables) | Confirm row count in a precheck; if 0, propose DROP | S | Medium (payment-adjacent; it may hold history) | P2 (tidy) | code; medium confidence |
| U-03 | `member_badge` (duplicate of `player_badge`) | chengyinhub-admin/sql/migration_unify_player_badge_source_20260806.sql:2-3 | "新 jar 不再读写 member_badge… 保留作可回查旧表" (kept for rollback) | Duplicate source of badges; the only remaining reference is a comment at subpackageP3/pages/growthcenter/index/index.js:23 | Once rollback to the pre-0806 jar is ruled out, archive then drop | S | Low | P2 (tidy) | code; high confidence |
| U-04 | `celebration_board`, `_slot`, `_member` | chengyinhub-admin/sql/migration_20260829_celebration_board_decommission.sql:18-19 | "DELETE 段已于 2026-08-29 在生产执行… DROP 段尚未执行" (as written in the file) | Decommissioned feature; tables recorded as 0 rows; no code reference | Run the existing DROP section after its precheck (no new SQL needed) | S | Low | P2 (tidy) | code; high confidence |
| U-05 | `growth_level_config` | chengyinhub-admin/sql/migration_m7_xp_budget.sql:14 | Created and seeded; no reference in main Java or XML | Probably superseded (the level config is read elsewhere) | Confirm, then drop | S | Low | P2 (tidy) | code; medium confidence |
| U-06 | `explore_bundle_refund_hold` | chengyinhub-admin/sql/migration_doc2_f3_refund_hold_20261026.sql:2 | New table (2026-10-26); no Java or XML reference yet | Not dead: the header says "no feature enablement", so it is a staged schema for unshipped code | Keep; noted so nobody deletes it | – | – | – | code; high confidence it is staged, not dead |
| U-07 | `npc_route_egg` | chengyinhub-admin/sql/migration_x11_decommission_route_eggs_20260907.sql:12 | In the 0818 snapshot; DROP already written | Nothing new; the DROP is pending or done in production (unknown) | None | – | – | – | code |
| U-08 | `ums_member.freeze_end_time` | sql/base_schema_20260818.sql:5636 | No reference in main Java or XML (only in an IT fixture) | Unused column | Leave it (dropping a column on the member table is not worth the risk) | – | – | P2 (tidy) | code; high confidence it is unused |
| U-09 | `ums_member.user_type` vs `role` / `member_capability` | AGENTS.md ("user_type → RBAC role 迁移收尾") | Two sources of identity during the migration | Known, intentional transition; not for this lane | None here | – | – | – | code |
| U-10 | Quartz `qrtz_*` (11 tables) and `cy_migration_history` | – | No Java/XML references | False positives: Quartz uses them by prefix, and the migration runner and precheck scripts use `cy_migration_history` | Keep | – | – | – | code |

Column check: on the hot tables (`cms_topic` 83 columns, `cms_activity` 32, `mms_merchant` 46, `oms_order` 44, `oms_ticket` 33,
`cms_topic_node` 29, `cms_registration` 57, `cms_topic_chapter` 33), every column name appears somewhere in the main Java or
XML. The only exception is `ums_member.freeze_end_time` (U-08). This is a name match, not a proof of use, so the confidence is medium.

## 5. What users would feel, and what is only tidying

**Users would likely feel these** (inferred: faster first paint and back-navigation; nothing was measured):
- Theme detail: N-01, N-02, N-12, IX-01, IX-02, IX-04. This is the most-opened conversion page.
- Merchant home: R-01, N-03, N-10, N-14 (registration preview), IX-02, IX-03. Merchants return to this page constantly.
- Player home and roam: R-02, R-03, N-05, N-06, N-11, N-13.
- Play: R-04, N-07 (returning from the camera or a check-in).
- Ledger and customers: N-04, N-09, N-15, R-06.

**Tidying only** (no visible change): U-01 to U-05, U-08, IX-05 to IX-12 at today's likely table sizes. These matter for
backups, disk and future growth, not for today's page speed.

**Would need a redesign, not proposing now:** storing coordinates as typed DECIMAL or POINT columns with a spatial
index for the map queries (IX-11, RoamHangoutMapper.xml:122-123; CmsTopicMapper.xml:642-649 also builds a derived table
over all nodes); replacing `FIND_IN_SET(category_ids)` with a join table (IX-10); computing month totals in SQL instead
of in Java over the full finance history (MerchantFinanceQueryService.java:63-104, :494-513).

Suggested order: R-01/R-02 (client only, S) → N-01/N-02/N-12 (theme detail) → IX-01..IX-04 (one reviewed migration,
awaiting owner approval) → N-03/N-04 → the rest.

Note: every finding is static. Real row counts and the production index state after 2026-08-18 still need a
read-only `chengyin-db-migrate` precheck (`SHOW INDEX`, `information_schema.tables.table_rows`) before any DDL.

STATUS: COMPLETE
