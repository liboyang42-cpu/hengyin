# refs3 — Screen structure for checkout, group sign-up, sharing, results and the editor toolbar

For implementing agents. Sources: Mobbin (iOS references, 2026-11-10), the 10 Questo screenshots the owner shared on 2026-11-10 (described in §0, no image files), and `refs2/questo/README.md` (Questo lobby A1–A4 and the 8-screen settlement B1–B11).

**Rule for every section below: copy the structure, keep 城瘾 styling.** Player pages are dark, merchant pages are light, every colour/radius/spacing comes from the `--cy-*` tokens and the master CSS kit. Never copy names, art, colours, icons or copy from Questo or any Mobbin app. No helper captions or sub-descriptions (owner rule): if a section needs a caption to make sense, fix the layout. Unknown server values show `—` or are hidden, never a fake `0`.

`[BACKEND?]` marks every item that depends on a server fact that may not exist yet. Before building it, check the API. If the fact is missing, hide the element and list it under 待你决定 / needs owner. Do not change payment, refund or settlement code in a UI lane.

Image files: `refs3/mobbin/<topic>-<app>-<n>.jpg` (390 px wide). Questo images from refs2: `refs2/questo/*.jpg`.

## Concept map (Questo → 城瘾)

| Questo | 城瘾 | Notes |
|---|---|---|
| Unlimited (annual, auto-renew) | **自玩票** = platform player membership; covers all *official* (platform-published) themes | Not sold by publishers. `[BACKEND?]` membership product, entitlement check, expiry, renewal. WeChat mini-programs on iOS cannot sell virtual subscriptions through the mini-program the same way; owner must confirm channel and whether it auto-renews. |
| Trip Pass / City Pass / Exploration pass | **城市通票** (all themes of one city, or a curated set, for N days / 12 months) | `[BACKEND?]` city-pack SKU exists in some form (b5-x-p6-citypack); confirm coverage and validity fields. |
| Gems | **积分** (points) | Only a *partial* deduction on top of cash. Never the whole payment unless the server says so. No points top-up store unless the owner asks. `[BACKEND?]` deduction cap per order and points ledger API. |
| Seats stepper ("Add their phones") | **同行席位** | Buyer buys N seats on one ticket; others claim a seat by invite. `[BACKEND?]` seat count on order, seat-claim invite (P5). |
| Team up / Compete | **一起玩 / 各自比** | 一起玩 = one run, shared hints/skips/timer, one team score. 各自比 = same theme, each phone its own run, ranked at the end. `[BACKEND?]` per-run mode flag and the aggregate ranking for 各自比. |
| Live / Anytime | **同时开始 / 各自时间** | `[BACKEND?]` only show if the server supports it. Otherwise omit the row. |
| First 2 chapters free | **试玩前 N 章** | `[BACKEND?]` almost certainly missing: needs a per-theme free-chapter count and a gate at chapter N+1. Hide until it exists. |
| Beta quest "free while in testing, review it, get ◆20" | 内测主题 | `[BACKEND?]` beta flag + review reward. Out of scope unless the owner asks. |
| Crew (12 seats, long-term) | 俱乐部 / 我的队伍 | Keep separate from ticket seats (refs2 README). |
| Activate partner purchase | 兑换码 / 核销 | Use the existing redeem entry if it exists. |

---

## 0. Questo screenshots from the owner (2026-11-10), what each one teaches

1. **Explore**: real-player photo cards row → big ticket-shaped membership card (one line of value, struck price → price, one primary button, one line of renewal terms, a secondary row "short trip? passes from ¥88 ›", Terms/Privacy/Restore links) → "Top in city" cover cards (category tag, rating, distance). Floating tab bar with a centre search button. *Structure lesson: the membership pitch is one card inside the feed, not a separate wall.*
2. **Store sheet**: sheet title + receipts icon + close; balance card; segmented `Passes | Gems`. Passes tab: membership card first (annual price, button, monthly alternative as a text row, auto-renew legal line), then pass rows with ribbon labels, struck "price with card" and a green chip showing the points deduction. Gems tab: one explainer line, pack rows with discount ribbons, best value highlighted.
3. **Notification permission sheet** over the store: big icon circle, small label, headline, short body, a card of 3 benefits, primary "Allow", text button "No thanks". (城瘾: WeChat subscribe-message request — `wx.requestSubscribeMessage` — only after a purchase, never before.)
4. **Gems page**: balance card; segmented `Top up | Activity`; packs (middle one badged BEST) + a city pass upsell card with 6 cover thumbnails, struck price, save-% chip, "Get pass →". Activity = dated ledger rows with `+N reason`. Welcome sheet: "we added 15 to your wallet" + Continue.
5. **Beta quest detail**: hero with back / invite / favourite; status chip; title; stars + review count; route line with time and distance pills; invite-to-test card; start-location row; primary button; "What we ask" bullets; toast when too far from start.
6. **Quest checkout** (the key one, see §1).
7. **Lobby before play** (see §2).

