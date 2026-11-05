# Handoff to Codex: Chengyin launch fixes

Written 2026-11-05 by Claude session chengyin-2f. The owner stopped cloud routines because credit is low and asked Codex to continue locally. You now own this work. **Nothing has reached master or production.**

## Where things are

- Public repo `liboyang42-cpu/hengyin`. Working branch `integ/launch-test-20261103`, draft PR #723 (`[TEST ONLY, do not merge]`). Master is `c6eac3b8c`. Production jar is older (`014fc0d83` lineage) and production lacks `author_write_receipt`.
- Briefs on `origin/claude/walk-fix-20261102-brief` under `docs/delivery/walk-fix-20261102/`. Start with `ROUND12.md` (owner decisions, final), then `ROUND12K4.md`, `ROUND12K5.md`, `ROUND12K7.md`, `ROUND12K10.md`, `ROUND8.md` (the check method).
- Results on the working branch under `docs/delivery/walk-fix-20261102/integ/`. Check reports on `claude/check-1105-<lane>` under `docs/delivery/check-20261105/<lane>/REPORT.md`.

## Done, with results

`ROUND12-K1` (14 days, welcome points, quota, double order, 另存为模板 copy, price input, deploy order; PARTIAL), `K2` and `K3` (PARTIAL; leftovers handed to k7 and k9), `K5` merchant pay (COMPLETE, to the fen), `K6` merchant screens (COMPLETE). `ROUND11-I2` and `I3`: real-storage workflow, no product bugs. r1 second pass: complete.

## Unfinished when cloud stopped: yours to finish

Six cloud lanes had started with no final result: k4-club-flow, k7-map, k8b-play, k9-pages, k10-immersive, k11-platform-clawback. Some pushed commits, so read `git log` on the branch and pull first. Each lane's unfinished items are in its brief. Skip what's already in the log. Any of them may push until the owner has stopped them: pull before every push.

In priority order:
1. **k11:** on a refund after settlement the platform's balance gives back its share too (owner's decision), so ¥39 splits −1.17, −29.83, −8.00 in balances. Don't add an invite block on percentage terms (owner: leave).
2. **k8:** j3 second pass, especially N4 (D20 black screen; fail safe), N1, N2, N3, N5, N6. Some are already committed.
3. **k4:** invitation can be resent, merchant knows to accept, a full 自由探索 sale through the real admin API.
4. **k10:** the owner's main remaining request. A complete immersive game, authored only in the professional editor and played through with headphones in mind: intro music and video, background music, narration, photo and QR tasks, branches, easter eggs, ending music and video, all four looks. Also `integ/k10-theme-check.md` and `integ/k10-phone-script.md`.
5. **k7 and k9:** map behaviours, font scaling, page leftovers. Map **styles** are on hold until the owner says Tencent is sorted. Don't touch them.
6. **Small:** after 另存为模板 the copy shows as a second theme card: list theme templates under 模板 and hide them from 主题 (production has no member themes marked as templates).
7. **Clean run, alone:** all CI green on one commit, including `real-storage-it.yml` batches and `funds-mysql-it.yml`. Known reds: `ClubAttendanceTimeMappingMyBatisIT` `Unknown column 'create_time'` in `ClubEventOpsMapper.xml` (may be a real bug, check first), `P0DeregistrationMySqlIT` and `WechatMemberRegistrationMySqlIT` fixtures, danger-confirm consequences for `club.joinRequest.reject` and `club.enroll.refund`, some mini tests.
8. **Final check:** `ROUND8.md` lanes on that commit, then a fix round, then DevTools package size and screenshots for the owner. Ask the owner before DevTools.

## Hard rules

- Never push to master, merge a PR, deploy, or touch production. Production read-only checks and all migrations go only through the owner's `chengyin-db-migrate` flow with the owner's word. Ask the owner.
- **Don't run Java, Maven tests, Docker, MySQL or Redis on the Mac.** Backend builds, integration tests and the walk rig run on the dev VPS or CI. Follow root `AGENTS.md` and `~/.claude/CLAUDE.md`: the budget entry, no global prune, no GitHub runners on the VPS, no VPS or SSH details in output. If the rig can't run, say which items aren't walked. Never report unit tests as walks.
- Money rule changes need the owner. Never weaken a gate or test. Add or remove a `@Test` in a gated suite: update `EXPECTED` in `ci/funds_it_gate.py` and its fixture in the same commit.
- No keys in the public repo. Don't print `/Users/arlan/.config/chengyin/freeze-ui-test.env`. Don't run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`.
- No new captions; existing UI is the baseline; player dark, merchant light; input errors are red text under the field. Main package stays under 3,145,728 bytes by `scripts/preflight-release.js`.
- This worktree is a private branch: `git pull --rebase origin integ/launch-test-20261103`, check `git show --stat`, then `git push origin HEAD:integ/launch-test-20261103`. Never force-push, and never `checkout -B` the shared branch name.
- Plain words to the owner. Report what's proven and what isn't. Rig results aren't a phone.

## Owner decisions in force

See `ROUND12.md`. In short: a scan moves no money; merchant fee at settlement from the 主理人's share; 14 days to withdrawable; welcome points 50; club sessions don't count toward the limit of 3; solved station counts as completed on reopen; guests can view everything on a club page with guest projections; platform pays back its share on refunds after settlement; percentage terms left as they are; review wording 随机卡, 预测, 选; per-signup inviter points off; paid merchant extras closed; 探店日 is the old name for 自由探索, and those customers count. Keep full scope: the owner chose not to freeze.
