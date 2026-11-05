# Round 12, lane k4-club-flow

Rules and owner decisions: `ROUND12.md`. Report: `docs/delivery/check-20261105/j2-club-session/REPORT.md` on `claude/check-1105-j2-club-session`. Lanes k1, k2 and k3 run at the same time. k1 owns settlement and money code, so report anything that needs a change there to me. Don't touch the map styles: they're on hold.

1. Everything in j2's findings at the current head: N-01 (the terms invitation can be sent or resent from the 招商 layer and 我的合作 until it's accepted; the lead sees who's missing and why a theme can't go on sale), N-02 (merchant sees it must accept, with a clear way to the accept page), N-03, N-04, N-05 (自由探索 after 确认终价 tells the truth to owner and players), and every LOW and remaining first-pass item in that report. Skip what k1 or k3 fixed; pull first.
2. **自由探索 sale all the way:** the rig has no admin web, so drive the platform's back-office steps through its real admin API, with real admin tokens, and walk the rest on screen: on sale, buy, shop stamps at several shops, ticket stays in 票夹, ending, settlement, to the fen. Fix what breaks.
3. Theme-page taps j2 could only reach by handler: find out why they miss in the rig, and fix it if a product overlay is the cause.

Write `integ/ROUND12-K4-RESULT.md`, with screenshots you've viewed under `integ/fix-shots/`. Plain words, per item: status, files, proof, unverified. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
