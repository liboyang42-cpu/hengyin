# Player-theme lane: polish audit (2026-11-02)

Lane: player-theme. Audience: the player. Scope: `chengyinhub-xcx/pages/{topic,play,activity,templatedetail,square,search2,searchmap,team}`.
Base: this brief's branch (master a1d30fb). This is static code reading only; nothing was rendered.

Evidence: `code` means the line was read directly and the behaviour follows from it. `inferred` means the visual result is reasoned from the code but was not seen.

Assumptions:
- Merchant-only pages under `pages/topic` are out of this lane. That covers `merchantapply`, `merchantinfo`, `pricing`, `components/project-host`, `components/project-join` (used only by merchantinfo) and the `cy/*-form` components.
- These v13 screens are approved and are not re-opened: theme cover/detail three tabs, buy sheet, checkout, pass reveal, ticket wallet, completion medal. Only timing fixes on existing animations are proposed there.
- Where `utils/team-event-command.js` behaviour looks deliberate (durable-intent recovery contract), the finding is framed as an owner call.

## 1. Summary (top 5 by player impact)
1. **Team join looks like a failure screen** (T1). Every normal `接受邀请并加入` shows the recovery card `原请求结果待确认；输入已保留，请查询或按原请求重试` while in flight. It stays as `确认结果` after success. The same gate is used on team/detail, official-detail and official-organizer.
2. **Double-tap pushes two pages** at the most important hand-offs (P-T5): session confirm → baoming, and pass reveal `开始游戏` / `放进票夹` → play / wallet. There is no `_navigating` guard, although the rest of topic/index uses one.
3. **Press feedback is late on theme detail** (P-T1, P-T2). The v13 tab-stagger `transition-delay` (up to .35s) and the chapter-card inline delays (300–620ms) stay on after the entrance. Pressing `查看场次`, prep rows or chapter cards responds 0.3–0.6s late.
4. **Lists replay their entrance on every keystroke or page** (A2, S3). activity/list calls `pageMotion.enter` on each search character. search2/result runs two entrance systems on the same cards and replays them on `加载更多内容`.
5. **Loading shape jumps on the purchase path** (P-T7, A6). The topic skeleton is a short padded detail block, but the page is a 100vh cover. The baoming skeleton has no pay bar, so `立即支付` slides in after load.

Close sixth: on the play page, finishing a station flips the pack tile in one frame (PL2), two sheets cut on close (PL3), and the role card overshoots (PL1).

## 2. Per page (by how often players hit it)

