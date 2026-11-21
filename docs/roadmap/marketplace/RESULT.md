# R1 marketplace — backend receipt (`feature/r-marketplace`)

Base: `origin/roadmap/be-integ`. App half: chengyin-app `cloud/r-marketplace` (its own RESULT.md).

## Findings
| ID | State | What |
|---|---|---|
| MKT-01 推广曝光位 | closed | `merchant.promotionSlot.onSale` (env `CHENGYIN_MERCHANT_PROMOTION_SLOT_ON_SALE`, default **false**). `MerchantCommerceService.createOrder` refuses new promotion-slot orders (an earlier request id still replays). `/api/merchant/commerce/capabilities` returns `promotionSlotOnSale`. Mini-program decor page renders `saleTiers`; the full `TIERS` list is still used to name existing grants, and a tap on the item is refused. Admin 城市集市 › 推广曝光位订单 lists paid orders (status 1) with refund state so the owner can decide refunds (existing original-route refund). |
| MKT-04 deals | closed (backend) | `GET /api/marketplace/deals?minLat&maxLat&minLng&maxLng` (signed in, city-sized box ≤0.5°, ≤100 rows, rate-limited). It lists only coupons an operator opted in (`marketplace_deal`): merchant coupons that can be issued now, have stock left and belong to a public shop with a published city node in the box. `POST /api/marketplace/deals/{couponId}/claim` (signed in, 10/min) gives one claim per member per offer (`uk_deal_member`). Stock and the issue window go through the existing `grantCouponWithReceipt` atomic path, and a refusal rolls the claim back. Nothing here is read by the fog map or pin ordering. |
| MKT-03 / MON-07 | closed (display) / open (sale) | `/api/merchant/public-home` adds `storefront` {products (published only) with skus and prices, `purchasable:false`}. Selling merchant SKUs needs a player checkout = real money → stays off. |
| MKT-08 filters | closed | `/api/roam/pois` takes optional `keyword` (place or merchant name), `categoryId` (merchant category_id/category_ids) and `tag`. They only narrow; LIKE wildcards are escaped; without filters it runs the original query. |
| MKT-13 leads | closed | Admin 城市集市 › 销售线索 aggregates `member_visited_poi` at places with no published roam_poi within ~60 m: name, ~1 km area, players count, last seen. Requires at least 2 different players, configurable window (default 90 days), and returns no member data. |
| MKT-02/05/06 | app only | see app receipt. |

## Files
- SQL: `chengyinhub-admin/sql/migration_20261115_marketplace.sql` (2 tables + menu, idempotent, rollback at top).
- System: `business/marketplace/{MarketplaceDealService,MarketplaceAdminService,MarketplaceStorefrontService,DealClaimException}.java`; `mapper/MarketplaceMapper.java` + `mapper/business/MarketplaceMapper.xml`; `MerchantCommerceService.java` (off-sale switch); `RoamMapper.java/.xml` (`selectPoisNearFiltered`, `selectPoisNear` kept as a default method); `PublicMerchantVO.java` (`storefront`).
- Admin: `ApiMarketplaceController`, `MarketplaceAdminController` (`/business/marketplace/*`, perms `business:marketplace:list|edit`); `ApiMerchantCommerceController`, `ApiMerchantController` (storefront), `ApiRoamController` (filters; 3-arg `pois` kept); `application.yml`.
- Console: `chengyinhub-ui/src/api/business/marketplace.js`, `src/views/business/marketplace/index.vue`.
- Mini-program: `pages/merchant/decor/index.{js,wxml}`; `scripts/uiaudit/action-ledger.json` rebuilt with `npm run audit:actions:update`.
- Tests: `MarketplaceDealServiceTest`, `MarketplaceAdminAndStorefrontTest`, `MarketplaceMapperMyBatisTest` (XML parse + H2 claim uniqueness), `ApiRoamPoiFilterTest`, new case in `MerchantCommerceServiceTest`. Also `xcx/tests/unit/r1-promotion-slot-off-sale.test.js`, `ui/tests/unit/r1-marketplace-admin-contract.test.js`. Updated anchors: `merchant-subscription-view.test.js` (`saleTiers`) and `roam-redeem-pending-contract.test.js` (`selectPoisNearFiltered`).
- Note: commit `R1 marketplace: tolerate rounding on the deals box span` also carries the ledger rebuild and that test-anchor update (swept in by `commit -a`).

## Verification
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → OK.
- Targeted: `mvn -pl chengyinhub-admin -am test -Dtest='Marketplace*Test,ApiMerchantPublicHomeTest,ApiRoamCoordinateBoundaryTest,ApiRoamPoiFilterTest,MerchantCommerceServiceTest'` → 73 run, 0 failures.
- Full admin suite: 12,692 run, 1 failure + 34 errors. The 34 errors are MySQL ITs (no database here). The failure is `ChapterFlowWriteServiceTest.openBundleReview…`, which fails the same way on the untouched base.
- Mini-program `npm run test:unit`: 17,609 pass / 22 fail before the follow-up fixes. The 2 caused by this package were fixed; the rest match the base (20 failures on the base worktree).
- Console: new contract test plus `admin-plain-language-contract` pass. 30 suites fail only because `node_modules` is missing (`vue`, `vue-template-compiler`). `npm ci` hit the time limit, so the SFC compile contract was not run.

## Not verified
MySQL-only SQL (`date_format`, `greatest`, `find_in_set`, `insert ignore` locking) against real MySQL; the admin page in a browser; the migration against a real schema.

## Owner actions / open questions
- Decide refunds for any paid promotion-slot orders (admin 城市集市 › 推广曝光位订单). Keep `CHENGYIN_MERCHANT_PROMOTION_SLOT_ON_SALE` false.
- Run `migration_20261115_marketplace.sql`, then grant `business:marketplace:list/edit` to operator roles.
- Opt coupons into the deals layer per coupon (default: none, so play-reward coupons stay rewards). Should merchants be able to opt in themselves?
- The deals list requires sign-in (the roam map does). Should anonymous viewers see deals?
- Territory-holder field on `/api/roam/pois` waits for the territory backend (not in this base).
- Merchant SKU checkout (MON-07) needs a money decision. Product `price` unit (whole yuan?) needs confirming.
- Sponsored quests (brief goal) were not built in this package.
