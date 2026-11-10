# r2-join 结果：多人报名、邀请落地页、席位、结算（第二轮）

分支 `claude/r2-join-20261110`，基线 `origin/staging/questo-review-20261110` @ `6d51e844`（qx-s1 已写 STATUS 之后）。只改小程序，没有改 Java、SQL、迁移、支付/退款/结算状态逻辑。

截图在 `shots/`，全部来自走查 rig：后端是基线 jar（真实 MySQL 8 / Redis），浏览器包分别用基线（before）和本分支（after）的 `chengyinhub-xcx` 打出。390×844，JPEG q70，同页同状态。每张都看过：没有空标题、重叠、截断或前后矛盾的数字。

## 结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| b3-p1-5（推荐 / 席位 / 已关闭） | 已修 | 落地页重做成「同行」结构：标题下是「邀请人 ↔ 你」两个圆，中间连线上有光点流动，两个圆外有扩散光环；领到席位后右边的圆换成席位号并弹一下。下方新增「怎么玩」：一起玩（SHARED_TEAM）/ 各自比（INDEPENDENT 且 `competition=true`）/ 各自玩（其余 INDEPENDENT），卡片依次上浮出现，下面列提示、进度、计分各自还是共享（共享的圆点高亮）。服务端只给一种玩法时整宽一张卡，不画单选圈；给多种时卡片可点切换下方规则（只切换说明，不写服务端）。邀请有效期、报名已关闭照旧；按钮固定在底部。所有动效只用 transform/opacity，减弱动态效果时全部静止。 | `subpackageJourney/pages/landing/*`、`subpackageJourney/utils/join-view.js`（新） | 单测 `r2-join`（模式映射 4 例）。截图：推荐+报名已关闭 `b3-p1-5-referral-before/after`；席位 `b3-p1-5-seat-before/after`；组队主题席位 `b3-p1-5-seat-team-after`（真实 `/choices` 回 SHARED_TEAM，见 rig 夹具） | 动效帧率与光环在真机上的观感；截图只能拍静止帧 |
| b4-u06-01 席位卡 | 已修 | 队伍详情的席位卡从一列键值行改成一排席位：已入队成员头像（队长有「队长」角标）+ 空位虚线圈（依次弹入，空位轻呼吸）。空位数只来自服务端 `seats.available`，否则用 `maxMembers − joinedCount`，都读不到就不画。下方小标签：持有效票、票已失效（红）、待审申请、邀请到期。「还可加入」由空位表达，不再重复一行。 | `pages/team/detail/*`、`pages/team/utils/team-seats.js` | 单测 `r2-join`（席位 2 例）+ 原 `qx-b4-team-seats` 通过。截图 `b4-u06-01-before/after`（真实 `/api/team/info`，夹具队 501） | 真机头像加载 |
| b4-u06-01 加入页 | 已修 | 加入页卡片顶部同样一排席位（已入队实心、空位虚线），下面两栏「当前成员 / 加入方式」，邀请到期单独一行带时钟图标；「需要先买这一场的票」和按钮固定到底部。 | `pages/team/join/*` | 截图 `b4-u06-01-join-before/after`（同一队伍，非成员 9002 打开） | — |
| b5-x-p6-buy-refused | 已修 | 购买被拒时，后端原文里给开发看的内部编号（`topicId=…`、`xxxId:`、`orderNo=` 这类）被去掉，其余文字不改；去完为空就显示「暂不可购买」。 | `subpackageMember/utils/market-view.js`（`playerText`）、`subpackageMember/market/pass/index.js` | 单测 `r2-join`（2 例）。截图 `b5-x-p6-buy-refused-before/after`：真实下单被拒，before 显示「…假造票面 topicId=990014」，after 不再出现 topicId | — |
| b5-x-p6 结算（积分抵扣） | 需要你决定 | 详情页新增「结算」块：只列报价里真实存在的支付方式和金额，「现金 ¥99.00」一行在现金未开通时变灰并在下面写「现金支付暂未开通」，「积分兑换 990 积分」一行；原来混在一起的「价格 ¥99.00 / 990 积分」行去掉。**没有改付款方式**：后端这类商品的积分是「整单用积分兑换」（`payMode=2`），不是「现金上叠加的部分抵扣」。见下方「待你决定 1」。 | `market-view.js`（`checkoutRows`）、`market/pass/*` | 单测 `r2-join`（结算 1 例）。截图 `b5-x-p6-citypack-before/after` | 现金开通后的两按钮布局 |
| b5-x-p6-citypack | 已修 | 同上「结算」块；城市包详情其余行不变。 | 同上 | 截图 `b5-x-p6-citypack-before/after` | — |
| b5-x-s17-2-claim-ready / invalid | 已修 | 领取页不再用通用空态插画和警告三角，改成礼物图标：待领取是带光环的礼物圆章（进场轻转入），无效是灰色礼物圆章，已领取是绿色对勾圆章；整块居中，不撑高。 | `subpackageMember/market/gift/*` | 单测 `r2-join`（1 例）。截图 `b5-x-s17-2-claim-ready-before/after`、`b5-x-s17-2-claim-invalid-before/after`（真实领取接口回「领取码无效」） | 真机礼物分享卡进入 |
| b5-19 收益页签 | 已修 | 「收益明细 / 分佣」切换从左侧挤在一起的分段胶囊改成等宽铺满的页签；第一个页签叫「收益」，不再与页标题「收益明细」重名。空态插画沿用全站标准，没有动。 | `subpackageA/pages/assetcenter/income-detail/*` | 截图 `b5-19-before/after`（真实 `/api/marketing/revenue/author/mine` 有 1 条，页签才出现） | — |
| b5-x-s18a 视频悬赏 | 已修（只整理版式，等你的参考图） | 平台选择移到卡片外面（卡片里那块黑色渐隐不见了）；输入框改成与卡片同层的浅一档底色；第二个输入框占位从「同一原视频分组（选填，仅建议值）」改成「原视频分组（选填）」；空态不再撑满全屏。上方样式等你发参考图后再改。 | `subpackageA/pages/video-bounty/*` | 截图 `b5-x-s18a-before/after` | — |
| a5-v3-29 自玩票 | 已修（前端）/ 需要你决定（后端） | 发布者票务页去掉整个「自玩票」分区（开关、价格、票数），三个对应 handler 一起删掉。已存草稿里的 `selfPlay*` 字段原样保留，发布时照旧上送，不在前端改写；后端设计见「待你决定 2」。发布者实名区不在本 lane（r2-create 负责对齐）。 | `pages/publish/fabu/step3.wxml`、`pages/publish/fabu/index.js`、`tests/unit/publish-identity-and-audio-placeholder-contract.test.js`（断言改成「票务页没有自玩票」） | 截图 `a5-v3-29-before/after`（编辑器 → 票务设置） | — |

