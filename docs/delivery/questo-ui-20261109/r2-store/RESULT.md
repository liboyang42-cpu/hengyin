# r2-store 结果：主题结账、开局大厅、票夹与会员结构（第二轮）

分支 `claude/r2-store-20261110`，从 `origin/claude/r2-join-20261110`（STATUS: COMPLETE，`3d097ae9`）切出。草稿 PR #766 叠在 r2-join 上，方便两条一起看。

本次只改小程序。没有改 Java、SQL、迁移，也没有动支付、退款、结算的状态逻辑。所有新读口都是现有接口：报价、在售目录、席位、席位邀请、玩法、用户信息、主题详情。

截图在 `shots/`，全部来自走查 rig：后端是基线 jar（真实 MySQL 8 / Redis）；浏览器包 before 用 r2-join 分支，after 用本分支。规格 390×844、JPEG q70、同页同状态，每张我都看过。rig 里 OSS 图片拿不到，主题封面是灰底，这是 HARNESS，不是产品问题。

## 结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| R2S-1 主题结账 | 已修 | 自玩通行证「确认订单」整屏层按 STRUCTURE §1 重排，顺序如下：<br>① 主题头：封面、标题、类型 / 站数 / 场地小标签<br>② 「自玩通行证」单次购买卡，默认选中；卡上写「可玩至」<br>③ 城市通票行（票根造型），只列在售目录里 `items` 覆盖本主题的商品，点进原通行证详情页购买<br>④ 参与人<br>⑤ 明细：自玩通行证 × 1；有俱乐部会员减免时多一行「俱乐部会员 −¥x」，单价划线<br>⑥ 信任行：退款只读报价 `refundableNow / refundPercentNow`，另写「1 人自玩」<br>⑦ 单独同意（原样保留）<br>⑧ 底栏：合计 + 「支付 ¥x」<br>价格改读 `/api/registration/quote`（ownerType=1）的 `payAmount`，报价没回来时用主题详情的 `selfPlayPrice`。报价被拒时按钮变「暂不可购买」；后端原话里带字段名的（如 `sellingStartedAt 缺失`）不给玩家看。建单与支付链路一行没改。 | `pages/topic/utils/checkout-view.js`（新）、`pages/topic/components/cy/self-play-checkout/*`、`pages/topic/index/index.{js,wxml}` | 单测 `r2-store-checkout-view`（8 例）。截图 `R2S-1-checkout-before / after / top-after`：真实报价 ¥39、现在不可退；夹具通票「上海城市通票」 | 真机封面、支付弹窗 |
| R2S-1 同行席位步进器 | 不做（服务端没有） | 自玩通行证建单 `orderNum` 恒为 1，服务端没有每主题席位上限、加座单价，也没有从报名单发起加座的入口，所以不画步进器。 | — | 读 `CmsRegistrationServiceImpl.createSelfPlayPassInternal` | — |
| R2S-1 积分抵扣 | 不做（服务端没有） | 自玩通行证建单 `pointUsed` 恒为 0，报价不给 `pointsUsable`，所以不画抵扣开关。见待你决定 2。 | — | 同上 | — |
| R2S-1 自玩票（平台会员）行 | 不做（服务端没有） | 没有会员商品，也没有权益校验，所以不画。见待你决定 1。 | — | — | — |
| R2S-1 兑换码链接 | 不做（没有页面） | 后端有 `/api/channel/redeem`，但小程序没有兑换页，通行证页那行仍是「暂不可兑换」，所以结账页不放链接。见待你决定 4。 | — | — | — |
| R2S-1 付款后面板 | 保持原样 | 自玩通行证只有 1 席，「邀请同行」没有意义，付款后仍是原来的通行证到账动效 +「开始游戏 / 放进票夹」。多人票（活动报名）付款后落订单详情，从「同行席位」进开局大厅（R2S-2）。 | — | — | — |
| R2S-2 开局大厅 | 已修 | 原「同行席位」页（只有购票人能进）按 STRUCTURE §2 重排，页标题改成「开局大厅」，自上而下：<br>① 主题头<br>② 「以 昵称 身份游玩 · 编辑」：编辑进个人资料<br>③ 「一起玩的人 N/M」卡：自己是头像 +「房主」角标；已领的席位画席位号圆（服务端不下发受邀人头像，不编）；等待领取的画品牌色虚线圈；空位画虚线「+」，可邀请时点开邀请半屏。没有加入场动画：motion-property-ratchet 规定 keyframes 只减不增，r2-join 已经超过上限<br>④ 「怎么玩」：复用 r2-join 的 `join-view.modeTiles`，≥2 席才出，服务端只给一种玩法时整宽一张卡、不画单选<br>⑤ 底部固定「去起点」，带原活动 / 主题与报名单进游玩页，准入仍由游玩页向服务端核验<br>一人票不画同行卡。 | `subpackageJourney/pages/seats/*`、`subpackageJourney/utils/lobby-view.js`（新） | 单测 `r2-store-lobby`（5 例）+ 原 `qx-b3-player-journey` 通过。截图 `R2S-2-lobby-before / after`：夹具 4 人票，真实 `/seats`、`/choices` | 真机头像、分享卡 |
| R2S-2 邀请半屏 | 已修 | 半屏标题「邀请同行」，关闭在右上角，内容两块：<br>① 主按钮「发给微信好友」：首次打开就用原 `/seat-invitations/invite` 生成邀请，沿用原 requestId 防重和「查询邀请结果」，生成后按钮变成 `open-type=share`<br>② 已邀请 / 已领列表：等待领取的行可「撤回」（原撤销接口和确认框）<br>二维码、加入码服务端没有，不画。 | 同上 | 截图 `R2S-2-invite-sheet-after`：真实生成邀请，第 2 席变「等待领取」 | 真机分享回到群 / 好友 |
| R2S-2 受邀落地状态 | 已修 | 领取被拒时（席位已被领、邀请过期、已撤回，都是服务端 409 原话），原文照写在按钮下，并给「自己购买」回原活动或主题。已领过的再点：服务端回成功，照旧「进入本局」。 | `subpackageJourney/pages/landing/index.js` | 单测 `r2-store-lobby`。截图 `R2S-2-landing-revoked-before / after`：真实撤回后领取，服务端回「席位邀请已被撤销」 | — |
| R2S-2 一起玩 / 各自比选择 | 不做（服务端没有） | 服务端每个主题只回一种玩法，没有让玩家选的写接口；同时开始 / 各自时间也没有字段。大厅只读展示。见待你决定 5。 | — | — | — |
| R2S-3a 发现页通票入口卡 | 已修 | 发现 → 帖文流顶部一张票根造型卡，内容：城市名 + 通票、主题数（多种通票写「N 种通票」）、最低价（多种时加「起」）、最多 3 个主题名小标签、「获取通票」，点进通行证页。出现条件：玩家视角、已登录，并且在售目录里有城市包 / 时限通票 / 年卡。每次进页只读一次，读失败就不出、不占位。没有会员卡。 | `utils/discover-store-card.js`（新，主包 1.4 KB）、`pages/talent/list/index.{js,wxml,wxss}` | 单测 `r2-store-discover-card`（4 例）+ 发现页原有合同（帖文流请求顺序、空态文案、转发）全过。截图 `R2S-3a-discover-card-after`（rig 里没有帖文，卡在空态上方） | 有帖文时的位置 |
| R2S-3b 票夹 / 通行证页 | 已修 | 通行证页顶部加积分余额卡：数字只读 `/api/user/info.point`，读不到整卡不出、不写 0；右侧「积分记录 ›」进原积分明细页。不出积分充值包，也不出会员卡。 | `subpackageMember/market/passes/*` | 单测 `r2-store-market-balance`（2 例）。截图 `R2S-3b-store-before / after`（9009 积分 1260，夹具） | — |
| R2S-3c 自玩票平台会员 | 需要你决定 | 只出设计，不画任何 UI，见待你决定 1。 | — | — | — |

