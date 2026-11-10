# r3-share 结果：分享页改成整色卡（参考 refs4/share-page-cards.jpg）

- 分支 `claude/r3-share-20261110`，从 `origin/claude/r2-finish-20261110`（`0073a0b4`，r2-finish 已写 STATUS）切出。draft PR #767 合入 r2-finish 分支。
- 只改小程序，没有改后端、支付、退款、结算，没有新迁移。
- 截图在 `shots/`：390 宽、JPEG q70，都是走查 rig 里的真实页面加真实接口。before 用 r2-finish 的小程序包，after 用本分支的包，其余条件都一样。每张图我都看过。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| 通用分享层（主题 / 勋章 / 店铺 / 活动） | fixed | 照参考图改成「一种内容一张整色卡」，从上到下是：<br>1. 上半是封面；勋章卡是暖光加勋章本身；没有封面时用我们自己画的路线插画。<br>2. 下沿一道弧，城瘾徽标（白底圆角方块）压在弧顶。<br>3. 眉题胶囊（主题 · 章数 · 时长 · 距离 / 店铺 / 活动 / 勋章）。<br>4. 两行大标题，按词折行，放不下加省略号。<br>5. 一行副信息：主题写站点，活动写日期和时间，店铺写地址，勋章写说明。<br>6. 右下是小程序码，左下是「城瘾」字标。<br>卡下面是：色块圆点（P-8 换底色保留，封面取色照旧）→ **一颗白色主按钮「发给微信好友」** → 其余渠道（朋友圈、小红书、保存图片、复制文案、更多）缩成一排小圆钮，不再横滑。<br>页面底是压暗的同色，整色卡更突出。<br>进场时卡片上浮、徽标弹入、文字上浮，只用 transform / opacity；减弱动态时全部关闭。<br>导出图和预览是同一版式（同一个椭圆弧、徽标、折行）。 | `components/cy/share-card/index.{js,wxml,wxss}`、`components/cy/share-card/route-art.png`（新增，自绘，9.8 KB）、`utils/share-lines.js`（新增，标题折行共用） | rig 截图：<br>- 主题：`share-theme-before/after`<br>- 店铺：`share-shop-before/after`<br>- 勋章：`share-medal-before/after`<br>- 导出图：`share-theme-export-after`，在 rig 里点「保存图片」后从隐藏画布读出来的真实导出图<br>单测：`r3-share-cards.test.js`（新增，覆盖折行、各类卡的眉题和副信息、版式合同、只有一颗主按钮），`qx-a7-share-tint`、`round9-m2b` 按新版式改了断言 | 导出图在真机上的「保存相册 / 朋友圈 / 更多」；小程序码（rig 的出码接口不返回图，所以截图里没有码）；带封面的样子（见待你决定 1）；**活动卡没截图**（rig 没有官方活动夹具，组件与其他三类同一套） |
| 结算分享卡（r2-finish 结算第 4 屏） | fixed | 分享卡改成同一版式：<br>- 整色卡，上半是路线虚线加金色对勾印章，弧形下沿加徽标。<br>- 下面是「已通关」胶囊、两行大标题（主题名）、成绩一行（节点 / 分数 / 名次，都是战报事实，没有的不画）、「城瘾」字标。<br>- 帖文 4:5 和故事 9:16 都适配。<br>- 视频录制用的动画帧跟着改：印章 → 徽标 → 文字依次弹入、上浮。<br>- 按钮：「分享」仍是唯一主按钮，「保存图片」和「分享视频」并排放在它下面。<br>- 结算流程本身（总览 → 排行榜 → 评价 → 分享）没动。 | `subpackageJourney/utils/share-card.js`、`subpackageJourney/pages/settle/index.{js,wxml,wxss}` | 在 rig 里真实通关：玩家 9004 到两站并答对「3」，战报返回已通关、100 分、第 1 名。截图：`settle-share-before/after`（帖文）、`settle-share-story-before/after`（故事）。单测：`r3-share-cards.test.js`（只画事实、徽标缺失照样出卡、动画帧） | 视频录制和微信视频分享（rig 不能录）；保存到相册 |

## 待你决定

