# Lanes (2026-11-09)

The owner's notes are quoted verbatim in 「」. Their words override any proposal. Paths are relative to `chengyinhub-xcx/` unless stated otherwise.

The vision 3 IDs come from the owner's 2026-11-06 phone walkthrough. They do not match the internal V3-xx numbers in old tests; for example, test `r29-v3-41` is the owner's V3-47 here.

Items marked DONE are already fixed on master. Re-check them only if your change touches the same code.

---

## A lanes: walkthrough (base `origin/master` ≥ 2bb811437, PR into master)

### qx-a1: play page

| ID | Owner | Where / lead |
|---|---|---|
| V3-06 | 「底部不应该有在地图中查看路线的说明」 | `pages/play/index.wxml` around 1063–1066 |
| V3-10/53 | 「这种看起来并不是内嵌 还不如这种就做成全屏的 内嵌的样式是不需要底部背景的…现在这种不是嵌入 只是把之前的全屏缩小了」 and 「游戏没办法向上查找之前的剧情游戏 嵌入的样式不对」 | The story-embedded playkit games (coin toss, photo album, etc.). Make the inline form a true inline block in the story column: no backdrop or bottom background, no shrunken full-screen frame, sized to content. Games that need the whole screen (shake, album) open full screen from the inline card instead. |
| V3-11 | 「这种相册还是全屏的吧」 | The album opens full screen. Currently a white card with 收起相册 sits inside the dark page. |
| V3-13/14 | 「左上角的地图有问题…右下角的那个收起按钮箭头应该是向上的 打击之前是向下的 地图里面的样式不对」 | Radar mini-map (`radar__fold` uses `cy-icon name="back"`). Collapse arrow points up when expanded and down when collapsed. Map style must match the dark map style used on roam. |
| V3-15 | 「这种提示句不应该出现在剧情里面…延伸部分有几个小任务应该是弹窗或者什么的」 | System or hint sentences rendered inside the story text. Move extension sub-tasks into a sheet opened from a compact row. The story column shows only story. |
| V3-16 | 「背包没有的样式不对 应该是我们规定的那种背包样式 上面有格 下面可以使用的空态」 | `pages/play/theme-backpack.*` plus `pages/play/utils/theme-backpack.js`. Empty state is a grid of slots on top and an empty usable-items area below; reuse the existing backpack grid. |
| V3-17 | 「左边不应该有返回的按钮 看起来位置不对 并且这个二维码的弹窗不好 因为背景就是黑色的」 | QR ticket sheet in play: remove the left back button. Sheet colour comes from lane a2's token. |
| V3-21 | 「弹窗是黑色的所以有问题 图片就直接展示出来 我看到好像又嵌套了一个灰色的长方形」 | Remove the grey frame around images in play sheets. |

### qx-a2: visual system (global tokens and icons)