### pages/topic/index (theme cover/detail, buy sheet, checkout, reveal)
Already good: always `theme-dark`; 30/30 view taps have hover-class; favourite has a busy guard plus rollback; the buy sheet uses `--cy-motion-sheet-in/out` with no bounce; pay is guarded by `_selfPlaySubmitting`; cancel stays on checkout with `已取消支付`; reduced motion is wired.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| P-T1 | topic | pages/topic/index/index.wxss:2153-2158 | tab-content direct children (`查看场次` row, prep rows) | `.topic .tabcont > view` keeps `transition .46s` plus nth-child delays up to `.35s` after the entrance | Higher specificity than `.cy-pressed`, so the press scale/opacity starts up to 0.35s late and eases over 0.46s | Add `.topic .tabcont > .cy-pressed{transition-duration:var(--cy-motion-press);transition-delay:0s}` | tap-feedback | S | low | P1 | code |
| P-T2 | topic | pages/topic/index/index.wxml:372 | chapter cards `.story-cc` | Inline `transition-delay: 0s, {{300+index*80}}ms, …` is permanent | Press feedback on chapter cards starts 300–620ms late | `.topic .story-cc.cy-pressed{transition-delay:0s !important}` | tap-feedback | S | low | P1 | code |
| P-T3 | topic | pages/topic/index/index.wxss:1800 (+1896 reduced list) | `.hero-desc` intro line | Not in the `slide2--enter` stagger (by/title/acts/statbar) | On `查看详情与报名` everything rises except the intro, which sits still: a hole in the v13 order | Add `.slide2--enter .hero-desc{animation-delay:.09s}` (and to the reduced list) | motion-entry | S | low | P1 | code |
| P-T4 | topic | pages/topic/index/index.wxml:511 | buy-sheet dim `.bdim` | `hover-class="cy-pressed"` | Pressing the scrim scales the full-screen dim to .98, so the page edges flash before it closes | `hover-class="none"` | tap-feedback | S | low | P1 | code |
| P-T5 | topic | pages/topic/index/index.js:1257-1258, :877, :880; pages/topic/components/cy/pass-reveal/index.js:55 | session confirm → baoming; reveal `开始游戏` / `放进票夹` | `wx.navigateTo` with no `_navigating` guard | A double tap pushes two baoming or play pages | Wrap in the file's existing `_navigating` pattern | tap-feedback | S | low | P1 | code |
| P-T6 | topic | pages/topic/index/index.js:860 | pass reveal exit | `reveal.show:false, selfPlayCheckoutShow:false` set before `navigateTo` | The theme page flashes between the reveal and the next page | Move that setData into the `navigateTo` `success` callback | motion-transition | S | low | P1 | inferred |
| P-T7 | topic | pages/topic/index/index.wxml:38-39, :63 | first-load skeleton | `cy-skeleton type="detail"` padded below the nav | The loaded page is a 100vh full-bleed cover with a bottom title, so the shape jumps | Drop the skeleton padding-top and add `cy-fade-in` to `.slide1` | loading-state | M | low | P1 | code/inferred |
| P-T8 | topic | pages/topic/index/index.js:571, :801 | buy sheet → session picker / checkout | `selfPlayConfirmShow:false` removes sheet and dim in one frame | Skips the 250ms sheet exit; the dim blinks as the next layer comes in | Reuse the `onSelfPlayCancel` pattern (:1515-1517): `buyIn:false` now, unmount after `_later(250)` | motion-transition | S | low | P2 | code |
| P-T9 | topic | pages/topic/index/index.wxss:2128 | ticket-type underline `.bseg__u` in the buy sheet | `transform .4s cubic-bezier(.34,1.32,.64,1)` | Overshoot inside a popup (rule 6); `.4s` is not a token | `transition: transform var(--cy-motion-standard) var(--cy-ease-standard)` | motion-feedback | S | low | P2 | code |
| P-T10 | topic | pages/topic/index/index.js:430, :1073 | cover↔detail `pageScrollTo` | `duration: 500` in the reduced-motion branch and on the way back | Reduced-motion users get a 500ms scroll while full motion cuts | `:430 → 0`; `:1073 → reducedMotion ? 0 : 350` | motion-transition | S | low | P2 | code |
| P-T11 | topic | pages/topic/index/index.wxml:331 | `评价` score | `{{ info.averageRating \|\| 0 }}` | Fake 0 when there is no average | `\|\| '—'` | empty-state | S | low | P2 | code |
| P-T12 | topic | pages/topic/index/index.wxml:217 | node card duration | `duration \|\| '--'` then `分钟` | Renders `--分钟` | Show `—` without the unit when missing | empty-state | S | low | P2 | code |
| P-T13 | topic | pages/topic/index/index.wxss:2005 | `.topic-teams__card` | `35rpx` radius | Sibling dark cards are all 27rpx (:2014, :2018, :2050, :2053, :2061) | 35rpx → 27rpx (needs owner nod: v13 screen) | radius | S | low | P2 | code |