## 待你决定

1. **自玩票 = 平台会员（只覆盖官方主题）**
   - 默认做法：完全不出 UI。发现页只出城市通票卡，结账页不出会员行，通行证页不出会员卡。
   - 建议接口（与 r2-join 待你决定 2 的后端设计一致，补上前端要的读口）：
     - `GET /api/membership/self-play/offer`：返回 `{productId, name, cashPrice, listPrice?, validityKind, validDays, renew:false, coverage:'OFFICIAL_THEMES', officialThemeCount, termsUrl}`。没有在售时返回 `null`，前端整卡不出。
     - `GET /api/membership/self-play/mine`：返回 `{active, expiresAt, productId}`。为 `active=true` 时，官方主题的「随时开玩」直接进开局大厅，不进结账。
     - 结账用现有 `/api/marketing/product/quote | order`。会员在 `marketing_product` 新增一个 `product_kind`，覆盖规则是「全部官方主题」，不再逐条列 `item`。
     - 准入：`/api/play/nodes` 的 402「请先购买自玩通行证」改为：持有有效会员且主题是官方主题，也算放行。
   - 需要你确认的事：
     - 价格和有效期。
     - 是否自动续期。iOS 小程序不能卖虚拟订阅，默认不自动续期，做成固定期。
     - 已买单主题自玩票的人怎么处理。
