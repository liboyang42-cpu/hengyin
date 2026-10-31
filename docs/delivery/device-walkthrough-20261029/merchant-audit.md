# 商家版核对（M-01～M-35，2026-10-29）

lane `l6-merchant-audit`，分支 `feature/dw29-l6-merchant-audit`，基线 `integrate/device-walkthrough-20261029`（master `37181a41`）。

依据：`previous-fix-doc-20261027.md` 第 3 节、截图 `shots-merchant/m0676`～`m0702`、10-27 验收清单（`docs/delivery/device-walkthrough-20261027/验收清单.md`），以及 10-27 之后合入的提交（#641 集成、#643 v8 第二轮 `13f701ff`、模板页 v21 `5900ac96`）。每一条都在基线源码上重新核对过，行号以本分支为准。

- ✅ 已修：代码里已经有，附文件和行号。
- 🔧 本轮补：没改或改得不对，本分支按 10-27 方案补上。
- 🎨 待原型：业主说「重新设计」，原型还没确认（L10 #627 原型未合入）。
- ⛔ 需业主：要后端新接口、线上数据或部署，不在前端能做完的范围。
- ↪ 他 lane：本轮归别的 lane（媒体门禁 l5、帖文样式原型 lane），这里不动。

| 编号 | 截图 | 结论 | 依据（文件:行） | 说明 |
|---|---|---|---|---|
| M-01 | m676 | ✅ 已修 | `chengyinhub-xcx/subpackageB/pages/im/chat/index.wxml:1`、`:8`、`:45`、`:182` | 商家视角根节点 `theme-merchant`；标题进导航栏、去「更多」；整宽通知卡（10-28 v8 又按 iMessage/Gmail 加大间距）；底部只留一行说明 |
| M-02 | m677 | ✅ 已修（被 v21 取代） | `chengyinhub-xcx/pages/templatedetail/shelf/index.wxss:41-45` | 10-27 的对齐修复在 `pages/template/index.wxss`，10-28 模板页按业主定稿原型 v21 整块移进 `tpl-shelf`，搜索框与 tab 同一 88rpx 行，药丸居中 |
| M-03 | m678 | 🎨 待原型 | `chengyinhub-xcx/pages/merchant/customer/index.wxml` | 「筛选客户」重做要先出原型（10-27 L10 `m03-m04-customer.html`，未合入）。测试分群是线上数据，见 E-3 |
| M-04 | m679 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/customer/index.wxml:102` | 客户管理改成操作列表，10-28 v8 统一成 Threads 式菜单 |
| M-05 | m680 | ✅ 已修 | `chengyinhub-xcx/subpackageA/pages/assetcenter/earnings/index.wxml:2`、`:12`；`chengyinhub-xcx/pages/merchant/index/index.js:1453` | G-9 已在 v8 落地：商家「财务」进同一个账户收益页浅色版（`theme=merchant`） |
| M-06 | m681 | ✅ 已修 | `chengyinhub-xcx/subpackageB/pages/im/list/index.wxml:1`、`:28` | 商家浅色；顶部协作邀请横滑卡片带，点开进协作邀请 |
| M-07 | m682 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/coupon/coupon.wxml:41`、`:47` | 空态提示 + 常驻「发布优惠券」主按钮 |
| M-08 | m683 | 🔧 第二轮（真机未修） | `chengyinhub-xcx/pages/crop/index.wxml:44`、`index.wxss:63-75`；`chengyinhub-xcx/utils/wxs/img.wxs:23`；各页 Logo 渲染点见 M-11 | 见下方「第二轮」：遮罩改到台面级、圆外整片压暗；Logo 的 http 签名链接各页统一换 https |
| M-09 | m684 | 🔧 第二轮（最小修，重做仍 🎨） | `chengyinhub-xcx/pages/crop/utils/crop-geometry.js:67`、`chengyinhub-xcx/pages/crop/index.js:154`、`index.wxss:115-126` | 取景框居中留边，露出整图（框外压暗），「选取」变小；整页重做等原型 |
| M-10 | m685、m686 | ✅ 已修 | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/UploadImagePolicy.java:53`；`chengyinhub-xcx/pages/merchant/decor/index.js:847` | 后端加 `image_5_3`，封面上传走 5:3；保存后作主页背景（M-17） |
| M-11 | m687 | 🔧 第二轮（真机未修） | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:6`、`:46`；`chengyinhub-xcx/pages/merchant/index/index.wxml:59`、`:63` | 真因是 Logo 链接为 http，见「第二轮」 |
| M-12 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:434`；`chengyinhub-xcx/pages/merchant/decor/index.wxml:181`；`chengyinhub-xcx/utils/tag-library.js:2` | 商家标签库：图标 + 实底、无灰描边 |
| M-13 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1081`；`chengyinhub-xcx/pages/merchant/index/index.js:147` | 商家视角名称只取 `/api/merchant/info` 的商家名，改名经 identity-sync 广播 |
| M-14 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:153` | 只留箭头（商家视角整段订单不出，见 M-25） |
| M-15 | m687 | ↪ l5 | — | 点帖文的报错是媒体门禁（G-3）。10-29 文档第 0 节：后端门禁仍拒 UGC，归 lane l5 |
| M-16 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:121` | 本人视角删掉「核销」右边的 ⋯ |
| M-17 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1112`、`:1200` | 背景读店铺装修封面 |
| M-18 | m687 | 🎨 待原型 | `chengyinhub-xcx/pages/merchant/team/` | 经营团队重排（L10 `m18-team.html`，未合入） |
| M-19 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:419`；`chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:584` | 本人视角不出「关注」「发起合作」 |
| M-20 | m688 | ✅ 已修 | `chengyinhub-xcx/components/cy/cell/index.wxss:51-54` | G-7：行右侧单行省略 |
| M-21 | m689 | 🔧 第二轮（真机未修） | `chengyinhub-xcx/pages/merchant/marketing/ai-insight/index.js:247`、`:294`；`index.wxml:26`；`index.wxss:18-19`、`:164`、`:169` | 空格颜色看不见、店名/Logo 缺时补读、Logo 换 https，见「第二轮」 |
| M-22 | m690 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/marketing/index.wxml:36`；`index.wxss:46`、`:323`；`index.js:144` | 首帧即固定高度骨架，刷新提示不再挤动版面 |
| M-23 | m690 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/marketing/index.js:35`、`:313`；`chengyinhub-xcx/pages/merchant/utils/recruit-routes.js:1`；`chengyinhub-xcx/pages/merchant/coop-center/index.js:44` | 「发活动」；开放商机计数与合作中心列表同源 |
| M-24 | m692 | ✅ 已修（邀约绑定场次 ⛔） | `chengyinhub-xcx/pages/club/detail/index.wxml:2`、`:185-188`；`index.js:2611` | 商家可入团、浅色、活动卡「邀约承接」。邀约还没绑定到具体那一场活动，需要后端支持 |
| M-25 | m693 | 🔧 本轮补 | `chengyinhub-xcx/subpackageMember/order/order.js:16-22` | 10-27 只在「我的」页把入口改去我的项目；截图里商家仍能进黑色「我的订单」页。本轮：订单页 `onLoad` 先问视角，商家视角 `redirectTo` 我的项目（`scope=MERCHANT`），不渲染订单 |
| M-26 | m694 | ✅ 已修（业主：不要复制） | `chengyinhub-xcx/subpackageA/pages/myproject/index.wxml:120-128` | 长按菜单只有编辑、收到的申请、下架、取消、审核记录、另存为模板、删除，没有「复制」；单测锁住 |
| M-27 | m695 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/index/index.wxml:56`；`index.wxss:121`、`:128`；`index.js:146` | 头像去描边、换 Logo 即时刷新、收紧「累计收入」上方空白 |
| M-28 | m696–m698 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/decor/ai-npc/index.wxml:7`、`:22`、`:68`、`:171`、`:282`；`chengyinhub-system/src/main/java/com/chengyinhub/business/support/NpcPersonality.java` | 门店信息版式、首次说明、性格三选一、麦克风权限引导；后端读 `personality`。「配置是否生效」要在测试后端 8132 回测 |
| M-29 | m699 | ↪ l5 | — | 门店相册媒体门禁。10-29 文档第 0 节确认后端仍拒 UGC，归 lane l5 |
| M-30 | m700 | 🔧 本轮补 | `chengyinhub-xcx/pages/merchant/decor/index.wxml:193-197` | 截图是品牌中心「合作经营」页（`decor/index` 的 coop 视图），10-27 只改了二级页 `coop-setting`。本轮：「免费承接」摘要放进「承接设置」行内，`dc-heading` 已是 `space-between`，标题左、摘要右对齐，与下面「合作诉求」一致 |
| M-31 | m701 | ✅ 已修（迁移 ⛔） | `chengyinhub-xcx/pages/merchant/decor/index.wxml:114`；`chengyinhub-admin/sql/migration_20261027_merchant_brand_images_to_gallery.sql` | 审核通过后不显示「入驻资料」。品牌图并入门店相册的迁移要业主在测试库预检后执行、再部署 |
| M-32 | m702 | 🔧 第二轮（业主：要的） | `chengyinhub-xcx/components/cy/earnings-overview/index.js:46`、`:55`；`chengyinhub-xcx/utils/earnings-view.js:46`、`:56`；`chengyinhub-xcx/pages/merchant/ledger/index.wxml`（删去原 `:10`） | 俱乐部计酬并进「本月收入明细」作单独来源行，结算明细页的「俱乐部计酬」卡删除，见「第二轮」 |
| M-33 | 商家总述 | 🎨 待原型 / ↪ | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:247-250` | 媒体报错归 l5。商家主页帖文 tab 的空态没有发帖入口：「我的页」发帖入口属于 P-20，帖文样式属于 P-1 原型，归帖文原型 lane |
| M-34 | 商家总述 | ✅ 已修（同 M-24） | 同 M-24 | 同 M-24 |
| M-35 | 商家总述 | 🔧 本轮补（部分） | `chengyinhub-xcx/components/cy/scene-asset-earnings/index.js:174`；`chengyinhub-xcx/subpackageA/pages/assetcenter/income-detail/income-detail.wxml:6`、`:13`；`income-detail.js:7-11`；`income-detail.wxss:1`、`:10` | 遮罩和骨架在商家视角已浅色（`components/cy/loading-mask/index.js:14`、`components/cy/skeleton/index.js:22`），「我的」页占位也已按视角切换（`components/cy/profile-loading/index.wxml:1`）。漏掉的一处：商家账户收益 →「明细」进的收益明细页根节点写死 `theme-dark`，整页连加载骨架都是黑的。本轮：打开时带 `theme=merchant`（与「分析」页同一做法），页面根节点和原生导航栏按视角切换浅色 |

## 本轮改动

| 编号 | 文件 | 改动 |
|---|---|---|
| M-25 | `chengyinhub-xcx/subpackageMember/order/order.js` | `onLoad` 先调用 `roleGuard.isMerchantView()`，商家视角 `wx.redirectTo('/subpackageA/pages/myproject/index?scope=MERCHANT')` |
| M-30 | `chengyinhub-xcx/pages/merchant/decor/index.wxml` | 承接摘要从标题下方移到标题行内，右对齐 |
| G-10 / M-35 | `chengyinhub-xcx/components/cy/scene-asset-earnings/index.js`、`chengyinhub-xcx/subpackageA/pages/assetcenter/income-detail/income-detail.{js,wxml,wxss}` | 商家进收益明细带 `theme=merchant`；页根 `theme-merchant`、组件 `theme="merchant"`、原生导航栏浅色；玩家不变 |

验证：`node --test chengyinhub-xcx/tests/unit/dw29-l6-merchant-audit.test.js`（新增 5 条）；`activity-detail-single-source-contract` 的订单页 vm 桩补了 `roleGuard`（玩家视角）；`node chengyinhub-xcx/tests/lint/ui-integrity.js` 结果为 `UI-GATE-0:PASS`；动作台账用 `build-action-ledger.js --write` 重建，控件数不变（5552）。

## 需要业主

1. M-24 / M-34：「邀约承接」要绑定到具体那一场活动，需要后端在邀约上带活动 id。
2. ~~M-26「复制」~~：业主 10-29 裁决不要复制，已关闭。
3. M-31：在测试库对 `migration_20261027_merchant_brand_images_to_gallery.sql` 走 chengyin-db-migrate 预检，确认后执行并部署 jar。
4. ~~M-32~~：业主 10-29 裁决「要的」，第二轮已并进本月收入明细。
5. M-03、M-09、M-18：原型（10-27 L10 #627）确认后再写代码（M-09 第二轮只做了整图可见、框居中、按钮变小）。
6. M-28：在测试后端 8132 回测改性格后玩家侧对话是否按新性格回复。
7. M-35：前端只能按代码找写死深色的页。真机上如果还有商家看到黑色加载的页面，请记下是哪一页。

## 第二轮（2026-10-29 业主真机复查）

业主真机上判 M-08、M-11、M-21 未修，裁决 M-32「要的」、M-26「不要复制了」。这一轮按商家真实渲染链路重新查：视角切换、商家浅色主题、商家实际看到的组件、绑定字段、图片地址，以及身份缓存。

### 真因：商家 Logo 一律是 http 链接，商家各页直接绑定

- 后端出参经 `AppMediaOutputConfiguration` 把 `logo` / `coverImage` 这类媒体字段按媒体登记重签（`AppMediaAssetService.approvedUrl` 和 `systemImageUrl` → `FileOssUploadUtils.generatePresignedUrl`）。重签用的 OSS 客户端是默认 `ClientConfiguration`，协议是 http，所以每次读回来的 Logo 都是 `http://chengyin-oss.oss-cn-shanghai.aliyuncs.com/...?Signature=...`。新上传时 `upload-client` 已经换成 https，但保存后再读就又变回 http。
- 真机不加载 http 图片（#671 已为 `app.getImgUrl` 和 `img.wxs` 补过这条）。商家首页头像、「我的」/主页头像和背景、品牌中心 Logo/封面/弹窗预览、店铺参谋头像这几处都是直接 `src="{{logo}}"`，没走 `getImgUrl` 或 `img.wxs`，所以 Logo 空白。换完 Logo 后，广播和回读其实都到了，只是新图同样是 http，所以「别的页没更新」。
- 消息页（`subpackageB/pages/im/list`）没有本人头像位，会话头像是对方的；系统通知会话的 `myAvatar` 也没有渲染，所以这页不需要改。

