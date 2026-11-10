# r2-merchant 结果（2026-11-10）

分支 `claude/r2-merchant-20261110`，基线 `origin/staging/questo-review-20261110` 的 `6d51e844`（qx-s1 已写 `STATUS: COMPLETE` 之后）。草稿 PR #760，合入目标 `staging/questo-review-20261110`。没有合并、没有标为就绪，没有动 master、integrate、staging 或别的车道分支，没有碰生产，没有改支付 / 退款 / 结算逻辑，没有新增或修改迁移。

截图都在 `shots/`，390 px 宽、JPEG 约 q70，用走查 rig 拍：`claude/cloud-walk-20261101-rig` 加 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（占位值，不是密钥）和 club 分支的 `harness-patches.diff`。后端是本分支的 jar（本车道没有后端改动），库是一次性 MySQL/Redis。before 用 staging `6d51e844` 的小程序包，after 用本分支的小程序包，同一个库、同一个身份（商家老板 9002）、同一个页面状态。每张截图我都看过。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| a6-w2-07 | 已改 | 主题「客户」汇总格（已售 / 还没到店 / 已核销）没有数字就写 0，不再写「—」。主办和承接两种视图都改了。还没开售时下面那句「未售票」保留，业主只说了数字。 | `pages/topic/components/project-host/index.wxml`、`pages/topic/components/project-join/index.wxml` | 新测试 `tests/unit/r2-merchant-zero-counts.test.js`（3 项通过）；原有 `merchant-topic-fulfillment` 通过。截图 `a6-w2-07-before`（三个「—」）/ `a6-w2-07-after`（三个 0）。 | — |
| a6-w2-15 | 已改 | 店铺参谋「经营优化」四张卡改成横滑：卡宽 560rpx，右边露出下一张，和上面「为你推荐的主题」同一个横滑容器。删掉叠在封面右下角的 3D 图标（4 张 PNG 和它们的 MIT 许可文件一起删），卡上只剩封面、标题、一句话和按钮。按岗位权限决定显不显示哪张卡的逻辑没变。 | `pages/merchant/marketing/ai-insight/index.{wxml,wxss}`、`pages/merchant/utils/grow-tips.js`、`pages/merchant/images/grow/`（删 4 张 png 和 LICENSE，改 README） | `tests/unit/qx-a6-merchant-grow-intro.test.js` 改成断言横滑、没有图标（4 项通过）。截图 `a6-w2-15-before`（竖排大卡，带 3D 图标）/ `a6-w2-15-after`（横滑，右侧露出第二张）/ `a6-w2-15-after-2`（横向滑到第三张）。 | 真机横滑手感 |
| a6-w2-16（含 u2-w2-16） | 已改 | AI 店铺角色首次进入的四步介绍回到上一版（R12 定稿，原型 p7），也就是 logo 积木之前的版本：每步两个 3D 物件，图片从 git 历史 `6d1582bd^` 原样恢复，积木版的 6 张图和代码全部删掉。每一步一种动效，和这一步的话对得上：①「分身」店铺从下面升起，星光一闪一闪；②「打招呼」手绕手腕挥两下，气泡随后冒出；③「菜单和营业时间」餐盘从上面落下，闹钟左右摆两下；④「起名字、选性格」名牌翻正，面具转一圈换脸。每步光晕换色。只用 transform / opacity；系统开了「减少动态效果」就不播。只弹一次的存储标记 `cy_ai_npc_intro_seen` 没变。 | `pages/merchant/decor/ai-npc/index.{js,wxml,wxss}`、`pages/merchant/images/npc-intro/`（恢复 8 张 + README）、删 `pages/merchant/images/logo-blocks/` | 同一测试文件新增断言：不再有积木、四步物件和图片都在、四步动效两两不同、减少动态时不播（通过）。截图 `a6-w2-16-before-1..4`（积木）/ `a6-w2-16-after-1..4`（3D 物件，四步依次）；动效中间帧 `a6-w2-16-after-2-frame-1/2`（手挥到一半）、`a6-w2-16-after-3-frame-1`（餐盘落下中）。 | 真机动画手感、帧率 |
| a6-w2-17 | 已改 | ① 点赞变红：浅色卡面有一条覆盖把已赞的红心压成了黑色，现在浅色卡面已赞照样红、已转发照样绿。这是共享帖文卡，俱乐部详情里的商家视角也一起修好了。② 「正常的帖文样式」：商家「发现」改用玩家发现页（`pages/talent/list`）同一条流：俱乐部帖文和社区帖文按时间合成一条，用同一个合流器 `discover-feed.createFeedMerger`。卡片也一样：同样的字号，头像带「+」关注，点头像进个人页，有分享。「转发」是俱乐部成员的动作，商家不加入俱乐部，所以不出。③ 商家可以发帖：顶部加发帖入口，和俱乐部详情的入口同一个样子。点开是全站统一的全屏发帖弹窗（`cy-post-compose` 社区模式，带草稿和社区公约确认），发完回读帖文流。入口只在服务端社区写开关 `communityPostWrite` 打开时出现；开关还没读到时先读一次，和玩家发现页同一道闸。 | `pages/merchant/relation/index.{js,wxml,wxss,json}`、`components/cy/post-card/index.wxss`（只加两条规则） | `merchant-relation-state-contract` 改成异步合流后共 18 项通过，新增：同卡同动作、红心排在浅色覆盖之后、写开关关着不开发帖弹窗、首屏不足一页按 beforeId 补拉。`club-post-repost` 通过。rig：`a6-w2-17-before`（已赞是黑心，没有发帖入口）/ `a6-w2-17-after`（红心、关注「+」、分享、发帖入口）/ `a6-w2-17-after-compose`（发帖弹窗）/ `a6-w2-17-after-posted`（9002 走真实接口 `POST /api/v1/community/posts` 和 `/publish` 发出来的帖文出现在流里）。 | 评论页往返、分享卡片、真机键盘 |

