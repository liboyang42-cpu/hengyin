# Round 11: fixes from the player game, roaming and player pages checks

Reports, tested at `ecef2f078`, each on `claude/check-1104-<lane>` under `docs/delivery/check-20261103/<lane>/REPORT.md`: `j3-player-game`, `j4-roam-teams`, `p1-pages-player`. IDs below are the report's own. Read the finding first and confirm it at the current head: other lanes are fixing related things, and a finding may already be gone.

Three lanes run at the same time with `ROUND10.md`'s f2, f3 and f4. Its rules and "Parallel" section apply: work on `integ/launch-test-20261103`, `git pull --rebase` before every push, never force-push, never push to master or merge a PR, no production, no keys, no new captions, existing UI is the baseline, input errors are red text under the field, main package stays under 3,145,728 bytes by preflight's count (anything new goes in a subpackage). Fix causes; never weaken a test or gate. `探店日` is only an old name for 自由探索, and f4 renames it. `竞猜` becomes `预测` and `押` becomes `选` (f4): use the new words in anything you add.

Rule on dead ends: a station must never strand a player. If a game can't be played, closes, or a check service is down, the player can still go on, with no reward for what wasn't done. Don't let a client state contradict the server's.

## Lane g1-play

`pages/play` and the games. Start now.
1. j3 F1. A 预测 station past its closing hour must not stop the route: the player can go on without choosing. Copy must not promise a shop answer on a theme with no shop.
2. j3 F2. D20 三档难度 must play: fix the editor and the validator so a template either has playable `outcomeRules` or can't be saved. Check themes already stored by the same path. Also find every other game type where the editor's picker can produce a config the backend refuses to start: list them and fix them.
3. j3 F3. Photo check failure: follow the server's `fallback`, so retake while tries remain and go on after that.
4. j3 F4 (wait-for-release screen under the director runtime, and the lead header count), F5 (finished theme opens on its ending with the finish card, not as new), F6 (solved station completes on reopen without walking back).
5. j4 F7 (distance text updates while walking), F8 (no 「本章完成」 or giant 0 when nothing was done; heading not covered), F11 (确认到点 changes to its done state; card colour and distance units match the neighbouring card).
6. The remaining `j3-player-game` findings in this area, and the 8 game types j3 couldn't play: try each again once its blocker is gone.

Write `integ/ROUND11-G1-RESULT.md`.

## Lane g2-team-map

Teams and 漫游. Start now.
1. j4 F1: on APPLIED show the new state and archive the receipt silently; on REJECTED show the backend's reason at the button; keep the recovery card only for UNKNOWN. No raw ids. f2 fixed one instance of this (j2 F-11): fix it at the shared level, `utils/team-event-command.js` and `roam-team-recovery.js`.
2. j4 F2, F3, F4 (pending applications on the 我的队伍 card and on team detail, reusing the map sheet's rows), F6 (a session row counts only its own teams), F5 (without a real location: no 「距你」, no avatar at the fallback point, one 开启定位 row, and it must work in any city), F13, F12.
3. j4 F10: for a non-member reading a team by invite code, return only title, counts, status, names and avatars. Tests for guest, other session and member.
4. j4 F9 captions, and the false 「不可撤销」 line in the quit dialog, and 「加入方式」 bound to the real `joinMode`.
5. Then walk what j4 section 3 left undone: following a team from the rail, the author's 同场路线 off switch, swipe cards and fly-to, drag clouds, off-screen pins, visible rain, snow and fog with a stubbed weather reply, and rejecting, withdrawing and disbanding in teams. Fix what you find.

Write `integ/ROUND11-G2-RESULT.md`.

## Lane g3-player-pages

Orders, 我的, growth, checkout. Start now.
1. p1 F-1, j3 F9, F12: order and participation status must tell the truth. Nothing says 进行中 before the session starts; solo pass has one validity date, no redemption or gather steps it never reaches, and one 不可退款; a date-only value never shows as 00:00, and times are in order.
2. p1 F-3, j3 F10: one level and one rank everywhere. Use one source. Distance must count or the row goes. p1 F-4: no rank at 0 points; the board holds player accounts only. p1 F-5.
3. j3 F7: theme duration in the right unit (minutes stored, seconds formatted). Check every caller.
4. j3 F8: a real 姓名 at checkout that isn't the nickname and isn't unique-checked, using existing fields if there's a sound way, with checkout updating on return. If this needs a new column, migration file only, and tell me. Don't change the phone rule (WeChat phone only).
5. p1 F-6 (logged-in blank pages, using the empty or 链接已失效 state other pages use; check first that f3 hasn't done them), F-7 (don't render entries for features that are off or data the theme doesn't have, and the 发票抬头 form too), F-8 (chat failure shows a real 重试), F-2, F-9, F-10, F-11, F-13, F-14. F-12 captions, with the same keep list as f3.
6. L-1: leave the privacy page's fallback as designed, and list it for me.
7. Anything left in `p1-pages-player/REPORT.md` section 3 that a fixture can now reach: add the data and walk about 20 data states.

Write `integ/ROUND11-G3-RESULT.md`.

## Verify and report

Each lane: tests for what changed before each push, `ui-integrity.js`, `build-action-ledger.js --check`, rig walks at 390x844 with screenshots you've viewed under `integ/fix-shots/`, all CI green on a commit containing all your work (say which), and migration rehearsal if any. Plain words. Per item: status, files, proof, unverified. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
