# r1-release: release rehearsal (second pass)

- Tested commit: `573c463b3b457b77544e63be4271a07d5d565e09` on `integ/launch-test-20261103`.
- Branch: `claude/check-1105-r1-release`. Only files under `docs/delivery/check-20261105/r1-release/` were added.
- One cloud container: real jars, real MySQL 8.0.45 and Redis 7.4 in Docker, the real mini-program code in the rig's browser runtime. **This is not a phone.**
- Nothing touched production, the VPS, master or any PR. I have no production access; section 4 is the SQL for the owner's own read-only session.
- Old jar: master `c6eac3b8c` (the release's stated base). New jar: the tested commit.
- Old bundle: master `c6eac3b8c` `chengyinhub-xcx`, 157 pages.
- Rig: `claude/cloud-walk-20261101-rig` + the club lane's `tools/harness-patches.diff`, `LANG=C.UTF-8`, `mirror.gcr.io`.
- Local-only rig changes, not pushed:
  - `up.sh` takes `RIG_SHA`;
  - `walk.mjs` takes `RIG_DSF` / `RIG_Q` for 2x shots;
  - `app.py` passes fake `CHENGYIN_OSS_*` names (HARNESS).
- DB times are UTC. App log times are +08:00.

## Answer first: is the written deploy order complete and correct?

**No.** The order in `integ/RESULT.md` "Release order and rollback" is right about the content-safety jobs (pre-deploy files, then the new jar, then the post-deploy file; rollback deletes the two rows first). I re-proved all of that below. But:

1. **It is incomplete.** It lists 10 files. There are 13 new against master. It leaves out:
   - `invitee_signup_points_off`
   - `topic_total_time_seconds`
   - `review_wording_seeded_text`

   Its step 1 says "apply every pre-deploy file **above**", so an operator following it skips all three. Its ledger numbers are also stale: 109/110/390/395/428 are now 111/112/392/397/430. (F1)
2. **`topic_total_time_seconds` is not safe where it would naturally go.**
   - If it runs before the new jar, the old jar keeps writing minutes. I proved this over real HTTP: theme 990024 was saved through the old editor after the migration with `total_time = 120`, which is minutes.
   - The same happens to any theme edited during a rollback.
   - The file is not idempotent, though the repo's static classifier calls it `IDEMPOTENT_STATIC`. A second run multiplied one row again, 1 → 60 → 3600.
   - Its own assertion fails on the first run for that kind of row. The UPDATE is already committed when it does, and the runner stops the release there. (F2)
3. **The rollback notes don't say what each data change does to the old jar.** That includes the points-status behaviour change, which takes effect on deploy with no migration. (F3, F4)
4. **The plan assumes production is exactly at master.** The repo's last release ledger, 2026-10-29, records production's applied state as UNKNOWN. Section 4 [1] checks this. (Section 5)

## 0. Migrations new against master `c6eac3b8c`, with phase

From the repo's own `ci/release_manifest.py diff` (`proof/migration-diff-vs-master.json`): 13 ADDED, 0 MODIFIED, 0 DELETED. "Order" is the ledger position, out of 599, from `ci/migration_ledger.build_manifest`.

| Order | File (`migration_…sql`) | Phase | In written order? | What it changes |
|---|---|---|---|---|
| 77 | `20261102_merchant_industry_categories` | before jar | yes (77) | `sys_category`: unused type-1 merchant-like rows become type 6; inserts missing type-6 rows |
| 78 | `20261103_content_safety_recheck` | before jar | yes | new table `content_safety_recheck` |
| 79 | `20261103_sensitive_content_library` | before jar | yes | 4 `content_sensitive_*` tables; `sys_config content.sensitive.rescan.remove=false`; 2 `sys_menu`, granted to nobody |
| 80 | `20261104_content_safety_jobs_post_deploy` | **after jar** (`POST_DEPLOY_MIGRATIONS`) | yes | 2 `sys_job`, paused |
| 81 | `20261104_invitee_signup_points_off` | before jar (safe either side) | **missing** | `cms_points_setting` id 6 → `status 0, value 0` |
| 82 | `20261104_merchant_industry_categories_readback` | before jar | yes (as 81) | read-only assertion |
| 83 | `20261104_topic_total_time_seconds` | **undecided, see F2**: after jar, not before | **missing** | `cms_topic.total_time` minutes → seconds |
| 111 | `20261102_club_session_gather_point` | before jar | yes (as 109) | `cms_activity` lat/lng backfill |
| 112 | `20261104_club_session_gather_point_readback` | before jar | yes (as 110) | read-only assertion |
| 392 | `20261103_settlement_rule_three_percent` | before jar | yes (as 390) | generic `settlement_rule` → 0.03 / 0.97 / 0 |
| 397 | `20261102_club_checkin_phone_visible` | before jar | yes (as 395) | new column `club_role_assignment.customer_phone_visible` |
| 430 | `20261104_merchant_activity_limit` | before jar | yes (as 428) | `user_permission` merchant default → `1 / 3` |
| 571 | `20261104_review_wording_seeded_text` | before jar (safe either side) | **missing** | seeded 盲盒 / 探店日 / 竞猜 text; `sys_job` predict job name and remark only |

- Plus `20261101_author_write_receipt` (order 74). It is on master but not in the rollback jar `014fc0d83`, so it is needed only if production predates it (section 4 [1]).
- Also not a migration but part of this deploy: the new jar's points payout change (F3).

## 1. Steps

### A. Pre-deploy files, old jar running (twice)

The baseline came from master's own `build_schema.py` and `seed_fixtures.py`: 452 tables. Next, master's three post-deploy job files were applied, since production has them. Then fictional rows were added so each file has work: `proof/seed.sql`, ids 98xx.

- Settlement: generic rules at 0.10, one merchant-specific rule, one disabled rule.
- Categories: unused type-1 茶馆, and 书店 used by a topic.
- Gather points: five club sessions in different states.
- One `EVENT_CHECKIN` assignment.
- Merchant default permission at production's stated `1 / 0`.
- Points rules 1–7: id 6 on at 100. Id 3 is **disabled but valued** (5).
- Five themes for `total_time`:
  - minutes, all nodes (30);
  - seeded seconds (5400);
  - minutes, host-only nodes (10);
  - the edge case: 1 host minute + 59 shop minutes, `total_time = 1`;
  - zero.
- Wording rows: seeded template, edited copy, merchant copy, chapter and member template.

Every file was applied with the candidate's own `ci/run_migration.py`, which evaluates each `-- runner: assert=`, in ledger order. Driver: `proof/reh.py`.

| # | Step | Result | Read-back / evidence |
|---|---|---|---|
| A1 | Pass 1, all 13 pre-deploy files (incl. `author_write_receipt`), old jar live | **FAIL at `topic_total_time_seconds`** (exit 1, `readback=(0,)`); every other file exit 0, assertion `readback=1` | `proof/A-pre-pass1.json`. The edge theme 9834 went 1 → 60, and 60 equals its all-node minute sum, so the assertion counts it as unconverted. The UPDATE had already committed. See F2. |
| A2 | Each file's effect (pass 1 vs baseline) | PASS except F2 | Settlement: 9801/9802/9803 → 0.0300/0.9700/0; merchant-specific 0.05 and disabled 0.20 unchanged. Categories: 茶馆 → type 6, 书店 (used) stays type 1, 7 type-6 rows inserted. Gather: only 9801 filled (31.230400/121.473700). `customer_phone_visible=0` on 9831. Merchant `1/0 → 1/3`, club and player unchanged. Points id 6 → `0.00 / status 0`, id 5 stays 500/on. `total_time`: 30→1800, 10→600, 5400 and 0 unchanged. Wording: seeded template, nodes, member template, chapter, 9 `sys_menu` names and remarks, and the predict job name renamed; edited and merchant copies unchanged. `rescan.remove=false`; 2 menus, 0 role grants; 0 new `sys_job`. |
| A3 | Old jar keeps its scheduler during and after A1 | PASS | Jobs logged at 02:13, 02:14 and 02:15. 0 lines of 「调度主会话不可用」 or 「任务调用目标或参数无效」. Evidence: `proof/old-jar-during-predeploy.excerpt.log`, `proof/sys_job_log-0212-0240-utc.txt`. |
| A4 | Old jar writes during the window | **FAIL, see F2** | After A1, the candidate's `chains.py platform` ran on the old jar and created theme 990024 through the real editor API. Its `total_time` was 120, which is minutes (4 × 30). The new jar writes 7200 for the same content (990025, C3). |
| A5 | Pass 2, all 13 again | Exit 0 for all, structure fingerprint identical to pass 1. **But** theme 9834 went 60 → 3600, multiplied a second time, and 990024 went 120 → 7200. | `proof/A-pre-pass2.json`. The file is not idempotent. |
| A6 | Structure after pass 1 | 457 tables, 6298 columns, 2451 index columns | Same counts as the first pass's candidate full replay. |

### B. Old jar on the migrated schema

| # | Step | Result | Evidence |
|---|---|---|---|
| B1 | Restart old jar after pre-deploy files | PASS: ready in 14 s, no job-target error | `proof/old-jar-boot-migrated.excerpt.log` |

### C. New jar, then post-deploy file

| # | Step | Result | Evidence |
|---|---|---|---|
| C1 | New jar boots | PASS: ready in 14 s. The only ERRORs are alarm webhook and WeChat Pay certificate (HARNESS). | `proof/new-jar.excerpt.log` |
| C2 | Post-deploy file with new jar live, run twice | PASS: both assertions `readback=1`. Jobs 156/157: `contentSafetyRecheckTask.run()` `21 1/5 * * * ?` and `sensitiveContentRescanTask.run()` `33 2/10 * * * ?`, status `1` (paused), misfire 3, concurrent 1. The second run inserts nothing. | `proof/D-post-pass1.json`, `proof/D-post-pass2.json` |
| C3 | Registered paused, really | PASS: 0 `sys_job_log` rows for either target from 02:16:51 to 02:36:32 UTC (4 recheck slots, 2 rescan slots). 12 other jobs ran 80 times. 0 scheduler errors. | `proof/sys_job_log-0212-0240-utc.txt` |
| C4 | `content.sensitive.rescan.remove` defaults to false | PASS: row value `false`. With the row missing, the code reads `"true".equalsIgnoreCase(String.valueOf(null))`, which is false (`SensitiveContentRescanServiceImpl.java:82`). | `proof/D-post-pass1.json` |
| C5 | Real-HTTP chains on the migrated data (platform → merchant_club → sale_close → player) | PASS, all 4, topic 990025 | rig `chains.py`. New jar writes `total_time = 7200` (seconds). |

### D. Old mini-program bundle (master `c6eac3b8c`) on the new jar

Shots are 390x844 at 2x, JPEG under 51 KB. I looked at each. Identities: fixtures 9004 and 9009, and a guest.

| # | Step | Result | Shot | Read-back |
|---|---|---|---|---|
| D1 | Guest home | PASS | 01, 02 | 0 page errors |
| D2 | Login (returning 9009, 9004) and home | PASS | 03, 04, 09 | `/api/login/code` 200. The home list shows 「E2E 探店日一期 ¥69 起」 once OSS names were set (HARNESS). |
| D3 | Sign-up: theme → 立即报名 → 11.16 → 标准场次票 → 结算 | PASS (rig workaround on 立即报名) | 09–13 | `/api/registration/quote` 200, ¥69 on screen. heroCta read back as `signup`. |
| D4 | No participant yet → 参与人信息 form → 保存 → back at checkout | PASS | 13–15 | 林小测 / 13800001234 shown at checkout. F3 of the first pass (the name race) did not occur this time. |
| D5 | Consent → pay → order detail | PASS (rig workaround on consent and pay) | 16–18 | reg 75 `A20261105798993F4`: `payment_status=2`, `registration_status=2`, `payable_amount=69.00`, `fee_platform_rate=0.0300`, activity 9807. Same order number, ¥69 and date on screen. |
| D6 | Points paid on that payment | PASS, and shows F3 | — | One `ums_member_points_detail` row: rule 2, +10. Rule 3 is disabled with value 5 and paid **nothing**; the old jar would have paid it. |
| D7 | Game resume: 票夹 → 开始游戏 → 我到了 → 定位并签到 → cold start → 开始游戏 | PASS | 19–23 | `/club/lead/arrive` 「已到集合点」. The cold start reads 「1/1 已到集合点 · 已签到」 from the server. The QR (`/api/verify/dyncode/issue`) returns 200 with a data-URL code for reg 75 and 76. |
| D8 | Order detail for a completed 自由探索 (9009, 4/4 shops) | PASS | 07, 08 | 「已完成 · 本次订单已核销」 matches `exploreProgress.completed=true` |
| D9 | **Old bundle, 自由探索 ticket scanned at 1 of 4 shops** (reg 76) | **FAIL, see F5** | 27, 28 | Server: `verificationStatus=1`, `exploreProgress {redeemed 1, required 4, active true}`. The old 票夹 lists only reg 75; reg 76 is gone. The old order detail says 「已完成 · 本次订单已核销」. |
| D10 | Same player's theme page | dead end | 06a, 06b | 9009's theme hero is 「去票夹」, and 票夹 shows 「还没有票」. For 9009 that is correct, since all 4 shops are done. For a 1/4 ticket, the same path leaves the player nowhere to continue. |

**Rig workarounds, said plainly:**

- As in the first pass, the rig's click did not reach the theme hero button or the checkout consent row and pay button.
- So I called those controls' own handlers (`onHeroCta`, `toggleHostShare`, `handlePayment`).
- The participant form, session, ticket and 下一步 were real taps.
- Payment is the rig seam: a VERIFIED fact plus the real `/api/registration/callback`.
- 9004 was added to club 7002 as a fixture row.
- Reg 76 was bought over HTTP (`proof/inprogress.py`). Its one shop scan was real HTTP, merchant 9002 `/api/registration/scan_qr_code` (`proof/scan-one-shop.py`).

### E. Rollback, then forward again

| # | Step | Result | Evidence |
|---|---|---|---|
| E1 | Negative control: old jar with the two post-deploy rows present | Fails to boot, as expected: `sysJobServiceImpl` → 「任务调用目标或参数无效」 | `proof/rollback.excerpt.log` |
| E2 | Rollback as written: stop new jar, delete the two rows, start old jar | PASS: 2 rows deleted, ready in 12 s, no scheduler error | `proof/rollback.excerpt.log` |
| E3 | Old bundle on the rolled-back jar: home, 票夹, order 75 created by the new jar | PASS | 24, 25, 26: same order number, ¥69 and status |
| E4 | Forward again: new jar, then re-run the post-deploy file | PASS: rows back, paused, assertion `readback=1`, no scheduler error | `proof/F-forward-again-post.json` |

### F. Main package and preflight (lane carry-over)

- Main package by preflight's method (`proof/mainpkg-bytes.js`): **3,115,248 bytes**, 30,480 under the 3,145,728 ratchet, about 1926 KiB at ×0.633. 5 main pages, 28 subpackages. The old bundle is 3,567,952.
- `node scripts/preflight-release.js` on the tested commit gives **1 BLOCK**: 「Action Ledger 动态证据未完成:pending=5723,nonPassed=0」. The size check is INFO at 2.97 MB (`proof/preflight-release.txt`).
- WeChat's own size reading: NOT VERIFIED (no DevTools here).

## 2. Findings

### F1 HIGH: the written release order leaves out three migrations

- **Where:** `docs/delivery/walk-fix-20261102/integ/RESULT.md`, "Release order and rollback", table and "Deploy order" step 1.
- **What:** the table has 10 files. These three, new against master, are missing:
  - `migration_20261104_invitee_signup_points_off.sql` (order 81, f4);
  - `migration_20261104_topic_total_time_seconds.sql` (order 83, g3);
  - `migration_20261104_review_wording_seeded_text.sql` (order 571, h2).
- **Why it matters:** step 1 says to apply "every pre-deploy file above". An operator following it ships the new jar with:
  - rule 6 still on (100 points per invitee signup), which the owner turned off;
  - seeded 盲盒 / 竞猜 / 探店日 text that the review wording rule removes;
  - editor-made themes showing 1/60 of their duration.
- **Also:** the ledger numbers in the table are stale: 109→111, 110→112, 390→392, 395→397, 428→430. The relative order is still right. No phase is stated for any of the three.
- **Repro:** `python3 ci/release_manifest.py diff --base c6eac3b8cebf7311e8f00f208d294c3c8212aaec --head 573c463b3` lists 13; the table lists 10.

### F2 HIGH: `topic_total_time_seconds` is unsafe before the jar, not idempotent, and can stop the release on its own assertion

- **File:** `chengyinhub-admin/sql/migration_20261104_topic_total_time_seconds.sql`.
- **(a) Window.** The old jar's editor still writes minutes: master `CmsTopicServiceImpl.calculateTotalTime`, `:2500` and `:2968`.
  - Applied before the new jar, any theme saved in the window stays in minutes after the new jar is up. Proved: theme 990024 was saved through the old jar's real editor API after pass 1, with `total_time = 120`.
  - The same happens to every theme saved while rolled back.
  - The rollback notes say "going forward again, re-run the post-deploy file" only, so those rows are never fixed.
- **(b) Not idempotent.** The file says "可重跑…第二次命中 0 行" and the release tool says `IDEMPOTENT_STATIC`. A theme whose shop-node minutes are 59× its host-node minutes is converted on pass 1 (host sum 1 → 60). On pass 2 the value now equals the all-node sum, so it is converted again (60 → 3600). Proved on theme 9834 (`proof/A-pre-pass1.json` → `A-pre-pass2.json`).
- **(c) Own assertion fails.** For the same row, pass 1's assertion counts the converted 60 as "still minutes" and exits 1 (`readback=(0,)`). The UPDATE is already committed, the runner stops, and the rest of the release order is not applied. (I let my driver continue only to record every file.) The general condition is `total_time × 60` equal to either node-minute sum.
- **What the owner needs to decide:**
  - Run this file after the new jar is live (post-deploy), or before and again after.
  - Make the second run safe: for example, convert only rows not yet converted, by id list or marker.
  - Section 4 [3] counts the edge rows on production. If that count is 0 and the file runs once, after the jar, (b) and (c) don't occur.
- **Not changed:** I fix nothing; this is for the root.

### F3 HIGH: points payouts now honour `status` from the moment the new jar starts, with no migration and no line in the release notes

- **Code:** new `CmsRegistrationServiceImpl.pointsSettingValue` (`:2839`). Rules 2, 3, 5 and 6 pay at payment success only when `status = 1` and `del_flag = 0`. The old jar paid by `value` alone.
- **Proved over HTTP:** rule 3, disabled with value 5, paid nothing on reg 75; rule 2 paid 10 (D6). On the old jar the same rows would have paid 15.
- **Effect at deploy:** any of rules 2, 3 or 5 that production has disabled with a value above 0 is paying today and stops paying when the new jar starts. On rollback it starts paying again. Section 4 [5] marks such rows 「PAYS TODAY, STOPS ON NEW JAR」.
- **Not uniform:** rules 1 (注册), 4 (评价/投票), 7 (推荐商户) and event rules 11/12/13 still pay whatever their status. h3 already listed this as an owner question (`integ/ROUND11-H3-RESULT.md`). An admin who 停用s rule 1, 4 or 7 is shown a switch that doesn't stop the payout.
- **Rule 6 (invitee per-signup):** off both ways after the migration, because `value = 0` stops the old jar too. That part is safe on rollback.

### F4 LOW: the rollback notes don't say what the kept data changes mean on the old jar

- The notes say "the pre-deploy schema changes stay; the old jar boots and serves on them". That is true (E2, E3), but the data changes also stay, and the notes don't say so:
  - 3% rules stay; the old jar settles at 3%.
  - The merchant limit stays at 3. The old jar's `QuotaServiceImpl` counts every undeleted activity ever (ROUND10 j1 F1), so a merchant with three past activities is blocked on the old jar. Before the release they were blocked anyway at 0.
  - Rule 6 stays off.
  - Renamed seed text stays.
  - `total_time` stays in seconds, but the old jar writes minutes again (F2).
- None of these stops the old jar. The owner should decide which of them to undo on a rollback. The files' own "回退" lines give the SQL for 3%, merchant limit and points.

### F5 HIGH (until old clients update): on the old bundle, a 自由探索 ticket disappears after its first shop scan

- **Repro:**
  1. Old bundle (master), player 9004, paid 自由探索 ticket reg 76.
  2. Merchant 9002 scans it at chapter 1 of 4.
  3. The server says `verificationStatus=1`, `exploreProgress.active=true`, 1/4.
  4. 票夹 no longer lists it (shot 27).
  5. 订单详情 says 「已完成 · 本次订单已核销」 (shot 28).
  6. The theme page's hero is 「去票夹」, which leads to the empty 票夹 (06a, 06b, shown with 9009).
- **Why:** master `subpackageMember/signup/index.js:713-717` `usableTicket` drops every ticket with `verificationStatus == 1`. The new bundle keeps it while `exploreProgress.active` (`:730-737`, P-03).
- **Effect:** an old-bundle player can't continue a 自由探索 after the first shop until WeChat updates their client. There is no backend minimum-version gate. The old bundle only shows WeChat's 「新版本已就绪」 prompt when an update has downloaded (`utils/app-update.js`).
- **Scope:** this is the old client, and the old jar behaves the same. It is not caused by the new jar, but it affects every user still on the old version after launch.

### F6 LOW: the rig's MySQL runs UTC, so DB-`NOW()` times and Java times differ by 8 h on screen

- Old order detail shows 「权益已核销 2026-11-05 02:20」 next to 「支付已完成 10:17」 (shot 08). `verification_time` is written by SQL `NOW()`, the other times by the JVM (+08:00).
- This is HARNESS here.
- On production it depends on the DB time zone, which also decides the gather-point backfill's `end_date > NOW()` cut-off. Section 4 [0] reads it.

### HARNESS (not product bugs)

- `CHENGYIN_OSS_*` are not set in the rig. Then `/api/topic/list` and `/api/topic/info-to-user` return `HttpMessageNotWritableException: 对象存储配置缺失`, and the old theme page renders blank. With fake names it renders. Cover images on `oss-rig.invalid` don't load.
- The two master playpack seed files fail on this scratch DB with a collation mix (1267). Wording rows were hand-seeded with the exact seed text instead.
- 「数据库连接池等待」 alarm every 5 minutes from `DruidMetricsProbe`, on both jars, unrelated to the migrations.
- Rig clicks on the theme hero and on checkout consent and pay: see section D.
- WeChat Pay certificate and alarm webhook are not configured. The payment leg is the rig seam.

## 3. Release order as it should read (for the root to confirm; I changed nothing)

1. **Before anything:** the owner runs `production-readonly-precheck.sql` and keeps the output. Stop if:
   - [1] shows production is not at master;
   - any [3] precondition fails;
   - [5] shows a row marked 「PAYS TODAY, STOPS ON NEW JAR」 that the owner didn't intend.
2. **Old jar running:** apply in ledger order with `ci/run_migration.py`: 77, 78, 79, 81, 82, 111, 112, 392, 397, 430, 571 (+74 if [1] says it's missing).
3. **New jar:** deploy and start it, then wait for health.
4. **After the jar:** apply 80 (jobs, paused) and 83 (`total_time`, once, after the F2 decision). Then re-run the precheck: [2] 83 should be 0.
5. **Rollback:**
   - stop the new jar;
   - delete the two `sys_job` rows;
   - start the old jar;
   - decide per F4 which data to revert.
6. **Forward again:** new jar, then 80 again, then 83 only if it's made safe to re-run (F2b).

## 4. Production comparison (read-only, for the owner's session)

- `production-readonly-precheck.sql`: SELECT only (checked: no write, DDL, SET or PREPARE). Test-run on the scratch DB after the release: `proof/precheck-on-scratch-after-release.txt`. It answers, per migration:
  - [1] Is production at master? (`app_author_write_receipt`, master's post-deploy jobs.)
  - [2] Already applied? Each file's own final assertion, read-only.
  - [3] Every pre-assertion that would stop a file:
    - one `audit-center` menu;
    - exactly one generic activity rule and one topic rule;
    - one merchant default row;
    - no existing content-safety jobs;
    - the `total_time` edge count.
  - [4] Values to keep for rollback: settlement rules, permissions, `total_time` candidates, categories.
  - [5] Points rules and the deploy effect of F3.
  - [6] What the wording file will and won't change.
  - [0] Time zone.
- The repo's own read-only tool for "applied?" is `python3 ci/audit_migrations_applied.py <keyword>`. It replays only the runner assertions, so it can't see [3]–[6].
- `ci/migration_ledger.py plan` needs a `CHENGYIN_DB_MIGRATE_READ_ONLY_EXPORT` history snapshot, which only the owner's `chengyin-db-migrate` session can produce.

## 5. What I did not cover

- **Production's real state.** No access. The precheck SQL is written and test-run on scratch only.
- **Rollback jar `014fc0d83`.** This pass used master `c6eac3b8c` as the old jar, matching the written order's base. The first pass proved `014fc0d83` boots on the migrated schema with the two job rows held aside. The only migration between them is `author_write_receipt`.
- **Old-jar money chain on the migrated schema.** Only boot, home, 票夹 and order detail were run. No old-jar settlement at 3%.
- **New bundle on the new jar for comparison with F5.** I relied on code (`signup/index.js:730-737`) and the API read-back.
- **The two content-safety tasks enabled and running.** Not done.
- **Production-like `total_time` data.** Only fictional rows. The edge in F2 is real code behaviour, but how many production themes hit it is unknown until precheck [3].
- **Real devices, WeChat DevTools size, real WeChat login, phone number or payment.**

STATUS: COMPLETE
