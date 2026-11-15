# Q5 money-switch: result (hengyin)

Branch `feature/r-money-switch` from `fix/r5-be-infra-stubs`. App half: chengyin-app `cloud/r-money-switch`
(runbook: `docs/roadmap/money-switch/RUNBOOK.md` there).

Every server area defaults off. No production switch value was changed; the new server ceiling defaults to `false`.

## Findings

| ID | Status | Backend part |
|---|---|---|
| MON-03 (P0) | closed (with the app branch) | `GET /api/app/money-switch` gives the app one server flag per area; admin-editable; every change audited. |
| IOS-04, FLOW-07, MON-13 | app-side | See the app RESULT. |

The ~20 existing server money properties (`chengyin.release.money-payments`, `chengyin.release.payout`, `wx.pay.enabled`,
`payout.ecommerce.*`, `payout.creator.enabled`, `withdrawal.fast-withdraw.enabled`, `merchant.commerce.self-checkout-enabled`)
are **not** rewired: they keep gating the server writes on their own (defence in depth), and the runbook lists them
as part of "money on". Folding them under this flag is an open question for the owner.

## Server money gates added (fail-closed proof)

| Gate | File:line | Test |
|---|---|---|
| Server ceiling, default off | `chengyinhub-admin/src/main/resources/application.yml:152` (`CHENGYIN_MONEY_SWITCH_SERVER_CEILING:false`) | `MoneySwitchServiceTest.shippedConfigDefaultsTheServerCeilingOff` |
| Area = ceiling AND stored row exactly on | `chengyinhub-system/.../moneyswitch/MoneySwitchService.java:51` | `serverCeilingOffClosesEveryStoredOnArea`, `ceilingOnAndStoredOnIsTheOnlyOpenState`, `defaultIsAllOffWithNoRows` |
| Only `1`/`true` is on | `MoneySwitchService.java:148` | `onlyExactlyOneOrTrueIsOn` |
| Read error → all off | `MoneySwitchService.java:137` | `readFailureClosesEveryArea` |
| Route error → all off | `chengyinhub-admin/.../controller/api/ApiMoneySwitchController.java:44-49` | `appRouteAnswersAllOffWhenTheServiceThrows`, `appRouteAnswersAllOffByDefault` |
| Switching on refused while ceiling off (off always allowed) | `MoneySwitchService.java:99` | `switchingOnIsRefusedWhileServerCeilingIsOff`, `switchingOffIsAlwaysAllowedAndAudited` |
| Audit row per change (who, when, old, new, reason), same transaction | `MoneySwitchService.java:109`; table `cy_money_switch_audit` | `changeWritesWhoOldNewAndReason`, `unchangedValueWritesNothing`, `badInputIsRefusedBeforeAnyWrite` |
| Admin permissions, granted to no role | `MoneySwitchAdminController.java:33,40,64`; migration menu rows | migration runner assertion (no `sys_role_menu` rows) |

## What changed, file by file

- `chengyinhub-system/src/main/java/com/chengyinhub/business/moneyswitch/MoneyArea.java` (new): six areas and wire keys.
- `.../moneyswitch/MoneySwitchService.java` (new): snapshot, admin view, `set` with reason and audit, audit list.
- `.../moneyswitch/mapper/MoneySwitchMapper.java` (new, annotation MyBatis).
- `chengyinhub-admin/.../controller/api/ApiMoneySwitchController.java` (new): `GET /api/app/money-switch` (`@Anonymous`,
  booleans only, `ttlSeconds: 300`).
- `chengyinhub-admin/.../controller/business/MoneySwitchAdminController.java` (new): `GET/PUT /business/money-switch`,
  `GET /business/money-switch/audit`.
- `chengyinhub-admin/src/main/resources/application.yml`: `chengyin.money-switch.server-ceiling` (default false).
- `chengyinhub-admin/sql/migration_20261115_money_switch.sql` (new): two tables, menu + button under 资金与售后,
  no rows inserted (all off), no role grants, rollback note at the top. Named with an underscore because the migration
  ledger refuses hyphens in ids (`migration_<date>_money-switch.sql` failed `ci/migration-safety-check.sh`).
- `chengyinhub-ui/src/api/business/moneyswitch.js`, `chengyinhub-ui/src/views/business/moneyswitch/index.vue` (new):
  admin page (areas, stored vs app value, open/close with a required reason, change log).
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/moneyswitch/MoneySwitchServiceTest.java` (new, 13 tests).

## Verification

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='MoneySwitchServiceTest,FastWithdrawDefaultOffTest,MerchantPaidExtrasLaunchClosedTest,WechatPayNotifyUrlTest,E2eTestProvidersProfileTest,*PermitAll*,*Anonymous*' -Dsurefire.failIfNoSpecifiedTests=false`
  → MoneySwitchServiceTest 13/13; the related config and anonymous-route tests all pass (GuestBrowseAnonymousReadTest 19,
  PermitAllUrlPropertiesPathPatternTest 4, FastWithdrawDefaultOffTest 5, …). The stack trace in the log is the
  deliberate `readFailureClosesEveryArea` case.
- `bash ci/migration-layout-check.sh` and `bash ci/migration-safety-check.sh` → pass.

## Not verified

- The migration was not run against MySQL (no database here); the SQL follows the existing guarded menu pattern.
- The Vue page was not built or opened.
- No end-to-end call from the app to a running server.

## Owner actions and open questions

- Set `CHENGYIN_MONEY_SWITCH_SERVER_CEILING=true` on TEST only, for the Staging rehearsal. Production stays false until
  "money on".
- Grant `business:moneyswitch:edit` to named people only.
- Decide whether the existing server money properties should also require this flag (today they are independent).
