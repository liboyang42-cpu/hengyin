# Round 2 (2026-11-10): owner review verdicts → fixes

BRIEF.md stays binding (hard limits, UI rules, evidence, gates). This file adds round-2 scope. The owner reviewed the round-1 page: 207 items approved, 92 marked 要改, 4 questions. **Items that were fixed and not commented on are approved — do not touch them.** Every owner note below is quoted verbatim (Chinese) with its review screen ID and round-1 item ID; the round-1 screenshots are in `qx-<lane>/shots/` on the staging branch.

## Base, branch, PR

- **Base:** `origin/staging/questo-review-20261110` *after* lane qx-s1 has finished. Before starting, poll every 5 min (max 3 h) until `docs/delivery/questo-ui-20261109/qx-s1/RESULT.md` on that branch has a line starting with `STATUS: `. Meanwhile read refs and code.
- **Branch:** `claude/r2-<lane>-20261110`. One **draft** PR into `staging/questo-review-20261110`. Never merge, never mark ready, never push to master, integrate, staging or other lanes' branches.
- **RESULT.md in Simplified Chinese** (owner reads only Chinese) at `docs/delivery/questo-ui-20261109/r2-<lane>/RESULT.md`: table `| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |`, ID = the review screen ID or item ID below, then `## 待你决定` (each with the default you implemented), last line `STATUS: COMPLETE` or `STATUS: PARTIAL - <中文>`. Shots `r2-<lane>/shots/<ID>-before.jpg` / `<ID>-after.jpg` (390 px, q70, same state), from the rig on your branch. Inspect every shot.

## Global rules from this review (apply in every lane)

1. **Player sheets/pop-ups must be clearly visible.** Owner: 「弹窗不能是黑色的 所有黑色弹窗都需要改 不然看不见弹窗啊」「这种灰色的弹窗看起来还是看不太清」「我发现玩家的灰色都不是很清楚？」. Lane r2-visual owns the token change (player sheet surface clearly lifted from the page: lighter elevated surface + visible top edge/shadow, readable secondary text contrast ≥ 4.5:1); other lanes use the tokens, never hard-code greys.
2. **Close buttons top-right** (「关闭按钮在右上角」). No duplicate back buttons (「上面有两个返回 很奇怪」).
3. **Centre** short result/empty/status screens and don't make them tall (「居中」「感觉…有点高」).
4. **No helper/system copy shown to players or authors**: no topicId, no 「待运行验证」-style hints (「不能有提示词 什么topicid 这种给玩家看」「不用写多余的提示」).
5. **Bottom buttons in sheets sit at a fixed bottom position**, no big empty gap above them, and **one corner radius for bottom buttons site-wide** (「底部按钮应该放到底部固定位置…底部按钮的圆角全站可以统一一下」). r2-visual owns the radius token.
6. **Game feel and motion.** Owner: 「当我说 UI 需要一些动画，就是指这种」— refs in `refs/` and `refs2/`: heysatya onboarding, emilkowalski gestures, Triopixels morph/stack, `refs2/chapter-end-dissolve-frames.jpg` (card dissolves into particles, next card arrives), `refs2/album-map-stamps-frames.jpg` (photo stamps pinned on a map with a route line, stacked card carousel), `refs2/team-rank-laurel-frames.jpg` (huge rank number in a laurel wreath, 「前 8%」, digits roll when switching period, dark glass stat cards). Respect reduced motion; prefer transforms/opacity; canvas particles allowed where needed (mini-program `canvas type="2d"`).
7. **Questo structure, our styling.** Owner: 「可以参考 Questo 的结构，但保持我们自己的页面样式。尤其注意结账流程、多人报名流程、视频分享功能。」 Questo screenshots + notes in `refs2/questo/` and `refs2/questo/README.md`. Copy flow and information structure, never Questo's look, names, art or copy.
8. **Animated images** where the owner asked (success / failure / waiting, 「侦探找凶手」): use free-licensed Lottie/GIF/APNG from LottieFiles (Lottie Simple License) or similar, record source + license in RESULT; keep each asset small and in a subpackage; main package must stay < 2048 KiB.

