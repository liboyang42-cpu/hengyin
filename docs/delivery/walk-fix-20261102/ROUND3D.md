# pay-gate follow-up, 2026-11-03

Branch `claude/walk-fix-1102-pay-gate`, draft PR #718. Read BRIEF.md first, and your own RESULT.md `Round 3` section. **Commit and push after every finished item.** Merge origin/master first (merge, no force push). Do not merge the PR, do not touch `assertCollectible`, do not run anything against production.

This moves real money, so unit tests alone are not enough. Do these in order:

1. **Bring up the rig** (`claude/cloud-walk-20261101-rig`, club lane's `harness-patches.diff`, `up.sh` pinned to this branch). `LANG=C.UTF-8 LC_ALL=C.UTF-8` for Maven. If Docker Hub limits you, pull the same digest from `mirror.gcr.io`.
2. **Real HTTP plus database read-back** for each, saved under `docs/delivery/walk-fix-20261102/pay-gate/proof/`:
   - player self-cancel more than 24 h before start: 100% refund;
   - player self-cancel within 24 h: 50%, rounded down to the cent, and settlement counts the retained half for the organiser;
   - player self-cancel after start: refused;
   - refund dispatch: the row leaves `payout_status=5` without `RefundReconcileTask`;
   - club session settlement pays the club's current 主理人, not the creator; change the 主理人 in the rig and show it;
   - club self-play theme settlement: PLATFORM line and INITIATOR_MARGIN line, paid to the 主理人 through `payoutDueCoopSettlements`.
   Write `payment_status` only through the chains' trusted-fact fixture and the real callback.
3. **Screenshots**, before (master) and after, in `shots/`: checkout refund table, the within-24 h confirm, and the order page status words 付款成功 → 正在退款 → 退款成功.
4. **Platform-owned themes pay no 3%.** This is an approved owner decision that round 3 left unbuilt. Settlement of a theme where `isPlatformOwned` is true must record no platform commission line taken from the organiser. Check who actually receives the money there and report it. Add a test that is red on old code.
5. **Rate rows.** Add `sql/migration_20261103_settlement_rule_three_percent.sql` that sets the rows listed in your RESULT `Needs owner 1` (`activity`, `topic`, and `topic_club` only if that row exists). It must be safe to rerun and carry asserts, and be registered in `ci/migration-dependencies.json`. Run it on the rig's throwaway database twice. Do not edit any existing migration. Existing themes keep their frozen rate.
6. **Refund wording conflict.** The theme page shows the ticket's own `refund_rule`, for example 「退改 · 开场前24小时可退」, which no longer matches the three tiers. Report every place a player can see old wording, and fix the ones that are code. If it is stored ticket data, leave it and list it under Needs owner.

Do not build tiers for theme passes; that waits for the owner. Finish with a `Round 3D` section in RESULT.md, shots and proof pushed, `node chengyinhub-xcx/tests/lint/ui-integrity.js` printing UI-GATE-0:PASS, and no CI failures on #718. Last line `STATUS: COMPLETE` or `STATUS: PARTIAL - reason`.
