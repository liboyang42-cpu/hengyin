# Lanes and items

Each lane: branch `claude/walk-fix-1102-<lane>` from `origin/master`, one draft PR to master, results under `docs/delivery/walk-fix-20261102/<lane>/`. Never merge. 「…」 quotes are the owner's own words. Items with no quote were approved as proposed in the walkthrough report.

## l1-play: city orienteering play page, story, ending and game feel

L2 also edits `pages/play` (free exploration), so keep edits narrow.

- **P-04** ending never plays. 「你需要核对清楚」. Establish the true cause first and state it in RESULT.md, then fix so the author's 尾声 plays after the last station.
- **P-02** text answers with alternatives: split on 、 and comma, match any.
- **P-11** a right answer has no response. 「而且答题打错了不要抖动界面」. Remove any shake on a wrong answer. Show 「对了」 and the fragment or reveal.
- **P-09** number guess. 「这种游戏界面可以不显示顶部的地图和主题标题 需要展示的是游戏的标题或问题 然后要有返回」. Show the question and a back control; hide the top map and theme title on this game screen; send the question and post-submit reveal from the server. Report other full-screen games with the same problem; change them only if trivial.
- **P-10** wrong answer shows nothing. 「打错要有反馈的 参考其他app游戏的反馈的」. Clear, satisfying wrong-answer feedback in the style of good mobile games, with no shake: for example the field turning red with a short message, then clearing on the next input. Offer it as before/after.
- **g20** six overlays at the finale: play the 尾声 first, then one summary card.
- **P-08** a locked chapter is playable, then silently refused: show 等队长放行 at the chapter gate. The release rule itself stays.
- **P-13 (leader-led sessions)** 「等待过程需要有动画播放 那种等待的动画」. Add a calm waiting animation on the gather screen. L2 removes the wait for free exploration.
- **Small, as proposed:** P-23, P-24, P-25, P-26, P-28 (city menu part), P-29, P-35 (player cue on leader release).

## l2-explore: free exploration for the player, ticket wallet, order page

- **P-03** ticket vanishes after the first shop: keep a free-exploration ticket until all required chapters are redeemed or the session ends; wallet and order show 1/4 progress; not 「已完成」 after one shop. `verification_status` is a registration-level boolean read by finance and merchant counts: find every reader and do not break them.
- **#707 proof, your most important check:** after P-03, walk one non-pure free-exploration theme using the editor's rule format (`{"nodeCompletion":{"mode":"ALL"}}`) to completion over real HTTP in the rig: all four shops redeemed, completion reward granted, read back from the database. #707 has only unit tests so far. Report the result either way.
- **P-13 (free exploration)** approved default: no waiting for a leader; enter directly at the start time.
- **P-14, g21** name the perk on the shop card; add 「出示入场码」 linking to the wallet code; 「轻点拆开」 under the pack; CTA 「到店打卡」.
- **P-12** wrong-shop code gets one plain sentence naming the shop scanned.
- **P-22** 「没数据显示0 收集册的样式不对 不符合我们的设计风格」. On this achievements page, missing data shows 0. Fix the 收集册 block to match the existing design. Mark city orders complete on finish.
- **Small, as proposed:** P-19, P-20, P-21, P-27, P-28 (free exploration menu), P-37, P-39.

## l3-sell: selling, checkout, publishing and honest errors

- **P-01 / C-01** paid city themes must be sellable at launch (owner decision). 「你需要核对代码」. Trace the whole path from price confirmation to a paid sign-up, on a platform-owned city theme and a club-owned one; report each gate by file and line. Fix the sale-review path (organizer actor, canonical draft, sale submit) and prove it over HTTP: a paid registration on a platform-owned city theme through the real callback. Do NOT change `assertCollectible`: a club-owned paid theme stays refused. Show the organiser that state plainly instead of 「已确认」.
- **P-05 / C-02, conflict, needs owner.** Approved C-02 default: keep the payment rule and tell the organiser on 开一场 and the workbench. But on P-05 the owner wrote 「为什么不能报名 应该是可以报名的啊」. Do the safe part: show the real reason instead of 「缺少报价签名」 and don't let the player fill the form for nothing. Under `needs owner`, explain the gate and ask whether non-platform themes may take payment at launch.
- **P-15 / C-15** 「都需要说清楚真实原因 玩家俱乐部商家」, 「说清楚原因」. Publish and sign-up errors must pass through the real, user-safe reason for all three roles; audit generic 「请稍后重试」 catch-alls here. Never leak internals.
- **P-16** a real 人民广场 station is rejected. 「不应该这样啊」. A deliberately chosen map point there must be accepted; only an untouched default is refused, and it is flagged at the picker. Drop the stray 「:79」.
- **P-17** 「封面应该是必须的 不能有没有封面的活动」. Require a cover at publish; replace the stock 「Marina」 fallback and LIVE tag for existing rows. Don't break themes whose cover is merely pending review; report how you handled them.
- **P-18** 「不应该有这个选择场次吧 购买的时候就要确定场次的」. The auto-created 00:00 two-month window must not appear as a session. Show only real sessions, and don't ask where there is only one. If removing the window breaks self-play tickets, state that under `needs owner` with a default.
- **C-22** approved default: limit per session, not per theme. Check refunds and capacity still hold.
- **P-07 / C-07** checkout, order page and actual cancel all read one refund deadline; progress after refund must not say 「等待支付」.
- **Small, as proposed:** P-30, P-31, P-32, P-33, P-34, P-36, P-38.