1. **没有封面时卡片上半放什么**：rig 里的主题封面和店铺封面都是占位 OSS 地址，被小程序的可信域名检查挡掉了，所以截图里都是「没有封面」的状态。**默认做法：用我们自己画的路线插画**（虚线路线、站点、定位针，透明底，叠在卡片色上）。有真封面时，封面会铺满上半，弧和徽标照样压在上面。
2. **卡片颜色比参考图暗**：参考图是亮粉、荧光绿、亮紫。我们沿用 P-8 已批准的四个色块和封面取色，取色会压暗到白字能看清的亮度。**默认做法：保持这套暗一档的整色**（玩家域是深色）。要更亮的话，需要改成深色字，或者给出一套新色板。
3. **主题卡的站点**：原来卡上是竖排的编号路线，新版式放不下。**默认做法：改成一行「外滩 · 豫园 · …」**，超出加省略号。
4. **结算卡底色**：战报里没有封面，所以结算卡固定用主题卡的第一个色块（深红），不做取色。
5. **关闭按钮位置**：分享层是全屏页，右上角是微信胶囊。**默认做法：关闭按钮留在左上**，和原来一样。

## 门禁

- 单测通过：`r3-share-cards`（新增 4 例）、`qx-a7-share-tint`、`round9-m2b`、`r2-finish-settlement`、`round9-m1-address-share-profile`、`uiux-player-state-adoption-contract`、`topic-share-card-contract`、`roam-session-share-card`。
- 按文件名挑了一批相关单测（share / settle / journey / asset / action-ledger / title 等，共 682 例）一起跑，有 5 例失败，都在 `action-ledger.test.js` 和 `title-system-owner-contract.test.js`。在 r2-finish 基线工作树上对比这两个文件的结果见下方「基线对比」。
- 动作台账：用 `build-action-ledger.js --write` 重建，共 5893 条，和基线条数相同。
- UI 门禁：`node tests/lint/ui-integrity.js` 输出 `UI-GATE-0:PASS`。中途报过 3 个零消费字段（date / when / stops），已在代码里删掉，没有加基线。
- 主包体积：`mainpkg-size-report.js` 显示本分支 3,244,091 字节，约 2005.4 KiB（< 2048）；基线 3,233,159 字节，约 1998.6 KiB。多出的 10.9 KB 来自路线插画和折行工具（插画已降到半分辨率）。
- 没有跑 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

### 基线对比

（见下方补充）

## rig

- 搭法：
  - 走查 rig 用 `claude/cloud-walk-20261101-rig`，加上 staging 的 `rig-up-sha-param.diff` 和 `rig-app-oss-seam.diff`（OSS 用占位值，不是凭据），再加 club 分支的 `harness-patches.diff`。
  - Docker Hub 限流，所以 MySQL / Redis 按同一个固定 digest 从 mirror.gcr.io 拉下来，再在本地打标签。
  - 后端 jar 用 r2-finish 分支构建（本 lane 不改后端）。
- 夹具：staging 的 `free_orient_theme.py`（主题 990014）。玩家 9004 在 rig 里真实走完两站、答对，用于结算页。店铺卡用商家 9002 的主页。
- rig 里的注入，都只在 rig、不进产品代码：
  - `rig/walk-fake-oss-seam.diff`：让占位 OSS 域名返回一张生成图。最后小程序的可信域名检查还是把它挡掉了，所以对截图没有影响，只是留档。
  - 主题分享：rig 的页面滚动到不了详情第二屏，所以用 eval 直接调页面自己的 `openShareCard()`。数据都是接口真值。
  - 勋章分享：rig 里没有已获得的勋章，所以在漫游页 setData 一个勋章名和说明（「外滩夜行者」），再调页面自己的 `shareMedal()`（脚本 `rig/medal-share.mjs`）。**勋章名和说明是注入的**，分享层本身是真实运行的。
  - 导出图：`rig/export-canvas.mjs` 在点「保存图片」后读出隐藏画布。

## Cross-lane

- `components/cy/share-card` 是主包共享组件，有四个宿主页：主题详情、官方活动、漫游（勋章）、商家主页。宿主页一行没改；`card` 入参不变，`data-share-kind="card"` 也照旧。
- `subpackageJourney/pages/settle` 和 `utils/share-card.js` 是 r2-finish 的新文件。本 lane 只改了分享卡的画法和分享步骤的按钮排布。

STATUS: PARTIAL - 主题、店铺、勋章和结算分享卡都已改完并在 rig 截图；活动分享卡没有夹具，未截图；带真封面的样子、小程序码、保存相册和视频分享需要真机验证
