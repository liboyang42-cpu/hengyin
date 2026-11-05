# Round 12: fixes from the second check pass

Reports tested at `573c463b3`, each on `claude/check-1105-<lane>` under `docs/delivery/check-20261105/<lane>/REPORT.md`. IDs below are the report's own. Read the finding first and confirm it at current head.

Three lanes run at the same time on `integ/launch-test-20261103`, alongside `i2-real-storage`. Use a private local branch per worker and never `checkout -B` the shared name (`ROUND10-F3-RESULT.md` "Incident"). `git pull --rebase` and `git show --stat` before every push, no force-push. Never push to master, merge a PR or touch production. No keys in the repo. No new captions; existing UI is the baseline; input errors are red text under the field. Main package under 3,145,728 bytes by preflight's count. If you add or remove a `@Test` in a gated suite, update `EXPECTED` in `ci/funds_it_gate.py` and its fixture in the same commit. New migrations: new files only, with runner assertions, registered in `ci/migration-dependencies.json`, listed with a phase in the release section of `integ/RESULT.md` and PR #723 ③.

## Owner decisions, 2026-11-05 (final, given in the owner's terminal)

- **A scan never moves money.** It only records that a player took part. Money moves only at settlement.
- **Club session with a merchant on 按核销付费:** at settlement the merchant gets the agreed fee per player who took part; the rest stays with the 主理人; the platform's 3% is unchanged.
- **Money becomes withdrawable 14 days after the event ends**, for every payee and path. It is 7 days today.
- **Welcome points are 50.** Balance and ledger must agree.
- **Club sessions don't count** toward the limit of 3 activities at a time.
- **A solved station counts as completed on reopen.** The player doesn't walk back. This replaces the arrival proof for these stations.
- **Sale period comes from the ticket settings;** sales normally stop when the event begins.
- **Guests can view everything on a club page:** themes, posts, members, leaderboard. This replaces the 11-04 decision. Guest projections only: no phone, member id, per-viewer flags or unpublished content; only live public clubs and rows. No writes.
- **Club owner's name:** nickname everywhere, so real names aren't published.
- Unchanged: price lock blocks late invitations; 已参加 counts paid sign-ups; platform fee 3%.

## Lane k1-money

Owns settlement, refund, order and points code.

1. **Merchant pay at session settlement.** `settleActivity` today has no merchant step, and the theme-level co-op settlement refuses (`REFUND_NET_BASIS_UNPROVEN`, `ACTIVITY_CASH_BASIS_UNSUPPORTED`) and alarms on every run. See `integ/ROUND11-H4-RESULT.md` item 1 and `fix-shots/h4-walk-readbacks.txt`. Pay `fee × heads` per co-op order. Heads: paid, not fully refunded players of a session the merchant group-scanned, who took part. Define exact rules for a 50% refund and report them; default is that a refunded player isn't counted. Merchant comes out of the 主理人's share, the platform stays 3% of retained sales, and parts equal what was collected to the fen. If the fee would exceed the 主理人's share, refuse settlement of that session with a clear alarm and report it: don't invent a rule. No double payment across the two settlement paths. A refund after settlement claws back the merchant's part too. No false alarms for club session tickets. Show it on 俱乐部结算, the merchant's 财务 and 核销与退款. Rehearse: four players, 50% refund, scans, settlement, refund after settlement, all to the fen on real MySQL. Check percentage terms for the same gap.
2. **14 days.** Find every place the wait lives: `selectActivitiesForSettlement`, theme settlement, `coop_settlement` T+7, 商家结算T+7打款, club, holds, deadline copy on screens. All become 14 days, from one constant if you can. Report each. Update tests that pinned 7.
3. **Welcome points.** `UmsMemberServiceImpl` hard-codes 50 for the balance and reads rule 1 for the ledger, and production's rule 1 says 100. One source. New migration sets rule 1 to 50 with an assertion. Check no other path grants rule 1 at the old value.
4. **Quota:** `selectActivityQuotaUsedByMemberId` leaves out sessions a club opens (j6 N7). Merchant and club published activities still count.
5. **Double order (j6 N1).** While a paying order exists for the same member and session, a second order is refused and the player is sent to that order. A paid sibling must not leave a payable stuck order: pay params refused for it, ticket released. No double charge in any order of events, including a late WeChat callback. Multiple tickets across orders per person (C-22) still work for distinct participants: read C-22 before changing anything. Closing the pay sheet shows the player where things stand.
6. **`另存为模板` (p3 N1, blocker).** `saveAsTemplate` sets `is_template = 1` on the live row, taking an on-sale theme off sale with no way back. Make it copy. Tests: the original stays on sale with its roster and sessions.
7. **`PricingServiceImpl.platformRate()` (p3 N2):** missing rule refuses, never silently 10%. Same for any similar fallback in money code.
8. **Merchant money screens:** j1 N4 and p2 N-3 (rows add up; refund rows show the merchant's effect; no 「平台已打款」 for in-app balance), p2 N-1 (paid activity row opens its detail), p2 N-2 (a non-host shop shows only what it was paid, not the buyer's whole ticket), p2 N-4, N-5. j1 N3 (deleted activity leaves home and 我的项目).
9. **Prices:** j1 N2 (39.99 must not become 3999): `digit` keyboard, keep decimals while typing, validate to two places; check every price input in the app. p1 N-1 (no 积分抵扣 row when the product refuses points or the deduction is 0).
10. **Migrations:** `migration_20261104_topic_total_time_seconds.sql` runs after the new jar, is safe to re-run with its assertion correct, and rollback notes cover it (r1 F2). Fix r1 F1, F3, F4: complete release order with phases, including `author_write_receipt` (production lacks it). Release note: ship the mini-program together with the jar, because the old bundle hides a 自由探索 ticket after the first scan (r1 F5).
11. **h3 follow-up:** make 停用 work for all award rules that read `value`, rules 1, 4, 7, 11, 12, 13, as for 2, 3, 5, 6, if the change is the same small one. Production has them all on. If it's not small, list it for me.

