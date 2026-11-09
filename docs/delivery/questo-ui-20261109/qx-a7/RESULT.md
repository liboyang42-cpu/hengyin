# qx-a7 结果：分享页与动效

基线是 `origin/master` 2bb81143，分支是 `claude/qx-a7-20261109`。截图在 `shots/` 目录，宽 390 px、JPEG 质量 70，全部在走查 rig 里用真实页面拍摄（见文末「Rig」）。

| ID | 状态 | 改了什么 | 文件 | 怎么验证的 | 真机上还没验证的 |
|---|---|---|---|---|---|
| V1-P36 通用分享页 | fixed | 通用分享页 `cy-share-card` 已在 master（P-8，10-31 批准），活动、主题、勋章和店铺都在用。这次补上了两处缺口：① **底色取自内容**（参考 Spotify）：打开分享页时，从封面取主色并压暗到白字可读的亮度，作为第一个色块并默认选中，其余三个仍是原有色块；取不到主色时，原四个色块不变；用户已经换过色块，就不覆盖。② **预览和导出图一致**：眉题（「主题 · 4 家商家 · …」）在预览里折成两行，导出图却被卡片边缘裁掉，现在两边都是一行加省略号；导出图的标题原来按字数硬切（「E2E 探店日一期」被切成「E2E 探店日一」），现在按宽度截断并加省略号。渠道一排（微信好友、朋友圈、小红书、保存图片、复制文案、更多）和小程序码逻辑都没动。 | `components/cy/share-card/index.js`、`index.wxss`、新增 `utils/share-tint.js` | `tests/unit/qx-a7-share-tint.test.js`（6 项：取色、压暗、失败回退、用户换色后不覆盖、组件运行、导出截断）。走查 rig 截图：`V1-P36-before/after`（主题，封面是青色街景：原来是固定的红底，现在是封面青色），`V1-P36-export-after`（真实执行 `_exportImage` 生成的导出图：底色跟着变，眉题和标题都是一行加省略号），`V1-P36-medal-before/after`（勋章没有封面，原色块不变，证明回退正常）。已有的分享卡测试 round9-m2b、round9-m1、uiux-player-state 都通过。 | 真机 canvas 2d 离屏 `getImageData` 读 OSS 封面（rig 里给图片加了 crossOrigin；微信没有跨域污染，但仍要在真机上看一次）；小程序码（rig 生成不了微信码，见 Rig） |
| V1-P36 勋章卡带码 | already fixed | 勋章卡已经会请求首页小程序码（`/api/topic/share-code`，不传 id），并画在卡片右下角。 | — | 代码复核过。rig 里接口返回 200，但不能生成微信码，所以截图里没有码。 | 码图本身 |
| V1-P59 邀请好友动效（IqRophy） | fixed | 邀请记录汇总卡「累计邀请 N 人」下面加了一排叠放的被邀请人头像，最多 5 个，后一个压住前一个，外圈是卡片底色。头像只来自 `/api/user/invite_list` 的真实记录，没有记录时不显示。入场时头像从左边一个接一个叠进来。 | `subpackageA/components/scene-member-invite-history/index.{js,wxml,wxss}` | `tests/unit/qx-a7-invite-motion.test.js`（4 项）和 `myinvite-load-state-contract`（15 项）都通过。截图：`V1-P59-before/after`，`V1-P59-empty-after`（没有邀请的账号仍是原来的空态）。 | 真机动效手感 |
| G-11 页面动效（uixsiam09） | fixed | 只用过渡（transition），沿用 `utils/page-motion.js` 的 `--pm-*` 约定，没有新增关键帧：进入邀请记录页时，正文整块从下方推上来（160rpx，0.42s，`--cy-ease-page-rise`）；汇总卡和每月一张的记录卡像聊天卡片一样依次升起；只在第一次拿到数据时播放，刷新和翻页不重播。打开「减少动态效果」（`play_reduced_motion`）或系统 `prefers-reduced-motion` 时不播放。动效只改 `translate` 和 `filter: opacity()`，不碰按压反馈。 | `subpackageMember/myinvite/myinvite.{js,wxml,wxss}`，以及上面的组件 | 在 rig 里从收益页真实跳转（`wx.navigateTo`）后，按 120/220/320/470 ms 拍下 `G-11-motion-1..4-after`：第 1 帧正文半透明、偏下；第 2 帧头像只叠进来两个，月份卡还在上升；第 3 帧四个头像都到位；第 4 帧静止。测试里也覆盖了「先隐藏再上浮、刷新不重播、减少动效时不播放」。 | 真机 60fps 下的流畅度 |

