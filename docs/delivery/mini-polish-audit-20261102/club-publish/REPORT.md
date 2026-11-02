# Club / publish polish audit (lane: club-publish)

Base: `claude/mini-polish-audit-20261102` (master a1d30fb). Scope: `chengyinhub-xcx/pages/club/**`, `pages/publish/**`, `pages/crop`.
Method: static reading of WXML / WXSS / JS only. Nothing was rendered. Evidence is `code` (read directly) or `inferred` (reasoned from code, not observed).
All paths below are relative to `chengyinhub-xcx/`.

Assumptions:
- Order of use is my estimate: club/detail (every member, every visit) > publish/fabu and publish/temp (creators) > club management sub-pages > crop > the rest.
- Merchant viewers of club/detail and crop correctly get `theme-merchant` (owner rule 4); I did not treat that as a finding.
- "Small edit" means one class, one guard or one transition rule in an existing file. Anything bigger is under "Would need a redesign".

## 1. Summary (top 5 by user impact)

1. **club/detail: the whole post feed blinks out and fades back after every like, comment, edit, pin or delete.** `loadPosts()` calls `pageMotion.enter(that,'pmPosts')` every time, and every write calls `loadPosts()`. Run the entrance on first load only. (CP-01, P0)
2. **club/detail: owners/admins coming back to the page see the Manage tab drop to skeletons and the Events tab go blank.** `onShow → loadAll` resets `topicState`, `clubStatsState`, `membersState`, `messageState` to `'loading'` and clears the data. Keep the old snapshot while refreshing, as join-requests already does. (CP-02, CP-03, P1)
3. **publish/temp: tapping "移除" on the 剧情故事 module wipes every story beat and image with no confirm.** 玩法 and 完成奖励 go through `confirmRemoveModule`, but story is wired straight to `removeModule`. (CP-10, P1)
4. **publish/temp: while saving, the 查看预览 button also spins.** Two spinners make it look as if preview is loading. (CP-11, P2)
5. **Refresh banners push lists down** (join-requests, enroll, badges): a `wx:if` "正在更新…" line is added above the list while it reloads, which changes the page shape. (CP-05, cross-page pattern X-2)

Already good: tap feedback is close to complete in this lane (nearly every tap target has `hover-class` or is a `cy-btn` / native `button`). Radii and spacing are almost all tokens. fabu already has a 5-second undo for node removal and a save-state chip.

## 2. Per page

