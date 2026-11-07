# ROUND14 MONEY — launch eligibility and exploration money

Status: SOURCE READY; backend compilation and focused Java/MySQL tests await coordinator evidence. This is not a business walkthrough or release acceptance.

Base: public `hengyin` commit `4aff0697d`. Worktree: `cy-r14-money-1105`. Ownership: MONEY source and tests only; ACCESS guards/read endpoints and all player/editor frontend are untouched. Source manifest: `ROUND14-MONEY-MANIFEST.json` (36 exact file hashes). The coordinator requested one unified tested Java commit; this worker has not triggered the old compile hook, committed Java, pushed, merged, deployed, accessed production, or opened DevTools.

## Approved changes

| Item | Implemented behavior | Boundary |
| --- | --- | --- |
| S1 | A new `FOUR_STORE_REDEEM` finalization refuses before freezing compensation/payee terms or changing sale status. New purchases with such a frozen contract refuse too. | Historical SELLING finalization replay still reads its original immutable contract and timestamp. Historical FINAL, compensation services, migrations and liabilities remain intact. |
| S2 | Opening a new ordinary exploration session and selling/retrying an activity ticket require session club = content owning club. The openable list omits another club's exploration themes. | Existing paid callbacks and settlement alarms are preserved. No content revenue split is invented. Platform-owned themes without a club keep their separate path. |
| S3 | Ordinary exploration uses ACTIVE per-player `redemption` facts at that particular merchant, never a group scan or general attendance. Each registration counts once per shop across multiple chapter scans. | Existing group-session counting remains in place for other products. Refunded registrations and complaint-held registrations are excluded under the existing settlement/release rules. |
| S3 terms | Fixed co-op terms reuse their frozen snapshot check. Chapter offers use the immutable sale canonical fee and payer club. New ordinary canonical drafts freeze `paymentRoute=SESSION_SETTLEMENT`; historical `CLUB_DIRECT` canonical JSON still decodes. | Two cash contracts for one shop, inconsistent per-head fees, missing frozen terms, and fees greater than organiser share or per-registration organiser line refuse atomically. |
| S3 routing | A normal club-owned exploration scan records participation but does not accrue a second platform payable or club-direct receivable. | An existing off-app liability makes in-app settlement refuse; nothing deletes or rewrites the liability. Old four-store/platform-only paths are preserved. |
| S3 callers | Pending-money estimates now read shop-specific exploration heads; direct-offer merchant ledger rows expose their frozen per-head fee from the original line. | Settlement still decides under its existing claim/transaction boundaries. No UI file changed. |
| S4 | Successful manual evidence verification records terminal manual refund facts and reverses each original organiser/platform/merchant share, with existing balance debit and debt mechanisms. | Verification CAS, verified evidence digest, refund facts, ledger adjustment, balance/debt, registration refund state and pending-line voiding share the same transaction. No external refund is dispatched. |
| S4 safety | Handler/verifier separation and controlled versioned evidence validation stay in force. Only the winning verify CAS invokes the bridge; exact verify replay returns its receipt. Refund facts use deterministic case/registration keys and `payout_status=4`. | Missing original ledger/platform recipient, mismatched order membership or paid snapshot, outstanding pending refund, and amount beyond remaining approved-refund cash refuse verification. The terminal manual case is not silently marked complete on failure. |

The platform remains 3%; withdrawal remains end + 14 days. Percentage terms keep their existing behavior. No dependency, migration, production config, JWT/Redis/RBAC change, or new schema was introduced.

## Verification

Executed on the Mac, without backend infrastructure:

- `python3 -m unittest ci/test_funds_it_gate.py`: **45 passed**.
- Python XML parsing of both changed mapper XML files: passed.
- `git diff --check`: passed.
- Added gated MySQL source count: `ClubSessionMerchantFeeMySqlIT` **8 → 13 @Test**, updated together in `ci/funds_it_gate.py` and its selftest fixture.

Prepared Java/MySQL regression cases (NOT RUN by this worker):