## 需要业主确认（needs owner）

1. **漫游足迹分享卡，拿不到的数字显示成 0。** 漫游分享（`pages/roam/index.wxml` 中 C2 分享 sheet 的 `rsh-v`，以及导出 `_drawShareCard`）里，取不到的数字现在显示为 0。这是 2026-09-18 的业主裁决 UI-04（推翻了原来 R9-42 的「—」）。本轮总纲写的是「未知不能显示成 0」，两条冲突。**默认做法：沿用 UI-04，没有改。** 如果改用新口径，要同时改预览、导出图、`_shareCopy` 文案和相关测试。
2. **漫游足迹分享仍是漫游页自己的 sheet。** 它有「谁能看到」和「发到广场」这两个真开关，P-8 当时批准它保留在漫游页那套里，所以没有并进 `cy-share-card`，取色也就没有覆盖到它（它也没有封面）。**默认做法：维持 P-8。**
3. **「谁能看到」下面的提示行。** 这行（「全站可见 / 仅好友可见 / 仅存相册」）属于总纲里的辅助说明。它不在本条点名的元素范围内，**默认做法：保留。**

## 跨 lane（Cross-lane）

无。没有改到 C00 保留文件；`scripts/uiaudit/action-ledger.json` 是由 `build-action-ledger.js --write` 重新生成的，因为加了新的 `cy-avatar`。

## 闸门

- `node --test`：`qx-a7-share-tint`、`qx-a7-invite-motion`、`myinvite-load-state-contract`、`round9-m2b`、`round9-m1-address-share-profile`、`uiux-player-state-adoption-contract`、`mainpkg-leaves-contract` 全部通过。
- 行为台账已重建，`ui-integrity` 的结果见 PR 正文。
- 主包大小：主包里只新增了 `utils/share-tint.js`（2.5 KB），分享卡多了约 1.6 KB。新页面为零。

## Rig

- 用的是 `claude/cloud-walk-20261101-rig` 的 worktree，并打上了 `claude/cloud-walk-20261101-club` 的 `harness-patches.diff`。下面这些改动只在 rig 里，没有进产品代码：
  - `up.sh` 的候选 SHA 改为可以用环境变量 `RIG_SHA` 指定；
  - Docker Hub 返回 429 限流，所以 MySQL 和 Redis 改从 `mirror.gcr.io` 拉取，digest 相同；改的是候选 worktree 里的 `ci/real-storage-it-registry.json`，这个 worktree 不会推送；
  - `app.py` 透传一次性的 `CHENGYIN_OSS_*`。主题详情接口在缺少 OSS 配置时会报 500，用的是假值；
  - `walk.mjs` 会拦截可信 OSS 主机，用本地生成的一张封面图应答，请求不会离开容器；
  - 运行时的 `createImage` 设置了 `crossOrigin`。
- 数据（只在一次性测试库里）：
  - 用 `seed-walk.py add-players 4` 通过真实登录建了 4 个玩家，再直接在测试库里把它们的 `invite_user_id` 写成 9004，作为邀请关系夹具；
  - 主题 990014 来自 `chains.py`。这次 chains 的 sale_close 失败了，但主题已经建好，不影响分享页。
- before 截图用的是 master 打的包；after 截图在同一后端上，用本分支重新打包后拍的。
- 打开分享层用的是页面真实的 `openShareCard()` / `shareMedal()`，通过 `eval` 调用：rig 里主题详情的分享按钮不在可点状态。
- **HARNESS 限制：**
  - 小程序码要调微信接口，rig 生成不了，所以所有分享卡截图都没有码；
  - 活动分享（官方活动）在全新的库里没有数据，没有拍；它和主题、店铺用的是同一个组件、同一条取色代码。

STATUS: COMPLETE