2. **结账页积分抵扣**
   - 默认做法：自玩通行证不出抵扣。后端建单把积分固定为 0；活动报名（baoming）那边已经有「部分抵扣，单笔上限 ¥50」。
   - 如果要让自玩通行证也能部分抵扣，需要后端在 `createSelfPlayPassInternal` / `quoteSelfPlayPass` 接入 `PointsValuePolicy`（与活动同一套），并在报价里下发 `pointsUsable`、`pointsDeductYuan` 和单笔上限字段 `pointsDeductCapYuan`（新）。这是计价逻辑，本 lane 不能动。
3. **同行席位步进器 / 加座**
   - 默认做法：自玩通行证固定 1 席，不画步进器。
   - 建议接口：主题详情下发 `seatCap`（每单最多几席）和 `extraSeatPrice`；自玩通行证建单接受 `seatCount`。买完之后加座，需要一个以 `registrationId` 为原单的加座报价接口（现在 `/api/marketing/extra-seat/quote` 只认通行证商品订单号）。
4. **兑换码**
   - 默认做法：结账页不放「已有兑换码」链接，通行证页仍是「暂不可兑换」。后端 `/api/channel/redeem` 已存在，缺的是小程序兑换页。
   - 如果要开，建议在通行证页做一个输入框加「兑换」，结果页按 `reasonCode` 写人话。
5. **一起玩 / 各自比由玩家选、同时开始 / 各自时间**
   - 默认做法：大厅只读展示服务端那一种玩法。
   - 建议接口：`POST /api/play/player-journey/mode {registrationId, mode: SHARED_TEAM | INDEPENDENT_COMPETE, start: LIVE | ANYTIME, requestId}`，只有购票人能写，开局后锁定；`/choices` 下发 `selectable: true` 和当前选择。各自比的结束排名还需要一个按报名单聚合的榜单读口。
6. **席位邀请二维码与加入码**
   - 默认做法：不画。
   - 建议接口：`POST /api/play/seat-invitations/{id}/wxacode`（scene=`si.<短码>`，与现有 wxacode 服务同一套）和 6 位 `joinCode`（只在邀请有效期内可用，可撤回）。
7. **受邀人头像与昵称**
   - 默认做法：大厅里已领席位只画席位号。
   - 服务端现在 `recipientIdentityVisible=false`。如果要显示头像，需要你确认隐私口径（例如只给购票人看昵称首字和头像），再由 `/seats` 下发。
8. **付款后的订阅消息请求**（Questo 截图 3 结构）
   - 默认做法：不加，因为没有模板 ID。

## rig 夹具（HARNESS，只写 rig 测试库，不在产品代码里）