### pages/club/detail (highest traffic)
Already good: staged profile `cy-rise-in`, a per-tab rise on tab switch, post stagger, the fold-panel keyframe with a reduced-motion crossfade, the join float button with a busy → done check, full error/empty/skeleton coverage, and `toast.undo` on member removal and unpin.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| CP-01 | club/detail | pages/club/detail/index.js:1769 (also :1752; callers :1728 :1833 :1954 :2007 :2811 :3152 :3268) | post feed `.club-post-anchor` | every `loadPosts()` runs `pageMotion.enter('pmPosts')`, which sets `--pm-p:1` (opacity 0, 31rpx drop) and then staggers back in | liking a post, sending a comment, deleting, pinning or editing makes the whole feed vanish for ~34ms and fade back over ~0.3s. The open comment panel sits inside the anchor, so it blinks too | call `pageMotion.enter` only when `!that.data.postsLoaded` (first arrival), the same rule `countIn` already documents ("刷新不再重数") | motion-feedback | S | low | P0 | code |
| CP-02 | club/detail | pages/club/detail/index.js:774-775, 1220, 1254, 1356, 2241 | Manage tab: stats grid, project list, members, messages | `onShow` → `loadAll()` for owners/admins; each loader sets its state to `'loading'` and clears its data | every return from a sub-page (roles, enroll, settlement…) swaps the 2×2 stats grid and project list for `cy-skeleton type="list"` and back. The page shape changes while loading (owner rule 6) | in each loader, set `'loading'` and clear only when there is no snapshot yet; otherwise keep the rows and replace them on success (the `hadSnapshot` pattern in `pages/club/join-requests/index.js:87`) | loading-state | M | med (stale-data races, already epoch-guarded) | P1 | code |
| CP-03 | club/detail | pages/club/detail/index.wxml:197-211; index.js:2151 | Events tab list | `loadTopics` sets `topicsLoaded:false`; the tab renders only `wx:if="{{topicState !== 'error' && topicsLoaded}}"` and has no skeleton branch | first load shows an empty tab under the tabs; on every owner return the event cards vanish and pop back | add `<cy-skeleton wx:if="{{topicState === 'loading' && !upcomingTopics.length}}" type="list" count="2" />` before the block and keep `topicsLoaded` true on refresh (same fix as CP-02) | loading-state | S | low | P1 | code |
| CP-04 | club/detail | pages/club/detail/index.wxml:450 | `.manage-project-meta` | `{{… != null ? item.signupCount + ' 人' : '—'}}报名` | no-data renders "—报名", with the dash glued to the word | move "报名" inside the true branch: `item.signupCount + ' 人报名'` : `'—'` | empty-state | S | low | P2 | code |
| CP-04b | club/detail | pages/club/detail/index.wxml:430, 333 | `.club-stats-note` "仅统计本俱乐部自办项目。", `.ov-lb-note` "每周一重置" | helper caption lines | rule 3 (no helper captions). Both were added on purpose (CU-C-51), so this is listed for the owner to decide, not proposed as a change | owner decision: keep, or fold into the section title | structure | S | low | P2 | code |

### pages/publish/fabu (creator editor)
Already good: a confirm plus a 5-second "撤销" undo for node removal, honest copy on chapter delete ("删除后不可恢复。"), a drag lift on reorder, the `pd-save-state` chip (保存中 / 已存云端 …), validation scroll-to-anchor, and `cy-btn` loading on the draft CTA.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| CP-06 | publish/fabu | pages/publish/fabu/index.wxss:1345, 1368 | `.slopes-node-row` drag lift | `is-dragging` adds `scale(1.02)` and a shadow; `.slopes-node-row` has no transition | the lift and the drop snap in one frame, so the row looks like it jumps when grabbed and released | add `transition: transform var(--cy-motion-press) var(--cy-ease-out), box-shadow var(--cy-motion-press) var(--cy-ease-out);` to `.slopes-node-row`, with the reduced-motion override | motion-feedback | S | low | P2 | code |
| CP-07 | publish/fabu | pages/publish/fabu/index.wxml:686; index.wxss:3053 | `.pending-undo` bar "撤销" | `wx:if`, fixed bar, no entrance | the undo bar appears in one frame right after the confirm modal closes, so it is easy to miss within its 5 s window | add the existing `cy-rise-in` class to `.pending-undo` (global, reduced-motion aware) | motion-entry | S | low | P2 | code |
| CP-08 | publish/fabu | pages/publish/fabu/index.wxml:99; index.wxss:4869-4884 | `pd-save-state` chip | background and colour switch between `--saved` / `--unsaved` with no transition | autosave flips the chip colour on every save cycle, which reads as flicker | add `transition: background-color var(--cy-motion-fast) var(--cy-ease-out), color var(--cy-motion-fast) var(--cy-ease-out);` to `.pd-save-state` | motion-feedback | S | low | P2 | code / inferred (flicker) |

