# r1-release: release rehearsal (first pass)

- Tested commit: `8b78f5ca3c06f486631e827827e973cbb47e3094` on `integ/launch-test-20261103`.
- Branch: `claude/check-1104-r1-release`. Only files under `docs/delivery/check-20261103/r1-release/` were added.
- Everything ran in one cloud container: real jars, real MySQL 8.0.45 and Redis 7.4 in Docker, and the real mini-program code in the rig's browser runtime. **This is not a phone.**
- Nothing touched production, the VPS, master or any PR.
- Rig: `claude/cloud-walk-20261101-rig`, plus the club lane's `tools/harness-patches.diff`, `LANG=C.UTF-8`, and the `mirror.gcr.io` Docker mirror.
- Local-only rig changes, not pushed:
  - `up.sh` pinned to the tested commit.
  - `walk.mjs` takes `RIG_DSF=2` for 2x shots and logs API bodies whose `code` is not 200.
  - `app.py` passes fake `CHENGYIN_OSS_*` names (see HARNESS).
- Times in app logs are JVM time (+08:00). Times in this report are UTC unless marked.

## 0. Migrations new against master in the tested commit

Listed with the repo's own `ci/release_manifest.migration_diff`. Order is the position in the ledger's topological order (592 entries), which is also the order `build_schema.py` uses.

| Order | File | vs master `c6eac3b8c` | vs rollback jar `014fc0d83` | Touches |
|---|---|---|---|---|
| 74 | `migration_20261101_author_write_receipt.sql` | already on master (added in `a653ee8b7`) | ADDED | new `app_author_write_receipt` |
| 77 | `migration_20261102_merchant_industry_categories.sql` | ADDED | ADDED | `sys_category` (type 6) |
| 78 | `migration_20261103_content_safety_recheck.sql` | ADDED | ADDED | new `content_safety_recheck`, `sys_job` |
| 79 | `migration_20261103_sensitive_content_library.sql` | ADDED | ADDED | 4 new `content_sensitive_*`, `sys_config`, `sys_job`, `sys_menu` |
| 107 | `migration_20261102_club_session_gather_point.sql` | ADDED | ADDED | `cms_activity` backfill |
| 387 | `migration_20261103_settlement_rule_three_percent.sql` | ADDED | ADDED | `settlement_rule` |
| 392 | `migration_20261102_club_checkin_phone_visible.sql` | ADDED | ADDED | `club_role_assignment` column |

- Nothing is MODIFIED or DELETED against either base.
- Six migrations are new against master. The seventh, `author_write_receipt`, is on master but not in the rollback jar `014fc0d83`, so it is part of this release.

## 1. Steps

### A. Migration rehearsal

The throwaway database is `cy_e2e_51` on 127.0.0.1:3316.

**Baseline.** It stands for production's structure as the repo's schema tools capture it at the rollback jar.
- Built with `014fc0d83`'s own `scripts/e2e/build_schema.py`: base snapshot plus 293 replayed migrations, 451 tables, "迁移阶段问题: 0".
- Then `014fc0d83`'s `seed_fixtures.py`.
- Then fictional rows so each data migration has work to do (`proof/rehearse.py seed`):
  - generic `settlement_rule` rows at 0.10/0.90/0, the values the 3% file names as production's 2026-08 state;
  - one merchant-specific rule and one disabled rule;
  - `sys_category` 茶馆 (unused) and 书店 (used by a topic) as type 1;
  - five club sessions in different states, with session tickets carrying gather coordinates;
  - one `EVENT_CHECKIN` assignment.

**Runner.** Every migration was applied with the candidate's own `ci/run_migration.py`, which evaluates each `-- runner: assert=` line. The config is `proof/rehearsal-druid.yml` (loopback only).

