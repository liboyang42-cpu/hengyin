# 城瘾 mini-program acceptance walk: launch/questo-20261110

- **Tested SHA:** hengyin `launch/questo-20261110` @ `844638cf7a4d2e7b482b2538753a029d43c1335c` (newer than 60c43860). Only `chengyinhub-xcx` was extracted to `acc/mp`.
- **Backend:** TEST only, `http://127.0.0.1:8099`. This is VPS `cy-dw29-master-app`, jar from candidate 17de18de (git 3d6b2f1f), DB `cy_dw29_dev` @ 127.0.0.1:33162. Every shot asserted `getApp().globalData.siteBaseUrl === 'http://127.0.0.1:8099'`. The test backend has no OSS env, no AI config and no WeChat Pay cert.
- **Local edits to the scratch copy:**
  - `utils/config.js`: API base set to 8099 for all env branches.
  - `urlCheck` was already false.
  - **`utils/wxs/gesture.wxs:115`: `Number(ds.wh)` changed to `(ds.wh * 1)`.** Without this the app does not compile at all. See ISSUE B-1.
- **Main package (source bytes, `scripts/mainpkg-size-report.js`):** 3230 KB before compression. DevTools' compressed size was not read, because that needs `cli preview/upload` to WeChat servers.
- **Data:** shots 117, page paths 45. Log: `log.json`. Contact sheets: `sheet-{player,merchant,club,author}.jpg`. DB changes and restores: `DBCHANGES.md`. All changes were restored and verified.
- **Shots prefixed `MOCK-`** use the built-in dev `?mock=city` play data, not the backend. A real chapter end or finish needs a completed city run, which the walk did not reach.

