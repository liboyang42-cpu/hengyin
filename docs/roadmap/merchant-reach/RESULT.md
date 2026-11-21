# Q1 merchant-reach — backend receipt (hengyin)

Branch `feature/r-merchant-reach`, based on `fix/r5-be-infra-stubs`. App side: `chengyin-app` branch `cloud/r-merchant-reach`.

## Findings

| ID | State | Notes |
|---|---|---|
| MER-01 / FLOW-12 | Closed with the app branch | Server side: `feature.flag.merchantNonMoney` is the one switch the app reads; it also opens the counter desk. Redemption itself still uses the existing endpoints (`/api/verify/groupcode/redeem`, `/api/coupon/verification`, `/api/registration/scan_*`). |
| MER-02 / MER-03 / MER-04 | Closed on the app branch | The server endpoints were already live for the mini-program; the app now gates them with the same switch. |
| MER-05 | Closed on the app branch | No server change: `merchant_registration` already accepts `categoryIds`, `locationLat`, `locationLng`, and `/api/publisher/identity` accepts source `merchant_apply`. |
| MER-07 | Closed | Cohort defined (`RETURNING_WITHIN_28_DAYS`) in the CITY value report; new weekly proof report. |
| MER-12 | Closed | The desk's arrivals and resolve routes are now used by the app; the switch opens the desk. Reconciliation is not shown in the app (see open questions). |
| MER-13 | App only | — |

## What changed

- `chengyinhub-admin/.../api/ApiConfigController.java`: `/api/config/features` now also returns `merchantNonMoney` (sys_config `feature.flag.merchantNonMoney`, strict `true`/`1`, default off).
- `chengyinhub-system/.../support/MerchantDeskSwitch.java`: `isEnabled()` is true when either `merchant.desk.f4.enabled` or `feature.flag.merchantNonMoney` is on. Product and price writes still need `feature.flag.merchantDeskProducts`; nothing about money changed.
- `chengyinhub-admin/sql/migration_20261115_merchant_reach.sql`: idempotent insert of `feature.flag.merchantNonMoney = false`; rollback note at the top. No table or column added.
- `chengyinhub-system/.../merchantproof/MerchantWeeklyProof.java` (new): pure arithmetic for the weekly proof and the shared cohort split.
- `chengyinhub-system/.../merchantproof/MerchantWeeklyProofService.java` (new): reads committed rows only, for the signed-in member's own shop (BASIC_READ): ticket redemptions (`redemption` + `cms_registration`), coupon redemptions (`sms_coupon_history` on the owner's coupons), map-node redemptions (`business_audit_event` CITY_NODE_REDEEM), node check-ins (`roam_poi_reward_log` at the shop's `roam_poi`), CITY board entries (`city_value_visit` at points with the shop's `merchant_id`). Week = Monday 00:00 to next Monday, Asia/Shanghai. Writes nothing.
- `chengyinhub-admin/.../api/ApiMerchantProofController.java` (new): `POST /api/merchant/proof/weekly`, body `{}` or `{"weekStart":"yyyy-MM-dd"}` (no future weeks, at most one year back). Refusals answer HTTP status == code with `errorCode` (same as `ApiMerchantGapController`).
- `chengyinhub-system/.../citygame/MerchantCityValueReportService.java`: `byCohort` was `MISSING_DEFINITION`; now `MEASURED` with `definition = RETURNING_WITHIN_28_DAYS`, `count` = returning players, `rate` = `returning/players`, plus `players`, `returning`, `firstTime`, `lookbackDays`; `MISSING_COVERAGE` when the window has no visits. The app's existing decoder reads `status/count/rate` unchanged.

Definitions (also in the class comments):
- verified visit = one player on one local day with at least one staff verification (ticket, coupon or map-node redemption);
- play at the node = a check-in at one of the shop's map nodes or an entry into a CITY point the shop operates; `MISSING_COVERAGE` when the shop has no node or point;
- redemptions = all counter verifications, split ticket / coupon / cityNode;
- returning player = a player with any of those touches this week who also had one in the 28 days before the week.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                      # ok
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='MerchantWeeklyProofTest,MerchantNonMoneySwitchTest,NpcFeatureFlagsTest,ApiMerchantDeskControllerTest,MerchantCityValue*,MerchantDesk*,ApiConfig*' \
  -Dsurefire.failIfNoSpecifiedTests=false                                      # all pass
```
Surefire: MerchantCityValueReportServiceTest 8/8, MerchantDeskMapperSqlTest 5/5, MerchantWeeklyProofTest 5/5, MerchantDeskServiceImplTest 23/23, MerchantDeskCodePolicyTest 12/12, MerchantNonMoneySwitchTest 4/4, ApiMerchantDeskControllerTest 12/12, NpcFeatureFlagsTest 11/11.

Updated tests: `NpcFeatureFlagsTest` (exact key set now includes `merchantNonMoney`, default false), `MerchantCityValueReportServiceTest` (cohort is defined instead of `MISSING_DEFINITION`).

## Not verified

- The weekly proof SQL ran only against a mocked `JdbcTemplate`; it was not run against MySQL. Column names were checked against `sql/base_schema_20260818.sql` and the 2026-11 migrations (`city_game_point.merchant_id` comes from `migration_20261114_gaps_a_city_r491.sql`).
- `sms_coupon_history.use_time` is assumed to be set by `redeemCoupon`; coupons are attributed to the shop through `sms_coupon.member_id` = shop owner, the same check `/api/coupon/verification` uses.

## Owner actions and open questions

1. At deploy, run the migration and set `feature.flag.merchantNonMoney` to `true` when merchant non-money writes should be live in production. Leaving it `false` keeps production as it is today.
2. Turning it on also opens the counter desk (`/api/merchant/desk/*`), including its read-only reconciliation and frozen-batch views (FINANCE_READ). Say if those reads must stay behind `merchant.desk.f4.enabled` only.
3. Coupon publish/stop stay behind the money switch (the app keeps `ReviewedAreaSwitch.merchantWrites`). Confirm whether coupons count as "money" under the 2026-11-15 ruling.
4. Spend is still not captured in the proof report (no order total is tied to a visit). Needs a product decision on which amount counts.
5. Staff accounts: the app shows the "My shop" row for accounts whose role is `merchant`. If staff keep the `player` role after accepting an invite, the server should expose the shop membership on the account (or the app should call `/api/merchant/access/me` on 我的).