| ID | Owner | Where / lead |
|---|---|---|
| V3-18 | 「统一修改纯黑色弹窗 改成稍微透明的灰色？看看revolut怎么做的」 | `style/tokens.wxss` `--cy-comp-sheet-bg` and the dark sheet surfaces: a slightly translucent elevated grey (Revolut dark sheets ≈ #1C1C1E at 92–96% with blur where supported) and a lighter grabber. Change only via tokens, then sweep hard-coded `#000` sheet backgrounds. |
| V3-19 | 「扫码没有icon」 | `style/proto-icons.wxss:48`: `.pic--scan` stroke #1A1A1A is invisible on dark. Use currentColor. |
| V3-20 | 「下面的开关应该是绿色的 我们所有的开关都应该是绿色的」 | `style/proto-sheet.wxss:97` `.dsw.is-on{background:#FFFFFF}`. Make every switch green; grep all switch styles. |
| V3-25 | 「底部的导航 我发现都不是很清晰 这个玩家版和商家版都是一样的 需要你找到合适的矢量图 最好选中之后是填充的」 | `components/tabBar/index.js` and its icons. Draw outline and filled SVG pairs, same family, for player and merchant tabs. Merchant `selectedIconPath` currently equals `iconPath`. |
| V3-30 | 「我觉得我们的icon普遍偏右下角 就是里面没有居中的感觉」 | Audit `cy-icon` and proto-icons glyph boxes. Centre optically by fixing viewBox and padding, not per-use offsets. |
| V3-23/24/32 | 「整体帖文的字体大小偏大 我们整体字体大小要和x或者threads一样 转发按钮的位置不对」 and 「帖文的整体字体…似乎没有固定」 | `components/cy/post-card`, `pages/square`. Body 15pt-equivalent (30rpx), name 15pt semibold, meta 13pt, one font stack. Action row order follows Threads: like, comment, repost, share, aligned under the text column. |

### qx-a3: orders, profile, my projects

| ID | Owner | Where / lead |
|---|---|---|
| V3-02 | 「下面的可玩至和报名与客户是连在一起了」 | `subpackageMember/components/cy/scene-member-order-detail` wxml around 230/294: separate the cards with the standard gap. |
| V3-03 | 「订单这里不需要有开始游戏按钮 开始游戏只从票夹那进去」 | Same file, around 321: remove the play CTA from the order detail. |
| V3-22 | 「我的订单卡片需要设计优化一下 感觉间距太空了 而且都没有对齐」 | `subpackageMember/order` list cards. An earlier rule still holds: 「不用写几张票 也不用出示核销码 应该是主题的信息 城市定向还是自由探索 具体的类别」. |
| V3-33 | 「这个要居中 看起来不对称」 | `pages/gerenziliao` |
| V3-37 | 「个人资料没有icon」 | 我的 page, settings row 个人资料 |
| V3-38/39 | 「这个审核中和标题应该在一行」 and 「卡片样式重新设计 通过 两个字就好」 | `subpackageA/pages/myproject` |
| V3-28 | 「空态的状态我们不是这样的」 | `subpackageTools/exploration-goals`: use the standard two-layer empty illustration component (`cy-states`). |
| V1-P53 | Settings 投诉建议 row | `pages/shezhi`: confirm and fix. |

### qx-a4: roam, passport, stamps

| ID | Owner | Where / lead |
|---|---|---|
| V3-26 | 「左上角头像旁边的那个要删掉」 | `pages/roam/index.wxml` |
| V3-27 | 「集邮册已经被我们统一到一个页面了 里面有勋章」 | `subpackageP3/pages/stamp-album`: add the medal section; remove the separate 勋章墙 card from the passport. |
| V3-34 | 「收集册卡片里面的样式想做成3d的很好 但是看起来没做好 并且收集册的卡片不需要灰色背景」 | `pages/member/index/index` collection cards: real 3D tilt with perspective and specular sheen, no grey backing. |
| V3-35 | 「没有这个城市漫游清单 这个应该直接展示在地图上的」 | `subpackageRoam/components/roam-city-guide`, `pages/roam/index.wxml:18`: remove the list and show those POIs as map markers. |
| V3-36 | 「这些官方活动 漫游记录 集邮册 都换成真实的合适的现实的照片」 | `pages/roam/index.js:804-806` uses `/images/roam/tile-*.svg`. Use real licensed photos: Unsplash or Pexels licence, or CC0. Compress to webp ≤60 KB each and put them in a subpackage or OSS-ready `assets/`. Record the source and licence in RESULT.md. |
| V3-40, V1-P37 | 「这个需要的是真实的地图 不是这种虚拟的」 and 「漫游历史…卡片设计也不对 应该就是地图 参考nrc」 | `components/cy/scene-roam-history`: replace `cy-route-thumb` with a static real map snapshot (the `map` component in a non-interactive mode, or the existing static-map helper) with the route polyline, NRC style. |
| V3-42 | 「时光胶囊先删除」 (owner decision) | Remove the entries `pages/roam/index.js:809,843` and the `subpackageTools/capsule` route registration. Keep the backend. |
| V1-P32 | 「地图上放的活动 组队要都能看到 组队卡片效果：ashdesignin」 | Team and activity cards on the roam map. Reuse the approved map prototype style (dark map, place pins). |

### qx-a5: creator flow (small items)

| ID | Owner | Where / lead |
|---|---|---|
| V3-29 | 「这里发布者实名UI优化 需要对齐」 | `pages/publish/fabu/step3.wxml`, `publish/activity` |
| V3-31 | 「为什么会出现这种样式 任何模板广场玩法都应该可以用啊 不应该有这个提示和页面」 | `pages/publish/temp/index.wxml:57`, `utils/publish/template-adoption.js:54`, backend `ADOPTION_CAPABILITY_LOCKED`. Find why public-shelf gameplays are locked and make every published shelf gameplay adoptable. Keep real permission checks; only remove the false lock. Backend change allowed. |
| V3-43 | (screenshot: 模板广场 top area under the nav bar is flat black; the maroon gradient starts below it) 「顶部是黑色的 看起来没有全屏覆盖」 | `pages/template/index`: the hero background extends under the status and nav bar. |
| V3-44 | 「暂时先不做这个快速的ai配置了」 (owner decision) | `components/cy/publish-sheet/index.wxml:15`: remove the 快速配置 entry. Leave `pages/publish/simple` routable but unlinked. |
| V3-45 | 「上面有一条线 看起来不对 还有多少没填的样式不对 统一一下 后面不要有三角」 | `pages/publish/fabu/index.wxml:148` |
| V3-46 | 「这里不能选这些的 只能选择公共玩法库和自己的 不然就去自己配置」 | `fabu/index.wxml:943`: remove the 新建故事玩法 list. |
| V3-47 | 「不显示草稿…自己建一个应该是放到我的模板最前面 标题就是自己建一个 样式和我的模板其他样式一样的 底部按钮应该是确定 而且为什么没有图片」 | Drafts hidden and confirm callback already DONE (67e48cd87). Still to do: 自己建一个 as the first card of 我的模板, same card style, with an image; bottom button 确定. |
| V3-48 | 「这个地点的现实有问题 写不完就省略号 不要影响前面的地点 地点两个字就横着写」 | fabu place rows |
| V3-50 | 「选中的描边样式也不对」 | fabu selection outline: use the kit's selected style. |

### qx-a5d: article-style story editor (owner's top concern)

Owner: 「这样一段一段写太麻烦了 你看一下x article是怎么做的 我看到是可以直接复制 然后玩法/图片/音频之类的统一顶部标签 然后添加 然后添加之后可以拖动 放到合适的剧情里那种 也可以添加分割线之类的 这个问题最大 因为现在写剧情非常复杂」. The current screen shows 第1章 · 故事流, a chapter title box and a single "+" divider row: empty, and one paragraph at a time.

Build it in `pages/publish/fabu` (story flow editor). `pages/publish/utils/publish/pro-editor-story.js` already has `insertAtCaret`, `pasteTextAtCaret`, `moveBlock` and `divider` commands (2ee3a4c1e); they are not wired to the UI.

- **One continuous article.** A single writing surface per chapter. Pasting multi-paragraph text splits it into paragraph blocks at the caret. Enter creates a paragraph. Typing feels like one document, not separate boxes.
- **Fixed top insert bar** (above the keyboard while focused, under the header otherwise): 玩法 · 图片 · 音频 · 分割线, plus the existing block kinds. Each inserts at the caret.
- **Inserted blocks are cards inside the article.** Long-press drag reorders any block, text paragraphs included. Use a `movable-area` or a touch-driven reorder with haptic `wx.vibrateShort({type:'light'})` on pick-up and drop and an auto-scroll near the edges. Remove the 「节点块不做拖拽」 restriction.
- **Saving.** Save, read-back and publish must round-trip exactly. Add unit tests on the block-order model and a save→reload test, and keep the existing story-flow contracts and the publish check green.
- **Delivery.** Evidence must include a short sequence of shots: paste, insert, drag mid-way, after drop.

### qx-a6: merchant

| ID | Owner | Where / lead |
|---|---|---|
| W2-01 | 「项目的卡片需要重新设计一下 有点丑 可以变成大的卡片」 | `pages/merchant/index` project cards: a large cover card (cover image, title, status, key counts). |
| W2-07 | 「客户页面需要重新设计 没有开放售票哪里来的客户 这里就可以写未售票 售票之后…下面是已经核销的 上面是还没到店的 我记得还有一个广播功能」 | `pages/topic/components/project-host` customers: three states (未售票 / 未到店 / 已核销) and broadcast. Reuse `club-director-broadcast-sheet` plus `/api/merchant/crm/broadcast` and `/preview` with `scope=MEMBERS`, limited to that theme's ticket buyers. If the API cannot scope by theme, add an owner- and scope-checked backend parameter. |
| W2-11 | 「这个更多UI样式不对 里面的结算明细外面已经有了 你看看还少什么功能」 | Merchant 更多: remove 结算明细 and add 收款账户 if missing. |
| W2-15/16 | 「经营优化 应该有一些图片 如何利用城瘾吸引客流之类的」 and 「上面的提示应该是打开的弹窗 而且只有第一次有 就是一个介绍」 | `pages/merchant/marketing`: image cards promoting how to use 城瘾 for footfall. A first-visit intro sheet (once only; the store flag in localStorage is acceptable) using the 城瘾 building-block logo (`images/cy_logo_mark.png`, #7A5CFF). |
| W2-17 | 「帖子要有 我在玩家版那边说过这里是全局的 上面的合作改成发现」 | `components/tabBar/index.js:11`: merchant tab 合作 becomes 发现, showing the global post feed. Merchants may like and comment. |
| W2-03/04/05/19 | 「都统一到消息了…上面横着滑动的协作邀请 下面是消息 收件箱 发件箱」, 「邀约提醒做成一个开关 放到协作邀请旁边」, 「合作俱乐部 合作商家 更多 客户 弹窗高度都有问题，需要全部统一成和商家一样的全屏弹窗…因为这在一个主题内部，不能跳出去」, merchant message stack | First verify what exists in `pages/coop/list` and `pages/topic/components/project-host`, then finish the gaps: horizontal invite cards on top, inbox and outbox below, an invite-reminder switch beside 协作邀请, and in-theme stacked full-height sheets that never navigate out of the theme. |

### qx-a7: share page and motion

| ID | Owner | Where / lead |
|---|---|---|
| V1-P36 | 「分享页面需要重新设计 参考spotify的分享 这个分享页面可以通用 不仅是这个漫游 还是各种活动」 | A generic share sheet or page component: a big poster card in the content's colours plus channel row (微信好友, 朋友圈 via save image and copy text, 小红书 via save image and copy text, save image). Used by roam, activity, theme and medal (medal cards get the QR code too). Use the existing poster canvas helpers. |
| V1-P59 / G-11 | Invite-friends motion (IqRophy) and page motion (uixsiam09) | `subpackageMember/myinvite`: invite card entrance and a page transition using transitions only. Read `docs/design/` and the page-motion kit before adding anything. |

---

## B lanes: Questo UI (base `origin/integrate/questo-ui-20261110`, PR into it)

Spec: `docs/questo-teardown/UI优化施工清单.md`. "§" refers to that document. Build the mini-program first. The admin UI is lane qx-b6; iOS is lane qx-b7 in the chengyin-ios repo. For each new screen, choose the nearest existing page as the structural template and say which one in RESULT.md. Every §十二 item shows an unavailable reason or nothing.

### qx-b1: play core

- **Publish result** (U03-01/02/03, §23.1): keep `publishRequestId` and never re-generate it after an unknown result; show the returned revision identity.
- **Story and capability** (U04-01..04, §27.2): `chapters[].blocks` in order. Send `clientPlatform`, `clientVersion` and `supportedCapabilities` on orient prepare/start and advanced start, declaring only the capabilities this client really renders. Missing means undeclared, which differs from an explicitly empty value.
- **Arrival** (U05-01..03, §23.2, §27.3): `arrivalReasonCode`, `canRetry`, `approvedFallbacks`, `locationTimestamp`. `arrivalReading` is played once per receipt. An unknown result re-reads the original receipt.
- **Help** (UG-05/06/07/09): the hint ladder panel from `GET /api/play/help` and `POST /api/play/help/use`. One 「本局剩余免费 N 次」. Buttons have three states (free, cost, unavailable). Re-viewing is free. The receipt and score detail fold out. NOT_SCORED is never shown as 0.
- **Skip** (UG-08): only when the server says it is available. Confirm with 「本题记 0 分并标辅助完成」. No success animation.
- **Contract shards:** fix the mini-program contract shards 14/20 and 20/20 that CI flags.

### qx-b2: new gameplay types (player and author)

- **Player:** UG-01 hangman slots and keyboard; UG-02 image options with alt text and fallback; UG-03/04 accusation person cards, dossier and two-step confirm. §27.9: `readyForBase` is not success. UG-10 clue transform.
- **Author:** UG-11 author hint config under 结果反馈 in `pages/publish/temp`.
- **Catalog and matrix:** sync the node game catalog and support matrix, so authors see the new types.
- **§23.1 author fields:** answer aliases (private), `placeCountryCode`, `arrivalPolicy` (radius, accuracy, age) and `arrivalReading` (level, log) in the node arrival settings.

### qx-b3: player loop P1–P9 and rankings

- **First chain:** P7 战报 → P1 被邀请落地 → P4 模式选择 → P5 票内席位. Prove it end to end in the rig: real completion, then report, share, landing with a new account, mode, seat claim, and back to the same run.
- **Then** P2 推广中心 (no withdrawal), P8 同行回忆, P9 单关/队内榜, and the §23.4 multi-scope rank (world, country, city, quest, club; server `rank`, `me` and the period window).
- **Shape:** reuse existing pages and sheets; the doc says one report container plus rank and review sheets, reusing the share card. P5 write actions stay hidden unless the backend reports them enabled.

### qx-b4: template change city, presets, co-op

- **Template change city** (U02-01..05): adoption conditions on the detail page (`contentDelivery`), keep places or change city (`/use` with bindingIntent and the like, §27.1 `sourceContentRevisionId`), location to-dos (only `level=error` blocks), source revision, attribution and provenance (§27.5).
- **Presets:** §21 preset list and draft.
- **Team** (U06-01..04): seats, invites and roles.
- **Co-op** (§26.1): shared help VIEW/USE/SKIP, `sharedPreview`, `QUEST_RESOLVE` with `answerRevision`. No shared timer, shared skip quota or single team score.

### qx-b5: commerce

- **Orders:** U07-01/02/04 (quote `delivery` and `paymentChannels`, order check after payment, cancellation and partial refund display).
- **Marketing:** §17.1 products and passes (buy only on server-sellable SKUs), §17.2 gifts, §17.4 group/school, §17.5/17.6 promotions, quote and coupon (no client-side pricing, coupons never offset cash), P6 city pack.
- **Income and hosting:** §19/§27.10 personal income lines, §27.6 host reservation (`reservationState`).
- **Gated** (show the unavailable reason only): §17.3 add-seat, §18 C/D host tips, §20.2/§26.3 OTA redeem, U07-03 paid workshop.
- **Video bounty author side** (§18 A/B, I): accrual shown as 「已记录（未开放兑付）」.

### qx-b6: admin (chengyinhub-ui, Vue 2)

U03-04, UG-12, U07-05, U08-03, U08-04 with §27.4 `repeatSignupV1`, §27.7 per-item refund, §18 E/F video bounty and verified group, §20.1 content-landing translation review, §20.2 channel vouchers, U08-05. Menus and permissions: add the menu SQL as a new migration but **do not grant it to any role** (owner decision pending). `npm run build:prod` must pass.

### qx-b7: iOS (repo liboyang42-cpu/chengyin-ios, base `main`)

- **First:** the paid-workshop screens still call v1 `api/workshop/purchased/*`, which now returns 409 `WORKSHOP_CONTRACT_UPGRADE_REQUIRED`. Decode that and show "unavailable / update" instead of breaking.
- **Then:** port B1 and B2 (help panel, hangman, image options, accusation, arrival reasons and reading, capability declaration, `missingCapabilities`/UNDECLARED) per §十一 of the spec, which you can read from the hengyin base branch.
- **Style:** native SwiftUI with the Apple design language. The mini-program is the structural reference only. Chinese and English strings.
- **Build:** you cannot run Xcode; keep code typecheck-clean by careful reading. Claude typechecks locally afterwards.
- **Delivery:** one draft PR into `main`.

### qx-b8: post-game and catalog

U08-01/02/05 and §20.1 public catalog, plus P3 theme collection landing. Hide counts and ratings unless the server provides them. Show no 到货提醒 success.

---

## C lane: backend gaps found by the UI lanes (base `origin/integrate/questo-ui-20261110`, PR into it)

### qx-c1 backend gaps

Fix each one with a test that fails on the old code. Prove each fix over real HTTP in the rig with a MySQL read-back. Never weaken permission, ownership, answer secrecy or money rules. Don't add UI (the lanes own UI), except where a field must be passed through.

1. **`WorkshopMapper.xml` `lockLicenseForDraft` uses `... FOR UPDATE OF o`, which the app's Druid wall rejects.** This blocks theme `/use` adoption for themes with node templates on the real stack (found by qx-b4).
   - Rewrite the query so it keeps exactly the same rows locked: lock the license row explicitly, for example lock it first by primary key in its own `FOR UPDATE` select, then read the joined data.
   - Add the case to the real-stack test, since unit tests miss the wall.
   - Grep every mapper for other `FOR UPDATE OF` / `BINARY a = BINARY b` uses the wall rejects.
2. **Collation mismatch.** `GiftEntitlementMapper` joins `cms_registration.parent_order_no` (utf8mb4_general_ci) to `marketing_product_order.order_no` (created without COLLATE, so MySQL 8 defaults it to utf8mb4_0900_ai_ci), which fails with `Illegal mix of collations` (found by qx-b3).
   - Fix with a new migration that aligns the marketing table's string columns to the schema's collation. Check every table created by the Questo migrations for the same omission.
   - Never edit existing migrations. Add a migration-gate test.
3. **`QaOptionMediaSupport`.** The runtime session view passes option `img` and suspect `avatar` as raw `media-asset:{id}` references, so the client can't show them (qx-b2).
   - Project them to the same signed or public URL form other play media use, only for published and approved assets.
   - Keep the alt text.
4. **UG-12 admin read-only projection** (qx-b6).
   - For one topic or run, show: the hint ladder config summary (layer count, costs, `hintFreeUsesPerRun`), and the per-run help, skip and net-score lines with receipt numbers.
   - Never return answers, aliases or hint text bodies.
   - Put it behind a new `business:...:query` permission plus a menu row in a new migration, granted to no role.
5. **§23.1 author write paths** (qx-b2).
   - **(a) `contentContract.placeCountryCode`:** two uppercase ISO letters or empty, never defaulted, owner-checked. Write it through the existing topic location contract save.
   - **(b) `arrivalPolicy` and `arrivalReading` in `scenes[].nodeConfigs[nodeId]`:** these currently collide with the node template-override semantics (client `theme-ownership.effectiveTemplate`, server `GameplayTemplateRules`). Store them so they never change template-override behaviour: either a dedicated sub-key that both rules ignore, or a sibling map keyed by nodeId.
   - Save and publish validation must accept them and freeze them into the run snapshot. Document the final shape in RESULT.md so qx-b2's UI can wire it.
6. **A player/author video-bounty campaign list endpoint** (qx-b5). List only campaigns that are active and visible to the caller, with the fields the §18 A UI needs.
7. **A post-game feedback write endpoint** (qx-b8, U08-02). Reuse the existing feedback or report table if there is one.
   - Owner and participant checked.
   - Idempotent by requestId.
   - Rate limited like other user writes.
   - Goes through the content-safety check.
8. **`/api/template/topic-template/use` idempotency** (qx-b4). Accept an optional `requestId`. The same id plus the same payload returns the original result; the same id with a different payload is rejected. This follows the existing action-receipt pattern.
9. **Preset missing-fields error.** When the error is longer than 120 characters, it is masked to 「系统繁忙」 by `UserSafeMessage`. Return the structured `requiredFields` list (as the other publication problems do) instead of a long message; do not raise the global mask limit.
10. **Audit `domain_type` length.** qx-b6 found that `business_audit_event.domain_type` overflows (16 chars) for `VideoBountyServiceImpl` and fixed it on its branch. Grep every writer of that table on the base and fix any other over-length domain the same way, so the rule is consistent.

Work on branch `claude/qx-c1-20261109`. Write RESULT.md in the same format as the UI lanes, plus a "contract for UI lanes" section listing exact request and response shapes.

---

## Staging and polish wave (owner, 2026-11-09 evening)

The owner is away and will review everything together on return. They asked to keep optimizing the UI with three references, saved in `refs/` in this folder:

1. **Onboarding** (`ref1-onboarding.jpg`, heysatya_):
   - A dark first-run pager.
   - Top bar: round back button, segmented progress pill, Skip.
   - Hero: one tilted, layered 3D card stack, glassy, with a soft glow.
   - Title: big, centred, two lines, with a coloured period accent.
   - Below it: one muted line, a white pill CTA, and a plain text secondary action.
2. **Gestures** (`ref2-gestures-frames.jpg`, emilkowalski /gestures):
   - Bottom sheets drag with your finger and dismiss on a downward swipe once velocity or distance passes a threshold.
   - Dragging up past the top detent rubber-bands: the travel resists and springs back.
   - A floating mini player (picture-in-picture) is draggable and snaps, with momentum, to the nearest corner, like iOS.
3. **Motion** (`ref3-*`, Triopixels):
   - Morph: a pill control becomes a progress state, then the icon pulses, then a success line appears.
   - A side panel reveals with the content sliding over.
   - Stacked cards or sheets show depth: the previous layer scales down and dims behind.
   - The bottom card grows into a full sheet.

Respect the standing rules in BRIEF.md: the existing UI is the baseline, no secondary captions, player dark / merchant light, `prefers-reduced-motion`, and transitions over keyframes. **Do not restyle approved pages wholesale.** Apply each reference only where it says.

### qx-s0: staging integrator (cloud)

Build branch `staging/questo-review-20261110`, a preview build for the owner's review. It is not a merge of any PR, so never merge, close or mark any PR ready.

1. Start from `origin/integrate/questo-ui-20261110`.
2. Merge, with `--no-ff` and in this order: `claude/qx-c1-20261109` (once its RESULT.md has a STATUS line; poll every 10 minutes for up to 3 hours, then continue without it and say so), then b1, b2, b3, b4, b5, b6, b8, then a2 (global tokens first), a1, a3, a4, a5, a5d, a6, a7. All of these are `claude/qx-<lane>-20261109`.
3. Resolve conflicts by keeping both lanes' intent: a2's tokens and switches win on colour; play-page logic combines a1 and b1.
4. After each merge, run the mini-program unit tests touched by that lane, plus `node chengyinhub-xcx/tests/lint/ui-integrity.js`.
5. At the end:
   - Rebuild the action ledger.
   - Run `mvn -q -DskipTests package` and the Java tests of classes touched by lanes.
   - Run `npm run build:prod` for chengyinhub-ui.
   - Report the main-package size estimate: about 0.633 × uncompressed main-package source bytes. It must stay under 2048 KiB; move pages to subpackages if needed.
6. **Rig fixture.** Fix the rig so a ticketed play state is reachable on this base. Today `chains.py sale_close` fails with 「自由探索只开放内容所属俱乐部的场次」 and then 「四店固定补偿产品已停止新开售」. Add a rig-only fixture: a sellable self-run 城市定向 theme with one free ticket, created through normal endpoints where possible. Put it on the rig branch or under `docs/delivery/questo-ui-20261109/staging/rig/`, never in product code.
7. **Missing screenshots.** Re-shoot every lane item whose RESULT.md says the rig could not reach it. The b1 play core and the album both lack screenshots; for the album, use a phone-sized viewport and accept a headless limitation only after trying.
8. **Deliverables:**
   - Push the staging branch.
   - Write `docs/delivery/questo-ui-20261109/staging/RESULT.md` with the merge log, conflicts and how each was combined, gate results, sizes, and new screenshots under `staging/shots/`, named `<lane>-<ID>-after.jpg`.
   - Open one draft PR from staging into `integrate/questo-ui-20261110` titled `[REVIEW BUILD] ...`, marked do-not-merge.

### qx-u1: gestures (mini-program, base `origin/staging/questo-review-20261110`, PR into it)

1. **The shared sheet** (`components/cy/*` sheet and the scene-registry sheets, peek/half/full).
   - Make it follow the finger, using a WXS touch handler for 60 fps.
   - Dismiss on a downward swipe once velocity > 0.5 px/ms or distance > 30 % of the sheet.
   - Snap to the nearest detent otherwise.
   - Rubber-band dragging up past the top detent: resistance is offset × 0.25 with a spring back.
   - Only the drag handle and header start a drag when the content is scrolled to the top; otherwise the content scrolls.
   - Reduced motion: no rubber-band; snap instantly.
   - Unit-test the detent and velocity maths in a pure JS module.
2. **Floating corner player.** The play page music corner and any other floating corner widget (find them; the 2026-10-29 note mentions a music corner) becomes draggable and snaps with momentum to the nearest of four safe-area corners, avoiding the nav bar and tab bar.
3. **Swipe-back.** Horizontal swipe-back on full-screen overlays opened as pages-in-page (photo album, story preview) closes them with the same physics.

### qx-u2: first-run and onboarding screens (mini-program, same base)

Apply ref 1 only to these screens, which are new in this wave or are first-run screens. Use player dark and merchant light; on merchant screens, use the same structure in the light palette.

- P1 被邀请落地 (`subpackageJourney/pages/landing`): a hero with a tilted card stack built from the theme cover, a big two-line title, one muted line, a white pill CTA and a text secondary action.
- P4 mode choice: a horizontal pager of mode cards with the segmented progress pill.
- P7 战报 hero: a layered result card, with the score in big italic numerals (NRC rule) and the share CTA as a white pill.
- The merchant 经营优化 first-visit intro (W2-16): a three-step pager with Skip and the building-block logo.
- Template-to-publish first use, if a first-run intro exists. Search for `firstRun`/`intro` keys; don't invent new intros.

Use only cards and data the screen already has. No fake numbers. The coloured period accent uses the brand accent token.

### qx-u3: motion (mini-program, same base)

- **Morph** (ref 3): adopt it for the success moments that already exist:
  - seat claim success (P5)
  - gift claim (P1)
  - theme adoption done (`/use`)
  - hint unlocked (UG-05)
  - publish success (U03-02)

  The button morphs into a progress state and then into a check, with the icon pulse in transitions only. Reuse the page-motion kit (`rise-in`/`count-up`) where it fits.
- **Stacked sheets with depth:** when a sheet opens on top of another sheet (the in-theme 邀约/招商 stacks, W2-05), the lower one scales to 0.94, moves up 10 px and dims. This is coordinated in the sheet host, not per page.
- **Card to detail:** the bottom card grows into the full sheet for order card → order detail and theme card → theme detail, only where the destination is already a sheet. Pages keep normal navigation.

### qx-u4: iOS polish (repo liboyang42-cpu/chengyin-ios, base: branch `claude/qx-b7-20261109`, PR into it)

- Port the same three references natively in SwiftUI:
  - interactive sheets: `presentationDetents`, plus custom rubber-band drag where a custom sheet exists
  - a PiP-style corner snap for any floating player
  - the onboarding pager for the new Questo first-run screens
  - `matchedGeometryEffect` morphs for the same success moments as qx-u3
- Keep Apple-native components and the light/dark domain rule.
- Write RESULT.md and open a draft PR.

### qx-c2: docs, deployment list and storage verification (base `origin/integrate/questo-ui-20261110`, PR into it)

1. **UI checklist.** Update `docs/questo-teardown/UI优化施工清单.md`:
   - Fill in the empty §25.2 and §25.3: the client-contract sync, i.e. what the mini shard fixes and the C00 auth-contract change were.
   - Correct the four wrong paths listed in BRIEF.md.
   - Add §28, "2026-11-09 实施波次": for each UI task ID, the lane, PR, status (fixed / partial / needs owner) and the evidence path. Read every `docs/delivery/questo-ui-20261109/qx-*/RESULT.md` on the lane branches.
   - Add the vision 3 walkthrough status table from LANES.md and the qx-a* RESULTs.
   - Keep the document's existing conventions. Do not rewrite history sections.
2. **Backend results.** Update `docs/questo-teardown/后端优化执行结果.md` with a new top section: the merge of master into #731, the CI fixes, the owner-approved architecture registration PR, the qx-c1 backend gaps, and the current CI run IDs and results. Read them with `gh run list`.
3. **Deployment list.** Add `docs/questo-teardown/部署清单_20261110.md`:
   - Every new `sql/migration_*` on `integrate/questo-ui-20261110` (and on `claude/qx-c1-20261109`) that is not on production's release set. Use `ci/` release-manifest tooling to compute it; the release pack is stale (pack 88 vs 117/127), so recompute rather than trusting it.
   - Their dependency order, rollback notes, any data assertions needing production rows, the new scheduled tasks (registered paused), and the feature switches that must stay off (`cash-enabled`, `apple-iap.enabled`, `workshop.wechat-app-enabled`, channel providers).
   - The menu/permission rows granted to no role.
   - This is a document for the owner to execute. Never run anything against production.
4. **Storage verification.** Trigger the manual real-storage workflow (`Real Storage IT (manual)`, via `gh workflow run` with the selected-classes input the workflow defines) for every MySQL IT class added or changed on `integrate/questo-ui-20261110` since 147754ba, plus the qx-c1 classes once that branch exists. Collect the XML counts. Record pass/fail per class in the results doc. Fix genuine failures on a branch `claude/qx-c2-fixes-20261109` with a PR into the integrate branch; never weaken assertions.
5. Finish with RESULT.md under `docs/delivery/questo-ui-20261109/qx-c2/` and a draft PR.