| id | role | page | screenshot | what's wrong | evidence | suspected file / endpoint | severity | test-data gap |
|---|---|---|---|---|---|---|---|---|
| B-1 | all | whole app | (none: app would not boot) | **The WXML compile fails, so the app cannot launch.** The WXS compiler rejects `Number(...)` used as a value inside an object literal. Every page that imports gesture.wxs fails: sheets, scene-sheet, publish/fabu, playkit-album and all roam/tools subpackages. DevTools hangs at `routeTo appLaunch timeout`. | DevTools log: `Error in file utils/wxs/gesture: utils/wxs/gesture:115:60:Expected variable\|string`. Reproduced with DevTools `wcc -d`. After replacing it with `(ds.wh * 1)`, all 465 wxml files compile. The line came from c395eb1d (qx-u1) and was touched again in eaa77ce7. | chengyinhub-xcx/utils/wxs/gesture.wxs:115 (`wh: Number(ds.wh) \|\| 0`). Line 95 `return Number(fallback)` compiles. | blocker | no |
| M-1 | merchant | 商家工作台 / 我的 | merchant-05-workbench.png (after workaround) | Before the workaround the workbench rendered nearly empty: no projects, income or todos. `access/me` and `project/my` return 500 when a merchant logo or project image is a `media-asset:N` reference and OSS config is absent. One unsignable image takes down the whole merchant identity endpoint. | `/api/merchant/access/me` and `/api/project/my` returned code 500 "Could not write JSON: 对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME". The page rendered after nulling `mms_merchant.logo` (id 16) and `cms_activity.img_url` (id 60) in the copy; both are restored. | Backend: media-asset URL serializer (FileOssUploadUtils.requiredEnv). Frontend: pages/merchant/index | major (env) | yes: env (OSS unset on test) |
| M-2 | player | 勋章墙 | player-16-badges.png | Blank pastel page with no title or content. `loadFail` is true; a blank veil on load failure is by design. The same OSS serializer failure as M-1 causes it. It is also a light page on the player side, and the status bar is invisible on it. | `/api/badge/wall-v2` returned 500 "对象存储配置缺失" | Backend /api/badge/wall-v2; subpackageP3/pages/badge-wall | major (env) | yes: env |
| P-1 | player | 主题详情 990053 → 确认订单 | player-30/32-*.png | The theme ran 10.11–10.13 and is closed, yet the hero CTA reads **立即报名**. The checkout then shows 「主题已截止」 and 暂不可购买. The CTA should not offer sign-up for an ended theme. | `/api/registration/quote` returned 500 "主题已截止". `heroCta={kind:'signup'}` and `lifecycle=0`. | pages/topic/utils/topic-motion.js:33-45 (buildHeroCta ignores end_date / closed state) | major | partly (old test theme, but the logic gap is real) |
| P-2 | player | 确认订单 | player-32-topic-990053-buy-go.png | The checkout option for a **city-orient ticket** (self_play=0) is labelled **「自玩通行证」**. The owner renamed 通行证 to mean the platform pass bought under 我的会员, kept 门票 naming, and said there is no pass in checkout. | Rendered text. `title: '自玩通行证'` is hard-coded. | pages/topic/utils/checkout-view.js:84,99; pages/topic/components/cy/self-play-checkout | major | no |
| C-1 | club | 俱乐部 → 管理 | club-03-club-manage.png | The 「现在要做」 card shows **重试管理进度 / 暂时无法确认当前阶段**. The club owner cannot see the next step. The club page calls the merchant-only `/api/coop/list`, which returns 403, and that failure turns `messageState` into error and then the manage stage into error. | `/api/coop/list` returned 403 "商家身份无效或权限不足" for owner 990044. Merchant 17 is invalid: `access/me` returns 403 "商家申请归属无效". | pages/club/detail/index.js:1395-1424 (coop/list failure → stage error), :76 | major | partly (990044 also holds an invalid merchant record; a club owner with no merchant record probably hits the same 403) |
| P-3 | player | 游玩(自由探索)工具菜单 | player-40-play-tools-menu.png | Contradictory numbers. The header says 「2 章 · **1 家商家**」, but the 我的权益 row says 「**2 家** · 已核销 0 家」. The row uses node `total`, not `merchantCount`. 我的权益 then opens 优惠券 「这里还没有优惠券」 (player-44). | page data: `total=2`, `merchantCount=1` | pages/play/index.wxml:374 (`{{total}} 家`) | minor | no |
| P-4 | player | 游玩工具菜单 | player-40-play-tools-menu.png | The 「各站奖励」 row has no icon or chevron, so it reads as an empty section heading between 紧急求助 and 切换章节. | rendered | pages/play/index.wxml:304 | minor | no |
| P-5 | player | 站点详情(自由探索) | player-48/49-play-station.png | The 「到店三步」 heading shows with no visible steps under it, because the fixed 到店打卡 bar covers the step list. A stray bullet 「•」 also shows under the shop name. | page data has `hero.node.isMerchant` | pages/play/index.wxml:839-845 (fx-step3 under fixed .fx-hero CTA) | minor | no |
| P-6 | player | 自由探索卡包首屏 | player-37-play-after-loc.png | The pack screen shows only the foil pack, with no back button, title or hint. A torn white artifact sits at the pack's bottom-left. The city play start screen (player-50) also has no back button. | rendered | pages/play/index.wxml:662-690; assets/pack-foil.png | minor | no |
| P-7 | player | 探索节点卡 | player-38-play-pack-open.png | The chapter label 「第 1 章 · Questify E2E …」 runs under the 「未到店」 pill. | rendered | pages/play/index.wxml (fx-tile__tags) | minor | no |
| P-8 | player | 城市定向地点卡 | player-59-play-city-continue.png | The big stat slot labelled 「开放」 holds a long sentence: 「公共区域观察；以现场通行和开放时间为准」. It wraps to three lines in the large stat font and pushes the layout. | data from node openTime text | pages/play/index.wxml (place sheet stat row ~1064-1072) | minor | partly (content) |
| P-9 | player | 旅程手记 | player-46-play-journal.png | The left column wraps a long node name to four lines (「Questify / E2E 拒绝重 / 申独立点位」). The sheet also has explanatory captions (「这一趟自动记下来的」, 「走完本章坐标…显形。」), against the no-secondary-labels rule. | rendered | pages/play/index.wxml (journal sheet) | minor | no |
| P-10 | player | 结算(MOCK) | player-65-MOCK-play-finish.png | Awkward progress copy: 「你的第一个主题正在路上 · 距 1 个还差 1 个」. | rendered with mock data | pages/play finish view | minor | no |
| P-11 | player | 首页 | player-01-home.png | The 首页城市定位 modal copy is a long, awkward question (「…后续自动定位且不用于附近排序吗?」). 编辑精选 is empty: 「暂时没有精选主题」. | rendered | pages/index/index (location consent modal) | minor | 编辑精选 empty = yes |
| P-12 | player | 主题详情 | player-30-topic-990053-detail.png | The stat 「开放时间 10.11–10.13」 wraps to 「10.11–10. / 13」. The cover placeholder illustration (pin) draws over the WeChat capsule, and the white status bar is invisible on the white hero. | rendered | pages/topic/index/index.wxml (stats row / hero) | minor | cover missing = yes |
| P-13 | player | 游戏主题库 | player-12-pass-library.png | With the pass off (`state:'off'`), the filter chips 全部/城市定向/自由探索 still show above 「通行证暂未开放」. | flags `state=off` | subpackageMember/market/library/index.wxml | minor | pass plan not enabled = yes |
| P-14 | player | 收集册 | player-17-stamp-album.png | The page is light (white or pastel) on the player side; the owner rule is player always dark. Needs an owner call. | rendered | subpackageP3/pages/stamp-album | minor | no |
| P-15 | player | 设置 | player-13/14-settings.png | 「AI 对话记录」 and 「投诉建议」 use the same chat-bubble icon, so the icon is a repeat rather than its own. | rendered | pages/shezhi/shezhi.wxml | minor | no |
| P-16 | player | 漫游附近 | player-20-roam-nearby.png | The selected chip 「全部」 has no pill background, so the text floats on the map. | rendered | subpackageRoam/nearby/index.wxml | minor | no |
| P-17 | player | 漫游 tab | player-07-roam-tab.png | A large blank black band sits between the mode cards and 附近活动. `mapLoad=pending`; the map may not render in DevTools. | flags `mapReady=false` | pages/roam/index | minor | maybe DevTools |
| P-18 | player | 票夹 | player-09-wallet.png | The ticket card is mostly empty below the title, with no cover or illustration. The player holds 2 valid tickets (registrations 143 and 144), but the first card is 144. Not verified whether 143 is reachable by swipe. | DB: cms_registration 143 (9912601) and 144 (9912610), both status 2 | subpackageMember/signup/index.wxml | minor | covers = yes |
| A-1 | author | 我的节点玩法 | author-10-mytemplate.png | **A literal 「null」 shows** as the template description. | rendered | subpackageMember/mytemplate/mytemplate.wxml:49 (`{{item.description}}` no fallback) | major | partly (null description) but page must not print null |
| A-2 | author | 发布检查 | author-06/09-fabu-*-publish-check.png | The AI safety precheck fails with 500 「AI 服务配置缺失」. It is shown as an advisory, and publishing is not blocked by it. | `/api/ai/safety/precheck` returned 500 | backend AI config | minor (env) | yes: env |
| A-3 | author | 编辑器 | author-04-fabu-new-free-scroll.png, author-05-fabu-page2.png | When scrolled, the large title 「未命名主题」 and 「已存本机」 are half-clipped under the nav. On 票务设置, the bottom bar (返回 arrow + 预览) overlaps the last ticket row. | rendered | pages/publish/fabu/index.wxml | minor | no |
| A-4 | author | 模板介绍 | author-02-publish-template-intro.png | The top text 「暂无精选示例」 sits over a large empty black area. | rendered | pages/publish/template-intro/index.wxml:28 | minor | yes (no featured examples) |
| ME-1 | merchant | 门店信息 | merchant-28-store-info.png | Empty-value copy is inconsistent: 待填写 / 未填写 / 未上传 in one list. 联系电话 uses 「未填写」 and the others use 「待填写」. | rendered | pages/merchant/decor/index.wxml:346-369 | minor | no |
| ME-2 | merchant | 更多 sheet | merchant-27-workbench-more.png | 退款售后, 收款账户 and 管理优惠券 all use the same wallet icon. | rendered | pages/merchant/index/index.wxml:256-290 | minor | no |
| ME-3 | merchant | 账户收益 | merchant-15-earnings.png | The status bar and capsule are white or grey on a light page, so the time is invisible. | rendered | subpackageA/pages/assetcenter/earnings/index.json (custom nav, no dark text style) | minor | no |
| ME-4 | merchant/player | tab pages | merchant-05, merchant-18, player-08 | The custom tab bar has no backdrop, so card titles and buttons show through under the tab icons (「进入合作中心」, 「Questify E2E…X03」, 视频悬赏). | rendered | custom-tab-bar / components/tabBar/index.wxss (also locally modified in the private checkout) | minor | no |
| C-2 | club | 活动运营 | club-06-club-session-ops.png | 「本场名册 · 2 人」 sits next to chips 「已报名 0 · 未到场 2」 and 「这一段暂时没有成员」. The numbers read as contradictory. | rendered | pages/club/event-ops/index.wxml | minor | partly |
| C-3 | club | 主理人开局 | club-11-club-leader-play.png | The leader start could not proceed: the play page showed only 开始游戏. The leader is not a registered player of the session. | `/api/game/session/view` returned 500 "仅本场有效报名玩家可参与" | backend leader session rules / test data | minor | yes |

