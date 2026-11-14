# Game import review: handover (feature/game-import-review)

Branch `feature/game-import-review`, cut from `origin/integration/backend-app-r2` (2e6f6651c). VPS worktree: `/data/workspace/be-import`.
Handed to Cursor on 2026-11-13 before the work was finished (coordinator asked to save quota).

## Done

- **Submission** `POST /api/external-game/submissions` (multipart). Send either `archive` (the app's `ExternalGamePackageArchive` JSON: `{"manifest":{...},"files":{"path":"<base64>"}}`) or `manifest` + `entry` (single-HTML package). Optional fields: `requestId` (idempotent per author) and `monetization` (must be `NONE`). The new submission is `PENDING`.
  - `ExternalGamePackageValidator` mirrors the app's `ExternalGameValidator` in Core/ExternalGameImport.swift: id pattern, x.y.z semver, ratings 4+/9+/12+/17+, safe relative paths, extension allowlist, forbidden permissions, domain rules, payment-token scan, and limits of 10 MiB package, 5 MiB per file and 200 files. It recomputes the app's package SHA-256. A Swift-generated test vector checks the hash.
  - The server is stricter than the app in four ways: unknown manifest keys are rejected, domains must be ASCII, the token scan runs on bytes, and `monetization`, `monetizationDeclaration` or any price/products key is rejected unless it is `NONE`. A DB CHECK also enforces `monetization = 'NONE'`.
  - **Server-issued signature.** Ed25519 over the app's canonical `v1|id|name|version|entry|perms|rating|domains|sha256` payload. The client `signature` is ignored. The key is a per-environment secret: `chengyin.external-game.signing-private-key`, or env `CHENGYIN_EXTERNAL_GAME_SIGNING_KEY`, as base64 of a 32-byte seed or of PKCS#8. With no key, submit and download return 503 `SIGNING_UNCONFIGURED`. The public key is at `GET /api/external-game/signing-key` (anonymous). Ed25519 was chosen over HMAC so the app can verify without holding any secret.
- **Author routes:**
  - `GET /api/external-game/submissions/mine?status&cursor&limit`
  - `GET /api/external-game/submissions/{id}`: includes the timeline and `rejectReason`.
  - `POST .../{id}/cancel`: allowed from PENDING or ESCALATED.
  - `POST .../{id}/resubmit` (multipart): allowed from REJECTED or CANCELLED, needs the same game id and a strictly higher version, and links back through `previousSubmissionId`.
  - Rules: a game id belongs to the author who submitted it first (`GAME_ID_TAKEN`). Each game has at most one open submission, enforced by a generated-column UNIQUE.
- **Admin routes** `/business/external-game` (`ExternalGameReviewController`). They reuse `business:audit:list` for reads and `business:audit:review` for decisions.
  - `GET review/list`: ESCALATED first, then PENDING. Returns `counts` and `reviewTimeoutDays`, plus `openReports` per row.
  - `GET review/{id}` (files, timeline, reports) and `GET review/{id}/package`.
  - `POST review/{id}/approve` with an optional note, `reject` (reason required), `escalate` (reason required, PENDING only), and `review/sweep-timeouts`.
  - `GET reports`.
- **State machine:**
  - PENDING → APPROVED, REJECTED, ESCALATED or CANCELLED.
  - ESCALATED → APPROVED, REJECTED or CANCELLED.
  - APPROVED, REJECTED and CANCELLED are terminal.
  - Every transition inserts an `external_game_review_event` row (actor AUTHOR, ADMIN or SYSTEM) in the same transaction.
- **Timeout:** a submission PENDING for `chengyin.external-game.review-timeout-days` (default 3, range 1..60) moves to ESCALATED with source `TIMEOUT` and reason `REVIEW_TIMEOUT`, actor SYSTEM. This runs in `ExternalGameReviewTimeoutJob` every `chengyin.external-game.timeout-sweep-ms` (default 600000) and before every admin queue read.
- **Public catalog** (anonymous):
  - `GET /api/external-game/catalog` lists the newest APPROVED version per game.
  - `GET /api/external-game/catalog/{gameId}` returns the detail plus `downloadPath`. The path is Ed25519-signed and expires after `chengyin.external-game.download-link-ttl-seconds` (default 600).
  - `GET .../package?submissionId&exp&sig` returns the stored archive with the server-signed manifest. Errors: 403 `DOWNLOAD_LINK_INVALID`, 410 `DOWNLOAD_LINK_EXPIRED`, 410 `PACKAGE_SUPERSEDED`.
- **Report** `POST /api/external-game/reports` with `{requestId, gameId, version?, sha256?, reasonCode, detail?, blocked?}`.
  - `reasonCode` is one of INAPPROPRIATE_CONTENT, MALICIOUS_CODE, PAYMENT_OR_SCAM, COPYRIGHT, BROKEN or OTHER. OTHER requires `detail`.
  - Side-loaded ids are accepted and come back as `knownToCatalog:false`.
  - Each player can have one open report per game (409 `REPORT_ALREADY_OPEN`). Replaying the same `requestId` is idempotent.
- **Migration:** `chengyinhub-admin/sql/migration_20261116_external_game_review.sql` adds new tables only: external_game, external_game_submission, external_game_package (LONGBLOB), external_game_review_event and external_game_report. Its ledger entry `"20261116_external_game_review": []` is in `ci/migration-dependencies.json`. `ci/migration-safety-check.sh`, `migration-layout-check.sh` and `role_permission_contract.py` pass.
- **Tests (VPS, 3c54bf44b):** `ExternalGamePackageValidatorTest` passed 9 of 9. `ExternalGameReviewMysqlIntegrationTest` passed 7 of 7 on real MySQL (be-int-mysql 3330, `CHENGYIN_R2_MYSQL_IT=1`, `-Duser.timezone=Asia/Shanghai`); it covers timeout → ESCALATED, the queue order, resubmit, the signed download and its expiry, reports, and the DB guards.

## Found in the 8121 curl run and fixed in the WIP commit (not re-verified)

The Druid WallFilter rejects `WHERE 1 = 1`, so `GET /api/external-game/catalog` returned 500 on 8121. The plain-JDBC IT does not use Druid, so it did not catch this. The fix moves the APPROVED filter into WHERE and uses `WHERE id > 0` for admin reports. It compiles (see the commit), but it has not been re-run on 8121.

## Left to do

1. Restart 8121 against `cy_import_test` and finish the curl checks:
   - Start with `/data/workspace/be-import-out/run/start.sh` after rebuilding the jar into `/data/workspace/be-import-out/app.jar`. It uses Redis db 10 and a random signing key per start.
   - Player login: SMS code in Redis db 10, then `POST /api/login/phone`.
   - Admin: `cy_import_test` has **no sys_user rows** because `build_schema.py` does not seed them. Create a TEST-only admin with a random password and disable the captcha in that DB only.
   - Verified so far on 8121: `GET /api/external-game/signing-key` returned 200 with `configured:true`, and submit without a token returned 401.
2. Write `docs/delivery/game-import-review/RESULT.md` (routes, JSON, status machine, config keys, curl results).
3. Optionally add a controller-level test for multipart parsing (`ApiExternalGameController.read`).
4. App follow-up (not this repo): drop the hard-coded HMAC key in ExternalGameImport.swift, verify the Ed25519 signature with the key from `/api/external-game/signing-key`, and mark the new routes reviewed in ChinaReviewedRouteCapability.swift.
5. The DB `cy_import_test` and the directory `/data/workspace/be-import-out` belong to this work. Delete them when you are done. The 8121 process is stopped.

## Files

- chengyinhub-system/src/main/java/com/chengyinhub/business/externalgame/{ExternalGamePackageValidator,ExternalGameSigner,ExternalGameReviewService,ExternalGameReviewTimeoutJob}.java
- chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiExternalGameController.java
- chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/ExternalGameReviewController.java
- chengyinhub-admin/src/test/java/com/chengyinhub/business/externalgame/{ExternalGamePackageValidatorTest,ExternalGameReviewMysqlIntegrationTest}.java
- chengyinhub-admin/sql/migration_20261116_external_game_review.sql, ci/migration-dependencies.json