### pages/play (play page and mechanics)
Already good:
- Tap feedback is complete: every multi-line tap view in `index.wxml` has `hover-class="cy-pressed"`; the remaining taps are `cy-btn` or `noop` catchers.
- The node and journal sheets exit through `_outThen` using sheet tokens (index.wxss:195-203).
- There is a root reduced-motion kill-switch (index.wxss:44-55), and the scene canvas is gated.
- Loading uses a skeleton.
- Components are well built: `roadmap` is all tokens; the `play-stage` shake and the `play-verdict` fill → tick → text are deliberate; `play-countin` is good; all 46 playkit components with motion handle reduced motion.
- The medal screen and finish medals are approved (E) and were not touched.
- `play/merchant` has 17/17 hover.
- `pageMotion` and `count.wxs` are unused here because the page has its own motion system.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PL1 | play | pages/play/index.wxss:1111 (card), :1103 (mask); wxml:1655, :1669 | role card `确认身份` / `接下身份，出发` | `rolecard-in .45s cubic-bezier(.2,.9,.3,1.15)`; the scrim appears instantly | Overshoot on a popup (rule 6); the scrim pops while the card flips | `var(--cy-motion-slow) var(--cy-ease-out)`; add a fade on `.rolemask` | motion-entry | S | low | P1 | code |
| PL2 | play | pages/play/index.wxss:892-893 (states), :852-854 (transitions) | pack tile done / unlocked | `.is-done` grayscale+dim and the locked blur apply in one frame; transitions cover only transform/opacity | Completing a station, the main "task complete" change on the pack, flips instantly | Add `filter var(--cy-motion-standard) var(--cy-ease-out)` to the `.fx-tile.is-dealt` transition and an opacity transition on `.fx-tile__im` | motion-feedback | S | low | P1 | code |
| PL3 | play | pages/play/index.wxml:1689-1690, :1849-1850; js:2907, :3438 | game-module sheet `关闭本局线索`, board `关闭同行者榜` | Rise with `sheetUp`, but `closeGameModule` / `closeBoard` set show=false directly | Manual close cuts in one frame, unlike the other sheets on the page | Route both through the existing `_outThen` with `is-out` classes | motion-transition | S | low | P1 | code |
| PL4 | play | pages/play/index.wxss:323; wxml:1437 | first-load skeleton | `.play-loading{align-items:center}` around `cy-skeleton type="detail"` | Skeleton is vertically centred but content starts at the top, so the shape jumps | `align-items:flex-start` plus padding-top from `chrome.contentTop` | loading-state | S | low | P1 | code/inferred |
| PL5 | play | pages/play/index.wxss:586; wxml:1508 | per-station award `徽章` / `Woo!` | `.woo` has no animation | The reward moment hard-cuts in and out while confetti fires (this is not the approved medal screen) | `fade var(--cy-motion-standard) var(--cy-ease-out)` on `.woo`; `floatUp var(--cy-motion-slow)` on `.woo__hero` | motion-entry | S | med (owner confirm) | P1 | code |
| PL6 | play | pages/play/index.js:2941, :3011; wxml:1712-1713 | game-module result `选择已记录` / `任务已提交` | `cyToast.success` plus the same message as an in-place `.gmod__write` line, inserted without motion | Double feedback; the toast covers the line, and the line shifts the layout | Drop the two toasts while the sheet is open; give `.gmod__write` the existing status-in entrance | motion-feedback | S | low | P2 | code |
| PL7 | play | pages/play/index.wxss:711; wxml:1292, :1299; js:8065 | finish milestone rail | `transition:width` but mounts already at final width | The progress fill never animates (dead transition) | Mount at 0 and set progress on the next tick, or a scaleX keyframe | motion-feedback | S | low | P2 | code |
| PL8 | play | pages/play/index.wxml:1262, :1265, :1291 | finish stats `点亮坐标` / `解谜分` / `探索值 +N` | Plain values | Numbers land without counting; the kit's `countIn` + `count.wxs` exist | `pageMotion.countIn` after the finale closes; render via `cnt.f` | motion-feedback | S | low | P2 | code |
| PL9 | play | pages/play/components/advanced-game/index.wxss:5, :8 | advanced-game sheet | `.ag-mask` / `.ag` have no animation | Appears instantly while every other sheet rises | `sheetUp var(--cy-motion-sheet-full-in) var(--cy-ease-standard)` plus a mask fade, with a reduced off-switch | motion-entry | S | low | P2 | code |
| PL10 | play | pages/play/components/advanced-game/index.wxml:7, :61 | `正在准备玩法…` | A single text line, then cards and a footer | Body changes shape after loading | `<cy-skeleton type="list" count="2" />` | loading-state | S | low | P2 | code |
| PL11 | play | pages/play/index.wxml:181-184 | tools rows `本局用时` / `当前得分` | One merged row while loading, two rows when ready | The row count changes | Render both rows while loading, with the value `…` | loading-state | S | low | P2 | code |
| PL12 | play | pages/play/components/play-stage/index.wxss:118-124 | `还能错` dots | `--used` swaps the background instantly | Losing a life has no in-place cue beyond the shake | `transition: background-color var(--cy-motion-fast) var(--cy-ease-out)` | motion-feedback | S | low | P2 | code |
| PL13 | play | pages/play/index.wxml:539-543 | progress ring `{{doneCount}}/{{total}}` `已完成` | Arc image src swaps; the number jumps | The progress update has no transition | One-shot `is-bump` opacity/scale pulse when `doneCount` rises (no overshoot) | motion-feedback | S-M | low | P2 | code/inferred |
| PL14 | play | pages/play/index.js:6607-6608; wxml:998 | `前往下一站` | `continueNext` sets `'sheet.show': false` directly | Skips the exit that ✕ and the mask get | Call `_outThen('sheet')` first | motion-transition | S | low | P2 | code |
| PL15 | play | pages/play/index.wxss:274 | full-screen layer `.full` | `animation: fade .3s ease` | Literal timing | `fade var(--cy-motion-standard) var(--cy-ease-out)` | motion-transition | S | low | P2 | code |
| PL16 | play | pages/play/index.wxss:564, :570, :1747, :1769, :1777, :1784, :1820, :1845 | pills (`继续探索`, `去发现`…) | `border-radius:999rpx` ×8 | Raw copy of the pill token | `var(--cy-radius-pill)` (no visual change) | radius | S | none | P2 | code |
| PL17 | play | pages/play/index.wxss:610…1054 (23rpx ×8), 717…1899 (31rpx ×7) | game / option / finish cards | Raw 23rpx / 31rpx | 1rpx off `--cy-radius-md` / `-lg`, but marked `ds-ok 用户裁决字面值` | Swap only if the owner agrees | radius | S | med | P2 | code |

