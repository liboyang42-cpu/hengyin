# Round 11, lane i1-clean-run

This lane runs alone on `integ/launch-test-20261103`: every other fix lane has reported. Read `integ/ROUND11-H5-RESULT.md`, `ROUND11-H4-RESULT.md`, `ROUND11-G4-RESULT.md` and `ROUND10-F3-RESULT.md`. Rules from `ROUND10.md` and `ROUND11.md`. Use private worker branches, not `checkout -B` on the shared name (see the F3 incident), check `git show --stat` before each push, no force-push, never push to master, merge a PR or touch production. If you add or remove a `@Test` in a gated suite, update `EXPECTED` in `ci/funds_it_gate.py` and its fixture in the same commit. No money rule changes: report them. Merchant 按核销付费 settlement is waiting for the owner: don't do it.

**Goal: one commit with all CI green and these fixes in. Batch your pushes so runs finish.**

1. **自由探索 sale deadlock (blocker).** h4's rule stops sessions opening before SELLING, and the 自由探索 sale review needs sessions and tickets to exist first (`SALE_TICKET_PROJECTION_MISSING`), so 自由探索 can't reach SELLING. Let open-session through at lifecycle 2 for a 自由探索 (`product_type = 2`) theme with the club's live co-op order. Every other product keeps h4's rule, and an ended theme stays refused. Tests both ways. `chains.py player` must pass all chains, and all of `ci/chain_acceptance.py` that runs in CI.
2. **Dead ends (owner's rule: a station must never strand a player).**
   - 弹球, 安静挑战, 喊一嗓子, 计步 on a solo pass: same rule as the compass, 0-score skip after two failed reads or a denied permission. 计步 with WeRun denied must say something.
   - Generic theme with 状态: after a 结果反馈 FAIL the task sits at 「待审核 / 凭证未通过」, 取消 does nothing, and the screen keeps a dead 提交. Give a way on: a new attempt, or cancel that works, as the code's design allows. Report your choice.
   - Generic theme: the station card's 开始任务 refuses with 「请先开始当前任务」. It should start the task.
   - 九宫格: the picker's `bingo` has no section in `node-game-catalog`, so the editor can't build it. Fix it, or remove it from the picker if nothing can play it, and say which.
3. **CI reds, by cause, never by weakening a gate:**
   - gitleaks flags `subpackageRoam/components/roam-atmos-entry/index.js:8`, `const SEEN_KEY = 'cy_roam_atmos_seen_v1'`: a false positive on a storage key name. Rename the constant and don't add an allowlist unless renaming can't clear it. Check whether the scan reads history, and say what clears it.
   - Mini static gates: `merchantinfo.js` `hostMerchants` [C]; `session-picker` `ticketsError` and `pricing` `teamSizeError` U6 `RAW_ERROR_TEXT`.
   - Anything else red. Run the atomic-migration step, which has been skipped so h2's `ci/test_review_wording_seeded_text_mysql_integration.py` has never run in CI, and `real-storage-it.yml`'s `UmsMemberRankMySqlIT`.
4. **Walks not done, with screenshots you've viewed:** 「等队长放行」 and 返回地图 under the director runtime (j3's `tools/l6/fixtures.py`); closed 预测 with a shop; following a team from the rail; the author's 同场路线 off switch; swipe cards and fly-to; drag clouds; off-screen pins; visible rain, snow and fog with a stubbed weather reply. Fix what you find. Also recheck `pages/club/create` p3 F1 and F2, and p2 F-5 and F-7, which the F3 incident reverted and restored.
5. **Remaining findings:** j3 F13, F14, F16, F17, F18. p1 F-9: official-detail and 主办控制台 go dark for players, as official-mine does; merchant views stay light (owner's rule: player dark, merchant light). Update the two tests that pin the old layout and say why. f2's leftover captions on 开一场, co-op invite, station apply and 确认终价. j1 F11's pink error blocks.

Write `integ/ROUND11-I1-RESULT.md`. Name the final commit and its green run ids. Plain words, per item: status, files, proof, unverified. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