| # | Step | Result | Read-back / evidence |
|---|---|---|---|
| A1 | Baseline from `014fc0d83` tools | PASS | 451 tables, 0 migration problems (`proof/baseline-build_schema.tail.log`, `proof/fp-baseline-014fc0d83.summary.json`) |
| A2 | Negative control: generic `topic` rule missing | PASS | 3% file stops at its first assertion (`readback=(0,)`); the `activity` row stays 0.1000 (`proof/negative-controls.json`) |
| A3 | Negative control: empty `settlement_rule` (the state of a bare scratch build) | PASS | stops at the first assertion, exit 1, nothing written |
| A4 | `author_write_receipt` | PASS | assert `readback=1`; table exists, InnoDB |
| A5 | `merchant_industry_categories` | PASS | assert `readback=1`; unused type-1 茶馆 became type 6; 书店, used by a topic, stayed type 1; inserted type 6: 手作工坊 古着中古 文创杂货 花店 烘焙甜品 酒馆小酌 宠物友好. 咖啡茶饮 was skipped because type-1 「咖啡」 (id 4001) already exists. See F6. |
| A6 | `content_safety_recheck` | PASS | assert `readback=1`; 14 columns; job `contentSafetyRecheckTask.run()` cron `21 1/5 * * * ?` status `1` (paused) |
| A7 | `sensitive_content_library` | PASS | assert `readback=1`; 4 tables; job `sensitiveContentRescanTask.run()` cron `33 2/10 * * * ?` status `1`; config `content.sensitive.rescan.remove=false`; 2 menus under 审核中心 (`audit-center`), granted to 0 roles |
| A8 | `club_session_gather_point` | PASS (this file has no assertion, see F5) | Only session 9801 (club, open, future, no coordinates) was filled, from its lowest-id ticket with both coordinates: 31.230400 / 121.473700. Untouched: ended 9802, 9803 (already had coordinates), 9804 (not a club session), cancelled 9805. |
| A9 | `settlement_rule_three_percent` | PASS | both assertions `readback=1`; generic activity, topic and topic_club rows are 0.0300/0.9700/0.0000; the merchant-specific rule (0.05) and the disabled rule (0.20) are unchanged |
| A10 | `club_checkin_phone_visible` | PASS | assert `readback=1`; existing assignment 9831 has `customer_phone_visible=0`. `ADD COLUMN … AFTER version` also runs as `ALGORITHM=INSTANT` on a copy of the table, so it does not rebuild it. |
| A11 | Pass 2: all 7 run again | PASS | every file exit 0; tracked state identical to pass 1; structure fingerprint identical (`proof/pass2.json`, `proof/fp-after-pass2.summary.json`) |
| A12 | Migrated structure = candidate's own full replay | PASS | migrated: 457 tables, 6298 columns, 2451 index columns, digests `cc32ff59…/07d2be99…/9dc784a3…`, identical to the candidate's `build_schema` from scratch (`proof/fp-ref-candidate-full.summary.json`). Against the baseline: +6 tables, +1 column; 5 columns only changed position (after the new column). |

### B. Rollback: old jar `014fc0d83` against the migrated schema

| # | Step | Result | Evidence |
|---|---|---|---|
| B1 | Old jar boots on the migrated schema | **FAIL** | Application run failed: `sysJobServiceImpl` init → `CanonicalJobTarget.canonical` → 「任务调用目标或参数无效」 (`proof/old-jar-boot-on-migrated.excerpt.log`). See F1. |
| B2 | Old jar already running when the two job migrations are applied | **FAIL** | Within 5 s: 「调度主会话不可用，已停止新调度: ServiceException」 every 2 s, and the scheduler stays paused. HTTP keeps answering 200. See F2 and `proof/old-jar-running-then-migrated.excerpt.log`. |
| B3 | Control: the two new `sys_job` rows held aside, everything else migrated | PASS | Old jar boots in 22 s; login 200. Together with the old bundle, home, 票夹 and order detail for an order the **new** jar created (reg 75) all render; 0 page errors; no scheduler errors. Shots 21–23. So these two rows are the only boot blocker. |

### C. New jar (tested commit) on the migrated schema

| # | Step | Result | Evidence |
|---|---|---|---|
| C1 | Boot | PASS | ready in 22 s; only ERROR lines are the unconfigured alarm webhook and missing WeChat Pay certificate (HARNESS) |
| C2 | Both new tasks register paused | PASS | `sys_job` 152/153 status `1`. `ScheduleUtils` pauses status `1` jobs. Runtime: no `sys_job_log` row for either target from 04:32 to 05:10 UTC, which covers at least 7 contentSafety slots and 3 rescan slots. 7 other jobs logged runs in the same window. |
| C3 | `content.sensitive.rescan.remove` defaults to false | PASS | row value `false`. With the row missing, code reads `"true".equalsIgnoreCase(String.valueOf(null))` → false (`SensitiveContentRescanServiceImpl.java:82`). |
| C4 | Real-HTTP chains on migrated data (platform → merchant_club → sale_close → player) | PASS | all 4 PASS, topic 990014 (rig `chains.py`) |