## Passed checks
- **Merchant first-run intro (AI 店铺角色):** it is the previous 3D-object version, not logo building blocks (merchant-07..09).
- **Merchant theme:** all merchant pages are light.
- **通行证 in merchant views:** none of the merchant pages walked show a 通行证 entry. The profile row 「通行证与礼品」 is gated by `!isMerchantView`.
- **Player theme:** the player side is dark.
- **Club settlement:** plain black.
- **Player features present:**
  - SOS (紧急求助: 110 / 120 / copy location) is in the play menu.
  - 我的收藏 has a 据点 tab.
  - Settings has the 「AI 对话记录」 row.
  - 消息 has 收件箱 and 发件箱.
- **Merchant team page (M-18, 经营团队):** renders with 邀请员工.

## Not reached (and why)
- **Payment stop point:** every test ticket is ¥0 and no paid ticket or self-play price exists in the copy. The paid flow and the WeChat Pay stop point were never reached. The pass page shows 暂无在售通行证 because no plan is enabled, so `platform_pass_plan` has no live product.
- **Four theme looks (STARDUST/PAPER/SURVIVAL/CYBER):** no topic in the test DB has `look_json`, so only the default 星尘 story flow was seen (player-53..60).
- **Gameplay kits qa/coin/dice/album:** only the timeline kit was opened (player-61). No answers were submitted.
- **Real chapter end, finish card, scroll-back review and settlement:** these need a completed run. They are shown only with dev mock data (`MOCK-` shots).
- **Verification desk (核销 scan):** `wx.scanCode` cannot be automated. Only 核销记录 was captured.
- **Club sign-ups:** no pending join requests exist.
- **Author checks:**
  - Paid-price red text: the free-explore and self-play price validation needs a paid price entered. The draft was not filled.
  - Chapter text-block drag: not verified from screenshots.
- **Posts (发帖 entry on own 帖文 tab):** not opened separately.
- **Themes 9912627 and 990066-series:** they return 「主题活动不存在」 or are drafts in the test copy.

## Extra items from the lead's own screenshot review (2026-11-11)

| id | role | page | screenshot | what's wrong | severity |
|---|---|---|---|---|---|
| L-1 | player | 收集册 (stamp album) | player-17-stamp-album.png | light page on the player side; player pages must be dark | major |
| L-2 | player | play start | player-35-play-start.png | frame is fully black (no content/hint) — check whether it's a transition frame or a real blank state | major |
| L-3 | player | chapter gate / ending (MOCK) | player-63/64/68-MOCK-*.png | big blurred orange ring in the centre looks broken (expected dot-matrix chapter number / seal per l-icons) | major |
| L-4 | merchant | 商家主页 from store info | merchant-12-store-info.png | shows 「链接已失效」 for the merchant's own public page | major |
| L-5 | merchant | 口碑 | merchant-21-reviews.png | 「商家信息不完整」 dead end — needs a way to complete info or show reviews | minor |
| L-6 | player | home | player-01..03-home.png | first-city location card overlaps the feed; check layout | minor |
