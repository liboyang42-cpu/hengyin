# qx-b5 commerce — RESULT

Lane qx-b5 (B lanes, Questo UI). Branch `claude/qx-b5-20261109` from `origin/integrate/questo-ui-20261110` @ `449fb80d`.
Spec: `docs/questo-teardown/UI优化施工清单.md` v4.5 (§八 U07, §十二, §十三, §16–§19, §20.2, §24 P6, §26.3, §27.6, §27.10).
Mini-program only. No Java, SQL, migration or admin change.

## Evidence

- Shots: `shots/` (390×844 JPEG q70, rig browser runtime against the real backend jar built from the base commit).
- Before shots were taken on the base build (`449fb80d`) with the same fixture data; after shots on this branch.
- Rig: `claude/cloud-walk-20261101-rig` + `tools/harness-patches.diff` from `claude/cloud-walk-20261101-club`, run from a separate worktree. Rig-only changes (not in product code, not pushed):
  - `up.sh` `SHA=` pointed at the base commit; candidate worktree switched to this branch for after shots (bundle rebuilt, backend unchanged).
  - `app.py` passes dummy `--CHENGYIN_OSS_*` Spring args. Without them every page that serializes an image URL (activity info) returns 500 `对象存储配置缺失`. No real bucket or key.
- Data: the rig's own `chains.py player` fails on this base at `sale_close` (`自由探索只开放内容所属俱乐部的场次`), so sessions were opened through the real `POST /api/club/open-session` as the owning club (activities 9 and 10). Everything else below is a **direct test-DB fixture write** (rig DB only):
  - club 7001 membership for 9004/9009; `cms_topic 990014` lifecycle=3, `self_play_price=69`; a `topic_sale_snapshot` generated with the product's own `TopicSaleCanonicalCodec` + test fixture class.
  - `player_badge TOPIC_FINISH_990013` for 9004/9009; promotions FIRST10/AFTER15, offer DUO, tier T35 for activity 9.
  - `marketing_product` 9101–9105 (+items); `ums_member.point=2000` for 9004.
  - `marketing_revenue_rule/allocation/line` rows for 9003; `club_event_series 6601` (host_reservation_required=1) + occurrence for activity 10; `video_bounty_campaign rig-2026q4`.
  - Participant addresses, the order, the refund, the host request/cancel and the video submission were made through the real APIs/UI.

## Items

