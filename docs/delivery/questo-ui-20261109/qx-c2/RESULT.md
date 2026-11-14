# qx-c2 · docs, deployment list and storage verification — RESULT

Branch `claude/qx-c2-20261109`, based on `origin/integrate/questo-ui-20261110` (`24f5021a`). Fixes branch `claude/qx-c2-fixes-20261109` (draft [#749](https://github.com/liboyang42-cpu/hengyin/pull/749)). No production, VPS or private-repo access; nothing deployed. This lane has no visible UI items, so there are no screenshots.

## Items

| ID | Status | What changed | Files | Verified by | Not verified |
|---|---|---|---|---|---|
| 1a §25.2 / §25.3 | fixed | §25.2 maps the six main cards (MP-15…20) to their original cards and lanes, and lists the mini contract-shard fixes (`hangman` default inline, root `hintLadder`, play GET witnesses, help GETs in the matrix). §25.3 lists the five failing shard cases from run `42553085609`, their causes, the fixing commits (`a0824a43c`, C00 `24f5021ab`) and the green re-run `42717309700`. | `docs/questo-teardown/UI优化施工清单.md` | Failing case names read from the job logs of run 42553085609; fixes read from the commits; shard results read from run 42717309700 | — |
| 1b four wrong paths | fixed | `pages/play/theme-backpack.*` → template files plus `pages/play/utils/theme-backpack.js`; `node-game-catalog.js` and `gameplay-support-matrix.json` → `pages/publish/utils/publish/node-game-catalog.js` and `…/gameplay-support-matrix.js`; admin `finance/*` and `audit/index.vue` → `chengyinhub-ui/src/views/business/finance/*` and `views/business/audit/index.vue` | same | Every new path exists in the tree | — |
| 1c §28 | fixed | New §28 "2026-11-09 实施波次": lane overview, every B-lane task ID (lane, PR, status, evidence path), the qx-b7 iOS summary, the qx-c1 back-fill table, and the open owner decisions. Statuses are copied from each lane's RESULT.md and not upgraded. Version line bumped to 4.6. History sections untouched. | same | Read all 16 `qx-*/RESULT.md` files on the lane branches, plus qx-b7's in `chengyin-ios` | — |
| 1d vision 3 table | fixed | §28.3 lists every A-lane walkthrough ID with lane, PR, status and evidence path. V3-11 is marked as having no rig screenshot. | same | qx-a1…a7 and qx-a5d RESULT.md | — |
| 2 backend results | fixed | New top section: the master merge into #731 (`fae941a5`), the follow-up fixes, the current CI runs and why they are still red, #748 (and why its own CI is red on master), the ten qx-c1 gaps, the per-class storage table, and a new HTTP regression. | `docs/questo-teardown/后端优化执行结果.md` | Run and job data read through the GitHub API | Raw XML artifacts: the artifact storage host is blocked for this session, so counts come from surefire's per-class log lines and the workflow's own batch summaries |
| 3 deployment list | fixed | New `部署清单_20261110.md` with: the 59 migrations not in the 10-29 pack (88), recomputed with `ci/release_manifest.py diff` against production baseline `19408d49`; execution order, phases, rollback, assertions that need production rows, four paused `sys_job` rows, switches that stay off, and 15 menu/permission rows granted to no role. | `docs/questo-teardown/部署清单_20261110.md` | `release_manifest diff` on master (117), integrate (143), qx-c1 (146) and staging (147); headers and assertions read from every file | Production applied state is UNKNOWN; owner runs the read-only precheck |
| 3a deploy-phase bug | fixed (PR #749) | `migration_marketing_task_registration_20261108.sql` inserts two paused `sys_job` rows whose beans exist only in the new jar. The old jar resolves every row's bean (`CanonicalJobTarget.canonical` → `SpringUtils.getBean`), so running it before the new jar breaks the old scheduler. Added it to `POST_DEPLOY_MIGRATIONS`, with a ledger test. The migration file is unchanged. | `ci/migration_ledger.py`, `ci/test_migration_ledger.py` (fixes branch) | `python3 -m unittest` passes for test_migration_ledger, test_release_manifest, test_migration_runner_ci_contract, test_run_migration and test_migration_gates. `test_release_migration_rehearsal` was already failing on the base (stale pack) and gives the same result | — |
| 4 storage verification | partial | Dispatched Real Storage IT for every registered class in the 64 changed MySQL IT classes, plus qx-c1's one changed class. **40 classes / 323 tests PASS, CLEAN** on `24f5021a`; qx-c1 `WorkshopPurchaseMyBatisIT` 2/2 PASS on `76a710ce`. One genuine failure, `AuditTopicCurrentPublicationMySqlIT` (a fixture applied a migration twice after the master merge), was fixed on the fixes branch and passes 1/1 on `5dc31288`. 20 unregistered classes plus `ClubEventTransferCancellationMySqlIT`: 21 classes / 153 tests, 0 failures, taken from the integrate CI and Funds MySQL IT logs. | fixes branch: `AuditTopicCurrentPublicationMySqlIT.java` | Runs 42729051783, 42729056080, 42729060705, 42729065046, 42729146241, 42729178145, 42729934556; CI 42717309700; Funds 42717309788 | `TopicContentGraphMySqlIT` (registry 40 vs source 41) and `PostGameOutcomeMapperMySqlIT` (in no CI list) were never executed. `GenericGameplayHttpE2eIT` fails, see below |
| 5 RESULT + PR | fixed | This file; draft PR into `integrate/questo-ui-20261110` | — | — | — |

## Storage runs

| Run | Batch | Source | Result |
|---|---|---|---|
| [42729037611](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729037611) | marketing-db-acceptance | 24f5021a | plan refused: `ClubEventTransferCancellationMySqlIT` source 15 vs registered 14 |
| [42729046048](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729046048) | architecture-day-integrated-changes | 24f5021a | plan refused: `TopicContentGraphMySqlIT` source 41 vs registered 40 |
| [42729051783](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729051783) | extra-storage | 24f5021a | 4 classes, 15 tests PASS |
| [42729056080](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729056080) | core-funds | 24f5021a | 1 class, 2 tests PASS |
| [42729060705](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729060705) | p0-acceptance-ac1 | 24f5021a | 1 class, 1 test PASS |
| [42729065046](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729065046) | marketing-db-acceptance | 76a710ce (qx-c1) | `WorkshopPurchaseMyBatisIT` 2/2 PASS |
| [42729146241](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729146241) | marketing-db-acceptance | 24f5021a | 21 classes, 193 tests PASS |
| [42729178145](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729178145) | architecture-day-integrated-changes | 24f5021a | 13 classes, 112 tests PASS; `AuditTopicCurrentPublicationMySqlIT` 1 error |
| [42729934556](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729934556) | architecture-day-integrated-changes | 5dc31288 (fix) | `AuditTopicCurrentPublicationMySqlIT` 1/1 PASS |
| [42729385967](https://github.com/liboyang42-cpu/hengyin/actions/runs/42729385967) | Gameplay HTTP generic-only | 24f5021a | 25 of 26 fail, mostly a masked 系统繁忙 from `POST /api/topic/v2/update` (HTTP 500) |
| [42730235345](https://github.com/liboyang42-cpu/hengyin/actions/runs/42730235345) | Gameplay HTTP generic-only | 2bb81143 (master) | 25/26 pass (1 pre-existing 400) |

Per-class results are in `docs/questo-teardown/后端优化执行结果.md` under "Questo 波次真库复验".

## Needs owner

1. **HTTP regression on the Questo base.** `GenericGameplayHttpE2eIT` nearly all fail on `24f5021a` at `POST /api/topic/v2/update` with a masked 系统繁忙 (HTTP 500), but pass on master. The server exception lives in the HTTP artifact, which this session cannot download. Default: not fixed here; it needs a re-run where the artifact (server log) can be read. This check is not in regular CI, so #731's CI does not show it.
2. **Registry counts.** `TopicContentGraphMySqlIT` (40 → 41) and `ClubEventTransferCancellationMySqlIT` (14 → 15) need reviewed registry updates, together with the batch-total contract in `ci/test_real_storage_it.py`. Default: not changed, because the registry is a reviewed trust input and the workflow reads it from the dispatching ref.
3. **`PostGameOutcomeMapperMySqlIT`** is not in any CI list, so it never runs. Default: not added (adding it is a CI-scope change).
4. **#748 ordering.** #748's own CI is red on master (`OWNER_TYPE_INPUT_UNKNOWN`) because the Questo types exist only on #731. Default: documented; the order is to merge #748, then merge master into #731.
5. **Post-deploy classification** (#749 item 2). Until it merges, the deployment list tells the owner to run `marketing_task_registration_20261108` by hand after the new jar is up.

## Cross-lane

- qx-b2, qx-b5, qx-b6, qx-b8: qx-c1 has now added the backends they were waiting on (§23.1 write paths, video-bounty list, UG-12, post-game feedback). Their UI wiring is still open; §28.2 records this.
- qx-s0: the staging branch `4ad24b64` already carries the qx-c1 and qx-b6 migrations; the deployment list counts them.

STATUS: PARTIAL - TopicContentGraphMySqlIT and PostGameOutcomeMapperMySqlIT were never executed (registry and CI-list changes need review); the GenericGameplayHttpE2eIT regression on the Questo base is found but not root-caused (server log unavailable here).