### 多人报名结构（Questo A1–A4）对照

| Questo | 我们现在 | 说明 |
|---|---|---|
| 一起玩（Team up） | 显示：`SHARED_TEAM` → 「一起玩」 | 服务端事实：组队主题进度全队共享，**提示和计分仍是各自**（`hintScope/scoreScope=PER_MEMBER`）。所以没有写「全队一个分数」「提示共享」。 |
| 各自比（Compete） | 只在 `competition=true` 时写「各自比 · 结束后排名」 | 服务端对普通主题一律回 `INDEPENDENT, competition=false`，所以 rig 里显示的是「各自玩」。 |
| 玩前二选一 | 不做选择控件 | 服务端每个主题只回一种玩法（TEAM 主题只有一起玩，其他只有各自），没有「由玩家选择」的写接口。 |
| 实时 / 随时（Live / Anytime） | 不显示 | 服务端没有这个字段。 |
| 共享提示 / 跳过 / 计时 | 不显示 | 服务端没有队伍级的提示、跳过额度或计时。 |
| 邀请席位（头像 + 空位） | 已做：落地页「邀请人 ↔ 你」、队伍详情和加入页一排席位 | 席位数只读 `/api/team/info` 的 `seats`。 |
| Crew 二维码 + 加入码 + 分享 | 分享已有（队伍详情「邀请队友」）；二维码和加入码没加 | 加入码 `inviteCode` 在服务端有，但没有队伍二维码接口；把加入码直接给玩家看需要你确认（见「待你决定 3」）。 |

## 待你决定