| ID | Status | What changed | Files | Verified | Unverified on a real phone |
|---|---|---|---|---|---|
| U07-01 | fixed | Checkout 「包含内容」 block from `quote.delivery`: type, people, audit, service window, beneficiary, ticket refund rule. Mixed tickets (`ticketName=null`) show only 「按票种分别说明」; `refundRule=null` shows no refund row. | `pages/activity/utils/quote-view.js`, `pages/activity/baoming/baoming.{js,wxml,wxss}` | unit `qx-b5-quote-view` (10); rig `U07-01-before/after` | Font/line wrap of long refund rules |
| U07-02 | fixed | `paymentChannels` without `JSAPI` and payable > 0 ⇒ same `paymentReady=false` as the readiness probe, no pay. **Bug fixed:** base never sent `marketingSign`, so any order with an active promotion 409'd forever (「价格已更新」 loop, `U07-02-before`). After: promotion order created, rig-paid, read back as 微信实付 ¥59 (`U07-02-after`). SDK-success≠paid is kept by the existing verifier. | same | rig end-to-end create→pay→`/api/registration/info` | Real WeChat pay sheet |
| U07-04 | fixed | Order detail 「退款进度」: stage from `refundStage` (approved ≠ refunded), per-stage amounts, 「金额待确认」 when `amountsComplete=false`, refunded/active ticket counts, entitlement usage. The old 「退款 ¥x」 row hides when this block is present (it read as already refunded). | `subpackageMember/utils/order-settlement-view.js`, `subpackageMember/components/cy/scene-member-order-detail/index.{js,wxml,wxss}`, `tests/unit/order-refund-amount.test.js` (contract regex updated) | unit `qx-b5-order-settlement` (6); rig: real cancel-refund → `U07-04-before/after` | Partial-refund multi-ticket order on device |
| §17.5 | fixed | 「优惠」 block from `/api/marketing/promotions/catalog`: offers (省 ¥X only from `differenceAmount`), promotions (remaining time = `windowEndsAt − serverNow`), tiers as unit prices; unavailable rows use the reasonCode table; empty ⇒ block not rendered. | `quote-view.js`, `baoming.*` | unit; rig `S17.5-before/after` (real promotion data) | — |
| §17.6 | fixed | Quote breakdown: `marketingAdjustments` with server `label`, list price struck only when it differs from the ticket row, `payAmount` is the only total, `coveredByPass` hint, `completeRewardCoupon` read-only. Create sends `marketingSign`; 409 split into price vs promotion text; `requestId` rotates when `quoteSign` changes before an order exists. No coupon offset, no client pricing. | same | unit; rig create with promotion succeeded | — |
| §17.1 / P6 | fixed | New 通行证 pages: list (在售目录 / 我的订单 / 礼品), detail with covered works, validity (fixed term ≠ subscription; first-use wording), seats, price; buy buttons only from `availablePayModes`; cash closed shows `cashUnavailableReason`; order sends only `quoteSign/quoteExpiresAt/requestId`; network-unknown keeps the `requestId`; cash path verifies by server order status only. Entry: 我的 → 我的资产 → 「通行证与礼品」 row (P-53 keeps 探索与更多 to two rows). Template: `subpackageMember/points-history`. | `subpackageMember/market/{passes,pass}/*`, `subpackageMember/utils/market-view.js`, profile component, `app.json` | unit `qx-b5-market-view` (7); rig list/detail/confirm. A points purchase reached the server and was refused with 「商品覆盖的主题非平台自营,拒绝代收」 (fixture topic is club-owned) — shown verbatim (`P6-buy-refused-after`). | Successful purchase + activation read-back (needs a platform-owned topic) |
| §17.2 | fixed | Gift product: 使用人 = 领取礼品的好友; after purchase a sheet shares the one-time claim link; claim page (`market/gift`) with reasonCode texts that never reveal another order; bought/received lists say pending gifts are unplayable for both. | `subpackageMember/market/gift/*`, `market-view.js` | unit; rig claim with bad token → 「领取码无效」 | Share card, a real claim |
| §17.3 | fixed (gated) | Extra-seat products never quote; detail shows 「加座需从原订单发起，暂不可直接购买」. | `market/pass`, `market-view.js` | rig `S17.3-extraseat-after` | — |
| §17.4 | fixed | Group/school (route=2) shows 「去报名」 into the existing `baoming` checkout with the server `checkout.activityId/ticketId`; no purchase in the product domain. | `market/pass` | rig `S17.4-group-after` | group-checkout endpoints not used (existing checkout is the server pointer's `quoteEndpoint`) |
| §19 / §27.10 | fixed | 收益明细 gets a segmented 收益明细/分佣 switch (player only, only when the server has lines). Lines per role (author/referrer kept separate), amounts from `amountFen`, refund reversals listed separately and grouped by `sourceFact`, status by code; read failure ⇒ 「收益暂不可用」, never 0; no withdrawal, rate or ruleVersion. | `subpackageA/utils/revenue-view.js`, `subpackageA/pages/assetcenter/income-detail/*` | unit `qx-b5-revenue-view` (4); rig `S19-before/after` | — |
| §27.6 | fixed | Checkout 「主持预约」 block: `reservationState` text per state, 请求主持 (`/host/request`), 撤销申请 (existing `waitlist/cancel`, red row). Pay disabled until the host accepts and the server issues the offer. **Bug fixed:** the waitlist reader rejected the server's legal `ELIGIBLE + waitlistJoinAllowed=false` shape and `REQUESTED/REJECTED`, so host series showed a broken 「本票种已满」 card. | `pages/activity/utils/host-reservation.js`, `baoming.*` | unit `qx-b5-host-reservation` (3); rig request → 主持申请中 → cancel → 已取消 | Host accept + offer → pay |
| §18 A | fixed | New 视频悬赏 page: 我的投稿 with 申诉 (REJECTED) / 取消投稿 (not after APPROVED, red); submit form (platform, URL, optional group key labelled 仅建议值) only when opened with an operator link carrying `campaignKey` and `topicId`; field errors in red under the field; failures by reasonCode. Entry: 我的 → 我的项目 → 「视频悬赏」 row. Template: `points-history`. | `subpackageA/pages/video-bounty/*`, `subpackageA/utils/video-bounty-view.js`, profile, `app.json` | unit `qx-b5-video-bounty-referral` (6); rig real submit | — |
| §18 B | fixed | 播放与应计: views, group views, amount, always 「已记录（未开放兑付）」; empty and failure separate. | same | unit; rig empty state | Real accrual rows |
| §18 I | fixed | Activity and topic share add `promise` that swaps in `ref` from `/api/marketing/referral/token` (silent fallback); landing stores `ref` per account; checkout claims after the order exists (best effort, one retry, cleared on any terminal code). No referrer display, no amounts. | `utils/commerce/referral.js`, `pages/activity/detail/index.js`, `pages/topic/index/index.js`, `baoming.js` | unit | Real share card `promise` on device |
| §18 C | fixed (gated) | Session management shows 「主持小费 · 暂不可开通」, no switch, no ratio. | `pages/club/event-ops/index.wxml` | source only — the rig's fixture club owner gets `no-permission` on this page | Visual |
| §18 D | needs owner | No player tip entry (default: nothing, §十二 allows nothing while the channel returns 501). | — | — | — |
| §20.2 | fixed (gated) | 通行证 page row 「兑换码 · 暂不可兑换」, not tappable. | `market/passes` | rig `S20.2-U07-03-gated-after` | — |
| U07-03 | fixed (gated) | Same page row 「付费模板 · 暂不可购买」 when `/api/workshop/capabilities` says `wechatApp.available=false`; nothing when available (install flow not built here). | `market/passes`, `market-view.js` | rig (real capabilities call) | — |

## Gates

- Unit: 7 new files `tests/unit/qx-b5-*.test.js` (36 tests) pass. Related suites (222 files: baoming, waitlist, checkout, order, income, profile, topic, activity, share, route, title, nav, coupon, event-ops, action-ledger) 2255 tests: all pass after the contract updates below.
- Contract tests updated because the change is intended (counts and allow-lists, each with a dated comment): `route-matrix-freshness` 157→161, `title-system-owner-contract` 157→161 / custom 156→160 and the two inventories, `nav-route-exists` orphan exemption for the gift claim deep link, `activity-detail-single-source-contract` dependency stub for `referral.js`, `order-refund-amount` refund-row condition, `action-ledger` 5751→5780 (tap +12, event +17, external tap +1).
- `scripts/uiaudit/route-matrix.json` regenerated; `node scripts/uiaudit/build-action-ledger.js --write` → `ACTION-LEDGER WRITE PASS: 5780 controls`; `node tests/lint/ui-integrity.js` → `UI-GATE-0:PASS` (no baseline entries added; `silentError` replaced by `autoErrorToast:false` where a `fail` handler exists).
- Main package: `scripts/mainpkg-size-report.js` ≈ 3150 KB source ≈ 1994 KiB counted. Only `utils/commerce/referral.js` (3.8 KB) is new in the main package; checkout-only modules live in the `pages/activity` subpackage.
- Not run: `ci/xcx-check.sh`, `ds-hardcode-gate-selftest.sh` (forbidden by the brief), Java/admin builds (no backend or admin change in this lane).

## needs owner

1. **Video bounty campaign list.** There is no player endpoint listing active campaigns. Default implemented: the submit form appears only via an operator link with `campaignKey` and `topicId`.
2. **Video bounty entry location.** Spec says a tab in 创作者中心「我的作品」 (`subpackageA/pages/myproject`, owned by qx-a3). Default: a row right under 我的 → 我的项目. 通行证与礼品 sits under 我的资产 (P-53 forbids new rows in 探索与更多).
3. **§18 D player tip.** Default: no entry while the channel is off.
4. **Pass purchase success path** needs a platform-owned topic in test data; the refusal path is verified, success is not.

## Cross-lane

- qx-a3 edits `scene-member-order-detail` (V3-02/03 around 230/294/321). This lane inserted one block after 支付明细 (~line 106) and one condition on the 「退款」 row. Expect a trivial merge.
- qx-b3 (P1/P2 referral landing) may also touch referral capture; this lane's `utils/commerce/referral.js` stores under `cy_marketing_ref_v1` and only claims after a checkout order.
- Shared files touched: `app.json` (4 pages added to existing subpackages), `scripts/uiaudit/route-matrix.json` (regenerated), `scripts/uiaudit/action-ledger.json` (rebuilt).
- Rig: `chains.py` sale_close fails on this base (explore session must be opened by the owning club); `app.py` needs dummy OSS args. Both are harness fixes for the rig branch, not product.

STATUS: PARTIAL - pass purchase success, host accept→pay, real accrual rows and the 主持小费 row are not shot in the rig; video bounty campaign list needs a backend endpoint