### pages/activity/baoming (registration / checkout, v13 B2 layout)
Already good: double-submit guard (`订单提交中，请勿重复点击` plus a disabled button); full pay feedback (`确认支付结果` → `支付成功`, `您已取消支付`, fail sheet `这笔没有付成功` / `未扣款，可重试` with in-place `重新提交`); `—` fallbacks.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A6 | baoming | pages/activity/baoming/baoming.wxml:59 vs :225 | loading skeleton | 3 card skeletons and no `.bmbottom` | The fixed pay bar appears after load, so the page shape jumps | Render `.bmbottom-spacer` / `.bmbottom` during `pageState==='loading'` with a disabled cy-btn | loading-state | S | low | P1 | code |
| A7 | baoming | pages/activity/baoming/baoming.js:1595, :1630; wxml:240 | submit loading | Full-screen `cyLoading.show('提交中...')` while the button also spins with `提交中...` | Two indicators with the same text, then a third (result sheet) | Drop the two `cyLoading.show` calls; `isPaying` already blocks taps | motion-feedback | S | med (the mask also blocks back taps) | P2 | code |
| A8 | baoming | pages/activity/baoming/baoming.wxml:120 | ticket row | `<text>（点击切换）</text>` next to the arrow icon | Helper caption (rule 3); the arrow already signals tap | Delete that text | structure | S | low | P2 | code |
| A22 | baoming | pages/activity/baoming/baoming.wxss:407 | `.radio` | `border-radius: 30rpx` on a 28rpx box | Raw value meaning a circle | `50%` | radius | S | none | P2 | code |