### pages/publish/temp (game/template editor)
Already good: guarded add/remove with toasts at the min/max limits ("至少需要 2 个选项", "排序至少 2 条", "连线最多 6 对"), a confirm on removing a filled 玩法 / 完成奖励, `toast.success('玩法已保存')`, an inline submit error with retry, and `scrollToError`.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| CP-10 | publish/temp | pages/publish/temp/index.wxml:1312; temp/r5-editor.js:263-266 | 剧情故事 module "移除" (`.cg-mod-del`) | `bindtap="removeModule"`: clears `storyBeats`, `storyText`, `storyImg` immediately | the only content module that drops filled data with no confirm and no undo. The sibling modules use `confirmRemoveModule` | bind it to `confirmRemoveModule` and add `key === 'story' ? storyBeats.some(b => b.text \|\| (b.imgs && b.imgs.length))` to its `filled` check (copy: content "剧情节点和配图会一起清空") | tap-feedback | S | low | P1 | code |
| CP-11 | publish/temp | pages/publish/temp/index.wxml:1392 | 查看预览 `cy-btn` | `loading="{{submitting}}"` | saving a draft spins 保存草稿 and 查看预览 at once; publishing spins 查看预览 and 发布 at once. Two spinners suggest preview is loading | drop `loading` on 查看预览 and keep only `disabled`/`aria-disabled` while submitting | loading-state | S | low | P2 | code |
| CP-12 | publish/temp | pages/publish/temp/index.wxml:61 | main `scroll-view.cg-scroll` | `scroll-into-view` without `scroll-with-animation` | a validation failure (`scrollToError`) teleports the form, so the user loses where they were | add `scroll-with-animation` to the scroll-view | motion-transition | S | low | P2 | code |
| CP-13 | publish/temp | pages/publish/temp/index.js:2218-2235 | `addModule` from the add sheet | appends the module at the bottom of a long form and closes the sheet | after closing, nothing shows where the new module went (it is usually off-screen) | after `_setFormState`, call the existing `scrollToError`-style setter with the module's anchor (`fieldStory`, `sec-game`, `sec-reward`); this relies on CP-12 for the smooth scroll | motion-feedback | S | low | P2 | code / inferred (off-screen) |

### Club management sub-pages (join-requests, roles, governance, enroll, badges, notify, settlement, event-ops, customers, group-code)
Already good: the access gate, per-row "正在通过… / 正在拒绝…" busy text, inline retry errors, `toast.success` on every write, `hover-class` set to `none` while busy, and token radii (2-3 values per page).

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| CP-05 | join-requests / enroll / badges | pages/club/join-requests/index.wxml:18; pages/club/enroll/index.wxml:17; pages/club/badges/index.wxml:14 | "正在更新入会申请…" / "正在更新报名名册…" / "正在确认勋章列表…" | `wx:if="{{refreshing}}"` line above the list | on every refresh the list shifts down one line and back (owner rule 6) | render it always and toggle `opacity` (or `visibility`) by `refreshing`, so the height stays the same | loading-state | S | low | P1 | code |
| CP-14 | join-requests | pages/club/join-requests/index.js:217 | approved/rejected `.request-card` | removed from `requests` in the same `setData` as the toast | the card disappears in one frame, and the list jumps up under the finger that just tapped | add a `leaving` flag on the row (opacity → 0, 160ms) and filter it out in a `setTimeout` of `--cy-motion-fast` | motion-feedback | S | low | P2 | code |
| CP-15 | settlement | pages/club/settlement/index.wxml:22 | `.settled-amount` "已入账分润" | plain text, no count | the merchant dashboard counts revenue up with slow-fast-slow (`merchant/index` `countIn('rvK')`), while the club owner's money number just appears. `count.wxs` cannot parse "¥…" | split the currency sign into its own `<text>` and run `count.f(num, settledK)` plus `pageMotion.countIn(this,'settledK')` on first `ready` only | motion-entry | S | low | P2 | code (format of `settledAmountText` inferred) |

Pages with nothing worth proposing: roles, governance, notify, event-ops, customers, customer-detail, group-code, checkin-detail, apply, create (step `cy-rise-in` already), topic-story, edit and workbench (deep-link shells).

### pages/club/topic-detail
Already good: a fixed hero, the scrolled nav swap and complete state coverage.

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| CP-16 | club/topic-detail | pages/club/topic-detail/index.wxml:21-24 | skeleton → content swap | no `animation`/`transition` anywhere in `index.wxss` | the only club page in this lane with zero entrance; content hard-cuts from the skeleton | put `cy-fade-in` (fade only, no rise, so the hero geometry is untouched) on the content block below `.ctd-head`. Skip if the owner counts this under the approved v13 "theme detail" screen | motion-entry | S | low | P2 | code |

