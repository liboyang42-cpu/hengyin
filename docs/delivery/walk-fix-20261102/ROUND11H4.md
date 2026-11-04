# Round 11, lane h4-club-money

From `integ/ROUND10-F2-RESULT.md`, "Leads not fixed". Rules: `ROUND10.md` "Parallel" section and `ROUND11.md`. Work on `integ/launch-test-20261103`, `git pull --rebase` before every push, no force-push, never push to master, merge a PR or touch production. If you add or remove a `@Test` in a gated suite, update `EXPECTED` in `ci/funds_it_gate.py` and its fixture in the same commit, naming the test. If a fix would change a money rule, don't make it: report it.

1. **Walk 按核销付费 terms, first.** A club theme with a merchant station on cash-share terms, through real invitation, acceptance, sale, four paid players, scans, a 50% refund and settlement, to the fen. Parts must equal what was collected at every step. Fix what breaks. Report the table.
2. **L7:** a refund after settlement is subtracted from the session's row on 俱乐部结算.
3. **L6:** `settleActivity` computes a designer share but never pays it. Don't change the rule. Report whether any production path can have a non-zero `creator_rate`, and add a check that refuses settlement instead of silently dropping the share.
4. **L1:** opening 开放商家承接申请 before sale must not leave the sale review refusing with 「必选章节 chapterId=42 缺有效 SUPPLY 供给」: warn the lead at the point it happens, and reviewers read plain Chinese.
5. **L5:** a session can't be opened while the theme isn't on sale.
6. **L3:** players not in a team appear in 定向广播 and 队伍进度. The recap counts and the play page's x/N agree with the real paid, checked-in and finished numbers.
7. **L4:** member-facing lists show real names, as far as the privacy rule allows: club home, 贡献榜, 「来自 城瘾用户」 on the merchant side. **L2.**
8. **LOW:** 「主题审核中 · 审核通过后就能开一场」 (cut), 帖子 vs 帖文, raw 「#14」 in 核销据点, the 「查找原邀请请求」 row, the 「品」 icon on 活动分润 rows, 「客诉期结束后可提现」 at ¥0.00. Skip any f3 already fixed.

Also run the `UmsMemberRankMySqlIT` test from `real-storage-it.yml`, which g3's rank query change (`4751ec46`) may have broken, and fix whichever is wrong.

Write `integ/ROUND11-H4-RESULT.md`. Plain words, proof per item, CI on a commit containing your work, ending with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