## 门禁和测试

- `node tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`。商家发现帖文流的两路请求失败时，都经过具名回调 `_onFeedFail` 显示错误卡并给重试，门禁能静态证明，所以 `relation:265` 那条 U5 登记过期了，按门禁要求删掉（u5Silent 43 → 42）。digest 按门禁自己的 `baselineKeyDigest` 重算。**没有新增登记。**
- 动作台账用 `build-action-ledger.js --write` 重建，共 5881 个控件（+1 tap 发帖入口；+5 事件合同：关注、头像、社区帖点赞 variant、发帖弹窗关闭 / 发布）。`build-action-ledger.js` 给商家发现 `onLikePost` 加了社区帖 variant、`onFollowPost` 只打关注接口，和 `talent/list` 同一口径。`action-ledger.test.js` 的四个计数按重建实测更新，各自写了注释。
- 在最终提交上重跑：和本次改动相关的 96 个测试文件，加上全部 ledger 测试，共 1336 项通过，0 失败。
- 主包：改动都在商家、主题分包里；主包只有 `components/cy/post-card/index.wxss` 多了 3 行，主包大小基本不变（远低于 2048 KiB）。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。推送前查过 diff 里没有密钥或 token。

## rig 备注（HARNESS，只在一次性库里，不在产品代码里）

- 一次性库里只有一个种子主题 990013（属于 9003，挂在俱乐部 7001 上）。复制出 990101「探店日 · 咖啡巡游」当 9002 自办主题，并把 `club_id` 清空。不清空的话 `/api/project/players` 会走俱乐部名单分支，抛出不带 code 的 `ServiceException`，`ApiProjectController.players:194` 的 `e.getCode()` 就会 NPE（见下面跨车道）。
- 以 9003 身份走真实接口 `POST /api/club/post/create` 发了两条俱乐部帖文；9002 在 before 里点过一次赞（`/api/club/post/like`）；after 里 9002 发了一条社区帖文。
- `a6-w2-17-after-compose` 里弹窗内容整体向左偏了几个像素。这是 rig 里 textarea 聚焦后浏览器横向滚动造成的，没输入时的同一弹窗是正的。这个组件就是玩家在俱乐部详情里用的那一个，本车道没改它。

## 跨车道

- 后端（不在本车道）：`ApiProjectController.players` 里 `catch (ServiceException e) { return new AjaxResult(e.getCode(), ...) }`，遇到不带 code 的 `ServiceException` 会 NPE，玩家看到的是「系统繁忙」，看不到原来的拒绝原因。建议 code 为空时按 500 兜底。本车道没改。

## 待你决定

1. **「数字没有就是 0」的范围**：我只改了你圈的这一屏，也就是主题「客户」的三格计数。商家其他地方的金额类数字（结算、到账等）仍然按「未知不写 0」，避免把没确认的钱写成 0 元。点评数、名单人数这类其他计数，要不要也统一写 0？默认：先不动。
2. **商家发帖发到哪里**：现在发的是全站社区帖文（和玩家在帖文流里看到的社区帖同一类），不是俱乐部帖文，因为商家不加入俱乐部。帖文会走社区审核和公约确认。默认：按这个做了。如果你想让商家帖带「商家」标或者门店链接，需要后端给出身份事实。
3. **转发**：商家发现的帖文卡没有「转发」，因为转发是俱乐部成员的动作。默认：不出。
4. **AI 角色介绍动效**：四步动效是我按每一步的话设计的（升起、挥手、餐盘落下 + 闹钟摆、名牌翻正 + 面具换脸），素材还是原来那 8 个 3D 物件。如果你要换成真正的动图（Lottie），告诉我想要的样子。默认：用 CSS 动效，不增加包体。

STATUS: COMPLETE