## Lanes

### r2-play — play page (owner of `pages/play/**` except the ending/finish panel)
- a1-v3-06 「弹窗不能是黑色的…」 → apply rule 1 to place card and all play sheets.
- a1-v3-10-53 「上面有两个返回 很奇怪 而且我晃动手机并没有反应」「ui 样式太丑了 不需要打开两个字 没有图片或其他信息吗」 → one back only; fix shake (wx.startAccelerometer/onAccelerometerChange, real threshold, haptic); embedded gameplay card shows cover image + title + type, no 「打开」 label.
- a1-v3-13-14 「顶部标题居中 还有我们的顶部数据的显示 地图放大的时候就隐藏 地图缩小 出现那个 生命值 背包之类的那一行」 → centred title; HUD row (HP, backpack…) hides when map zooms in, shows when zoomed out (map `bindregionchange` scale).
- a1-v3-15 「UI要改 看起来不像是游戏」「这种灰色的弹窗看起来还是看不太清」 → extension-task row/sheet with game feel.
- a1-v3-16 + question V3-21 「只有背包装载的格子是灰色的 还有物品下面应该也是一行两个的正方形？就是正常有样式的空态是灰色的」「背包里面的额度还有使用物品应该有空置的状态 是灰色的」 → items grid 2 per row squares; empty slots are visible styled grey squares.
- a1-v3-17 「不需要有白色的底 正常就是弹窗 直接放上二维码 但是还是：弹窗颜色不对」 → entry-code sheet: QR directly on the sheet, no white card.
- a1-v3-21 「我们的节点打开样式这种弹窗不对吧 我记得我们之前做过html的游玩 节点游戏进入不是弹窗？开启定位最好进入游戏就提示 后面就不需要提示了 而且最好有提示怎么打开」「提示应该是弹窗 不是背包 页面应该是新页面」 → node gameplay opens as a full-screen game screen (as in the earlier approved play prototype: full-screen playkit), not a sheet; location permission asked once on game entry with a how-to-enable guide (open settings) and never nagged again; hints open as a pop-up; backpack opens as a new page.
- c4-ug-05 「关闭按钮在右上角」 → hint sheet close top-right.
- s0-fixture-play-entry 「这个看起来bug了」 → find and fix (shot `staging/shots/s0-fixture-play-entry-after.jpg`).

### r2-finish — chapter end, run end, settlement, leaderboards, team rank, share
- Chapter end: style per `refs2/chapter-end-dissolve-frames.jpg` + Questo `B1-chapter-complete` structure (chapter N 完成, 用时, 本章得分, 下一站 …).
- s0-b1-run-ending 「本局完成应该有庆祝 然后要有动画效果的」; s0-b8-u08-01 「怎么感觉这么乱呢」 → celebration + motion; tidy finish page.
- **Settlement flow** after the last chapter, Questo order trimmed (README conclusion): 总览（名次/分数/章节/…）→ 排行榜（队内 / 全体 / 好友）→ 评价（星级 + 文字，可跳过）→ 分享卡（帖文/故事，保存图片；**含视频分享**: generate a short share video or the platform video-share path if available — investigate `wx.shareVideoMessage`/`wx.shareToWeRun`-like APIs and the existing video-bounty feature; implement what is real, else needs owner）. Only server facts; unknown never 0.
- Leaderboards: b3-23-4-3 「不用加那么多其实刚开始」 → start with few scopes (本关: 队内 / 全体 / 好友; keep city/world behind a single 「更多」 if needed); b3-p9 「这个榜单横向滑动的看起来有bug」 fix; **team rank** styled after `refs2/team-rank-laurel-frames.jpg` (laurel number, 前 N%, roll animation). Question 「P9 队伍之间榜」 default: wire `/api/orient/team/ranking` as the team tab now.
- Report pages b3-p7-1-2/3 「居中 icon可以换一下」「居中 感觉和上面说的一样有点高」, b3-p7 / b3-p8 「UI需要优化一下」.