### pages/activity/list
Already good: sticky search/tabs; skeleton → error → empty; `—参与` fallback; staggered `pageMotion` entrance; all taps have hover.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A2 | activity/list | pages/activity/list/index.js:304 → :339 | `pageMotion.enter` in `applyFilter` | Runs on every `cy-search` keystroke and tab change | Every card hides and rises again on each typed character | Call `enter` only on load and tab change, not for `extra.keyword` | motion-transition | S | low | P1 | code |

### pages/activity/official-* (official events; player-facing except organizer)
Already good: facts strip uses `—`; honest `未配置奖励`; per-card busy `正在提交，请稍候` with inline retry (inbox); refresh keeps content (mine); organizer is light, tokenised and confirms danger actions.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A5 | official-detail | pages/activity/official-detail/index.wxml:1; js:170, :179; wxss:1 | page theme | `theme-merchant` plus `merchantPageShow()` force light for everyone | A player signup page is light, which breaks rule 4 | Switch to `theme-dark` and drop `merchantPageShow` (owner decision; check hero scrims) | structure | M | visual QA | P1 | code |
| A9 | official-detail | pages/activity/official-detail/index.wxml:180-186 | `.od-reward-tip` | `完成活动任务后按上方配置发放` / `实际发放结果以活动结算为准` | Two helper captions (rule 3) | Remove, or keep one line if it is legal copy (owner call) | structure | S | low | P2 | code |
| A10 | official-detail | pages/activity/official-detail/index.wxml:169, :172 | collective progress | `'--'` fallback (`--%`); `{{current}} / {{threshold}}` with no fallback | Wrong dash; null renders ` / ` | Use `—`; guard `current != null` | empty-state | S | low | P2 | code |
| A18 | official-mine | pages/activity/official-mine/index.wxml:61 | `安全恢复原投递` | Bare `<button>` with no class or hover | Native light-grey button on a dark card | `<cy-btn size="sm" variant="secondary">` | tap-feedback | S | low | P1 | code (look inferred) |

### pages/templatedetail (+ shelf, topic-detail, topic-preview)
Already good: theme follows identity (player dark); hover coverage 9/9, 15/15, 2/2, 3/3; `_navigating` and `_using` guards; shelf has `cy-rise-in`, reduced motion and an honest `封面暂不可用`.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| D1 | topic-preview | pages/templatedetail/topic-preview/index.wxml:37, :39, :48 | title / ✓ list / `查看详情` | Delays .45s, .6+.12i s, 1.05s (+220ms rise) | Main button invisible for about 1.3s; the owner asked for "a little faster" | Delays .25 / .35+.08i / .6 | motion-entry | S | low | P1 | code |
| D2 | templatedetail | pages/templatedetail/templatedetail.wxml:26; topic-detail/index.wxml:22 | content after skeleton | Hard cut | No mount fade, unlike sibling pages | Add `cy-fade-in` to the scroll-view | motion-entry | S | low | P2 | code |
| D3 | templatedetail | pages/templatedetail/templatedetail.js:229-234 | hero recede | `applyHeroRecede` runs in onReady while `loading:true`, before `#xbScroll` exists | The recede never attaches (dead motion) | Call it from the `loading:false` setData callback, once | motion-transition | S | med (owner picks the minimum opacity) | P2 | code/inferred |
| D4 | templatedetail | pages/templatedetail/templatedetail.wxml:227-233 | `评价` stars | 5 static outline stars whatever the rating | Stars do not match the number | Fill by rating (as topic does with `ratingStars`), or drop the row | structure | S | low | P2 | code |
| D5 | shelf | pages/templatedetail/shelf/index.wxss:66 | page dots `.sh-dot` | Active dot changes width 12→32rpx, but only `background .25s` transitions | The pill snaps its width | Transition width too, with `--cy-motion-fast` | motion-feedback | S | low | P2 | code |
| D6 | shelf | pages/templatedetail/shelf/index.wxml:17, :98 | loading | `cy-skeleton type="list" count="3"` | Loaded page is a hero-card rail, so the shape changes | `type="card" count="1"` | loading-state | S | low | P2 | inferred |