Write `integ/ROUND12-K1-RESULT.md`.

## Lane k2-player

Owns `pages/play`, 漫游, teams, player pages, guest access.

1. **Solved station on reopen:** a solved game completes the station without `/api/play/arrive`'s fence, in all routes; rewards as for a normal completion unless a rule says otherwise. j3 F6.
2. **Guests on club pages:** open `/api/club/topics`, `/post/list`, `/members`, `/leaderboard` for GET or the methods the page uses, exactly, with guest projections and security chain tests; sibling and write paths stay 401. Only live public clubs. Remove the 登录后查看 row. `f3`'s result section 2 lists the fields. Write a read-only SQL for me that counts existing production posts containing phone or WeChat ids.
3. j6 N2 (stale login state), N3 (我的参与 guest), N5 (trail before start; `done` from server), N6. p1 N-3, N-4, N-5, and the guest deep-link list under it. j5 N2 (club feed header; sweep every `@RequestBody` endpoint called form-encoded, since this bug has now appeared twice). j5 N1 (editing a 广场 post runs the same machine check as a new post and stays live unless the check holds it, no human queue by default; tell me if the code says otherwise for a reason).
4. **Map:** j4 N1 (photo pins or the 「附近 N 个地点」 chip open the swipe row; no new captions), F14 (activity cards, stat card and shop stickers scale with the phone's text setting, as the owner asked), N10 (delete a caller's points when the author's switch is off), N2, N3, N4, N5, N6, N8, N9, F15, F16.
5. i1's leftovers: dead 提交 on a FAILED generic game, mic denial reason, re-walk of the hidden main button. j3 F13, F16, F17, F18. j1 N1: a no-game ticket shows its QR, with no 开始游戏, on order and 票夹.
6. The rest of p1's LOW list.

Write `integ/ROUND12-K2-RESULT.md`.

## Lane k3-org-pages

Owns club, merchant and publish screens.

1. **Names:** p3 N4, j2 F-08 leftovers, p2 N-6. No 「成员#id」 and no bare 「城瘾用户」 where an organiser chooses or acts on someone. Use the sign-up name in rosters where the organiser has the right to see it, nickname elsewhere, and show a role on removal. Club owner: nickname everywhere.
2. **p3 N3:** template previews read real data or drop the claim. Remaining p3 findings. Remaining p2 findings (N-7, N-8, LOW). Remaining j1 LOW.
3. **Sale period:** confirm a club session's tickets stop selling when the session begins, from the ticket's own settings. Report the code path. Fix if not.
4. f2's leftover captions on 开一场, co-op invite, station apply, 确认终价. j1 F11's pink error blocks.
5. Anything in p3's or p2's section "not covered" a fixture can reach.

Write `integ/ROUND12-K3-RESULT.md`.

## Verify and report

Each lane: tests for what changed before each push, `ui-integrity.js`, `build-action-ledger.js --check`, rig walks at 390x844 with screenshots you've viewed under `integ/fix-shots/`, CI on a commit containing all your work (say which), migration rehearsal. Plain words. Per item: status, files, proof, unverified. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
