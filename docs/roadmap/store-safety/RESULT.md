# P1b store-and-safety — backend receipt

Branch `feature/r-store-safety`, based on `fix/r5-be-infra-stubs` (`32b425c33`). App half: chengyin-app `cloud/r-store-safety` (its `docs/roadmap/store-safety/RESULT.md` has the full finding table).

## Findings

| ID | Backend part | Status |
|---|---|---|
| SEC-14 age gate | `ums_member_age_attestation` + `POST /api/compliance/age/status`, `POST /api/compliance/age/confirm`; `app.agePolicy` (`adults` default, `guardian` reserved) | Closed for the server. `adult=false` is refused with a plain message and `errorCode=AGE_UNDER_18`; nothing is stored. Only "confirmed 18+", the time, the entry (`sign_in` / `app_launch`) and the request IP are stored. `guardian` is reported (`configuredPolicy`) but still enforced as adults (`AgeGatePolicy` TODO). |
| SEC-16 filing number | `/api/config/features` now also returns `appFilingNo` (sys_config `app.filing.icp-no`) | Closed; empty until the owner fills it. |
| SEC-13 support contact | `/api/config/features` returns `supportContact` (sys_config `app.support.contact`); compliance whitelist already had `community_content_rules / publish` | Closed; empty until set. |
| SEC-15 walk safety | New consent doc `walk_safety_notice / walk_start` (v1.0); nearby-runner coordinates snapped to a ≥ 500 m grid | Closed. Opt-in on both sides was already enforced (`selectActivePresence` and `selectNearbyRunners` both require `presence_visible = 1`); the public coordinate is now at least 500 m in both directions at any latitude (`RoamPresenceBlur`, 0.01° latitude, longitude cell widened with latitude). Stored 3-decimal points are only used for distance filtering. |

## Changes, file by file

- `chengyinhub-admin/sql/migration_20261115_store_safety.sql` (new, idempotent, rollback note at the top): `ums_member_age_attestation` table; sys_config seeds `app.agePolicy=adults`, `app.filing.icp-no=''`, `app.support.contact=''` (insert only when missing).
- `chengyinhub-system/.../business/mapper/MemberAgeAttestationMapper.java` (new): select + `insert ignore` (first confirmation time is kept).
- `chengyinhub-system/.../business/service/compliance/AgeGatePolicy.java` (new): reads and normalizes `app.agePolicy`.
- `chengyinhub-system/.../business/service/compliance/MemberAgeAttestationService.java` (new): status / confirm, under-18 refusal.
- `chengyinhub-admin/.../controller/api/ApiAgeGateController.java` (new): the two endpoints; signed-in only; strict boolean.
- `chengyinhub-admin/.../controller/api/ApiConfigController.java`: `appFilingNo`, `supportContact`, `agePolicy` keys added (existing keys unchanged).
- `chengyinhub-admin/.../controller/api/ApiComplianceController.java`: `walk_safety_notice → walk_start` scene.
- `chengyinhub-admin/src/main/resources/application.yml`: `compliance.doc-versions.walk_safety_notice: v1.0`.
- `chengyinhub-admin/.../controller/api/RoamPresenceBlur.java` (new) and `ApiRoamController.java` (nearby-runners uses it; comment updated).
- Tests: `ApiAgeGateControllerTest` (4), `MemberAgeAttestationServiceTest` (6), `RoamPresenceBlurTest` (4), plus additions to `NpcFeatureFlagsTest` (key set + new settings test) and `ApiComplianceControllerTest` (walk-safety whitelist).

No route or field was removed or renamed.

## Verification

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → success.
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test -Dtest='RoamPresenceBlurTest,MemberAgeAttestationServiceTest,ApiAgeGateControllerTest,NpcFeatureFlagsTest,RoamPresenceControllerTest,ApiComplianceF7ControllerTest' -Dsurefire.failIfNoSpecifiedTests=false` → 48 tests, 0 failures.
- `... -Dtest='ApiComplianceControllerTest'` → 13 tests, 0 failures.
- Wider run: `-Dtest='Roam*Test,ApiRoam*Test,ApiCompliance*Test,NpcFeatureFlagsTest,ApiAgeGateControllerTest,MemberAgeAttestationServiceTest,ApiLoginController*Test,CommunityPostLaunchConfigTest'` → 353 tests, 0 failures, 0 errors, 19 skipped (MySQL ITs).
- `bash ci/migration-layout-check.sh` → pass; `bash ci/migration-safety-check.sh` → pass; `python3 ci/dup_create_table_gate.py` → pass.

## Not verified

- The migration was not run against any database (no MySQL here). The `insert ignore` mapper was not exercised against MySQL.
- The account-deletion anonymization job does not touch `ums_member_age_attestation`; the row is kept as an audit record like consent records. Counsel should confirm the retention period.

## Owner actions and open questions

1. Run `migration_20261115_store_safety.sql` through the usual read-only precheck and approval.
2. Fill `app.filing.icp-no` (filing number) and `app.support.contact` in the admin parameters.
3. Mini-program: its sign-in does not call `/api/compliance/age/confirm` yet; the app asks those accounts once.
4. `guardian` policy: not built (TODO in `AgeGatePolicy`).