### r2-join — multi-person sign-up, invites, landing, seats, checkout
- Questo A1–A4 structure: before play choose **一起玩（组队：一局、共享提示/跳过/计时、全队一个分数）** vs **各自比（同一关、各自手机、结束后排名）**, 实时/随时; invite seats; crew QR + join code + share. Map to our existing team/seat/club backend facts only (seat invitation P5, team detail, club crew); where a server fact is missing, show nothing and list it.
- b3-p1-5 (推荐/席位/已关闭) 「感觉没有游戏的感觉 一起玩下面可以有一些动画？」 → landing pages with game feel and motion.
- b4-u06-01 「UI需要优化一下」 seats card / join page.
- Checkout: Questo-like clear checkout summary; b5-x-p6-buy-refused 「不能有提示词 什么topicid 这种给玩家看 积分可以抵扣的 不能是全部用积分付款吧 这个我不确定」 → remove ids from player copy; points are a partial deduction on top of cash, never the whole payment, unless the server says so (needs owner if backend differs); b5-x-p6-citypack, b5-x-s17-2-claim-invalid/ready (「icon不对 需要换一下」), b5-19 revenue tab, b5-x-s18a 「上面的样式我会发你参考」(do layout cleanup only, mark waiting for owner ref).
- **自玩票 model** (a5-v3-29): 「自玩票不是主题发布的 是我们app官方的 就是像是平台会员 买了自玩票之后 平台发的官方主题都可以玩的意思 不是主题发布的需要的 更像是玩家会员」 → remove 自玩票 from the publisher's ticket page UI now; write a backend design (platform self-play membership covering official themes) under 待你决定 — **do not change payment/refund/settlement code**.

### r2-kits — gameplay kits (player + author)
- b2-ug-01 hangman: 「UI设计再优化一下 居中」「格子可以小一点的」「UI要优化一下 现在看起来结构不太好」.
- b2-ug-02 image options 「UI需要优化一下」(player + author).
- b2-ug-03 accusation 「UI需要优化一下」「不用写多余的提示 类似待运行验证这种」「UI需要改一下」(board, confirm).
- b2-ug-04 results 「字体大一点？ 居中 结案要有logo或动效」「成功失败 等待 都要有动态的图 你可以找一找有没有合适的动图 比如侦探找凶手的？成功的？失败的？」 → animated success / failure / waiting (rule 8).
- b2-ug-10 「这是什么 不能这样反转」「删掉这个翻转谜面」 → **delete clue transform (MIRROR/REVERSE) entirely** from player and author UI (server field may stay; never render or offer it).
- b2-x-cat 「不是这种啊 不是表单吗」 → gameplay catalog entry is the existing form flow, not this page.

### r2-create — creator / editor
- **Story editor redo** (a5d, all screens 要改; question 「这个整体改一下 就是类似 我发你参考图」, 「我给你截图了 参考x？」). Reference = **X Articles editor**: top bar 「草稿 · 刚刚最后保存」 left, 「预览」 text button + 「发布」 pill + expand + ⋯ right; toolbar row: B, I, S, paragraph-style dropdown (正文/标题/副标题), quote, bullet list, numbered list, link, emoji, **「插入 ▾」** menu (图片、音频、玩法、分割线、地点、…), info, word count right; then a wide cover image drop zone (5:2), big title 「添加标题」, author row, body 「开始撰写」. Mini-program version: keep our light editor styling; toolbar fixed under nav and docked above the keyboard; **every block draggable** (long-press handle, lift + shadow, neighbours slide, auto-scroll near edges, haptic). Read `refs2/editor-research.md` (GitHub research) before choosing the mechanism. Backspace at paragraph start merges into previous (implement with the closest real signal available; document limits).
- a5-v3-46 / v3-47 picker: 「这种叠层卡片感觉不太对 看看苹果怎么设计的…公共玩法库和我的模板可以横向滑动的 可以漏出来右边没有显示完整的正方形 让玩家知道可以滑动 感觉底部空了一大截 按钮应该放到底部固定位置的」; 「不应该是这样的？…如果这个是主题 主题只可以选择公共模板和我的模板 就是配置好了的 不然就自己建一个 不能在主题中配置玩法的这些游戏细节」 → theme picks only configured public/my templates (horizontal rows with peek) or 「自己建一个」 = the existing template form; no gameplay detail config inside the theme. Question V3-47 answered 「要」 → remove the top 完成.
- b4-21 preset pages 「为什么有这个 这个页面不对吧 我们玩法打开的动画不是那种圆形旋转的吗」「这个页面不对 没有这个」「这些就是我们的玩法模板啊 就是直接从主题或玩法那点进去配置的 上面的都不对啊」 → remove the §21 preset page/entry; presets are our gameplay templates reached from theme/gameplay; keep the existing circular-rotate opening animation.
- b4-u02-02 / u02-04 「不需要选择城市 就是配置的时候需要选择地址就好了」「不需要换城市」 → remove 换城 choice; adoption keeps original places and the author picks addresses while configuring.
- b4-u02-03 「UI需要优化一下有点乱 替换地点 还有票务之类的 不是整个节点需要替换」 → location todo: replace the location (and ticket items) per item, not the whole node.
- b4-u02-01 「UI需要优化一下」; b4-u02-05 「不显示「AI 生成」、「来源待确认」」.
- Question provenance editing 「放到模板配置里」 → add author provenance declaration in the template config media upload.
- a5-v3-29 publisher real-name 「发布者实名看起来怎么还是没有对齐 标题不应该是在下面白色边缘的页边距吗」 → align to page margin.
- a5-v3-31 「修改原模板应该是蓝色的 UI样式有问题 感觉卡片这个修改原模板的结构不对」.
- a3-v3-39 template cards, a3-v3-38 project cards 「和我的订单一样」 card structure (coordinate with r2-visual order card).