1. **积分是「抵扣」还是「兑换」**（b5-x-p6-buy-refused）。你说「积分可以抵扣的，不能是全部用积分付款吧」。后端现在：通行证类商品有两种互相独立的付款方式，`payMode=1` 现金、`payMode=2` 整单积分兑换；报名结算（baoming）那边才有「积分抵扣一部分现金」。**默认做法**：不改付款逻辑，结算块把两种方式分两行写清楚（「现金 ¥99.00」/「积分兑换 990 积分」），按钮仍只来自服务端 `availablePayModes`。如果要改成「只能抵扣」，需要后端把通行证的 `payMode=2` 改成现金单上的积分抵扣（与 baoming 同一套），这是支付逻辑，本 lane 不能动。
2. **自玩票改成平台会员（a5-v3-29）的后端设计**。默认做法：前端只把发布者票务页的自玩票分区拿掉，后端一行没改。建议设计（等你确认后另开工单）：
   - 新商品类型「玩家会员 / 官方自玩通行证」，放在现有 `marketing_product`（`product_kind` 新增一种，或用现有「时限通票」3 / 「固定期年卡」4 承载），覆盖范围 = 平台官方主题（`productRoleService.isPlatformOwned(topicId)=true` 的主题），不再按主题逐个列 `marketing_product_item`，改成「覆盖规则：全部官方主题」。
   - 购买走现有通行证下单 / 报价 / 履约链路（`/api/marketing/product/quote|order`），有效期用现有 `validity_kind`；不新建支付或退款状态。
   - 进入游玩时的准入：现在 `cms_topic.self_play / self_play_price` + 「请先购买自玩通行证」(402) 判定按主题；改成「主题是官方主题且玩家持有有效会员」也算准入，原有按主题的自玩票保留只读直到下线。
   - 发布者侧：`cms_topic.self_play*` 字段不再由发布者写（创建/更新接口忽略或拒绝），已有数据迁移时一次性关掉非官方主题的自玩票。需要一条新的 `migration_*.sql`（不改旧迁移）。
   - 需要你确认：会员价格、有效期、是否包含组队 / 活动场次、已买过单主题自玩票的人怎么处理。
3. **队伍二维码与加入码**。默认做法：没加。服务端有 `inviteCode`，但没有生成队伍小程序码的接口；如果要「二维码 + 加入码」，需要后端出一个按 `inviteCode` 生成小程序码的接口（与 wxacode 现有调用同一套）。
4. **b5-x-s18a 上方样式**。默认做法：只整理了版式，等你的参考图。
5. **b5-19 你没有写具体意见**。默认做法：只把页签改成等宽、首个页签改名「收益」，空态插画沿用全站标准。

## rig 夹具（HARNESS，只写 rig 测试库，不在产品代码里）

- rig 分支 `claude/cloud-walk-20261101-rig` + `harness-patches.diff`（club 分支）+ staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`；Docker 镜像源用 `mirror.gcr.io`。
- `chains.py player` 在这个基线上 `sale_close` 失败（与 qx-b5 / staging 记录一致），但 topic 990014 已由前两段链路真实建出。
- 直接写库的夹具（文件在 `rig/`）：`seed-team.sql.txt`（队伍 501：9004 队长、9009 队员、9001 待审申请）、`seed-pass.sql.txt`（商品 9101「上海城市包」，¥99 / 990 积分，覆盖 990014；9004 积分 2000）、`seed-rev.sql.txt`（9004 一条作者分配，让「分佣」页签出现）；`cms_topic 990014 team_mode=1`。
- 组队截图 `b3-p1-5-seat-team-after`：临时把 990014 的 `published_theme_runtime_config_json` 设为 `{"schemaVersion":1,"gameplayRuntime":{"runtimeScope":"TEAM","worldMode":"TEAM_INDEPENDENT"}}`，拍完改回 NULL。
- 推荐链接用真实 `POST /api/marketing/referral/token` 签发；购买被拒、领取码无效都是真实接口回包。
- 编辑器票务页：从「票务设置」进入后页面滚动到底，不改页面数据。

## 门禁

- `node --test chengyinhub-xcx/tests/unit/r2-join.test.js`：10/10 通过。
- 与本次改动相关的单测（引用 team / journey / market / income-detail / video-bounty / fabu step3 的 72 个文件 + `r2-join`）：833/833 通过，含 `action-ledger.test.js` 48/48。
- `build-action-ledger.js --write`：5875 → 5873（去掉自玩票开关 + 两个输入框 −3，落地页模式卡点选 +1）。`action-ledger.test.js` 只改了四个计数（总数 5873、tap 2928、外部 tap 367：模式卡点选带轻震归 external、事件合同 2945 / 本地事件合同 2849），每处都写了来源注释。
- `ui-integrity.js`：`UI-GATE-0:PASS`。`fabu/index.js` 删了三个自玩票 handler（−8 行），5 个 U4-DYNAMIC 锚点只是行号位移（6640→6632、6652→6644、6719→6711、7277→7269、9559→9551），调用行逐字相同，理由与条数不变，没有新增登记；keyDigest 按门禁同一算法重算。
- 主包：1998.6 KiB（与基线相同，本次改动都在分包）。
- 没有运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`。

## 跨 lane

- `subpackageJourney/utils/journey-view.js`、`style/journey.wxss` 没动（战报 / 排行榜页属于 r2-finish）；落地页的新样式都在 `landing/index.wxss`，新映射在新文件 `join-view.js`。
- 颜色全部用现有 token，没有硬编码灰色；r2-visual 改 token 后这些页面自动跟随。

STATUS: COMPLETE
