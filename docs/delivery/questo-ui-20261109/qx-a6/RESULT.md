# qx-a6 · 商家（2026-11-09）

分支 `claude/qx-a6-20261109`，基线 `origin/master` 2bb81143。截图在 `shots/`，390 px 宽，JPEG 质量约 70。截图来自走查 rig：`claude/cloud-walk-20261101-rig` 加上 `claude/cloud-walk-20261101-club` 的运行时补丁，后端是 master jar，库是一次性 MySQL/Redis。前后两组截图用同一个库和同一个身份（商家老板 9002）。before 用 master 的小程序包，after 用本分支的小程序包。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机仍未验证 |
|---|---|---|---|---|---|
| W2-01 | already fixed | master 已是横滑 4:3 大卡（方案 B v3，ce28d839）：封面、状态签、类型、标题、日期，还有待核销深色签。本分支没改。 | — | rig 截图 `W2-01-before/after`（两张相同）。没封面时显示城瘾 logo 占位：rig 里后端会把非 OSS 封面清空，所以封面图本身没有截到。 | 带 OSS 封面的卡面 |
| W2-07 | already fixed | master 已有三态（6b8f43ef）：未开售写「未售票」，汇总格写「—」，也不出筛选和广播；开售后上面是「还没到店」，下面是「已核销」；广播走 `/api/merchant/crm/broadcast` 和 `/preview`，带 `topicId`、`scope=MEMBERS`。后端已经按 `topicId` 收窄到这个主题的买票人（`MerchantCrmBroadcastServiceImpl:440`），所以不用加后端参数。本分支没改。 | — | rig 截图 `W2-07-before/after`：主办主题的「客户」是全屏叠层，状态是「未售票」。 | 「未到店 / 已核销」两段和广播没有截到：rig 的 sale_close 链路在 master 上失败（「自由探索只开放内容所属俱乐部的场次」），造不出已售票的主题，记为 HARNESS。这部分由 master 现有单测覆盖。 |
| W2-11 | already fixed（「结算明细」）/ needs owner（「收款账户」） | 「更多」菜单（账户收益）在 master 上已删掉「结算明细」，也已经贴底（604d3f5c）。「收款账户」没有加：后端没有商家可读写的收款账户接口；按 2026-09-15 收款模型定稿，平台不打款，提现走客服弹窗（`earnings/index.js:83`）。没有服务端事实，就不出控件。 | — | rig 截图 `W2-11-before/after`：菜单只有本人俱乐部结算、提现记录、邀请记录。 | — |
| W2-15 | fixed | 店铺参谋（`pages/merchant/marketing/ai-insight`）底部加了「经营优化」：四张带图卡，讲怎么用城瘾带客流，每张一个动作。四个动作是：承接主题路线 → 合作中心；把门店设成打卡点 → 成为节点；发到店券 → 优惠券；AI 店铺角色 → 配置。每张卡只在守卫放行、且岗位有对应权限时出现（`canManageCoop` / `canManageCoupons` / `canWriteProfile`；合作中心不设岗位闸），没有权限的卡整张不出。为此守卫的 `state` 事件在放行时附带了已经归一的权限，页面不用再请求一次。 | `pages/merchant/utils/grow-tips.js`（新），`marketing/ai-insight/index.{js,wxml,wxss}`，`components/cy/access-gate/index.js`（第 50 行，事件多带 `access`），`pages/merchant/images/grow/`（4 张封面，每张 ≤32 KB；4 个 3D 图标，MIT） | `tests/unit/qx-a6-merchant-grow-intro.test.js`；rig 截图 `W2-15-before`（页底没有这一区）、`W2-15-after`（标题和第一张）、`W2-15-after-2`（页底）。 | 真机滚动、图片加载 |
| W2-16 | fixed | AI 店铺角色首次进入的四步介绍（只弹一次，沿用 `cy_ai_npc_intro_seen` 标记）把 3D 物件换成城瘾 logo 的六块积木：散开 → 靠拢 → 拼成 logo → logo 说「嗨」。积木按定稿原型 p6 的切法用无头 Chrome 渲染成透明 PNG；动画只用 transition，并支持 `prefers-reduced-motion`。旧 `npc-intro` 图删掉了，商家分包少了 31 KB。 | `pages/merchant/decor/ai-npc/index.{js,wxml,wxss}`，`pages/merchant/images/logo-blocks/`（新，6 张共 78 KB），删除 `pages/merchant/images/npc-intro/` | 同一个测试文件；rig 截图 `W2-16-before-1..4` 和 `W2-16-after-1..4`（四步依次截）。 | 真机动画手感 |
| W2-17 | fixed | 商家第 4 个 tab 改名「发现」（底栏 aria 文案和页面标题）。「帖文」改读全站流（`/api/club/post/feed` 加 `scope=all`），和玩家发现页同一条接口、同一份映射（`discover-feed.normalizeClubPost`）。触底按 `beforeId` 翻页；翻页失败保留已有内容，不出错误文案。帖文卡可以点赞（`post-feed-actions.toggleLike`，先改卡面，失败回滚）和评论（进俱乐部详情）。空态只留标题。 | `components/tabBar/index.js`（只改 relation 一行的 text），`pages/merchant/relation/index.{js,wxml}`；测试 `merchant-relation-state-contract`、`merchant-aggregate-pages`、`club-post-repost` | rig：master 上同一个库显示「这里还没有帖文」（只看合作过的俱乐部）；本分支显示全站两条帖文。点赞后库里有 `club_post_like(post_id=2, member_id=9002)`：截图 `W2-17-before/after/after-liked`。 | 评论页往返 |
| W2-03 | already fixed | 消息页顶部是横滑协作邀请卡，下面是收件箱和发件箱（`subpackageB/pages/im/list`，m681 / R03）。主题里不再跳到总页面，「只看 #id」筛选条也没有入口再带过去。 | — | 代码核对；rig 里 9002 没有邀约，所以卡片带没有截到。 | 有邀约时的卡片带 |
| W2-04 | fixed（收口） | 「邀约提醒」开关原来已经放在「协作邀请」旁，但只在有邀约时才出来；收件箱为空时（最需要先开提醒的时候）看不到。现在商家收件箱即使为空也保留「协作邀请 · 邀约提醒」这一行，卡片带仍然只在有邀约时出现。 | `subpackageB/pages/im/list/index.{wxml,wxss}` | rig 截图 `W2-04-before/after`。 | 订阅弹窗（真机才有） |
| W2-05 | already fixed | 合作俱乐部、招商、更多、客户都是同一档全屏 `project-drawer`，下一层用 `project-layer` 叠在主题里，不跳出去（P-6 / R06）。 | — | rig 截图 `W2-07-after` 就是这一档全屏叠层。 | — |
| W2-19 | already fixed | 商家首页「我的消息」已是叠层：三张叠放，点开展开，单条 ×、「清空」、「都看完了」（R02，2026-11-01）。 | — | rig 截图 `W2-01-*` 底部能看到消息叠层。 | 展开动画 |

