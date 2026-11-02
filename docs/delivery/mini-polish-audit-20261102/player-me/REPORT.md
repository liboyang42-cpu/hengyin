# Player "Me" lane: polish audit (2026-11-02)

Lane: player-me. Audience: the player (dark side). Base: `claude/mini-polish-audit-20261102` (master a1d30fb).
Method: static reading of `chengyinhub-xcx/` only. Nothing was rendered. Each finding is marked `code` (read directly) or `inferred`.
Scope pages: subpackageMember, subpackageP3, subpackageA, subpackageB, subpackageTools, pages/shezhi, gerenziliao, userinfo, mylike, address, addressinfo.

## 1. Summary (top 5 by player impact)

1. **Refresh status rows push the list down** on 6 pages (mycanyu, mylike, address, im/list, myproject, gerenziliao). Pull-to-refresh inserts a 64–88rpx in-flow row, so the whole list jumps down and then back up. This breaks "page shape never changes while loading". One shared overlay class fixes all 6 (PM-01).
2. **The skeleton has the wrong shape** on points-history, appeal-mine and report-mine. A `feed-card` skeleton (cover-image cards) stands in for compact hairline text rows, so the page visibly changes shape when the data lands. Changing it to `type="list"` is a one-attribute fix per page (PM-02).
3. **The money roll is fast-slow, not slow-fast-slow.** The player "账户收益" balance counts up over 220ms with the default `easeOutCubic`. The merchant home already uses `motion.easeInOutCubic`, and the fix is the same one option (PM-05).
4. **Fake zeros appear while data loads.** IM route cards show "0 km · 0 个点" before `topicCache` fills. Other players' profiles show "0 获赞 / 0 粉丝" from `userInfo: {}`. Anniversary shows "0 年前" when the value is missing. These should show "—" (PM-07, PM-08, PM-14).
5. **Sibling record lists don't match each other.** 我的参与 has no list entrance while 我的订单 and 积分明细 stagger in. Its cards sit 16rpx in from the edge, but everywhere else uses `--cy-page-x` (32rpx). On 我的订单, the illustration changes depending on the filter. These are small moves that make the three lists read as one family (PM-03, PM-04, PM-10).

Tap feedback is in good shape in this lane. Every raw `bindtap` I found already has `hover-class`, or sits on `cy-btn` / `cy-cell`, which have built-in press states. I am not proposing any tap-feedback work here.

## 2. Pages, ordered by how often a player reaches them

The ranking is `inferred` from how prominent each entrance is: the profile tab (`subpackageMember/components/cy/profile/index.wxml`), its nav button to Settings, and the Settings list (`pages/shezhi/shezhi.wxml`).