### 改动

| 编号 | 真因 | 改动 | 文件:行 | 测试 |
|---|---|---|---|---|
| M-08 / M-11 | 同上：Logo 和封面是 http 签名链接，直接绑定在真机上不显示 | `img.wxs` 新增 `one()`，单图字段只把自有域名 http 换成 https，签名原样保留。商家 Logo 和封面的各渲染点都改走 `img.one` | `utils/wxs/img.wxs:23`；`pages/merchant/index/index.wxml:59`、`:63`；`subpackageMember/components/cy/profile/index.wxml:6`、`:46`、`:722`；`pages/merchant/decor/index.wxml:75`、`:82`、`:533`、`:561`；`pages/merchant/marketing/ai-insight/index.wxml:26`、`:275` | `dw29-l6-merchant-r2`：img.one 换协议，渲染点全部经 img.one |
| M-08 圆框 | 圆形遮罩挂在 `overflow:hidden` 的取景框里面，只压暗框内四角；框本身贴满屏宽，四角和圆几乎重合，真机上看不出圆 | 取景框去掉 `overflow:hidden`，遮罩改成 `crop__mask`（Logo 时加 `--round`），用 box-shadow 把圆外和框外整片压暗，由取景台的 overflow 收住 | `pages/crop/index.wxml:44`；`pages/crop/index.wxss:56-75` | 遮罩在图之后、圆角、框不裁 |
| M-09（最小） | 取景框宽度等于屏宽、cover 满框、框外裁成纯黑，横图两边和竖图上下都看不到（「顶部被裁掉」）；「选取」是 88rpx 高的实心块 | `fitFrame`：框四边留 24px 居中；cover 图比取景台大时整体缩框，露出整图（框最小留一半），框外压暗显示。导出几何不变。「选取」命中区仍是 88rpx，实心只画在 64rpx 内容盒，字号降到正文 | `pages/crop/utils/crop-geometry.js:64-79`；`pages/crop/index.js:18-19`、`:154`；`pages/crop/index.wxss:115-126` | 几何三例和导出矩形；`c01-home-search-state-contract` 桩补 `fitFrame` |
| M-21 方块 | 方块其实每次都画了，但空格色 `#EDEDF2` 画在白卡上几乎看不见。全零的店整片都是空格，看起来就是「没有方块」 | 逐日格的空格改用 `--mi-heat-off #DCDDE2`，1 档随之加深成 `--mi-heat-1 #C2C3CA`；其他用 `--mi-off` 的空进度不变 | `pages/merchant/marketing/ai-insight/index.wxss:18-19`、`:164`、`:169` | 全零 30 格、三数 0、比率 0% |
| M-21 店名/Logo | 店名和 Logo 只取参谋包的 `profile.storeName/logo`。10-27 截图里是「Hi，店主」，说明当时后端回包没带店名（旧 jar，或商家行没按会员号查到）。现在缺了就只剩「Hi」和空圈。另外 Logo 也是 http | 头像绑 `storeLogo`，经 `img.one`。参谋包缺店名或 Logo 时，补读首页和「我的」同一来源 `/api/merchant/info`，只补空的那一项，换号/离页后的回包不落地 | `pages/merchant/marketing/ai-insight/index.js:75`、`:218`、`:247`、`:268`、`:294-312`（去掉 WXML 已不消费的 `profile` 字段）；`index.wxml:26` | 补读只补空项、过期回包丢弃 |
| M-32 | 业主裁决「要的」 | `cy-earnings-overview` 在商家视角另读 `/api/club-direct-merchant/merchant`，经现成的 `ledgerView` 校验后，把本月「待结清」「已结清」两类记录并进「本月收入明细」，按时间倒序。每行标「俱乐部计酬 · 待结清/已结清」，标题「核销 #id」。不计入余额口径合计。删掉结算明细页的 `cy-club-direct-ledger`；俱乐部侧的「商家计酬」不动。商家余额为 0 时也显示明细（不出玩家的「去发布路线」空态），否则看不到计酬。权限矩阵登记这处 GET（109 → 110） | `components/cy/earnings-overview/index.js:3`、`:20-21`、`:46-72`、`:84-87`；`index.wxml:18`；`utils/earnings-view.js:40-60`；`components/cy/scene-asset-earnings/index.wxml:22`、`:24`；`pages/merchant/ledger/index.{wxml,json}` | 只取本月两态、按来源标注、合并顺序、组件到达顺序、403 不出重试、玩家不读 |
| M-26 | 业主裁决「不要复制了」 | 没有「复制」入口，不改代码，用单测锁住菜单项 | `subpackageA/pages/myproject/index.wxml:120-128` | 菜单 data-k 白名单 |

没有做后端聚合：前端用现成接口和现成的 `ledgerView` 校验就能合并，不用新增后端代码。

验证：`node --test chengyinhub-xcx/tests/unit/dw29-l6-merchant-r2.test.js`（新增 11 条）；受影响的既有契约同步更新：`uiux-merchant-secondary-state-contract`（参谋夹具带店名/Logo，补读不多发请求）、`merchant-workbench`（hero 头像经 img.one）、`action-ledger`（控件 5552 → 5553、tap 2413 → 2414）、`identity-sync-l08`（遮罩类名）、`merchant-insight-redesign-20261022`（头像绑定）、`scene-asset-earnings-contract`（overview 带 merchant）、`get-auth-matrix-contract`（新 GET 入矩阵）、`c01-home-search-state-contract`（crop 几何桩）。
