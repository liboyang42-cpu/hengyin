# Round 4: owner's review of round 3, 2026-11-03

Read BRIEF.md first. Merge origin/master (merge, no force push). **Commit and push after every finished item.** Before calling UI missing or building UI, search `components/cy`, `pages/*/components` and comments. Reuse what exists. No new captions or helper lines. Draft only; never merge. Finish with a `Round 4` section in RESULT.md (item, status, files, proof), before/after rig shots named `<ID>-r4-before/after.jpg`, `node chengyinhub-xcx/tests/lint/ui-integrity.js` printing UI-GATE-0:PASS, no CI failures on your PR. Last line `STATUS: COMPLETE` or `STATUS: PARTIAL - reason`.

**Standard for all lanes (owner):** a problem with what the user entered is shown in red under the field, with the label's red `*`. Use the shared `cy-inline-error`. Never a blue or grey notice box and never a pop-up for this.

## l1-play
- **P-08 redo.** The held chapter screen is wrong. Layout: 「等待放行」 near the top, a waiting animation in the middle of the screen (reuse the `lg-wait` three-dot animation from `pages/play/index.wxml`; static under reduced motion), and any button at the very bottom. When the lead releases, enter the chapter as now. Keep `cy-chapter-flip` for the normal transition.
- **P-09.** The question title sits level with the WeChat capsule, beside the back button, not on its own row below the round buttons. Up to two lines, must not run under the capsule. Remove the extra `这一关结束了` outline button if it is not a real action; `继续故事` stays.
- **P-10/P-11 real proof.** The owner requires advanced-question author feedback to be tested for real. Build a rig fixture with the theme value rules the R5 runtime needs (initialised `sys.hp`), publish a theme with `outcomeRules.pass/fail` `reply` and `images`, play pass and fail, and shoot both. Fix anything it exposes.

## l3-sell
- **P-34.** Remove the blue 「有人带定价需填写成团人数…」 box. Show that problem with `cy-inline-error` under 成团人数. On a sold theme hide 带队成本 and 重新计算地板 altogether.
- **Several ticket prices (owner: optimise the page).** The pricing page lists each authored ticket with its own confirmed-price field, prefilled from the author's price and checked against the floor. Confirming writes each ticket's own price; inventory untouched. One-ticket themes look as they do today. Prove over HTTP with two tickets at different prices.
- **Sweep.** Find every blue or grey notice box and every pop-up in `chengyinhub-xcx/pages` that reports a problem with user input. Replace those with `cy-inline-error` under the field. Do not touch notices that aren't about input, or backend-mirrored message text. List all findings in RESULT with file and line, marking replaced or left and why.

## l4-counter
- **M-04.** When the card shows the today line (「今天 03:51 · 1 人已报名」), hide the theme's date line so the card can't show two dates.
- **M-27.** The ledger row must show the customer's name. Add a read-only field from the registration's sign-up name (decrypted on the server as C-05 does on l6-club; check that code on its branch). Scope to the merchant's own rows. No name logged. Show it in the row; no name available → show nothing, not a placeholder.

## l5-onboard
- **Fewer words on the 申请承接章节 sheet.** Remove the grey fact lines under the chapters. Show the perk minimum on the 报名表 at the perk field instead. Template tiles show cover and name, plus at most one short truncated line.
- **Theme with no chapters (owner: this is a backend restriction, a template must be able to submit).** This sheet exists only for 自由探索 themes today. If the theme has zero `cms_topic_chapter` rows, the template-only state is enabled and `ChapterMerchantNodeServiceImpl.submit` accepts it, creating the chapter it needs under the same owner, scope and audit checks. If chapters exist but none is open, do not bypass the organiser's recruiting: show the existing closed empty state with no dead button. Prove both over HTTP with read-back and report the rule.
- **M-16: separate the tables (owner decided).** Merchant industries must not appear in theme categories or player route preferences. Give them their own `sys_category` type, or a flag if a type would break something; check every reader. Merchant sign-up, 门店信息, chapter recruitment categories and matching use it. Theme category and route preference pickers keep the current rows. Existing merchants and chapters must keep resolving. Your unmerged `migration_20261102_merchant_industry_categories.sql` may be edited; merged migrations may not. Run it twice on the rig's throwaway database and report any existing-row handling that needs the owner.

## l6-club
- **Recruiting sheet: revert.** The owner rejected the Figma J3 sheet: the prototype approved on 2026-10-31 (P-6) is better, and it is already built: the 招商 layers in `pages/topic/components/project-host` on master (per-station tier chips, tier layer, invite, reject, dark for club hosts; commit bbcc6f0a). Revert your ROUND3B sheet and the read-only overview additions made only for it. The 商家 button on `pages/club/topic-detail`, for someone with `canReviewChapterApplications`, opens that P-6 招商 layer in `pages/topic/merchantinfo?scope=CLUB` directly. Others keep today's read-only list. Add nothing to the P-6 UI. Shoot the path from the button to the open layer, in dark.
- **C-04.** Make the reason textarea the same width as the card above.

## pay-gate (starts after ROUND3D; finish any ROUND3D item still open first)
- **Tiers on passes (owner: unified).** Theme self-play passes and ③ explore passes use the same 100/50/0 tiers as session tickets, measured from the fulfilment-window start. Old frozen purchase snapshots: check what they promised and report; do not take away a refund already promised. Points stay fully returned on a 50% refund.
- **Old refund wording must go (owner).** Whatever ROUND3D item 6 found: no player may see wording that contradicts 100/50/0, including stored `refund_rule` text. Show the platform's tier text instead of the stored text in player views, and keep the stored data unchanged. Test and shoot it.
