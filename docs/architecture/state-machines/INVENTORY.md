# Implicit state machines — inventory (qx-sm, 2026-11-10)

Scope: backend (`chengyinhub-system` domain + mapper XML + Quartz) and mini-program (`chengyinhub-xcx`).
Ranked by **risk × user impact**. `$` = money aggregate (payment / refund / settlement / withdraw / coupon / deposit):
for those this lane only documents. It does not refactor them (BRIEF hard limit). Paths: `S=chengyinhub-system/src/main/java/com/chengyinhub/business`, `M=chengyinhub-system/src/main/resources/mapper/business`, `X=chengyinhub-xcx`.

Legend: **Explicit** = single enum + transition table/guard exists. **CAS** = SQL `WHERE status = old`. **Blind** = generic update writes the column with no old-state predicate.

## Already explicit (templates to copy)

| Machine | Where | Notes |
|---|---|---|
| Topic lifecycle | `S/domain/enums/TopicLifecycleState.java`, `TopicLifecycleServiceImpl.transition()` + CAS `transitionLifecycle` | The reference pattern: enum + `isAllowed` + CAS. |
| Audit task (**new, this lane**) | `S/domain/enums/AuditTaskState.java` | Review transitions + open set; mapper lint. |
| Seat invitation (**new, this lane**) | `S/domain/enums/SeatInvitationState.java` | All generic transitions go through `transitionGuarded`. |
| Coop invite labels (**new, client**) | `X/utils/coop-invite-status.js` on `X/utils/state-machine.js` | One table for labels, badges, terminality. |
| Community post | `S/service/support/CommunityPostLifecyclePolicy.java` | Action-based `requireOneOf`. |
| Cash withdraw | `S/domain/MemberCashWithdraw.java` + `CashWithdrawStateMachineTest` | `$`. Mostly CAS, well tested. |
| Client checkout | `X/utils/checkout/checkout-workflow.js` | `idle|submitting|paying|verifying|unknown|done`. |
| Client order summary | `X/utils/order-status.js` `summarizeOrderState` | Single client projection of registration/payment. |
| Client activity status | `X/utils/activity-status.js` | 0–9 table, five consumers. |

## Ranked inventory

