# S3a merchant-plans: result (hengyin)

Branch `feature/r-merchant-plans` from `origin/roadmap/be-integ` (e1310ef01). App half: chengyin-app `cloud/r-merchant-plans`.
Scope: build steps 1 to 3 of `s3-merchant-plans.md` and their tests. Sponsored quests (step 4) are the `sponsored-quests` package; no campaign code here.
This run picked up after an earlier run hit a usage limit. Its pushed commit cb3a8de87 (the backend) is kept unchanged; the tests, the console page and this receipt were added on top. Nothing was force-pushed.

## Findings

| ID | Status | What now exists |
|---|---|---|
| MON-04 (P1) take hard-coded | **Closed** | The take is a setting per product type (official ticket, third-party ticket by source club / organiser, bundle, template) in the console. Every change is audited. `cy_take_rate` starts empty, so every product keeps today's value until someone edits it. |
| MON-07 (P1) merchant SKUs cannot be sold | **Partly closed** | Merchant **plans** are configured products that can be sold, but only behind the money switch. Merchant goods SKU self-checkout is unchanged: still off, price unset. |
| MON-15 (P2) no merchant / brand products | **Partly closed** | Built: merchant plans (Free, Basic, Pro, plus a per-visit plan that is not offered) and verified-visit billing. Not built here: sponsored quests (separate package). Season pass, cosmetics and insight products are not in this package. |

## Money

All charges here go through `MerchantPlansMoneyGate`. The gate is open only when money switch area `merchantMoney` is effectively on, meaning the server ceiling AND the stored row are both on. Any error reads as closed. The charges are:

- plan checkout, which also needs `merchant.commerce.self-checkout-enabled`;
- paid grants and order confirmation;
- visit accrual;
- monthly invoices.

While the switch is closed:

- plans and the current plan stay readable;
- `salesState` is `AVAILABLE_SOON`;
- the console shows 「即将开放」 and disables the charge buttons;
- accrual writes nothing, so visits made while the switch is closed are never charged later.

Moving a merchant to Free is always allowed. Nothing calls a payment provider: checkout records a PENDING order, and operations confirm it once payment arrives. No production switch value is changed.

## Changes, file by file

**Commit cb3a8de87, backend.** Unchanged in this run.
- `chengyinhub-admin/sql/migration_20261115_merchant_plans.sql`: six tables, four plan seed rows, the console menu 商家套餐与抽成 under 资金与售后, and four button permissions granted to no role. Idempotent, with a rollback note. Entry added in `ci/migration-dependencies.json`.
- `business/merchantplans/TakeProduct.java`: product types and sources, mapped to `settlement_rule` biz types.
- `business/merchantplans/TakeRateService.java`: the configured take, cached for 10 seconds; a read error means today's values. It also provides the console view and the audited `set`.
- `business/merchantplans/MerchantPlan.java`, `MerchantPlanService.java`: plans with feature flags (`weeklyProofDetail`, `extraNodeGames`, `dealsOffersPerMonth`), the current plan, checkout, grant, confirm-paid and plan edits. Free stays free and keeps today's features.
- `business/merchantplans/MerchantVisitBillingService.java`, `MerchantVisitBillingJob.java`: one ledger row per (shop, player, local day) with a staff verification, the same definition as the weekly proof's verified visit. Also hourly accrual and monthly invoices.
- `MerchantPlansMoneyGate.java`: the money gate described above.
- Changes to existing code that keep every method signature, so the collection-model package picks up the configuration without edits:
  - `SettlementRuleServiceImpl#getActiveRule` passes generic rules through `TakeRateService.applyTo`. Per-merchant contracts are never changed.
  - `MarketingProductFulfillmentServiceImpl` gets the bundle line rate from `bundleRate(themeFrozenRate)`.
  - `CreatorEarningsService` gets the template creator share from `templateCreatorShareBps(configured)`.
  - `MerchantWeeklyProofService#touches` is now public, and `Kind#staffVerified()` was added.
- Controllers:
  - `MerchantPlansAdminController` serves `/business/merchant-plans/**`. It requires a reason for every write and uses `@PreAuthorize` permissions that match the migration.
  - `ApiMerchantPlanController` serves `POST /api/merchant/plan` (read, used by the app), `/api/merchant/plan/checkout` (merchant console only) and `/api/merchant/plan/visit-billing`.