## 验证

- `node --test`：`qx-a6-merchant-grow-intro`（4）、`merchant-relation-state-contract`、`merchant-aggregate-pages`、`ui-empty-cta-contract`、`final-review-fixes-contract`、`club-post-repost`、`action-ledger`（控件计数 5751 → 5755：tap +1，事件合同 +3），以及所有引用 relation / ai-npc 的单测。
- `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write`：重建了台账，PASS。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`：`UI-GATE-0:PASS`，没有新增 baseline。`relation/index.js:265` 原有那条登记的行号保持不变。
- 没有动后端，也没有新增 migration。

## 共享文件

- `components/tabBar/index.js`：只把商家 relation 一行的 text「合作」改成「发现」。
- `components/cy/access-gate/index.js`：第 50 行 `state` 事件多带一个 `access`（放行时才有），其他调用方不受影响。
- `scripts/uiaudit/action-ledger.json`：重建。

## 跨 lane

- qx-a2（V3-25 底栏图标）：商家「发现」tab 现在用的还是 `icon_tab_relation.svg`（握手图）。请在重画图标时给这个 tab 换一个「发现」图形，`key` 保持 `relation` 不变。

## needs owner

1. W2-11「收款账户」：后端没有商家收款账户，平台也不打款，所以没加这一行（这是当前默认实现）。如果要加，需要先定收款模型和接口。
2. W2-15 / W2-16 的位置：LANES 写的是 `pages/merchant/marketing`。但截图 0759 的「页顶说明卡」和定稿原型 p6 都在 AI 店铺角色页，「经营解读 → 经营优化」在店铺参谋页（`pages/merchant/marketing/ai-insight`）。默认按原型实现：经营优化在店铺参谋，logo 积木介绍在 AI 店铺角色。营销 tab 本身没有页顶说明卡，所以没有加首次介绍。

## rig 备注（HARNESS）

- Docker Hub 对 redis 返回 429，所以从 mirror.gcr.io 拉了同一个 digest 再打 tag。
- 种子主题封面是 `media-asset:*`，rig 没有 OSS 配置，店铺参谋接口会 500。在一次性库里把 990013 / 990014 的 `img_url` 改成本地上传路径，并复制出两条 9002 自办主题（990101 / 990102）当项目卡数据，又以主理人 9003 身份通过真实接口发了两条帖文。这些都只在 rig 库里，不在产品代码里。
- `chains.py player` 的 sale_close 在 master 上失败（见 W2-07），player 链路没有跑。

STATUS: COMPLETE