---

## 1. Checkout — single theme with seats, pass upsell, points deduction

### References
- Questo quest checkout (owner screenshot 6) — selectable "this quest" card with nested seats stepper; alternative selectable ticket-shaped pass row; trust row; primary + sticky bottom bar; "play first chapters free" link; "activate partner purchase" link.
- Swiggy event booking — [flow](https://mobbin.com/flows/7ccef161-d08e-4870-93dd-329fe2331160?tab=screens&utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp): ticket rows with `ADD` that turns into a `− 1 +` stepper, sticky bottom bar `1 ticket · ₹1,399 | Proceed` (`checkout-swiggy-1.jpg`); then **Review**: item summary with stepper still editable, wallet-credit toggle with available balance, coupon row, bill details (line items, fee, To pay), collapsible "things to remember" and terms, sticky `pay using … | Pay ₹1538` (`checkout-swiggy-2.jpg`).
- Luma registration — [flow](https://mobbin.com/flows/92c2dde4-f712-43c5-b1c0-fba5bc81858b?tab=screens&utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp): one compact screen, event thumbnail + title, ticket row, required agreement checkbox, total + Pay; Pay disabled until the box is ticked.
- Ahead plan list — [screen](https://mobbin.com/screens/97c7002a-57bd-413d-8d29-9550601efed0?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`checkout-ahead-1.jpg`): selectable rows, the selected one filled, ribbon "most popular", save chip; Lifesum [screen](https://mobbin.com/screens/8b62f92e-5ada-4dc4-89f1-70a82820887e?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`checkout-lifesum-1.jpg`) 3 plan tiles + struck prices + one-line renewal terms.
- Badoo credit packs — [screen](https://mobbin.com/screens/1ac8523c-9064-4d69-b32e-5af89b256323?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`checkout-badoo-1.jpg`): 2×2 pack grid, selected tile outlined with a check, single Continue. (Reference only if a points pack is ever approved.)

### 城瘾 structure: 主题结账页 (page, dark player style)

Entry: theme detail `购买` / `开始` when not entitled. A full page, not a sheet (it has a stepper, options and a summary; a sheet would scroll inside a sheet).

1. **Header block** (scrolls): theme cover (short hero), title, rating + count `[BACKEND?]` hide if no rating data, route pills (时长 / 距离 / 章节数) — only server values.
2. **Option A — 单次购买 card** (selectable, selected by default):
   - Price, struck original price only if the server has one, `一次购买，永久可玩` style one-liner only if the server says tickets never expire `[BACKEND?]`.
   - Nested row **同行席位**: `− N +` stepper. Min 1 (the buyer), max = theme's seat cap `[BACKEND?]`. Stepper disabled at the bounds. Price per extra seat shown on the row (`+¥x/人`) `[BACKEND?]` seat price may equal ticket price.
3. **Option B — 城市通票 row** (selectable, ticket-shaped in our own style): `城市名 通票 · N 个主题 · 有效期` + price. Only when a pass covering this theme exists `[BACKEND?]`. Selecting it collapses the seat stepper (or keeps it if passes also carry seats `[BACKEND?]`).
4. **Option C — 自玩票 row**: only for official themes, and only if the membership product exists `[BACKEND?]`. If the user already holds it, skip checkout entirely and go to the lobby.
5. **积分抵扣 row**: toggle with `可用 N 积分，抵 ¥x`. Deduction is capped (never 100% of the order) `[BACKEND?]` cap rule. Hidden when balance is 0 or unknown.
6. **优惠券 row** › only if coupons exist on the server.
7. **明细 (bill)**: line items (票 × N 席位, 积分抵扣 −¥x, 优惠 −¥x), total. Same numbers as the sticky bar.
8. **Trust row** (three small icon+label items): e.g. refund rule, validity, play count — only facts the server or the published refund policy guarantees `[BACKEND?]`. No marketing numbers we cannot prove.
9. **Links** (text, small): `试玩前 N 章` `[BACKEND?]` hidden until it exists; `已有兑换码` → existing redeem page.
10. **Agreement**: checkbox with 购买须知 link, Pay disabled until ticked (Luma pattern) — only if 城瘾 already requires consent at purchase.
11. **Sticky bottom bar** (always visible): left `¥total` (+ `N 席位`), right primary `支付并开始` / `支付 ¥x`. Updates live as stepper/toggles change.

States: loading skeleton; option unavailable (greyed with reason in the row title, no caption); payment cancelled → stay on page, toast; payment success → **post-purchase sheet**: success icon, "已解锁", seat summary, primary `邀请同行` (goes to §2 seat invite) and secondary `开始`; then the subscribe-message permission sheet (Questo screenshot 3 structure) as an optional step `[BACKEND?]` template IDs.

### 城瘾 structure: 自玩票 / 城市通票 surfaces

- **Explore feed card** (Questo screenshot 1): one membership card inside 发现, after the first content row, not a full-screen paywall. Contents: name, one value line, price (struck only if real), primary button, one terms line, secondary row `只玩几天？城市通票 ¥x 起 ›`. Hidden when entitled.
- **票夹 / 通票 sheet** (Questo screenshot 2): sheet with title + `订单` icon + close; points balance card; segmented `通票 | 积分记录`. 通票 tab: 自玩票 card first, then pass rows (ribbon label, price, struck "原价", green chip `积分可抵 ¥x`). 积分记录 tab: dated ledger rows `+N 原因` / `−N 抵扣` (Questo screenshot 4 Activity) `[BACKEND?]` ledger API. **No points top-up packs** (points are earned, not sold) unless the owner decides otherwise.
- City-pass upsell card (screenshot 4): city name, `N 个精选主题 · 有效期`, row of up to 6 cover thumbnails, price + struck price + save chip, `获取通票 →`.

---

## 2. Multiplayer sign-up — invite, mode choice, seats

### References
- Questo lobby (owner screenshot 7; `refs2/questo/A1–A4`): language, "playing as … Edit", a play-with-friends card with avatar + dashed `+` slot, "How do you want to play?" two option cards with one consequence line each, "When you play" two options, sticky "See the starting point →".
- Grab group ride — [screen](https://mobbin.com/screens/50db841d-bd01-458d-abec-b14688addbb7?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`group-grab-1.jpg`): "Your group 1/3 joined", avatars + dashed `+` slots, `Invite friends` link top-right, disabled sticky primary until ready, expiry line under it.
- GoPay add member — [screen](https://mobbin.com/screens/46d506cf-463a-4d0a-b57d-e5740c02ad21?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`group-gopay-1.jpg`): QR card, "invite via link" row with Share button, "Joined members" avatars, Close.
- Abode join code — [screen](https://mobbin.com/screens/c4f8a5a9-caa1-479e-b942-5fa369422b4e?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`group-abode-1.jpg`): big readable code with scan + share icons, then contact list with Invite / Added states.
- Wanderlog invite sheet — [screen](https://mobbin.com/screens/e4d1ab6a-339c-4a5a-b6f0-fa9a7064d0f3?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`group-wanderlog-1.jpg`): bottom sheet, segmented permission, 3 round share actions (copy link / message / other), "manage" row.
- Tempo challenge mode — [screen](https://mobbin.com/screens/4192498d-46db-41fb-9732-b2b24103c72c?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`group-tempo-1.jpg`): two large mode cards (Competition / Target) with a small illustration each, plus "join a challenge from a friend" row. Deezer — [screen](https://mobbin.com/screens/c72f4bf4-114c-4c01-821e-829a727ecbdb?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`group-deezer-1.jpg`): cover + "pick your mode" two stacked buttons.
- PlayStation party — [flow](https://mobbin.com/flows/3a2b825e-bf6e-4714-a086-ad1d36e2b966?tab=screens&utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp): party settings sheet (name, type, require approval toggle, invite avatars ›, player limit slider), then party room "In party (1/6)" + owner badge + waiting state "Waiting for others to join" + Invite button + toast "Invitations sent".

### 城瘾 structure: 开局大厅 (page, dark) — after entitlement, before the first chapter

Order top → bottom:
1. **Compact header**: theme cover thumb + title; `以 昵称 身份游玩 · 编辑` row.
2. **同行 card** (Questo A1 + Grab): title `一起玩的人 N/M` (M = seats on the ticket `[BACKEND?]`). Avatars for claimed seats (owner badge on the buyer), dashed `+` slots for open seats. Tap `+` → invite sheet (below). Each claimed avatar long-press / tap → sheet with `移出` (owner only, red destructive row) `[BACKEND?]` seat release API.
   - If the ticket has 1 seat: show the card with a single `+` that leads to `加席位` (back to checkout stepper, add-on purchase `[BACKEND?]`), or hide the card if add-on seats are not supported.
3. **玩法 segment — 怎么玩**: two option cards side by side (Questo A2/A4, Tempo). `一起玩` with one consequence line ("一局 · 共用提示与计时 · 一个队伍分"), `各自比` ("各玩各的 · 结束后排名"). Only shown when ≥2 seats are claimed or invited; solo players skip it. `[BACKEND?]` run mode flag. Selecting `一起玩` can expand an inline "怎么一起玩" disclosure (Questo A3) — a collapsed row, not a caption.
4. **开始时间** row (`同时开始 / 各自时间`): only if the server supports async 各自比 `[BACKEND?]`; otherwise omit.
5. **Sticky bottom**: primary `去起点 →` (opens the start-point map/directions). Disabled with reason in the button label when a required choice is missing (e.g. `等待队友加入` when the owner chose 同时开始 and seats are unclaimed).

### Invite sheet (bottom sheet, half height)
Structure from GoPay + Abode + Wanderlog, mapped to WeChat:
1. Title `邀请同行` + close.
2. Primary row: `发给微信好友` (button `open-type="share"` → share card that opens the seat-claim landing page). This is the main path in WeChat.
3. **QR card**: mini-program code for this seat invite (scan in person) `[BACKEND?]` wxacode generation with seat token.
4. **Join code** row: short code in a large monospace style + copy icon (Abode) `[BACKEND?]` join code.
5. **已加入** list: avatars + names; pending invites with `撤回` (Wise pattern) `[BACKEND?]`.
Toast `已发送` after share returns.

### Invited-player landing (P1, page)
From the share card: theme cover, `昵称 邀请你一起玩`, seat state (`剩余 N 席`), mode already chosen by owner (read-only chip), primary `加入`. States: seat full → `席位已满` + `自己购买` secondary; invite expired/revoked → `邀请已失效`; already joined → go straight to lobby. Login on tap of `加入` only (guest-browse rule).

### Club crew vs ticket seats
Keep 俱乐部 (long-term crew) and 同行席位 (per ticket) as separate concepts and screens. The lobby may offer `从俱乐部选人` as a row inside the invite sheet only if the club member list API exists `[BACKEND?]`.

---

## 3. Sharing — poster card and video recap

### References
- Questo share (`refs2/questo/B11`): segmented Post / Story, a card with place, theme, score, chapters, link; `Share` / `Save image` / `Done`.
- Garmin share activity — [flow](https://mobbin.com/flows/29d268a9-9eb6-49d3-9878-b70605a4aebe?tab=screens&utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp): step 1 choose aspect ratio (9:16 / 4:5 / 1:1) (`share-garmin-1.jpg`); step 2 horizontal carousel of card designs (photo / stats / map) with page dots and a bottom row `Share | Save | Copy` (`share-garmin-2.jpg`).
- Duolingo year in review — [screen](https://mobbin.com/screens/ad1dd30e-896f-4060-9179-6ab3d9f4a6fd?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`share-duolingo-1.jpg`): card carousel with a peek of the next card, bottom sheet with 3 round actions (Messages / Save image / More).
- Polarsteps trip reel — [screen](https://mobbin.com/screens/21271093-b35b-407a-89ec-7fb8dd665e3e?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`share-polarsteps-1.jpg`): **video recap**: full-bleed 9:16 video preview with title, round `Download` + `Other`, then `Edit` and `Replay` pill buttons.
- Slopes — [screen](https://mobbin.com/screens/d3a22b7c-ad68-461d-9431-c945384e874f?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`share-slopes-1.jpg`): stats card with a 3D map of the place + "made with" footer; actions row Save / Messages / Story / More.
- Spotify Wrapped — [screen](https://mobbin.com/screens/6b681412-559a-4fbf-af3e-1e97b4207e84?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp): card carousel + single `Share` pill (owner already named Spotify for V1-P36). Strava month recap — [screen](https://mobbin.com/screens/3c96c039-8f09-4101-8fe1-c24716b83638?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp): card carousel + "Share to" row + Post-to-feed / Save / More.
- Nike Training Club share accomplishment — [flow](https://mobbin.com/flows/14b34f87-869d-4264-9afc-ca069381264e?tab=screens&utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp): choose poster/background → "Share to" list.

### 城瘾 structure: 通用分享页 (full-screen page over a dimmed background, dark)
One component for theme completion, roam, activity and medal (matches LANES V1-P36).

1. Top: close (left), title `分享` (centre), format toggle (right) `图片 | 视频` — the 视频 option only when a video can actually be produced (below).
2. **Card carousel** (center, 9:16 by default, peek of next card, page dots): card variants — ① 成绩卡 (theme, city, score, chapters, 名次, date) ② 路线卡 (route map with stations) ③ 照片卡 (player's photo from the run, if any) ④ 勋章卡 (with mini-program code). Only server values; missing stat → line hidden.
3. Optional aspect toggle `9:16 / 1:1` as a small segmented control under the carousel (Garmin) — only if both sizes are rendered; otherwise skip.
4. **Action row** (bottom, round icons with labels): `微信好友` (`open-type="share"`, uses the card as `imageUrl`), `朋友圈` (save image + copy text, then toast with the steps), `小红书` (save image + copy text), `保存`. One primary row, no more.
5. Video mode: preview player in place of the card carousel (Polarsteps), `重播` and `保存视频` buttons, then `发给微信好友`.

### Video recap — what is real in WeChat, flags
- `wx.shareVideoMessage` shares a **local video file** to a WeChat friend (requires the file to be on the device, size/length limits). `[BACKEND?]` 城瘾 has no recap-video generator; options are (a) server-side render of a short clip from run photos + map + score, returned as a URL, downloaded then shared; (b) client-side canvas frames — not practical in a mini-program. Both need an owner decision. Until then: hide the 视频 toggle.
- `wx.saveVideoToPhotosAlbum` for 保存视频 (needs album permission; handle refusal with the settings re-entry).
- The existing 视频悬赏 / video-bounty feature (player-uploaded clips) may be reusable as a source of the player's own clip `[BACKEND?]` check whether a completed run links to an uploaded video.
- Channels (视频号) publishing APIs need a bound 视频号 and are out of scope without the owner.

---

## 4. End of game — results, leaderboard, rating

### References
- Questo settlement (`refs2/questo/B1–B11`), trimmed per README to 4 screens: 总览 → 排行榜 → 评价 → 分享.
- Duolingo league result — [screen](https://mobbin.com/screens/99e7f2d1-6876-4848-b5cf-dbd596d2e0ce?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`results-duolingo-1.jpg`): trophy + "You finished #9" headline, list centred on the player's row (highlighted), single Continue.
- corner city leaderboard — [screen](https://mobbin.com/screens/e86500f6-d8ad-4205-8c41-3786c78ca0e6?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`results-corner-1.jpg`): segmented `City | Global` + period dropdown, photo rows with rank medals 1–3 and `#4` numerals for the rest.
- Tempo leaderboard — [flow](https://mobbin.com/flows/909f5e17-4136-4181-b007-033077cc516f?tab=screens&utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`results-tempo-1.jpg`): "My place 1" header, list, and **the player's own row pinned at the bottom** while scrolling.
- Garmin challenge — [flow](https://mobbin.com/flows/53b12814-0647-4c5e-bc1c-1e25c3e8e57d?tab=screens&utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp): leader-vs-second progress line above the ranked list; ties share a rank number.
- Rating: Nibble — [screen](https://mobbin.com/screens/140c1cc2-6837-4f0c-be4f-14b2de4225d1?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`results-nibble-1.jpg`): success mark on top, rating card at the bottom with empty stars, Submit disabled until a star is picked, Skip top-right. Walmart — [screen](https://mobbin.com/screens/77c729e7-3f3e-457b-ba04-15972cf5fdf3?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`results-walmart-1.jpg`): stars → "what went well" selectable chips/cards appear → optional text with counter → Submit.

### 城瘾 structure (4 steps, one page with steps or 4 pushed screens — keep the existing settlement route if one exists)

**Step 1 — 总览** (full screen, dark, celebratory motion from the existing kit):
- Headline `通关` + theme title; big 名次 (`本主题第 N 名`) `[BACKEND?]` per-theme rank; stat row 分数 / 章节 n/n / 用时 (server only).
- Rewards strip (勋章 / 积分 +N) only if granted by the server.
- Buttons: primary `看排行榜`, secondary `完成`. Share icon top-right jumps to step 4.

**Step 2 — 排行榜** (page):
- Segmented `队内 | 全体 | 好友` (Questo B5–B7). 队内 only for 一起玩 teams with >1 member or 各自比 groups; 好友 `[BACKEND?]` WeChat has no friend graph API for mini-programs — needs a 城瘾 follow/club relation or `wx.getGroupEnterInfo` share-ticket group ranking; hide the tab until a source exists.
- Top 3 rows with medal marks, rest `#N`; ties share rank (Garmin). Each row: avatar, name (team name for 一起玩), score, time.
- **Own row pinned at the bottom** when it is off-screen (Tempo); highlighted in-list when visible (Duolingo).
- Empty 好友 state: illustration from the Figma empty-state kit + one line, no helper caption.
- Sticky primary `下一步` → step 3.

**Step 3 — 评价** (bottom sheet over step 2, or a page; skippable):
- `跳过` top-right; 5 stars; after a star: tag chips (what was good / what to fix) `[BACKEND?]` tag list; optional text with counter; `提交` disabled until a star is chosen. Note that reviews are public only if the server publishes them `[BACKEND?]`.
- After submit: toast, auto-advance to step 4.

**Step 4 — 分享 + 下一个** (the §3 share page), then a final card `下一个主题` (Questo B10) with a city-pass upsell row if passes exist and the invite-reward row only if a referral reward exists `[BACKEND?]`.

Per-chapter end (Questo B1) stays a small interstitial inside play: `第 N 章完成` + 用时 / 本章分 + `下一站：…`.

---

## 5. Theme editor toolbar with insert menu (merchant, light)

### References
- Notion — [screen](https://mobbin.com/screens/23ebacc5-4ddd-4afc-ae8e-3a07063e80cd?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`editor-notion-1.jpg`): a pill toolbar above the keyboard area (undo, indent, move up/down, **+**, text style, close); `+` replaces the keyboard with a 2-column grid of block types under a section label.
- Craft — [screen](https://mobbin.com/screens/af7b909c-1e76-4536-b9e0-33c522c81816?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`editor-craft-1.jpg`): insert sheet with search on top, then grouped icon tiles (media / rich blocks / files).
- Substack — [screen](https://mobbin.com/screens/c89442d1-55da-4ea3-a887-290eddf19ca9?utm_source=claude-code&utm_medium=ai-assistant&utm_source_platform=mcp) (`editor-substack-1.jpg`): Cancel / Saved state chip / Next in the top bar; small popover insert menu (Image, Video, Button, Divider) anchored to the toolbar.
- Also seen: Medium bottom format bar (style, quote, list, divider, mention, code, image); Fabric toolbar + 2-column block grid + destination row + Save.

### 城瘾 structure: 主题编辑器 (章节 / 站点 content)
- **Top bar**: back, title, save-state chip (`已保存` / `保存中` / `未保存`) — explicit save button if the review-page rule applies (no autosave where the owner said so), `预览` on the right.
- **Body**: block list (story text, image, question, clue, location, game). Each block has a drag handle and a `⋯` menu (上移 / 下移 / 删除 red).
- **Bottom toolbar** (sticky, above keyboard when editing, at safe-area bottom otherwise): `+ 插入`, undo, redo, text style, `收起键盘`. Matches the owner's earlier decision "tools at bottom".
- **Insert sheet** (`+`): half-height sheet; optional search field only if there are >10 block types; grouped 2-column tiles: 内容 (文字, 图片, 音频) / 互动 (问题, 线索, 小游戏 from 玩法库) / 地点 (站点, 打卡). Pick-not-configure: tapping inserts a block with defaults; settings open later from the block itself.
- Reuse the existing 玩法库 horizontal template cards for the 小游戏 group rather than a new tile style.

---

## Backend facts to verify before building (collected)

1. Seat count on an order, seat cap per theme, extra-seat price, seat claim/release/revoke APIs, invite token + QR (wxacode) + join code.
2. Run mode flag (一起玩 / 各自比), shared hint/skip/timer pool, aggregate ranking for 各自比, async start.
3. 自玩票 membership product and entitlement (official themes only), renewal model, iOS virtual-payment constraints — owner decision.
4. 城市通票 coverage, validity, price, and whether it carries seats.
5. 积分 deduction cap per order and points ledger API (no top-up).
6. 试玩前 N 章 (free-chapter count + gate) — likely missing.
7. Per-theme rank, team/everyone ranking lists; 好友 ranking source (none in WeChat by default).
8. Rating tags, public reviews, referral reward.
9. Recap video generation (server render) and whether runs link to uploaded clips; subscribe-message template IDs.
