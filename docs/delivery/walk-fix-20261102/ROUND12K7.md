# Round 12, lanes k7, k8 and k9

Rules and owner decisions: `ROUND12.md`. Read `integ/ROUND12-K2-RESULT.md` and `ROUND12-K3-RESULT.md`. Private local branch, `pull --rebase` and `git show --stat` before each push, no force-push, never push to master, merge a PR or touch production. If you add or remove a `@Test` in a gated suite, update `EXPECTED` in `ci/funds_it_gate.py` and its fixture in the same commit. k2 had no Docker daemon, so it walked nothing: **start Docker first (see `integ/map-shots/m2-rig-notes.md`), and if the container has none, say so at once in your result and stop rather than reporting unit tests as walks.** k5 owns settlement code; k6 owns merchant money screens; k4 owns the club flow.

## Lane k7-map

`ROUND12.md` k2 item 4 in full: j4 N1 (photo pins or the 「附近 N 个地点」 chip open the swipe row), F14 (activity cards, stat card and shop stickers scale with the phone's text setting, which the owner asked for), N10, N2, N3, N4, N5, N6, N8, N9, F15, F16. Walk each. The map styles themselves are on hold.

Then walk k2's unwalked work: solved station on reopen; guests on a club page viewing themes, posts, members and leaderboard with no private fields; no-game ticket QR; stale login state; 我的参与 as a guest.

Write `integ/ROUND12-K7-RESULT.md`.

## Lane k8-play

`docs/delivery/check-20261105/j3-player-game/REPORT.md` on `claude/check-1105-j3-player-game`, every finding:
1. **N4 first:** the D20 检定 station is a black screen, with `journeyCheck.show=true` and inline paragraphs that never become `in`. Find the cause in the code even though it might be the rig. A strand here would be a blocker, so make the screen fail safe: if the card can't reveal, the question and roll button still show.
2. **N1** (预测 stamps the wrong card; the player sees the other answer, and the stamp covers the question).
3. **N2** (`playkit-estimate` `locked` never clears on retry; the fail screen hides the real value).
4. **N3** (状态 theme: new task reads 「待审核 / 凭证未通过」 before anything was sent; dead 提交 after a fail; cancel path nobody finds; 「次数已用完」 beside 开始任务; 「背包装载 0 / 0 格」 with no bag; progress stays 0 after settlement).
5. **N5** (状态 and 检定 themes refuse most of the editor's games only at publish, with internal words): the picker doesn't offer what the theme can't publish, or they publish. Messages name the station in plain Chinese. If the default 参与方式 can never publish a game, the editor must not default to it.
6. **N6** (unfinished solo pass reads 「已完成」; 「首次扫码成功」 with no scan; date and validity mismatches; ASCII comma).
7. N7 to N18, including what's left of F13, F16, F17, F18.
8. `ROUND12.md` k2 item 5's leftovers: mic denial reason, re-walk of the hidden main button.
Play templates shaped like 248 and 267 (compass with check; album with random card) from the editor's config, since the live ones can't be copied.

Write `integ/ROUND12-K8-RESULT.md`.

## Lane k9-pages

k2's leftovers: item 3's p1 N-4 mycanyuinfo for non-owners, leaderboard double line, team-detail guest wording; item 6, the rest of p1's LOW list. k3's leftovers: N6's lost category and tickets, the LOW findings listed not done, item 5, p2 N-9. Read j5's LOW list and j6's LOW list on their second-pass reports, and fix what's still there. Don't change write contracts or money rules: list them for me.

Write `integ/ROUND12-K9-RESULT.md`.

## All

Tests before each push, `ui-integrity.js`, `build-action-ledger.js --check`, rig walks at 390x844 with screenshots you've viewed under `integ/fix-shots/`, CI on a commit containing your work. Plain words. Per item: status, files, proof, unverified. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