### pages/square/detail (post detail)
Already good: comment send feedback sits on the input; like/favorite have in-flight guards; heart pop is skipped under reduced motion; radii are tokens.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| S14 | square/detail | pages/square/detail/index.wxml:41, :161, :165, :168, :172 | `关注`, comment like, `回复`, `举报`, `删除` | `hover-class="none"` | No press feedback; comment like icon stays `heart` until the reply | `hover-class="cy-pressed" hover-stay-time="80"`; at :162 bind `heart-filled` when `isLiked==1` | tap-feedback | S | low | P1 | code |
| S15 | square/detail | pages/square/detail/index.wxml:14 | error state | `cy-error … auto-back` plus a separate `返回上一页` ghost button | Redundant second exit | Delete line 14 | structure | S | low | P2 | code |
| S16 | square/detail | pages/square/detail/index.wxss:538, :602, :608, :609 | head/actions gaps | Raw `4rpx`, `4rpx`, `12rpx`, `36rpx` | Off-scale gaps | Map to `--cy-space-*` (36 → space-4) | spacing | S | low | P2 | code |
| S17 | square popover | pages/square/components/cy/popover/index.wxml:7 | avatar menu panel | `wx:if` mount with no transition | Pops in and out instantly, unlike other overlays | `animation: cy-fade-in var(--cy-motion-fast) var(--cy-ease-out)`, opacity only | motion-feedback | S | low | P2 | code |

### pages/search2 (index + result)
Already good: history/recommendation chips; category block hidden when empty; delayed skeleton (400ms); `fill` empty/error states; card fly-in.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| S3 | search2/result | pages/search2/result/index.js:294; wxml:30; wxss:50-58 | result cards | `.cy-fly-in` (350ms) plus `pageMotion.enter` (.32s) on the same nodes | Double rise; replays on every source landing and on `加载更多内容` | Keep `cy-fly-in`; remove the `pageMotion.enter` call and the pm rules | motion-entry | S | low | P1 | code |
| S4 | search2/result | pages/search2/result/index.js:53-55; wxml:25 | while editing the keyword | `onKeywordInput` clears `visibleResults` | Shows `没有找到` before the user confirms | Keep results until `onSearchConfirm` | empty-state | S | low | P1 | code |
| S21 | search2/index | pages/search2/index.wxml:64 | category loading | `cy-skeleton type="card" count="2"` | Two big cards become a chip grid, so the shape changes | `type="list"` | loading-state | S | low | P2 | inferred |

### pages/searchmap
Already good: results pill reachable in every state; shared filter sheet; radii all tokens.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M1 | searchmap | pages/searchmap/index.wxml:91, :108 | no-coordinate notice | ` · N 个活动只在列表显示` and `N 个活动无位置，仅列表显示` | Same fact shown twice | Drop the pill suffix at :91 | structure | S | low | P2 | code |