**This run.**
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/merchantplans/MerchantPlansTest.java` (new, 15 tests):
  - default takes unchanged, including when the database read fails;
  - per-source take and the product-wide fallback;
  - contracts untouched;
  - shares keep their sum;
  - a take that would make the merchant share negative is ignored;
  - a reason is required and one audit row is written;
  - the console shows today's values;
  - the migration seeds no take rows and does not write to the money switch;
  - Free keeps today's features;
  - checkout, paid grant, confirm-paid, accrual and invoices are all refused or write nothing while the switch is closed;
  - checkout stays closed with the switch open until self-checkout is enabled;
  - billable visit = distinct staff-verified player per day;
  - the gate reads closed on any error.
- `chengyinhub-ui/src/api/business/merchantPlans.js`, `src/views/business/merchantplans/index.vue` (new): the console page, built with existing Element components and no new styles. It has four tabs:
  - 平台抽成: today's, configured and in-force value per product and source; set, or go back to today's value;
  - 套餐设置: price, per-visit fee, feature flags, on sale;
  - 订单与开通: orders, confirm paid, grant or move to Free, plan periods;
  - 到店计费: invoices and visit rows, with no player identities shown; re-accrue a day; issue a month.

  When the switch is closed the page shows 「即将开放」 and disables the charge buttons.
- `chengyinhub-ui/tests/unit/s3a-merchant-plans-admin-contract.test.js` (new): checks the page's routes against the controller, checks permissions across page, migration and controller, checks that the charge buttons follow the switch, and checks that reasons are required.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests install                                   # rc=0
mvn -B -q -pl chengyinhub-admin test -Dtest=MerchantPlansTest                               # 15/15 pass
mvn -B -q -pl chengyinhub-system,chengyinhub-admin test -Dtest='MerchantWeeklyProofTest,SettlementRuleServiceImplTest,
  ClubTopicSettlementRuleTest,MarketingProductFulfillmentServiceImplTest,CreatorEarningsPolicyTest,
  CreatorEarningsAccrualMyBatisTest,SettlementRulePlatformAccount*Test,ApiCreatorEarningsControllerTest,MerchantPlansTest'
  # 95 run, 1 error: ApiCreatorEarningsControllerTest.onlyGetRoutesAndPayoutWritesStayClosed
  #   (NoSuchMethodException ApiAppGapsWorkshopController.notEnabled; that controller is untouched by this branch)
bash ci/migration-safety-check.sh                                                            # 迁移安全门禁:通过
node ci/api-existence-gate.js                                                                # rc=0
python3 ci/role_permission_contract.py                                                       # all ✓
python3 -m pytest -q ci/test_role_permission_contract.py ci/test_migration_gates.py ci/test_migration_ledger.py  # 227 passed
node --test tests/unit/s3a-merchant-plans-admin-contract.test.js tests/unit/admin-sfc-template-compile-contract.test.js
  tests/unit/admin-plain-language-contract.test.js                                           # all pass (vue-template-compiler installed --no-save)
```

## Not verified

- The migration was not run against MySQL, and there is no MyBatis or JDBC integration test for the new SQL. The services use `JdbcTemplate` and were tested with mocks.
- The console page was not rendered in a browser.
- Visit accrual was not tested against real redemption data.

## Owner actions and open questions

1. **Sell merchant plans in the iOS app through in-app purchase?** Today the app only shows the plans, the current plan and what each includes. It has no purchase button, link or call to action (App Review 3.1.1 / 2.3.1). Plans are sold in the console only.
2. Confirm the prices: Basic ¥199 and Pro ¥499 a month are the 05 report's hypothesis and are seeded as such. The feature amounts per plan are also placeholders: Basic gives full weekly-report detail, 2 extra node games and 2 deals offers a month; Pro gives detail, 5 node games and 8 offers.
3. Per-visit plan: it is seeded as not offered and with no fee. Set a fee and turn it on sale in the console if wanted.
4. **The feature flags are stored and shown, but not yet enforced.** The weekly-proof detail, the node-game allowance and the deals-offer quota do not read `MerchantPlanService.feature(...)` yet. Free = today's features, so nobody loses anything. Wiring each feature is follow-up work, and the owners of the deals layer and node games need to decide the details.
5. To sell plans: grant the `business:merchantplans:*` permissions to a role, turn on money area `merchantMoney` (the server ceiling must be on), and set `merchant.commerce.self-checkout-enabled` if merchants should place orders themselves. Visit invoices only record amounts. How merchants pay them is an operations decision.
6. There is no mini-program plan page yet. The checkout API exists for it.
7. No hard-coded secrets were found in the files touched.