| # | Machine | States / storage | Writers | Problems found | User-visible symptom | Action |
|---|---|---|---|---|---|---|
| 1 | `$` Registration (`cms_registration`) | `payment_status` 0–4 × `registration_status` 1–4 × `verification_status` 0/1, plus nullable `paymentTime`, `sourceFrozenAt`, `verificationTime` | Mostly CAS in `M/CmsRegistrationMapper.xml` (`claimPaymentProcessing`, `claimPaymentSuccess`, `closeExpired`, `claimRegistrationRefunded`…). Blind: `updateCmsRegistration` from `TeamGroupServiceImpl` after a cash refund. Dead blind `completePointPayment`. | Two-column product state whose legal pairs live only in CAS predicates; `payment_status=3` means both "failed" and "closed". | None confirmed. A future blind write could produce an illegal pair. | Needs owner (money). |
| 2 | `$` Refund application | `status` 0–2 and `payout_status` 0–6 (+ lease token) | All CAS in `M/RefundApplicationMapper.xml` | `updatePayoutResult` allows `payout_status in (0,2,5,6)` without a lease check, so a late gateway result can overwrite "manual payout claimed" (2) or another worker's lease (6). | Possible double handling of a payout. | Needs owner (money). |
| 3 | `$` Legacy `oms_order` (non-commerce) | `status`, `aftersale_status`, `confirm_status` (undocumented Longs) | Admin PUT → blind `updateOmsOrder` | No state validation on admin edits. | Admin can set any status. | Needs owner. |
| 4 | `$` Coupon history | `use_status` 0/1/2 | CAS claim/expire/reverse/void; blind admin PUT `updateSmsCouponHistory` | Used → unused possible via admin PUT. Expiry is lazy (on read). | Coupon "revived" by admin edit. | Needs owner. |
| 5 | Activity (`cms_activity`) | audit `status`, `check_status`, `publish_status`, nullable `cancel_time`, `is_settlement`; derived in `S/util/ActivityLifecycle.java` | Blind `updateCmsActivity` from ~10 call sites; CAS only for settlement claim / cancel / reopen | Delete is check-then-act (counts paid registrations, then soft-deletes without CAS). Publish toggle computed from a read. | Rare race: activity deleted while a registration is being paid. | Documented; refactor after UI lanes merge (`ApiActivityController` is touched by the publish editor flow). |
| 6 | Play team / member | team 0–4, member 0–6 (`S/domain/PlayTeam*.java`) | `M/PlayTeamMapper.xml`, ~25 sites in `PlayTeamServiceImpl` | `STARTED(2)` is never written but read (`PlayEncounterServiceImpl:781`). `markMemberStatus` / `reapplyMember` accept any from→to; the REJECTED/KICKED rule is Java-only. `endExpiredTeam` leaves members JOINED while `disband` exits them. | Members still listed "joined" on an ended team. | Next pilot; needs an owner ruling on ended-team members. |
| 7 | Coop invite | 0–5 (`S/domain/CoopInvite.java`), deposit sub-states | Blind `updateCoopInvite` after `FOR UPDATE` in most sites; `supersede()` reads pending without lock | Supersede race. Client: label table copied in 2 files; templates use strict `status===N`. | Second copy of labels drifts. | **Client labels unified (this lane).** Backend supersede lock: needs owner (deposit-adjacent). |
| 8 | Game session / runtime session | String states + hidden `runControl.operationStatus` JSON machine | Single CAS `bumpSession` | Second machine in JSON with no guard. | None confirmed. | Documented; play lane owns it. |
| 9 | Audit appeal | SUBMITTED / REVIEWING / ACCEPTED / REJECTED / WITHDRAWN | 3 CAS writers | `finishAppeal` accepts any target. | None confirmed. | Candidate next (same pattern as AuditTaskState). |
| 10 | Audit task | 0–4, open = {0,3} | All CAS | Open set duplicated as literal `in (0, 3)` in 11 SQL statements and as `PENDING || ESCALATED` in 5 Java places. | Risk: one place drops ESCALATED. | **Done (this lane).** |
| 11 | Merchant operator invite | PENDING / ACCEPTED / REVOKED / EXPIRED | 3 CAS writers | `expireCas` has no caller; EXPIRED is only computed at read time. | None. | Documented. |
| 12 | Registration seat invitation | 0–4 | `claim`, `transition(from,to)`, `expirePending` (CAS) | Generic `transition` had no allowed-pair table. | None today; a wrong caller could release a cancelled seat. | **Done (this lane).** |
| 13 | Client registration payment verifier | `X/utils/checkout/registration-payment-verifier.js` | — | `registrationStatus === 4` (expired) is treated as `pending`, so the verifier polls 20 s then reports "unknown". Must also handle "paid after expiry" (`paymentStatus 2` + `registrationStatus 4`). | After timeout the user sees "unknown" instead of "closed". | Needs owner (payment display). |
| 14 | Client registration labels | `free-explore-bundle.js:26`, `mycanyu.js:113`, `club/enroll/index.js:14,396` | — | Inline maps; club/enroll aggregates on label strings (`=== '已成团'`); "unknown" shown as 募集中. | Copy change would break counting. | Documented; ticket-wallet lane owns part of it. |
| 15 | Client review tri-state (0/1/2) | `merchantinfo.js:46,2192,733`, `coop/list:369`, `project-host:64` | — | 6+ copies; unknown falls to 待审核. Dead `mytemplate.js getStatusText` contradicts `template-market.js`. | Unknown status shows "pending review". | Documented (merchant pages excluded this round). |
| 16 | Client coupon QR | page + component copies (`subpackageMember/coupon-qr`, `scene-qr-coupon`) | — | Same machine twice; different labels for code 1 (已核销 vs 已使用). | Inconsistent wording. | Documented. |
| 17 | Client paged lists | `topic/catalog`, `mytemplate`, `searchmap`, … | — | Boolean soup (`loading/loadingMore/hasMore/error/moreError`); each page has its own `hasMore` rule. | catalog stops paging when server omits `total`. | Candidate: `utils/paged-list-state.js`. |
| 18 | Client sheet stacks | `club/detail` (~14 flags), `club/topic-detail`, `topic/index` | — | Sheets open after async responses without epoch checks. | A late response stacks a sheet over another. | Documented. |

Excluded from refactor this round (other UI lanes): `pages/play/**`, the publish editor, `pages/merchant/**`, finish/share pages, ticket wallet (`subpackageMember/signup`, order detail/history), pass purchase.