### pages/team (detail, join)
Already good: fully tokenised; three-step danger confirms; `加入中…` busy label; refresh line reserves space.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| T1 | team/join (+ team/detail:58, official-detail:2, official-organizer:8) | pages/team/join/index.wxml:2; utils/team-event-command.js:15 | command recovery card | Gate `commandRecovery.state \|\| busy`; state reads `UNKNOWN` as soon as the intent is saved, before the reply | A normal join shows `接受邀请入队` / `原请求结果待确认；输入已保留，请查询或按原请求重试`, pushes the page down, and stays with `确认结果` after success | Gate the card on a real unknown (reply failed or timed out), not on in-flight; auto-acknowledge `APPLIED` and show `toast.success`. Owner call: may be a deliberate contract | motion-feedback | M | recovery-contract tests | P1 | code |
| T11 | team/detail, team/join | pages/team/detail/index.wxml:87, :133, :154; join wxml:36 | counts | `totalTasks \|\| 0`, `{{team.joinedCount}} / {{team.maxMembers}}` | Fake 0 or bare ` / ` | `!= null ? … : '—'` | empty-state | S | low | P2 | code |
| T12 | team/detail | pages/team/detail/index.wxml:128, :147 | board/ranking loading | A single `正在加载…` line | Shape changes when the cards arrive | `<cy-skeleton type="list" count="2" />` | loading-state | S | low | P2 | code |
| T13 | team/join | pages/team/join/index.wxml:32, :37, :39 | header stats | A constant stat `邀请制` / `加入方式` next to `当前成员` | Half the stats row is a constant, not data | Drop the `邀请制` cell | structure | S | low | P2 | code |
| T19 | team/detail | pages/team/detail/index.wxml:93 | `邀请队友` share button | `<button open-type="share">` with no hover-class | Native grey wash on a primary button | `hover-class="team-action--pressed"` | tap-feedback | S | low | P2 | inferred |

## 3. Real bugs noticed (out of scope to fix)
- pages/topic/index/index.js:1662: pay failure toasts the raw `res.errMsg` (for example `requestPayment:fail …`) to the player.
- pages/topic/index/index.js:886: the node card opens `templatedetail?…&scope=my`, which calls the creator-only `/api/template/myinfo`. A player likely hits `玩法没能打开` or creator copy such as `去模板广场选用`. Inferred.
- pages/topic/index/index.wxml:538: checkout `loading` is bound only to `selfPlayConfirmLoading`, so `立即支付` shows no busy state while the order is created. The loading mask hides most of this.
- pages/topic/index/index.js:1708: `_retrySelfPlayPayment` is never called (dead code).
- pages/templatedetail/shelf/index.wxss:36: `transition: background .6s` on a radial-gradient cannot interpolate, so it snaps.
- app.json:339: `"darkmode": true` with no `themeLocation`. Native dialogs may follow the system mode. Inferred.
- pages/activity/official-inbox/index.wxml:23, :24, :28: `hover-class="{{… ? true : false ? 'none' : 'cy-pressed'}}"`. Operator precedence yields the literal `true` when busy.
- pages/searchmap/index.wxml:50: `hover-class` on a native `<map>`.
- pages/searchmap/index.wxml:215: the `最热` sort is still offered, although search2 removed it because the backend has no hot sort. Inferred.
- pages/activity/baoming/baoming.wxss:455-523: dead `.paysuc__*` CSS (the comment at :430 says it was removed).
- pages/square/detail/index.wxss: dead selectors `.row1`–`.row4` and `.sport-block`, which are not in the wxml.
- pages/activity/official-detail/index.wxss:13-24: `.od-refresh-state` is `position:fixed; top:16rpx`, which lands in the status bar. Inferred.

- pages/play/index.wxml:1521: the root `.play` closes here, but the role card (:1655/:1669), the game-module sheet (:1688-1690) and the board sheet (:1848-1850) sit after it. They miss the `.play--reduced-motion` kill-switch, so they still animate with motion reduced. They also miss the root `--cy-psheet-max-h` (notch-overlap risk per the wxss:198 comment; inferred). Fix with a class and style on those nodes. Do not move them, because `.play--scaled` transforms the root.
- pages/play/index.js:5436: `flashLock()` has no callers, so `这扇门还没向你敞开。` (wxml:960) never shows; toasts are used instead.
- pages/play/circle/index.json: `"backgroundColor":"#F3F4F4"` / `"navigationBarTextStyle":"black"` on a dark page, so the overscroll shows light grey.
- pages/play/index.js:6501-6502, :6592: `wx.vibrateShort` ignores `reducedMotion`, unlike :1767 and :6975.
- pages/play/index.js:6173: on a correct answer the backend `r.msg` is toasted, although the comment says a correct answer gets confetti only. If it is a generic `操作成功`, it shows on every answer (inferred; not reworded).