### D. Last master mini-program bundle `c6eac3b8c` against the new jar

The bundle is built from master's `chengyinhub-xcx` (157 pages) and served on a second rig port. Identities are fixtures 9004 and 9009, plus a fresh guest. Shots are 390x844 at 2x.

| # | Step | Result | Shot | Read-back |
|---|---|---|---|---|
| D1 | Guest launch, home | PASS | 01 | 0 page errors; only banner gifs/favicon 404 (HARNESS) |
| D2 | Guest → avatar → 我的 → 登录 | PASS | 02, 03 | `/api/login/code` 200 → new member, 我的 renders (「城瘾用户 Lv.1」), 16 follow-up calls 200 |
| D3 | Returning player 9004: home | PASS | 04, 05 | dialogs, then the topic list shows 「E2E 探店日一期 ¥69 起」 |
| D4 | Sign-up: topic → 立即报名 → session → ticket → 结算 | PASS (rig workaround on 立即报名) | 06–10 | `/api/registration/quote` 200, ¥69 on screen |
| D5 | Consent → pay | PASS after one error, see F3 | 11–13 | first pay: 「参与人信息需要包含姓名」 (shot 12) |
| D6 | Paid → old order detail | PASS | 14 | reg 75 `A20261104E8F80856`: `payment_status=2`, `registration_status=2`, `payable_amount=69.00`, activity 9806 (club 7002, 11-14 10:00). The screen shows the same order number, ¥69 and date. |
| D7 | Game resume (player 9004, session 9812) | PASS | 15–19 | 票夹 → 开始游戏 → play page (`/play/nodes`, `/play/run-session`, `/club/lead/team-progress` 200) → 我到了 → location notice → 定位并签到. Read-back: `/club/lead/arrive` 「已到集合点」, row `club_lead_checkin(9812, 9004)`. Cold start (fresh runtime) → 票夹 → 开始游戏 shows 「1/2 已到集合点 · 已签到」, restored from the server (shot 19). |
| D8 | Order detail for a started session | PASS | 20 | reg 76 shows 「不可退款 / 已开始,不可退款」, matching the frozen refund tier for a started session |

Rig workarounds and fixtures, said plainly:
- **D4, D5 workarounds.** The rig's click did not reach two old-page controls: the topic hero button and the checkout's consent row and pay button. Hit-testing landed on the page host, and on the checkout a closed `cy-sheet` stayed over the bottom bar.
  - So I called those controls' own bound handlers (`onHeroCta`, `toggleHostShare`, `handlePayment`) from the page.
  - Everything after that, the page code, quote, create, payment seam and callback, is real.
  - The participant sheet and the 林小测 row were real taps.
- **D7 fixtures.**
  - 9004 was added to club 7002 as a direct fixture row.
  - Session 9812 was opened through the real `/api/club/open-session` as the club's executor.
  - 9004's ticket was bought over HTTP (consent/quote/create, then the rig payment seam): reg 76, `payment_status=2`.
  - Script: `proof/inprogress.py`. The screen sign-up is D4–D6 (player 9009).
- **Not reached.** The resume reached the gather stage only. The leader starting the run and a stop check-in from the old client were not reached (see section 3).

### E. Main package and preflight

Measured with the same method as `scripts/preflight-release.js`: every file outside `packOptions.ignore` and outside subpackage roots (`proof/mainpkg-bytes.js`).

| Tree | Source bytes | x0.633 estimate | Headroom to 3.00 MB ratchet | Main pages / subpackages |
|---|---|---|---|---|
| Tested commit `8b78f5ca3` | **3,140,124** | **~1941 KiB** | 5,604 bytes | 5 / 28 (157 pages) |
| master `c6eac3b8c` (the old bundle) | 3,567,952 | ~2206 KiB | −422,224 | 10 / 24 (157 pages) |
| Round 7 final, for reference | 3,090,788 | ~1911 KiB | | |