### pages/crop
Already good: no flash between waiting and ready (commented), the frame stays put, and a clear 取消 / 还原 / 选取 bar with "处理中".

| ID | page | file:line | element | now | problem | smallest edit | type | effort | risk | priority | evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| CP-17 | crop | pages/crop/index.js:306-310; index.wxss:84 | 还原 | sets `tx/ty/scale` back to 0/0/1 in one frame | reset teleports the image, so you cannot see what was undone | add a `settling` class for ~200ms on reset only, with `transition: transform var(--cy-motion-standard) var(--cy-ease-out)` (never during touch moves) | motion-feedback | S | low | P2 | code |
| CP-18 | crop | pages/crop/index.wxml:40-41 | `.crop__img` first appearance | `wx:if="{{ready}}"`, pops in | a small hard cut after "正在准备图片" | add `cy-fade-in` to the image | motion-entry | S | low | P2 | code |

### publish/activity, publish/simple, publish/template-intro, publish components
Already good: `cy-btn` with loading/disabled throughout, and disabled-tap explanations (`bind:disabledtap`). Nothing small to propose.

## 4. Real bugs noticed (out of scope to fix)
- **CP-01 is effectively a bug**: write actions on club/detail visibly hide the feed (see above).
- `pages/publish/topicadd/topicadd.js:1-2`: the redirect shell says "2026-10 之后删除本页并从 app.json 摘除"; it is now 2026-11 and the page is still registered. This is cleanup, not user-visible.
- `pages/publish/temp/index.js:3306, 3372`: `this.data.lastSubmitAction = …` assigns `data` directly. `sendData` later does a proper `setData`, so there is no visible effect today, but the view and logic can disagree in between.
- `pages/crop/index.wxml` 选取 (`crop__action--confirm`, ready state) has no `aria-role`/`aria-label`, unlike 取消 and 还原, and keeps `hover-class` while `busy`.

## 5. Would need a redesign, not proposing
- fabu `.pending-undo` is a page-local copy of what `cyToast.undo` (the approved overlay spec) does. Merging them touches the 5 s snapshot logic across story, material and binding removals; this is not a small edit.
- fabu chapter delete has no undo (`_pushUndo` stack with no visible control, per the CU-C-158 comment). Adding a real undo entry is new UI.
- temp row-level removals (options, sort items, match pairs, clue rows, cost rows… about 40 handlers) are instant with no undo. A shared "removed · 撤销" pattern for the editor is a feature, not polish.
- club/detail Manage tab: a full stale-while-refresh refactor of all six loaders, beyond the per-loader guard in CP-02, if the owner wants it done uniformly.

## 6. Cross-page patterns
- **X-1 Run entrance on first data only.** `pageMotion.enter` should not re-run on refresh (CP-01). A guard inside `utils/page-motion.js` (`enter(page, key, { once: true })`, remembering the key on the page instance) would protect every caller; 21 pages call it. One-line change per caller, or one option in the kit.
- **X-2 Stable shape while refreshing.** The "正在更新…" lines (CP-05) and the loading resets on club/detail (CP-02, CP-03) are the same problem: a refresh changes page height. Rule: show the skeleton only when there is no snapshot; otherwise keep the content and use an opacity-only status.
- **X-3 Removal feedback.** Instant row disappearance (CP-14, temp rows) versus confirm plus undo (fabu nodes, club member). Pick one small exit (160ms fade) to apply wherever a row leaves a list; undo belongs in the redesign section.
- **X-4 State-change transitions.** Class toggles with no transition (CP-06, CP-08, CP-17) are a WXSS one-liner each using the existing `--cy-motion-press/fast/standard` tokens.

STATUS: COMPLETE