### r2-merchant
- a6-w2-07 「数字没有就是0」 → merchant numbers show 0 when none (merchant exception to "unknown ≠ 0" is the owner's call here).
- a6-w2-15 「不是横着滑动的吗 而且没有这种3d logo的」 → horizontal scroll cards, no 3D logo.
- a6-w2-16 (+ u2-w2-16) 「这个不是之前说用上一版吗 不要用积木的这个啦」「每一个阶段是不一样的动图 要和ai店铺角色和文字匹配」 → restore the previous approved intro version (git history of the AI-role intro before the blocks logo), each step its own animation matched to the step text.
- a6-w2-17 「点赞是红色的 这里也应该是正常的帖文样式啊 字体大小 等等 也可以发的 商家也可以发的」 → merchant 发现 uses the normal post card style (red like, same type sizes) and merchants can post.

### r2-visual — tokens, cards, album, invites, misc
- Rule 1 token change (player sheet surface + grey text contrast) and rule 5 radius token; a2-v3-18 「底部不需要公开位置已模糊处理的文字提示 整体UI再优化一下」.
- a3-v3-02/03 grey readability; a3-v3-22 「查看详情按钮感觉太大了…如果主题图片是横图就可以把正方形的图改成横着的」; a3-v1-p53 settings feedback 「UI样式需要优化一下」.
- Album a4-v3-27/34/36: 「收集册打开要有一个过场动画 你来设计 参考x上的」(use `refs2/album-map-stamps-frames.jpg`), 「这里面的图片要干净 或者换成动画的 官方活动可以不改 上面的漫游记录 集邮册 探索目标 感觉需要统一一点的样式？」, 「收集册底部不需要灰色的底 直接在背景上 你找的勋章 邮票 藏品都不太对 网上有很多3d的资源库 去找合适的」(free-licensed 3D icon sets, record license), 「图片问题我说过」; question V3-34 「要加上」 → finger-follow tilt via WXS (make build-action-ledger accept it or register the binding properly — fix the tool, don't skip).
- a7-v1-p59 invite records 「页面设计的不对 参考其他页面怎么做的 参考revolut」.
- b8-x-p3-01 「上面的筛选标签栏应该就是一行 地址点击顶部可以修改这种」, b8-x-p3-02 「下面卡片的设计不太好看」.

## Owner answers to round-1 questions (implement)
- V3-47 top 完成: remove. V3-34 tilt: add. Provenance editing: template config. 自玩局/活动场次提示阶梯: done in #755/#756. P9 team ranking: wire it (r2-finish).