`node scripts/preflight-release.js` on the tested commit:
- **1 BLOCK, not 2:** 「Action Ledger 动态证据未完成:pending=5678,nonPassed=0」.
- The size check is now INFO: 「主包未压缩 2.99MB(棘轮 3.00MB;大头 utils 0.88MB / components 0.81MB / pages 0.69MB)」.
- WeChat's own size was not read: NOT VERIFIED (no DevTools here).

## 2. Findings

### F1 BLOCKER: after the migrations, the rollback jar `014fc0d83` cannot start

- **Repro:**
  1. Apply `migration_20261103_content_safety_recheck.sql` and `migration_20261103_sensitive_content_library.sql`.
  2. Start the `014fc0d83` jar.
  3. Startup aborts: `Error creating bean with name 'sysJobServiceImpl': Invocation of init method failed … ServiceException: 任务调用目标或参数无效`.
- **Why:**
  - Each file inserts a `sys_job` row (`content_safety_recheck.sql:28`, `sensitive_content_library.sql:66`). The rows name beans that exist only in the new jar.
  - `SysJobServiceImpl.init` (`:60`) checks every row through `CanonicalJobTarget.canonical` (`:32`), which resolves the Spring bean. It does this whatever the row's status, so "registered paused" does not help.
  - The code is the same in `014fc0d83` and the candidate.
- **Effect:** if the new jar has to be rolled back, the old jar does not come up. The site is down until someone deletes those two rows.
- **Proven safe workaround:** hold the two rows aside, and the old jar boots and serves the migrated data, including orders the new jar created (B3).
- **Options for the root:**
  - (a) Move the two `sys_job` inserts into a step that runs after the new jar is live.
  - (b) Put "delete the two `sys_job` rows first" into the rollback runbook. The rows must be restored when going forward again.
- Evidence: `proof/old-jar-boot-on-migrated.excerpt.log`.

### F2 HIGH: running these migrations while the old jar is live stops all its scheduled jobs until the new jar is up

- **Same root cause as F1.** The leader thread re-validates every `sys_job` row every 2 s (`MySqlSchedulerCoordinator.maintainLeadership` → `refreshQuartz.run()`, `:101`).
- **Repro:**
  1. Run the old jar.
  2. Apply the two migrations.
  3. Within 5 s the log shows 「调度主会话不可用，已停止新调度: ServiceException」 every 2 s.
- **What stops:** the Quartz scheduler is paused and leadership is dropped. Settlement, refund reconcile, expired-order close, auto-verify, before-start notices and every other job stop. HTTP keeps working.
- **Why this order matters:** it is the order the release uses. Both files say 「先跑本文件…再部署新 jar」, and `l8-safety/RESULT.md:231` says they must be applied before the jar.
- **Impact:** a quick deploy limits this to minutes. A slow or failed deploy makes it long, and with F1 there is no way back.
- Evidence: `proof/old-jar-running-then-migrated.excerpt.log`.

### F3 HIGH: checkout can refuse to pay with 「参与人信息需要包含姓名」 while a named participant is selected

- **Repro (old bundle, rig):** 9009 → topic 990014 → 11-14 session → 标准场次票 → 结算. 林小测 is selected and consent is ticked. Pay shows 「参与人信息需要包含姓名」 (shot 12).
- **Why:**
  - On load the page fires `getUserInfo()` and the participant list in parallel.
  - The list sets `userInfo.realName` from the chosen participant (`updateUserInfoFromAddress`).
  - When `/api/user/info` answers later, `userInfo: res.data` replaces the whole object, and that object has no `realName`.
  - Measured in the rig: `address/list` finished at …599 ms and `user/info` at …601 ms.
- **Files:**
  - master `pages/activity/baoming/baoming.js:337` (overwrite) and `:1540` (check);
  - tested commit `:348` and `:1576`, the same code.
- **Recovery:** re-picking the participant in the sheet the error opens, then paying again, works (shots 13–14).
- **Scope:** not caused by the new jar. It is a client race in both versions, and which response wins depends on network timing.

### F4 LOW: ROUND8 counts "7 migrations in integ/RESULT.md"; that file lists 6

- `integ/RESULT.md` "Migrations new against master" has 6 rows.
- The seventh, `author_write_receipt`, is on master already, so a diff against master does not show it.
- The release still needs all 7 (section 0), because `014fc0d83` does not have it.