Tier 1 is opened from the profile tab itself or from any avatar: profile hub, userinfo (other people's profiles), signup (票夹), order, coupon-wallet, shezhi, gerenziliao.
Tier 2 is a profile section link or the collection album: growthcenter, badge-wall, stamp-album, object-cards, points-history, earnings, mycanyu, im/list and im/chat.
Tier 3 is one level deeper, or reached from roam or Settings rows: mylike, myproject (creators only), infomation, subpackageTools (from roam), address/addressinfo (from the signup flow), appeal-mine, invoice*, report-*, about, marketing, complaint, character, orient-score, free-explore, coupon-qr, tixian*.

Finding columns: `ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence`

### Tier 1

**Profile hub** (`subpackageMember/components/cy/profile`): already good. The section heads, order cards (capped stagger `index < 5 ? index*40 : 200`), skeleton, error and empty states all go through shared components, and every tap has a press state.
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-08 | profile (other) / userinfo | subpackageMember/components/cy/profile/index.wxml:335-343 | public stats "发布内容 / 获赞 / 粉丝" | `{{userInfo.likeNum \|\| 0}}`, and `userInfo` starts as `{}` (index.js:394) | Before the profile loads, or if a field is missing, a real person appears to have 0 likes and 0 fans (rule 7) | Render `—` when the field is `null`/`undefined`, e.g. through a tiny wxs `n(v)`, instead of `\|\| 0` | empty-state | S | low | P1 | code (fallback); inferred (whether the header renders before load) |

**userinfo** (`pages/userinfo/userinfo.wxml`): already good. It is a thin host for `cy-profile`, with an honest missing-parameter empty state. Only PM-08 applies.

**signup / 票夹**: this is v13-approved work ("票夹" in commit 9bc8af56). It uses a `ticket` skeleton that matches the final shape. Not re-opened.

**order** (`subpackageMember/order` + `components/cy/scene-member-order-history`): already good. The `list` skeleton gives way to rows with a capped `--pmi` stagger; the error and empty states fade.
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-10 | order | subpackageMember/components/cy/scene-member-order-history/index.wxml:22 vs :25 | two empty states on one page | No orders shows the `d_yhqon.png` figure; a filter with no results shows the `kind="empty"` SVG art | Switching filter tabs swaps the illustration on the same screen | Use `kind="empty"` for line 22 as well and keep the title and CTA | empty-state | S | low | P2 | code |
| PM-11 | order | scene-member-order-history/index.wxml:25 | filter-empty sub "换个筛选试试" | helper caption | A second line that carries no data (rule 3) | Drop `sub` | empty-state | S | low | P2 | code |

**coupon-wallet** (`scene-game-coupon-wallet`): already good. Its states are ordered (login, permission, skeleton `card`, faded empty), it is wired to page-motion, and it has no findings.

**shezhi** (`pages/shezhi/shezhi.wxml`): already good. It is a single `cy-cell` list with built-in press, and logout is deliberately neutral.
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-12 | shezhi | pages/shezhi/shezhi.wxml:23 | 我的收藏 cell | description "收藏的帖文与主题" / "收藏的主题" | Helper caption on one row out of 12 (rule 3), which also makes this the only two-line row in the list | Remove the `description` | structure | S | low | P2 | code |

**gerenziliao** (`pages/gerenziliao/gerenziliao.wxml`): already good. It is the owner-approved 2026-10-30 P-2 layout with a `form-section` skeleton that matches the grouped rows. PM-01 applies at line 14 (the "正在确认个人资料…" line inserts 72rpx above the hero).

### Tier 2

**growthcenter / leaderboard / stamp-album / badge-wall**: already good. All four have page-motion staggers, and growthcenter counts its numbers with `countIn`. badge-wall and its enamel badges are approved and were not touched. The only note is that each page hand-writes an `nth-child` delay ladder; see cross-page pattern X3.

**object-cards**: already good. Every tap has a press state and the empty and skeleton states are present. No findings.

**points-history** (`subpackageMember/points-history/index.wxml`): already good. Its groups stagger in and it has an honest end line ("已经到底了").
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-02a | points-history | subpackageMember/points-history/index.wxml:9 | loading skeleton | `type="feed-card"` (cover and copy cards) | The content is thin text rows (`.ph-row`, wxss:4), so the page shape changes on load (rule 6) | `type="list"` | loading-state | S | low | P1 | code |
| PM-11b | points-history | index.wxml:14 | filter-empty sub "换个筛选试试" | helper caption | rule 3 | Drop `sub` | empty-state | S | low | P2 | code |

**earnings / 账户收益** (`subpackageA/pages/assetcenter/earnings` + `components/cy/scene-asset-earnings`): already good. It has an amount skeleton, the balance counts up from 0, and it respects reduced-motion.
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-05 | earnings | components/cy/scene-asset-earnings/index.js:149-151 | balance count-up | `motion.countUp(0, target, …, { decimals: 2 })`, which means the default `easeOutCubic` over `COUNT_UP_MS` = 220ms (utils/motion.js:16) | Money numbers should be slow-fast-slow (rule 6). 220ms reads as a jump, not a roll. The merchant home already does this right (pages/merchant/index/index.js:1017) | Add `ease: motion.easeInOutCubic, duration: 600` (600ms matches `pageMotion.countIn`). Timing only; the animation stays | motion-transition | S | low | P1 | code |

**mycanyu / 我的参与** (`subpackageMember/mycanyu/mycanyu.wxml`): already good. It uses a `list` skeleton, the error state fades, and rows have press states.
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-01a | mycanyu | mycanyu.wxml:16 / mycanyu.wxss:9 | "正在更新参与记录…" | in-flow row, `min-height: var(--cy-btn-h)` | The list jumps 88rpx on refresh | Shared overlay class (X1) | loading-state | S | low | P1 | code |
| PM-03 | mycanyu | mycanyu.wxml:18 | `.canyubox_li` rows | appear all at once | The sibling lists 我的订单 and 积分明细 stagger in; this one doesn't | Copy the order-history pattern: `pageMotion.enter(this,'pmList')` on first load, the `--pm-l` style on `.canyubox`, `--pmi:{{index}}` on rows, and the one-line `.orderbox_li` transition rule (scene-member-order-history/index.wxss:93) | motion-entry | S | low | P2 | code |
| PM-04 | mycanyu | mycanyu.wxss:12 | card side margin | `margin: 0 var(--cy-space-2) …` (16rpx) | Every other list in this lane, and the page title, sit at `--cy-page-x` (32rpx), so the card edges don't line up with the title | `margin: 0 var(--cy-page-x) var(--cy-space-4)` | spacing | S | low | P2 | code |
| PM-11c | mycanyu | mycanyu.wxml:15 | filter-empty sub "换个筛选试试" | helper caption | rule 3 | Drop `sub` | empty-state | S | low | P2 | code |

**im/list** (`subpackageB/pages/im/list`): already good. It is wired to page-motion and every tap has a press state. PM-01 applies at line 22 ("正在刷新消息" row, 64rpx).

**im/chat** (`subpackageB/pages/im/chat`)
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-07 | im/chat | subpackageB/pages/im/chat/index.wxml:96, :242 | route card / route picker sub-line | `{{… totalMileage \|\| 0}} km · {{… locationCount \|\| 0}} 个点` | While `topicCache` is still loading, or when the route has no data, the card says "0 km · 0 个点", which is false (rule 7) | Show `—` when the value is null, e.g. `{{x == null ? '—' : x}}` | empty-state | S | low | P1 | code |

### Tier 3

**mylike / 我的收藏** (`pages/mylike/mylike.wxml`): already good. The skeletons match their tabs (`post-card` and `card`), and the cover scrim correctly passes taps through.
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-01b | mylike | mylike.wxml:24, :39 / mylike.wxss:22 | `.favorite-refreshing` | an **empty** in-flow view with `min-height: var(--cy-btn-h)` and no text | On refresh an 88rpx blank gap opens above the list, with nothing in it to explain the jump | X1 overlay class, or simply delete the element (it shows nothing) | loading-state | S | low | P1 | code |

**myproject** (creators): already good. The motion is wired and taps have press states. PM-01 applies at lines 25 and 108. The light theme is listed under bugs.

**subpackageTools** (anniversary, monthly-recap, capsule, exploration-goals, exploration-export, reached from roam): already good. Every tap has a press state and rows use `cy-rise-in`.
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-13 | anniversary, monthly-recap, capsule | subpackageTools/anniversary/index.wxml:14; monthly-recap/index.wxml:42; capsule/index.wxml:62 | row stagger | `animation-delay: {{index * 60}}ms`, uncapped | With 20 rows the last one waits 1.2s, which is slower than "a little faster" | Cap it as the profile does: `{{index < 6 ? index * 60 : 360}}ms` | motion-entry | S | low | P2 | code |
| PM-14 | anniversary | subpackageTools/anniversary/index.wxml:15 | "N 年前" badge | `{{item.yearsAgo \|\| 0}} 年前` | A missing value reads "0 年前" (rule 7) | `—` when null | empty-state | S | low | P2 | code |
| PM-15 | anniversary, monthly-recap | anniversary/index.wxml:13; monthly-recap/index.wxml:13 | first load | one text line, "正在翻往年记录…" / "正在读取当月记录…" | Rows replace a single line, so the page shape changes on load (rule 6) | `<cy-skeleton type="list" count="3" />` in place of the text line | loading-state | S | low | P2 | code |

**address** (`pages/address/address.wxml`): already good. It uses a `list` skeleton, a fill empty state and a three-step delete confirm. PM-01 applies at line 14 ("正在更新参与人信息…").

**appeal-mine / report-mine**: already good. They have hairline rows with press states and tagged stages.
| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PM-02b | appeal-mine | subpackageMember/appeal-mine/index.wxml:23 | skeleton | `type="feed-card"` | The content is `.rp-row` text rows, so the shape changes on load | `type="list"` | loading-state | S | low | P1 | code |
| PM-02c | report-mine | subpackageMember/report-mine/index.wxml:9 | skeleton | `type="feed-card"` | same | `type="list"` | loading-state | S | low | P1 | code |
| PM-16 | appeal-mine, report-mine | appeal-mine/index.wxss:3-4 (report-mine same) | row rhythm | `.rp__body` has `gap: var(--cy-space-3)` **and** each `.rp-row` has a 24rpx padding plus a hairline | That is about 72rpx between row texts, against 48rpx in the same-style `.ph-row` list on points-history | Scope the gap so it skips rows: `.rp-row + .rp-row { margin-top: calc(-1 * var(--cy-space-3)); }`, or move rows into one wrapper | spacing | S | low | P2 | inferred (from CSS; not rendered) |

**invoice, invoice-* , about, marketing, addressinfo, coupon-qr, complaint, report-detail, character, tixian, tixianjilu, myinvite, mycanyuinfo, orderinfo, infomation, infomationdetail, analysis, income-detail, badge-3d, stamp-camera**: already acceptable. They use shared empty, error and skeleton components, and every tap has a press state. Nothing worth a small edit stood out on a static read.

## 3. Real bugs noticed (out of scope to fix)

- **B1, `subpackageA/pages/myproject/index.wxml:1`**: the root is always `class="mp-page theme-merchant"` and the stylesheet imports `merchant-light.wxss`. A player creator who taps "管理全部" on their own profile (profile/index.js:2286, which has no `scope`) lands on a light page, which conflicts with rule 4. The page json `backgroundColor` is `#000000`, so a dark flash before a light page is also likely. This may be deliberate (a "creation-domain" exception), so the owner should confirm. Evidence: code. The flash is inferred.
- **B2, `subpackageTools/*`**: the `0` fallbacks in PM-14 and capsule `{{item.daysLeft || 0}} 天` (capsule/index.wxml:64) would display a false "0 天开启" if the server ever omits `daysLeft`. Evidence: code (fallback); inferred (whether the server omits it).
- No other functional bugs were seen on this read. I checked that `cy-fade-in` is defined globally (style/components.wxss:10 imports fade-in.wxss), so the pages that use it without a local import are fine.

## 4. Would need a redesign (not proposing)

- **subpackageMember/free-explore** (11 stacked `cy-btn variant="secondary"`) and **subpackageMember/orient-score** (about 14 buttons): these are walls of equal-weight buttons with no primary action. Fixing that is a structure redesign, not a small edit.
- **subpackageB/pages/im/chat**: 256 lines of wxml and 8 distinct border-radius values. Bringing the radii down to tokens is a de-slop pass across bubbles, cards and pickers, which is bigger than a single edit.
- **subpackageMember/mycanyu vs order cards**: they use different card surfaces (`bg-surface`, no border, versus `bg-card` with a border and shadow). Unifying the surfaces would be a restyle; only the margin is proposed (PM-04).

## 5. Cross-page patterns (one shared fix, many pages)

- **X1. In-flow refresh status row (PM-01, 6 pages, 8 sites):** mycanyu.wxml:16, mylike.wxml:24/39, address.wxml:14, im/list/index.wxml:22, myproject/index.wxml:25/108, gerenziliao.wxml:14. Add one class to `style/components.wxss`, `.cy-refresh-float { position: absolute; left: 50%; transform: translateX(-50%); z-index: 2; animation: cy-fade-in var(--cy-motion-fade-swap) var(--cy-ease-out); }`, give the list container `position: relative`, and swap each page's class. The text strings stay as they are, and the content no longer moves. Effort M (one shared rule plus 8 class swaps). Risk low. P1.
- **X2. Skeleton shape mismatch (PM-02, 3 pages):** `feed-card` used for text-row lists. The fix is one attribute per page.
- **X3. Hand-written stagger ladders:** points-history.wxss:18-26, growthcenter.wxss:58-67 and stamp-album.wxss:53+ each copy 5 to 9 `nth-child` delay lines, while order-history does the same thing in one rule with `--pmi` and `min(var(--pmi),8)*30ms`. Adding one `.cy-pm-row` rule (using `--pmi`, `--pm-l`, `--pm-l-d` and `--pm-l-s`) next to `.cy-fade-in` in the globally imported `style/fade-in.wxss` would make PM-03 a two-attribute change. It would also let mylike, appeal-mine, report-mine, invoice and address gain the same list entrance with no new CSS. Existing ladders can stay as they are. Effort M. P2.
- **X4. "换个筛选试试" helper sub (PM-11, 3 sites):** order-history:25, points-history:14 and mycanyu:15. Drop `sub` on all three (rule 3).
- **X5. `|| 0` fallbacks on real-world quantities (PM-07, PM-08, PM-14, B2):** a tiny shared wxs, e.g. `utils/wxs/num.wxs` with `n(v) → v == null ? '—' : v`, would replace `|| 0` wherever the number is a fact about the world rather than a marketing counter.
- **X6. Uncapped `index * 60` stagger (PM-13, 3 tools pages):** use the profile's cap pattern.

## Assumptions

- I treated `subpackageMember/components/cy/profile` (the profile hub) as in scope because it lives in subpackageMember, but kept findings there minimal in case another lane also covers it.
- "Wallet" in the approved v13 list means 票夹 (signup), following commit 9bc8af56. Earnings is not part of v13, so its timing is open to tuning.
- Merchant-only pages in these folders (coupon/coupon, couponInfo, mytemplate merchant mode, tixianjilu merchant mode) are outside the player audience and were skipped.
- I used `duration: 600` in PM-05 to match the existing `countIn`. The owner may prefer the merchant's 1300ms.

STATUS: COMPLETE
