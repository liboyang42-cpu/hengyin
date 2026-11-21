# W7 x-disclosures: receipt (backend)

Branch `feature/r-x-disclosures` from `origin/roadmap/be-integ` (`87aaf3dd2`). App half: chengyin-app `cloud/r-x-disclosures`.

## Issues

| ID | State | Notes |
|---|---|---|
| ONB-10 | Backend part fixed (data copy) | Players can ask for a copy of their personal information (PIPL Art. 45). The policy text waits for counsel (see the app receipt). `compliance.doc-versions.privacy_policy` stays v2.1 until counsel signs off. |
| ONB-11, ONB-13 | Not applicable here | App only. |

## Changes

**Migration**
- `chengyinhub-admin/sql/migration_20261116_x_disclosures_data_copy.sql`:
  - creates table `member_data_copy_request` (member, status, note, handler, times; index `(member_id, status)`);
  - adds a hidden admin menu row (`visible='1'`, under 审核与风控) and permission rows `business:dataCopy:list` and `business:dataCopy:edit`;
  - is idempotent, with a rollback note at the top.
- `ci/migration-dependencies.json`: adds the entry `20261116_x_disclosures_data_copy`.

**Member routes**
- `ApiDataCopyController`, behind login:
  - `POST /api/user/data-copy/request` is idempotent: it returns the open request (PENDING or PROCESSING) if there is one;
  - `GET /api/user/data-copy/latest` returns the newest request, or `data: null`.
- Reply shape: `{requestId, status, createdAt "yyyy-MM-dd HH:mm:ss", dueBy "yyyy-MM-dd"}`.

**Due date**
- `dueBy` is computed when read: created date + `compliance.data-copy.response-working-days` working days.
- The setting defaults to 15 (env `COMPLIANCE_DATA_COPY_RESPONSE_WORKING_DAYS`).
- It counts Monday to Friday only, with no holidays (written in a code comment).

**Admin**
- `DataCopyAdminController`:
  - `GET /business/dataCopy/list` (paged, filter by status);
  - `PUT /business/dataCopy/{id}/status`, which allows PENDING→PROCESSING→DELIVERED, or a move to REJECTED.
- A rejection needs a reason.
- A status change only applies if the row is still in the status the admin saw.

**Service and storage**
- `MemberDataCopyServiceImpl`, `IMemberDataCopyService`, `MemberDataCopyRequestMapper` (.java and .xml), `MemberDataCopyRequest`, `DataCopyRequestVO`.

**Erasure**
- `MemberPrivacyErasureService`, mapper and XML: account erasure deletes the member's requests.

**Tests**
- `MemberDataCopyServiceImplTest`: idempotent create, working-day maths, illegal status changes.
- `MemberPrivacyErasureServiceTest`: updated.
- `UpgradeAuthorizationBoundaryTest`: the new routes require login.

## Commands (all with `LANG=C.UTF-8 LC_ALL=C.UTF-8`)

- `mvn -B -q -DskipTests compile`: OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest=MemberPrivacyErasureServiceTest,PlayerMessagePunctuationTest,UpgradeAuthorizationBoundaryTest,MemberDataCopyServiceImplTest,AccountDeregistrationServiceImplTest,AccountDeregistrationInterceptorTest,PublicReadEndpointsSecurityChainTest -Dsurefire.failIfNoSpecifiedTests=false`: all pass, 0 failures.
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过.
- `bash ci/migration-layout-check.sh`: 通过.
- `python3 ci/role_permission_contract.py`: all ✓.

## Owner and counsel actions

- Grant the two permissions to an admin role. There is no Vue page yet; the menu row is hidden.
- Decide how copies are delivered. There is no export generator yet; the admin marks a request DELIVERED after sending the copy through an in-app message.
- Counsel:
  - Confirm the deadline.
  - Decide whether members in the deregistration cooling-off period may still ask. `AccountDeregistrationInterceptor` blocks these routes during that period today.
- The deadline ignores public holidays and 调休 working days.