## 4. Would need a redesign, not proposing
- The pass reveal shows its buttons only at 6.3s, with no skip (pass-reveal/index.js:47). This is approved B3; shortening it is the owner's call.
- There is no player-facing node detail. templatedetail is a creator page.
- topic/index.wxss has 27 radius and 142 spacing values, mostly `ds-ok v13 原型定值`. Tokenising them would restyle approved screens.
- official-detail dark conversion, if more than a class swap is needed (A5).
- A proper in-place busy and success state for TEAM/SIGNUP/EVENT commands, beyond T1's gate.
- play: a real draw-on progress ring (the arc is an SVG-data `<image>`); PL13 is the cheap substitute.
- play: unifying the per-station `.woo` with the approved medal screen.
- play: a staged reveal for the chapter-finish page (`.finsheet`, owner redesign 2026-10-28). Not reopened.
- play: restructuring the 1883-line wxml, whose overlays sit both inside and outside the root.
- searchmap: the selected-marker sheet (`variant="full"` for one item) compared with v13 D5's grow-from-marker card.

## 5. Cross-page patterns
- **Late press feedback under entrance transitions.** One rule, `.cy-pressed{transition-delay:0s}` with the press duration and enough specificity, fixes P-T1 and P-T2 and any staggered list that keeps its delays.
- **`pageMotion.enter` only on first load or tab change**, never on input or pagination. This fixes A2 and S3.
- **Navigation guard.** Apply the existing `_navigating` helper to every `navigateTo` on the buy → play hand-off (P-T5).
- **`—` for null counts.** One small WXS helper (or an addition to `utils/wxs/count.wxs`) fixes P-T11, P-T12, A10 and T11.
- **Skeleton matching the final shape.** Choose the skeleton `type` and keep fixed bars mounted (P-T7, A6, D6, S21, T12).
- **One recovery-card gate** in `team-event-command` consumers fixes 4 pages (T1).
- **Close through the exit, not `show:false`.** On play, route every sheet close through `_outThen` (PL3, PL14); topic's buy sheet should use the same `buyIn` → delayed unmount (P-T8).
- **No overshoot on popups.** Three easings to swap: role card (PL1), buy-sheet underline (P-T9), and the `sc-rank` score overlay at `pages/play/components/play-overlays.wxss:66` (P2 candidate).
- **Pressed styles differ.** `.cy-pressed` is .88/.98 over 80ms, `.team-action--pressed` is .72 with no transition, and `.om-card--pressed` is .78. Converging on `cy-pressed` is a token-only change.

Numbers (taps / hover-class per wxml):
- topic/index 36/29
- play/index 204/169
- baoming 17/14
- templatedetail 12/10
- shelf 15/15
- square/detail 14/12 (5 of those 12 are `none`)
- search2 14/10 (results 7/3)
- searchmap 21/15
- team/detail 9/5
- team/join 6/2

Most of the gaps are cy-btn or components with their own feedback. `pageMotion.enter` callers in scope: activity/list and search2/result only. topic and play have their own motion.

Play numbers:
- Across all `pages/play` wxml: 435 taps / 378 hover. The rest are cy-btn, noop catchers, or circle `<button>`s.
- index.wxss: 29 distinct radius values (88 of 162 via var).
- 49 distinct raw spacing values (254 of 483 raw).
- 69 of 78 motion declarations use literal durations. Most are prototype values marked `ds-ok`, so tokenising them is not proposed beyond PL15 and PL16.

STATUS: COMPLETE
