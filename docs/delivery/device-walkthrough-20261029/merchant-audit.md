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
| M-08 | m683 | ✅ 已修 | `chengyinhub-xcx/pages/crop/index.wxml:43`、`chengyinhub-xcx/pages/crop/index.wxss:63`、`chengyinhub-xcx/pages/merchant/decor/index.js:847`、`chengyinhub-xcx/pages/merchant/decor/index.wxml:535` | 圆形取景框；Logo 预览居中正圆，没有图时不留空白。同步见 M-11/G-5 |
| M-09 | m684 | 🎨 待原型 | `chengyinhub-xcx/pages/crop/` | 裁剪页重做（L10 `m09-crop.html`，未合入） |
| M-10 | m685、m686 | ✅ 已修 | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/UploadImagePolicy.java:53`；`chengyinhub-xcx/pages/merchant/decor/index.js:847` | 后端加 `image_5_3`，封面上传走 5:3；保存后作主页背景（M-17） |
| M-11 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1081`、`:1112`；`chengyinhub-xcx/utils/identity-sync.js` | 本人商家视角头像读商家 Logo，换 Logo 后广播刷新 |
| M-12 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:434`；`chengyinhub-xcx/pages/merchant/decor/index.wxml:181`；`chengyinhub-xcx/utils/tag-library.js:2` | 商家标签库：图标 + 实底、无灰描边 |
| M-13 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1081`；`chengyinhub-xcx/pages/merchant/index/index.js:147` | 商家视角名称只取 `/api/merchant/info` 的商家名，改名经 identity-sync 广播 |
| M-14 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:153` | 只留箭头（商家视角整段订单不出，见 M-25） |
| M-15 | m687 | ↪ l5 | — | 点帖文的报错是媒体门禁（G-3）。10-29 文档第 0 节：后端门禁仍拒 UGC，归 lane l5 |
| M-16 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:121` | 本人视角删掉「核销」右边的 ⋯ |
| M-17 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1112`、`:1200` | 背景读店铺装修封面 |
| M-18 | m687 | 🎨 待原型 | `chengyinhub-xcx/pages/merchant/team/` | 经营团队重排（L10 `m18-team.html`，未合入） |
| M-19 | m687 | ✅ 已修 | `chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:419`；`chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:584` | 本人视角不出「关注」「发起合作」 |
| M-20 | m688 | ✅ 已修 | `chengyinhub-xcx/components/cy/cell/index.wxss:51-54` | G-7：行右侧单行省略 |
| M-21 | m689 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/marketing/ai-insight/index.js:300`、`:426`；`chengyinhub-xcx/pages/merchant/marketing/ai-insight/index.wxml:17`；`index.wxss:78` | 方块图每次画、数字写 0、真实店名和 Logo、问候行与头像居中对齐 |
| M-22 | m690 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/marketing/index.wxml:36`；`index.wxss:46`、`:323`；`index.js:144` | 首帧即固定高度骨架，刷新提示不再挤动版面 |
| M-23 | m690 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/marketing/index.js:35`、`:313`；`chengyinhub-xcx/pages/merchant/utils/recruit-routes.js:1`；`chengyinhub-xcx/pages/merchant/coop-center/index.js:44` | 「发活动」；开放商机计数与合作中心列表同源 |
| M-24 | m692 | ✅ 已修（邀约绑定场次 ⛔） | `chengyinhub-xcx/pages/club/detail/index.wxml:2`、`:185-188`；`index.js:2611` | 商家可入团、浅色、活动卡「邀约承接」。邀约还没绑定到具体那一场活动，需要后端支持 |
| M-25 | m693 | 🔧 本轮补 | `chengyinhub-xcx/subpackageMember/order/order.js:16-22` | 10-27 只在「我的」页把入口改去我的项目；截图里商家仍能进黑色「我的订单」页。本轮：订单页 `onLoad` 先问视角，商家视角 `redirectTo` 我的项目（`scope=MERCHANT`），不渲染订单 |
| M-26 | m694 | ✅ 已修（「复制」⛔） | `chengyinhub-xcx/subpackageA/pages/myproject/index.wxss:91`；`index.js:487`、`:1073` | 操作列表（编辑、删除标红），v8 统一 Threads 式菜单。「复制」后端没有复制主题/活动的接口。返回键见 G-2（已做） |
| M-27 | m695 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/index/index.wxml:56`；`index.wxss:121`、`:128`；`index.js:146` | 头像去描边、换 Logo 即时刷新、收紧「累计收入」上方空白 |
| M-28 | m696–m698 | ✅ 已修 | `chengyinhub-xcx/pages/merchant/decor/ai-npc/index.wxml:7`、`:22`、`:68`、`:171`、`:282`；`chengyinhub-system/src/main/java/com/chengyinhub/business/support/NpcPersonality.java` | 门店信息版式、首次说明、性格三选一、麦克风权限引导；后端读 `personality`。「配置是否生效」要在测试后端 8132 回测 |
| M-29 | m699 | ↪ l5 | — | 门店相册媒体门禁。10-29 文档第 0 节确认后端仍拒 UGC，归 lane l5 |
| M-30 | m700 | 🔧 本轮补 | `chengyinhub-xcx/pages/merchant/decor/index.wxml:193-197` | 截图是品牌中心「合作经营」页（`decor/index` 的 coop 视图），10-27 只改了二级页 `coop-setting`。本轮：「免费承接」摘要放进「承接设置」行内，`dc-heading` 已是 `space-between`，标题左、摘要右对齐，与下面「合作诉求」一致 |
| M-31 | m701 | ✅ 已修（迁移 ⛔） | `chengyinhub-xcx/pages/merchant/decor/index.wxml:114`；`chengyinhub-admin/sql/migration_20261027_merchant_brand_images_to_gallery.sql` | 审核通过后不显示「入驻资料」。品牌图并入门店相册的迁移要业主在测试库预检后执行、再部署 |
| M-32 | m702 | ✅ 已修（结算页「俱乐部计酬」⛔） | `chengyinhub-xcx/subpackageA/pages/assetcenter/earnings/index.wxml:2`；`chengyinhub-xcx/pages/merchant/ledger/index.wxml:10` | v8：先进账户收益，「更多」里放结算明细。但结算明细页还挂着 `cy-club-direct-ledger`（标题「俱乐部计酬」，读 `/api/club-direct-merchant/merchant`）。这份直付账不在余额流水里，直接删会让商家看不到这部分收入；要并进收入明细，需要业主确认展示方式（G-9 原型后续） |
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
2. M-26：长按菜单的「复制」需要后端提供复制自己主题/活动的接口。
3. M-31：在测试库对 `migration_20261027_merchant_brand_images_to_gallery.sql` 走 chengyin-db-migrate 预检，确认后执行并部署 jar。
4. M-32：结算明细里的「俱乐部计酬」（俱乐部直付账）要并进「收入明细」，需要确认展示方式。在确认前不删，否则这部分收入就看不到了。
5. M-03、M-09、M-18：原型（10-27 L10 #627）确认后再写代码。
6. M-28：在测试后端 8132 回测改性格后玩家侧对话是否按新性格回复。
7. M-35：前端只能按代码找写死深色的页。真机上如果还有商家看到黑色加载的页面，请记下是哪一页。