1. Three ¥39 exploration registrations, two distinct shops: one shop sees two tickets, with a duplicate chapter scan on one ticket; the other shop sees only one ticket. Expected fees **16.00 / 5.00**, platform **3.51**, organiser **92.49**; total **117.00**, and a settlement rerun changes nothing. Attendance alone does not count. Pending-money estimate is asserted.
2. A frozen chapter-offer fee above the organiser share refuses and rolls back the settlement claim. A pre-existing club-direct liability also refuses. With an isolated fixture liability cleared, two chapter scans by the same ticket at one shop pay **8.00 once**.
3. Manual refund **19.50 of a settled 39.00 ticket** with fixed merchant fee **8.00**: original shares reverse **14.91 / 0.59 / 4.00**. With balances **0.50 / 0.10 / 0.20**, balances reach zero and debt is **14.41 / 0.49 / 3.80**, total **18.70**. The refund fact is terminal manual-verified, never dispatchable. Replay cannot create another fact/debt/debit.
4. A manual remainder after an earlier half refund uses the cumulative per-payee target minus recorded prior reversals, so platform 1.17 is reversed as 0.59 then 0.58, never twice as 0.59. The complete ticket reverses exactly 39.00 without a spurious one-fen debt.
5. Missing manual proof, amount above original remaining cash, or incomplete original platform ledger cannot write a refund fact or debit balances.
6. Existing manual verification service regression now asserts that only the winning verification invokes refund accounting and exact replay does not invoke it again. Existing missing/corrupt evidence and same-operator checks remain.
7. A successful ordinary exploration scan, including replay, creates neither off-app payable nor club-direct receivable. Historical non-session accrual tests retain their original path.
8. New four-store sales refuse; original SELLING contract replay remains readable. Normal purchase/quantity/source-attribution regression fixtures now use an explicit ordinary exploration contract. Old new-sale compensation-freeze tests are superseded by the launch refusal test; historical FINAL/compensation suites are unchanged.

Focused suite list supplied to coordinator:

`PaymentCallbackFenceEntryTest`, `ClubSessionMerchantFeeMySqlIT`, `TopicSaleFinalizationServiceImplTest`, `ExploreSaleSnapshotGateTest`, `ClubSessionServiceImplTest`, `ActivityCancelManualRefundCaseServiceImplTest`, `MerchantPayableServiceTest`, `TopicSaleCanonicalV1Test`, `TopicSaleCanonicalBuilderTest`, `ExploreQtyGateTest`, `ExploreReserveWiringTest`, `ShopDayNoTicketLimitTest`, `ShopDayPurchaseFundsPolicyTest`, `TicketSourceAttributionTrustBoundaryTest`, `ShopDayOneTicketMySqlIT`, `ExploreAllianceMvpRealDbIT`, `RefundServiceImplTest`, `RefundMultiPartyClawbackTest`, `RefundPlatformShareClawbackTest`, `ClubSessionMerchantFeeSettlementTest`.

Other manually assembled payable-service fixtures were wired to the new mapper dependency (`B03AssetMybatisIntegrationTest`, `FundsChainO07PinMySqlIT`, `OWN_C04_20MySqlIT`). These must remain in the coordinator's broader regression sweep.

## Unverified and retained boundaries

- Backend compile, all Java tests, MySQL transaction/concurrency tests, and shared CI: await coordinator evidence. This source report does not claim they passed.
- No HTTP multi-account exploration sale/refund walkthrough, bank/WeChat receipt confirmation, phone, withdrawal, or real-money operation was run. A verified uploaded receipt remains distinct from provider-confirmed arrival.
- Historical off-app liabilities are intentionally refused by the new in-app fee path and require explicit reconciliation; they are not silently converted or paid again.
- Existing settlement and refund concurrency fences are reused; new claims/debts are not a replacement ledger.
- Coordinator confirmed S1/S2 also close the pre-2026-08-14 no-snapshot exemption for NEW purchases/payment attempts. The gate now requires a frozen contract for every new exploration sale; already-paid callbacks and stored data are untouched. A verified historical SUCCESS replay regression asserts the purchase-only snapshot mapper is never invoked. Caller review: snapshot gate is called by single/multi/self-play creation and payment-attempt validation; neither `handleWechatPayCallback` nor `applyConfirmedWechatPaymentWithinTransaction` nor their shared admission/complete methods call it.

STATUS: PARTIAL — source prepared; coordinator compilation/tests, final commit receipt and real runtime/device acceptance remain unverified.