- rig 分支 `claude/cloud-walk-20261101-rig` 加三个补丁：
  - `harness-patches.diff`（club 分支）
  - staging 的 `rig-up-sha-param.diff`
  - staging 的 `rig-app-oss-seam.diff`
- 主题 990014：先跑 staging 的 `free_orient_theme.py`，再直接写库：
  - `self_play_price=39`
  - `lifecycle=3`
  - `selling_started_at` 设为前一天，`sale_end_at=2027-01-09`
  - `fee_platform_rate=0.1`
  - 写完后真实报价返回 ¥39，现在不可退。
- `marketing_product 9201`「上海城市通票」：¥88、30 天、城市包，`item` 覆盖 990014 和 990013。
- `ums_member 9009.point=1260`；9004 昵称「玩家小城」。
- `cms_registration 901`：复制 9004 的 74 号单，改成活动 9、4 人票、`purchase_kind=1`。席位邀请、撤回、领取都走真实接口：邀请 1、2 已撤回。
- 截图走查用 `walk.mjs`；结账层用 `eval` 调页面原方法 `onSelfPlayGoCheckout()` 打开，与点「¥39 随时开玩 → 去结算」是同一个方法。

## 门禁

- 新单测 4 个文件，共 19 例，全部通过：
  - `r2-store-checkout-view`
  - `r2-store-discover-card`
  - `r2-store-market-balance`
  - `r2-store-lobby`
- 相关单测 162 个文件，全部通过：发现页、主题页、通行证、旅程分包、GET 矩阵、标题合同、`action-ledger`。
- 全量单测（`node --test tests/unit/*.test.js`）：16996 例，16994 通过，2 例失败，都是 r2-join 基线上就已经红的：
  - `motion-property-ratchet`「keyframes 只减不增」：r2-join 基线 155 组，上限 146。本分支起初加了 1 组，已删掉，不再新增。
  - `reduced-motion-coverage-contract`：r2-join 的落地页、队伍、礼品页动画没有走减弱动态效果的约定。本分支的大厅动画已删，不在名单里。
  - 这两项归 r2-join 修，见「跨 lane」。
- `build-action-ledger.js --write`：5873 → 5882。
  - tap +7：结账通票行、发现页通票卡、积分余额卡、大厅编辑身份、邀请、席位圆、去起点。
  - 事件合同 +2：结账层 `bind:pass`、邀请半屏 `bind:close`。
  - `action-ledger.test.js` 只改计数，每处都写了来源注释。
- `ui-integrity.js`：`UI-GATE-0:PASS`。
  - `topic/index.js` 的 buildRouteMap U4-DYNAMIC 锚点只是行号位移（2091→2121），调用行逐字相同，没有新增登记。
  - u4Dynamic keyDigest 按门禁同一算法重算。
- `get-auth-matrix-contract`：结账层、发现页两处在售目录 GET 已入 MATRIX，122 → 124。
- `route-matrix.json` 重新生成：大厅页读本人信息，`needsLogin` 改为 true。
- 主包：约 2001.6 KiB（r2-join 为 1998.4 KiB）。新增的只有 `utils/discover-store-card.js` 和发现页几行，其余都在分包。
- 没有运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`。

## 跨 lane

- r2-join：
  - 落地页只加了领取被拒时的「自己购买」出口（3 处小改）；模式卡复用它的 `join-view.js`，没有改它。
  - 它的分支在两项全量单测上是红的：`motion-property-ratchet`（keyframes 155 > 146）和 `reduced-motion-coverage-contract`（落地页 `jl-*`、队伍 `tm-seat-in` / `tj-seat-in`、礼品 `gf-*` 的动画）。需要它改成 transition 或接入减弱动态效果的约定，本分支没有替它改。
- r2-visual：本分支颜色、圆角全用 `--cy-*` 令牌。底部按钮圆角沿用原有 pill，等 r2-visual 的统一令牌。
- r2-finish：没有碰排行榜、战报、分享。

STATUS: COMPLETE
