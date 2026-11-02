# Round 3: owner review notes and Figma check, 2026-11-02

BRIEF.md still binds. Work on your lane's existing branch and draft PR. Pull first. Merge origin/master before pushing (merge, no force push). Never merge a PR. Shots go to `<lane>/shots/` as before-and-after, taken with real data states, and each shot must actually show the item. Finish without waiting for answers. End RESULT.md with `STATUS:`.

**Rule from the owner: existing designs were already built. Before you create or restyle any UI, search `chengyinhub-xcx/components/cy`, `pages/*/components` and the Figma node numbers in code comments (for example 「稿 159:346」). Reuse what exists. Where round 1 or 2 replaced a built design, restore it.** Do not redraw. No captions or helper text.

## l1-play (start only after the master merge has landed)

- **P-08:** the gate is `cy-chapter-flip mode="gate"`, built from the owner's references. A held chapter must stay on that full-screen flip, with 「等队长放行」 in place of 「轻点跳过」 and no 「继续探索」 button. Remove the `chgate-hold` line on the map. It must not auto-continue while held. Open by itself when released.
- **P-10, P-11:** right and wrong results show what the author set in the template's 结果反馈 (`outcomeRules.pass` / `fail`, `reply` and `images`). Read how the editor stores them in `pages/publish/temp`. Your invented 「对了」 and `reveal` text is only the fallback when the author set nothing. Also check `feedback: n.feedbackText || ...` in `pages/play/index.js` near line 4378: `feedbackText` has a success placeholder in the editor, so say whether a wrong answer can show success text, and fix it if so.
- **P-09:** question text must be readable on dark; the result line must not clip at the bottom.
- **g20:** restore the confetti. The summary card stays.

## l2-explore

- **g21:** while the pack is unopened and opening, show only the pack: no title, no 「轻点拆开」, no other text.
- **P-30:** the ticket's rule line still shows the ticket's own window. Finish it so it shows the session times.
- **C-22:** remove every 「限 1 张」 rule and wording, including the four-shop day. If the server enforces it, remove that too and list what changed.

## l3-sell

- **P-33:** take out the black band. The page stays light, with the status-bar area in the page colour and content not scrolling under it.
- **P-34:** owner marked it to change without a note. Re-check that the price, shown state and confirm flow are correct; report what you find and change only real defects.
- **P-38:** privacy and location in one pop-up: our gate covers both, and granting it goes straight to the location request. WeChat's own permission prompt cannot be merged, so say so in RESULT.md.
- **C-14:** check the club name when the owner leaves that field or moves to the next one. Show a red * and the reason there. Keep the final check as well.
- **P-01:** do not build the flow change. Claude is drawing it for the owner first. Leave your current work in place.

## l4-counter

- **M-05, M-23:** your custom `verify-result` panel is wrong. Use the existing `cy-result-sheet` (success badge, as in `pages/merchant/customer`), and remove the 「操作结果」 header. Keep the content: station name, perk, warning. M-06's other-day warning must still show. Check that the buttons still work.
- **M-04:** re-shoot on #702's card (now on master).
- **M-27:** remove the trailing line and the leading dot; tidy the row layout. Time is the only field this table has: say so plainly in RESULT.md.
- **M-10:** there is no 「营销」 category. Only themes and activities exist; an activity is a single event run by its organiser. Fix wording and counting to match. Say why the before and after looked identical.

## l5-onboard

- **M-08, M-09, T1 sheet:** your removal of the template section is wrong; restore it from master (`chapter-target-picker`, Figma 159:346). Figma has three states: chapters and templates, chapters only, templates only. No chapters to pick: show only templates. No templates: show only chapters. Keep the correct selected-chapter colour and refresh on return. The confirm handler requires `chapterId` (`onChapterTargetConfirm`), so check whether a theme with no chapters can apply; if the server needs a chapter, use the theme's single implicit chapter if one exists, otherwise list it under `needs owner`. Remove the stack of grey facts under the chapters (未配置招募品类 and so on) only if the form shows the same facts; otherwise list them under `needs owner`.
- **M-17:** remove the payment-note line from the card.
- **M-15:** a merchant's staff are not involved; a club person becoming club staff gets permissions, with no jump. Remove the jump you added; report what remains.
- **M-16:** no change yet; the tag list is with the owner.

## l6-club

- **C-11:** the page looks messy because the recap is appended below the whole live page (待核销, 主题, 接下来, 集合时间, 排行榜). Follow Figma D9: once ended, the page shows the recap as its own layout, with three large tiles then 完成情况 rows then 榜单, and no second leaderboard. Keep 节点完成 below if it has data. The large tiles are the funnel's first three numbers; the other two go in 完成情况.
- **C-12:** the owner sees a large empty area at the bottom. Fix it, and capture the 「本场已结束 · 你完成了 N/M 站」 line in a shot.
- **C-09:** 准备总览: fix padding, the blank box, raw `READY` and the second start button.
- **C-05:** remove the black outline on the red 已报名 number. Full names show in the roster and in the customer list. Leave phone masking as it is; that question is with the owner.
- **C-03:** the 「已到集合点」 notice must not cover the start button.
- **C-04:** the reason to release a chapter becomes optional.
- **C-20:** 引流型 · 无分成 means the merchant gets traffic but no money. No card change unless the copy contradicts that.
- **C-13:** wait for the owner's reference; no change.

## pay-gate (draft only; no deploy, no production config)

Owner's rule: whoever publishes is the organiser; the platform only takes 3%; a person, the club's 主理人, receives the money, never the club as an entity.

- **Self-play pass:** a club's own theme with no co-op order must sell and settle. Replace `NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN` for this case with a record of 97% due to the 主理人, using the existing ledger shape. Prove it over HTTP in the rig.
- **Who is credited:** session money and remainders credit the club's 主理人 (the person), not the club. Check that a change of 主理人 and past records stay correct, and list each changed path.
- **3%:** config value per the approved default; state exactly what must be set before launch. No code hard-coding.
- **Refund tiers, now:** 100% more than 24 hours before the start, 50% within 24 hours, none after start; platform-wide. Add the table to checkout, matching `RefundPolicy` and the approved order status tags 付款成功 → 正在退款 → 退款成功. Migrations in new files only.
- **Also:** report whether refund dispatch staying at `payout_status=5` is a rig artefact or a defect.