### F5 LOW: `migration_20261102_club_session_gather_point.sql` has no runner assertion

- The other six end with a `-- runner: assert=` read-back. This one is a bare UPDATE, so a run that matches nothing looks the same as a correct run.
- Its effect was proven here only with fictional rows (A8).

### F6 LOW: the industry-category assertion cannot tell whether any type-6 row exists

- The assertion counts type 1 **and** 6 with broad `LIKE`s (`%花%`, `%酒%`, `%咖啡%`).
- A database whose theme categories already contain those characters passes with zero merchant industries inserted.
- In the rehearsal it passed with 8 type-6 rows. 「咖啡茶饮」 was skipped because the type-1 theme category 「咖啡」 exists, so merchants see the theme category 「咖啡」 instead.
- That follows the file's stated design. I flag only that the assertion would not catch an empty result.

### F7 LOW: main package is 5,604 bytes under the preflight ratchet

- 3,140,124 of 3,145,728 bytes (~1941 KiB estimate, WeChat limit 2048 KiB).
- It grew ~49 KB since Round 7 (3,090,788).
- Any further main-package addition turns the size check back into a BLOCK. The real size needs a DevTools reading.

### Leads for other lanes (seen here, not chased)

- `/api/registration/quote` returns a price to merchant fixture 9006 for a club session, but `/create` then refuses with 「商家不能报名俱乐部活动」 (403). A merchant could be shown a price they cannot buy. API only; not seen on a screen (j1/j6).
- Old bundle, returning user: 「首页城市定位」 and the 隐私保护 sheet open on top of each other (shot 04). The privacy sheet then re-appeared in the rig after each dismissal, which looks like the rig re-asking on every privacy API call (HARNESS-suspect). Not checked on a phone.
- Old bundle order detail repeats 「不可退款」 four times on one screen (shot 20). This is the old client; the current page belongs to p1.

### HARNESS (not product bugs)

- **OSS.**
  - Covers on OSS do not load: no outbound access.
  - The rig gives the jar no `CHENGYIN_OSS_*`. As soon as a topic has a cover, `/api/topic/list` returns `code=500 对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME`, and the home list shows the empty state.
  - I set fake names (`oss-rig.invalid`) so URLs can be signed. Production has real values.
- Banner gifs and favicon 404.
- **Rig clicks.**
  - Clicks on two old-page controls did not reach them: hit-testing landed on the page host.
  - A closed `cy-sheet` stayed mounted over the checkout bottom bar. It is likely that `animationend` never fires in the rig.
  - Handled as described under section D.
- `invoice/orders/eligible` answers 「发票功能暂未开放」. The feature is off in the fixture config, and the old page shows nothing for it.
- WeChat Pay certificate and the alarm webhook are not configured.
- The payment leg is the rig seam: a VERIFIED fact plus the real `/api/registration/callback`. It is not WeChat Pay.

## 3. What I did not cover

- **Production schema.** The baseline is the repo's capture at `014fc0d83`. The release pack says production's applied state is UNKNOWN, and only the owner's read-only precheck on production can confirm it.
  - The 3% file stops unless production has exactly one active generic `activity` row and one `topic` row (A2, A3). That needs checking before release.
- **Old jar.** Only boot, login, home, 票夹 and order detail were run on the migrated schema (B3). No old-jar money chain was run.
- **Old-client game.** The game resume was proven at the gather stage. The leader starting the run, a stop check-in by scan, and an ending were not reached from the old client.
- **Real devices.** No real phone, no WeChat DevTools size reading, no real WeChat login, phone number or payment.
- **Admin side.** The admin UI for the new 敏感内容库 menu was not opened.
- **Not re-run:** Druid wall on mapper SQL, and the MySQL `*MyBatisTest` / `*MySqlIT` classes. CI covers both.
- **Other lanes' leads.** The ROUND8 leads for other lanes (scene-* async, club edit, privacy launch path, merchantinfo, the 成团人数 error style, anonymous `/api/city/nodes` 401, 「¥ -2.00」, 确认到点) were not run. The anonymous `GET /api/city/nodes` probe in `up.sh` again returned 401 on this commit.

STATUS: COMPLETE