## l4-counter: merchant counter, day-of screens, numbers

L5 covers onboarding.

- **M-02** rejected codes look like a possible double redemption. 「应该给出相应的结果展示的」. Forged, expired and wrong-shop codes each get a clear red 「未核销」 with the reason. The client treats business 500s as unknown on purpose, because an award failure after commit has the same shape. So the backend must mark pre-transaction rejections explicitly, and the client trusts only that marker; keep 「待确认」 for real ambiguity. Test both.
- **M-05** 「核销成功我记得我做了底部的弹窗 那种绿色的对勾底部弹窗」. Search git history and prototypes for that green-tick bottom sheet and restore it. If it never existed, build it from the existing sheet kit and say so. Show stop name and perk.
- **M-23** primary button 「继续扫码」. **M-25** invalid code uses the same sheet.
- **M-06** owner decision: other-day tickets stay redeemable, with a warning showing the session date.
- **M-03** redemption records read from the same source as 核销与退款, and show the stop name.
- **M-04, M-10, M-11, M-12, M-13 / M-14, M-24** as proposed.
- **M-27** 「需要有姓名」: names are shown, so drop the conflicting 「顾客信息受保护」 label. Merchants only see their own customers: verify scope.
- **Small, as proposed:** M-18, M-21, M-22, M-26, M-28, M-29.

## l5-onboard: merchant onboarding, joining a theme, staff

L4 covers the counter.

- **M-01** one shop's perk blocks a theme from sale: default redeem rule or one optional field; the error names the shop and perk. Prove over HTTP that a perk from the normal form no longer blocks sale.
- **M-07** duplicate stop for players: bind or retire the chapter's placeholder when a merchant joins. Write a migration file only if needed.
- **M-08 / M-09** 「并且如果只有承接章节 就不需要下面的选择玩法模板的样式 而且现在看起来这个界面是有问题的 上面选择章节的颜色不对」. Refresh category, game and perk lists on return; come back after publishing a game with it selected; when joining a chapter only, remove the template picker block; fix the chapter selector's colours.
- **M-15** 「店铺就在俱乐部中 需要跳转过去就好了」. Don't build a new home. Take new staff to where the shop already lives in the app. Find the existing entry, and if the meaning is unclear state your reading under `needs owner`.
- **M-16** 「标签可以更多一些 有很多城瘾合适的商家」. Ask once at sign-up with chips; add more tags that fit 城瘾's merchants (bookshops, galleries, tea, vintage, workshops and so on); use one term. New category rows go in a migration file.
- **M-17** 「卡片样式看起来不好 底部空了一大截」. Fix the 合作中心 card's empty lower area. Also add the one line on how the merchant gets paid: the owner's no-captions rule makes this their call, so give before/after with and without, and flag it under `needs owner`.
- **Small, as proposed:** M-19, M-20.

## l6-club: the club leader's session, opening to settlement

- **C-03 / P-06** 「我到了」 always fails. 「你需要核对代码」. Copy the ticket's gather point into the activity when a session opens, on both the series path and open-session. Handle existing sessions in a migration file only if safe. Prove over HTTP: check-in within 500 m succeeds, outside is refused, read back.
- **C-04** leader can't release a chapter. 「你需要核对代码」. Find why `chapterOptions` was empty and fix the real cause; one working control naming the chapter and how many are waiting; remove or reroute the dead play-page item for director-run sessions. Prove over HTTP that the player's chapter opens.
- **C-09** 「没有准备完成怎么会有提示 这个是给是谁的提示呢？玩家不应该看到的？」. Confirm who can see it, answer plainly in RESULT.md, verify players never see it, and show the leader the real blocker.
- **C-10 / C-25** 「这里的红色数字和样式看起来是乱的 需要优化页面」. One arrival fact for all counts; include registered-not-arrived while running; tidy the roster tabs' red badges and counts.
- **C-11** 「页面需要设计」. Render real values, and give the recap a proper layout with the existing kit.
- **C-13** 「卡片设计有点怪 需要优化」. Add per-session paid, refunded and net in ¥ to 报名名册, and tidy the card. Numbers must reconcile to the fen against the database.
- **C-12** plus the approved default: the executing club gets the workbench and end rights for its own session only (check ownership). Players see 「本场已结束」 and their result.
- **C-05 / C-06, C-08, C-17** as proposed.
- **C-02 notice** tell the organiser on 开一场 and the workbench that non-platform themes can't take online payment yet. L3 owns the player side. If the owner opens the rule, this is removed.
- **Small, as proposed:** C-14, C-16, C-18, C-19, C-20, C-21, C-23, C-24, C-26, C-27.
