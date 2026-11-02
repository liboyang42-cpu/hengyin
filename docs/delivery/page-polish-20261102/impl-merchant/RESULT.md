# impl-merchant: result (2026-11-02)

Branch `claude/page-polish-20261102-impl-merchant`, from `integrate/page-polish-20261102` (940846c3). Scope: `chengyinhub-xcx/pages/merchant`, `pages/coop`
and the components they mount. Audit read: `claude/mini-polish-audit-20261102-merchant` REPORT.md.

## Summary
- Done: all P0/P1 rows in scope except M-22 (open decision): M-01, M-03, M-07, M-10, M-11, M-13, M-20. M-33 was already fixed by X-2 on the base branch.
- Done: cross-page fixes X-1 (with M-12 and M-16), X-3 (with M-02, M-25 and M-28), X-4 and X-5. Bugs B-1, B-3 and B-4 are also fixed.
- B-2: I did not change it. The field is right and the label is wrong (see below).
- The audit had no `spacing` or `radius` rows in this lane. I did not change any spacing or radius values on existing elements.
- Shots: there are 3 before/after pairs from the rig. Rig identity 9002 has only seeded data, and every money value is `¥0.00`, so the money count cannot be seen there (see "Not exercised").

## Findings table

| ID | Result | Files changed | Evidence |
|---|---|---|---|
| M-01 | Done. `.rv-aftercare-alert` now has the `--pm-w` entrance rule, with a 60 ms delay. It sits between the actions (40 ms) and the first section (80 ms). | pages/merchant/index/index.wxss | Code: the audit claim held, because the alert had no entrance rule. The rig has no aftercare or predict alert for 9002, so this was not shot. |
| M-02 (X-3) | Done. The `nth-child(2..n+5)` ladder is replaced by `--pmi` on the three sections: `80ms + min(pmi,4)*40ms`. | index.wxss, index.wxml | Code. |
| M-03 / B-4 | Done. The `countIn(that,'rvK')` call is removed. `rvK` is not read in the WXML (grep shows 0 hits), and `countRevenueTo` still drives the revenue number. | pages/merchant/index/index.js | grep `rvK` returns no hits in WXML. |
| M-04 / B-4 | Done. `revBars` is no longer computed or setData'd, and it was never rendered. The `revenue7d` shape validation is kept (malformed-response test passes). I also removed its entry from `scripts/baselines/u4-dead-setdata-field.json`. | index.js, scripts/baselines/u4-dead-setdata-field.json | `setdata-dead-field-lint` passes. Request: the reserved `tests/lint/ui-integrity-baseline.js:1212` still lists `revBars` as debt. The integration step can drop that line. |
| M-07 (X-1) | Done. The ledger hero `overview.personalNetDisplay` now renders through `cnt.money(…, lgK)`. `pageMotion.countMoney(this,'lgK')` runs on the first valid overview only. | pages/merchant/ledger/index.js, index.wxml | Code. Not visible on the rig because the amount is ¥0.00. |
| M-12 (X-1) | Done. order-detail and batch-detail heroes use `odK` / `bdK`, and the count runs on the first detail only. I added `this._unloaded = true` in `onUnload` so the counter stops after the page closes. | ledger/order-detail/*, ledger/batch-detail/* | `merchant-ledger-display-contract` passes. Its vm stub got a no-op `page-motion`. |
| M-13 (X-1) | Done. `cy-stat-card` has a new optional `countK` prop, and the value renders as `cnt.money(item.value, countK)`. `sizeStep` is still computed from the real value, so the font size does not change mid-count. `scene-merchant-profit` passes `count-k="{{pfK}}"` and counts on the first load. | components/cy/stat-card/index.js/.wxml, components/cy/scene-merchant-profit/index.js/.wxml | `stat-card-component` test passes. Note: both components are shared. `cy-stat-card` is also used by `subpackageMember/coupon` (no prop there, so nothing changes), and `scene-merchant-profit` is also mounted by `subpackageA/pages/assetcenter/earnings`, which gets the same money landing. |
| M-16 (X-1) | Done. `cy-settlement-detail` has a new `countK` prop, and `.sd__amount` renders through `cnt.money`. The page counts `sdK` on the first detail. | pages/coop/settlement-detail/index.js/.wxml, pages/coop/components/settlement-detail/index.js/.wxml | Code. |
| X-1 | Done, using the kit's `countMoney` and `money()`. I made no kit changes. | (above) | `page-polish-kit` test passes. |
| M-10 (X-5) | Done. There is now one `cy-tabs` (with `shared-slab`) above the state chain in `.acl-tabbar`. It shows for `empty`, `ready`, and `loading && listShell`. `listShell` is set in `loadList`, so the first access check still shows the full skeleton. Switching tabs now replaces only the list area with the skeleton. | pages/merchant/aftercare/index.js/.wxml/.wxss | Shots `before-/after-aftercare-switching.png`: the network is held for 2.5 s, then 「处理中」 is tapped. Before: the tabs disappear. After: the tabs stay and the pill sits on 「处理中」. |
| M-11 (X-5) | Done. verify-records has one `cy-tabs` (with `shared-slab`) in `.vrl-tabbar`, shared by the empty and ready states. | pages/merchant/verify-records/index.wxml/.wxss | The rig shows 「已核销 0 / 未核销 0」 tabs above 「还没有已核销记录」 (no visual diff in the empty state, so it was not attached). |
| M-25 (X-3) | Done. `.vrl-group` is staggered by `--pmi`, so the first group no longer waits 30 ms. | verify-records wxss/wxml | Code. |
| M-28 (X-3) | Done. `.inv` is staggered by `--pmi:{{index}}`, appended to the 5 existing `--pm-l` styles. The `--pm-l` style stays on the rows. I kept the edit minimal and did not move it to the container. | pages/coop/list/index.wxml/.wxss | Code. |
| X-3 (others) | Done. The ladders in crm-campaigns `.ccl-row`, customer `.cu-row` and relation `.rel-item` are replaced by `--pmi`. | crm-campaigns, customer, relation wxml/wxss | Code. Customer and relation have no rows for 9002 on the rig. |
| X-4 | Done. The customer page's 4 native `<button>`s now have `hover-class="cy-pressed" hover-stay-time="80"`, and so do 2 ai-npc ones (「确认这句」 and 「试听录音」). Not changed: the ai-npc hold button keeps its intentional `hover-class="none"`, because it has its own `--active` state, and the disabled 「正在保存…」 is untouched. The 2 review buttons are handled by B-3. | customer/index.wxml, decor/ai-npc/index.wxml | `merchant-npc-voice-size-contract` passes. Attributes were placed after `class` so its regex holds. |
| M-20 | Done. 「衍生权益」 moved into card 2, directly under 「常备权益」, which loses its `hairline="{{false}}"`. The single-cell card 4 is deleted. | pages/merchant/decor/index.wxml | Shots `before-/after-decor-coop.png`. |
| M-22 | Skipped. This is an open decision (brief). | — | — |
| M-33 | Already done by X-2 on the base branch. | — | — |
| B-1 | Done. The 「入驻资料」 card is now wrapped in `<block wx:if="{{merchantAccess.canWriteProfile}}">`, so staff without profile-write no longer see an empty white card. The existing M-31 contract regex is kept intact. | decor/index.wxml | Code: confirmed, because the card condition did not include the cell's condition. The rig merchant 9002 is active, so this branch was not rendered. |
| B-2 | Reported, not changed. **`averageRating` is the right field and the 「近 30 天均分」 label is wrong.** Backend `MerchantReviewServiceImpl` manage page sets `averageRating` from `selectPublicRatingSummary` (`MerchantReviewMapper.xml`: `round(avg(rating),1)` over all `VISIBLE` reviews, with no date bound). No 30-day average exists anywhere in the backend. The fix is a label change (for example the same all-time meaning as the summary line) or a new backend field, and both are owner calls. Related: M-24 (the rating is shown twice). | — | Read-only backend code. |
| B-3 | Done. The reply-pending recovery block now has `class="review-recovery"`: a white card in token spacing and radius, the page side margin, and the `--cy-bg-card` background. Its two native `<button>`s became `cy-btn` (primary for 「读取原回复请求」, secondary for 「复制原请求号」), the same as the official-detail recovery block. The text and handlers are unchanged. | reviews/index.wxml/.wxss | Shots `before-/after-reviews-recovery.png`. The state was forced with dummy `replyPending:true, replyRequestId:'rig-dummy-req-0001'` via setData. Before: an unpadded text line and two full-width bare buttons. |

Other P2 rows (M-05, M-06, M-08, M-09, M-14, M-15, M-18, M-19, M-21, M-23, M-24, M-26 to M-32, M-34) were not touched. They are either not on a line I changed or they are owner calls.

## Tests and gates (run from `chengyinhub-xcx/`)
- `node --test "tests/unit/**/*.test.js"`: 15525 pass, 2 fail. The base branch gives 15527 pass and 0 fail. The 2 failures are `action-ledger.test.js` #139 (expected 5579 reachable variants, actual 5577) and #142 (inventory rebuild differs). This is **action-ledger count drift (−2)** from the reviews native buttons that became `cy-btn` and from the moved decor cell. Per the brief I did not regenerate the ledger, and the integration step should regenerate it.
- I updated 4 non-reserved tests that pinned the old structure:
  - `merchant-aftercare-list-contract`, `ui-review-0831-chrome-contract` and `ui-review-0831-marks-contract` asserted two aftercare `cy-tabs`. They now assert one shared instance and its visibility condition.
  - `merchant-ledger-display-contract` gets a no-op `page-motion` in its vm `require` stub.
- `scripts/ds-hardcode-gate.sh --ci` exits 0. `ui-theme-unit-lint --units` passes. `setdata-dead-field-lint` passes (its "6 stale baseline entries" note is the same on the base branch). `a11y-tap-target-lint` reports 0 new issues.

## Not exercised on the rig
- **Money landing (X-1):** every seeded amount for 9002 is `¥0.00` (home, ledger, coop/finance), so 0 counts to 0 and the effect cannot be seen. A count from 0 to a real non-zero value was **not captured**.
- **M-01 / M-02 entrance order:** 9002 has no aftercare or predict alerts, and the motion timing is not visible in still shots.
- **B-1:** this needs a non-active merchant without profile-write. The fixture is active.
- **Customer, relation, coop/list staggers and X-4 press states:** there are no rows, and the rig does not shoot press states.

## Requests (outside this branch)
- `tests/lint/ui-integrity-baseline.js:1212` (reserved): drop the `revBars` debt entry.
- Regenerate the action ledger once at integration (drift −2).

STATUS: COMPLETE
