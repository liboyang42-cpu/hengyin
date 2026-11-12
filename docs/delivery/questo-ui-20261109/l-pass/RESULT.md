# l-pass 结果:通行证(平台会员)后端 + 小程序

- 分支 `claude/l-pass-20261110`,从 `origin/launch/questo-20261110`(qx-base `STATUS: COMPLETE`,`ca1f85b68`)切出。
- 草稿 PR #773,合入目标 `launch/questo-20261110`。
- 没有合并,没有标记就绪,没有推 master / integrate / staging / launch 或其他 lane 的分支,也没有碰生产。

## 一句话

通行证从后端到小程序都做完了:
- 后端:套餐、订单、有效期、领取。
- 持有者结账 ¥0,按钮「免费领取」。
- 购买只在「我的 → 通行证与礼品 → 通行证」,有年卡 / 月卡,不自动续费。
- 页面:通行证页、开通成功钥匙动效、游戏主题库。
- 支付:安卓 / 开发者工具走微信支付 JSAPI,iOS 走小程序虚拟支付(道具直购)。

所有开关默认关闭。关闭时,现有自玩票的报价、下单、退款和结算结果一行都不变。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| LP-1 通行证数据 | 已修 | 新增四张表(只新增,不改旧表):<br>• `platform_pass_plan`:套餐。年卡 ¥299 / 365 天、月卡 ¥39 / 30 天,**默认未上架**。<br>• `platform_pass_order`:订单。`requestId` 按会员唯一,价格和天数在下单时冻结。<br>• `platform_pass_membership`:有效期。每笔已付订单一段,续买从最后一段到期接着算。<br>• `platform_pass_claim`:领取记录,一条报名只能记一次。<br>迁移已登记 `ci/migration-dependencies.json`。 | `chengyinhub-admin/sql/migration_20261110_platform_pass.sql`、`PlatformPassMapper.xml`、`platformpass/mapper/PlatformPassMapper.java` | 迁移安全门禁、布局门禁通过。<br>真库 IT `PlatformPassMySqlIT` 用迁移原文建表。 | — |
| LP-2 持有者 ¥0 | 已修 | 自玩票报价和下单时,如果本人此刻持有有效通行证,并且主题是官方主题(内容槽 = PLATFORM、开放自玩、售票中或进行中),金额就按 ¥0 算。<br>之后走原来的零元单完成路径,同一事务写一条领取记录。<br>这张 ¥0 自玩票的到期日不超过通行证本段的到期日。<br>报价多两个只读字段:`platformPassCovered`、`platformPassValidTo`。<br>总开关关闭,或通行证服务缺失时,不覆盖任何主题。 | `CmsRegistrationServiceImpl`、`RegistrationQuoteResult`、`PlatformPassService#coverage/recordClaim` | 单测 `PlatformPassSelfPlayCoverageTest`(3 例):覆盖时 ¥0、原价照记、到期日不越界、记领取;未覆盖时原价不变。<br>原有 `SelfPlayPassIdempotencyTest`、`MoneyPathContractTest`、`ClubOperationBenefitTest`、`ExploreSellGateTest` 全过。 | 真机领取一次 |
| LP-3 JSAPI 渠道 | 已修 | 下单复用 `createGenericOrder`。<br>`PPO` 号段接入订单锁(`JdbcWechatPaymentOrderOwner`)和回调路由(`WechatPaymentCallbackRouter`)。<br>开通只在已验证支付事实被消费的同一事务里发生,金额、交易类型、币种要逐项一致。<br>退款号段 `RPP` 单独走回调,不会落到报名退款链。 | `WechatPaymentCallbackRouter`、`JdbcWechatPaymentOrderOwner`、`PlatformPassService` | 单测:<br>• `PlatformPassRouteTest`(3 例):PPO / RPP 只归通行证;没有承载方时回 FAIL,不会兜底到报名。<br>• `PlatformPassServiceTest`:金额不一致拒绝,续买顺延。<br>• 原有路由测试 17 例全过。 | 真实微信回调 |
| LP-4 iOS 虚拟支付 | 已修(按配置) | 道具直购:<br>• 后端生成 `signData`(offerId / buyQuantity / env / currencyType / productId / goodsPrice / outTradeNo / attach,不带 platform)。<br>• `paySig = HMAC-SHA256(AppKey, "requestVirtualPayment&" + signData)`。<br>• `signature = HMAC-SHA256(session_key, signData)`,session_key 用前端新取的 `wx.login` code 换,并核对 openid 是本人。<br>开通只认服务端 `/xpay/query_order` 回查:状态是已付、单号一致、金额一致才开通。<br>消息推送(`xpay_goods_deliver_notify` / `xpay_refund_notify`)只当作「去回查」的信号,复用现有消息推送 URL 和它的 token 验签。<br>回查确认后平台还显示「待发货」时,补报 `notify_provide_goods`。 | `XpaySigner`、`XpayGateway`、`HttpXpayGateway`、`ApiContentSecurityController`(只对 `xpay_*` 事件分流)、`PlatformPassService` | 单测:<br>• `XpaySignerTest`:期望值用 Python 独立算出。<br>• `PlatformPassServiceTest`:signData 字段与签名正确、session 不是本人时拒绝、回查未付或金额不符不开通。<br>• 真库 IT:回查开通、重复回查不重复开通。 | **全部**。官方文档域名在本环境被网络策略拦截,接口形状按已知官方算法实现;沙箱 / 现网联调都要在真机做 |
| LP-5 退款 | 已修 | 后台整单退款 `POST /business/platformPass/orders/{no}/refund`,权限 `business:platformPass:refund`。<br>只退「还没领取过任何主题」的已付通行证。<br>JSAPI 按原支付事实原路退,虚拟支付调 `/xpay/refund_order`。<br>渠道明确拒绝时,订单恢复为已付;结果未知时保持「退款中」,按原退款号回查。<br>退款成功后撤销这一段,后面的段整体前移(按秒向上取整,不留空档)。<br>「退款中」的段不再覆盖主题。 | `PlatformPassAdminController`、`PlatformPassService#requestRefund/refreshXpayRefund/handleJsapiRefund` | 单测:已领取不能退、渠道拒绝后恢复。<br>真库 IT:退款撤段、后段前移、退款中不覆盖。 | 真实退款 |
| LP-6 游戏主题库 API | 已修 | `GET /api/platform-pass/library`:官方开放自玩主题,可按城市定向 / 自由探索筛选,返回本人是否已覆盖。<br>`GET /api/platform-pass`:套餐、本人有效期、可玩主题数、两个渠道是否可用。<br>都需要登录,会员身份只来自登录态。 | `ApiPlatformPassController` | 真库 IT:只列官方、售票中、开放自玩的主题;按玩法筛选。<br>rig 实接口 200。 | — |
| LP-7 通行证页(ps-pass) | 已修 | 新页 `subpackageMember/market/membership`,结构从上到下:<br>• 暖橙渐变底,新插图集的通行证 + 钥匙(cy-illus)。<br>• 大字「N 个主题 全部可玩」,N 来自服务端,未知时不写 0。<br>• 持有时显示「有效期至 …」。<br>• 年卡 / 月卡两张可选卡,按天最便宜的挂「最划算」。<br>• 白色胶囊按钮「开通通行证」,持有时写「续期通行证」;下面一行「不自动续费」。<br>• 可玩主题库 2×2 预览,带 ¥0 角标。<br>• 「进入游戏主题库 ›」。<br>iOS 没开虚拟支付时,按钮置灰,写「当前设备暂不可开通」,不会退回 JSAPI。 | `subpackageMember/market/membership/*`、`subpackageMember/utils/platform-pass.js` | 单测 `l-pass-platform-pass`(15 例)。<br>截图 `PS-1-pass-after`(安卓)、`PS-1-pass-ios-after`(iOS 不可开通)、`PS-1-pass-holder-after`(持有中)。 | 真机滑动、插图待机 |
| LP-8 开通成功(ps-success) | 已修 | 服务端订单 PAID 后才出现整屏层:<br>• 钥匙从左下飞入,转 90°,锁从「锁着」换成「打开」。<br>• 四枚积分币错开弹出。<br>• 三行文案(已开通 / N 个主题全部可玩 / 到期日 · 价格都是 ¥0),封面依次升起。<br>• 白色胶囊按钮「进入可玩主题库」,右上「跳过」放在胶囊下面。<br>只用 transition,没有新增 keyframes。打开「减少动态效果」时直接显示终态。 | 同上 `index.wxml/wxss/js` | 截图帧条 `PS-3-success-frames`、终态 `PS-3-success-after`。<br>rig 里用 eval 调用页面自己的 `_celebrate` 重放;rig 的支付桩走不通 PPO 回调,见下文。 | 真机动效节奏、轻震 |
| LP-9 游戏主题库页(ps-library) | 已修 | 新页 `subpackageMember/market/library`:<br>• 标题「游戏主题库」,不写数量。<br>• 筛选 全部 / 城市定向 / 自由探索。<br>• 俱乐部卡式,一行一张,底部四段黑渐变,城标。<br>• 持有通行证时挂柠檬绿「¥0 可玩」。<br>• 点进现有主题详情。 | `subpackageMember/market/library/*` | 截图 `PS-4-library-after`、`PS-4-library-holder-after`、`PS-4-library-explore-after`。 | 封面(见下文) |
| LP-10 入口 | 已修 | 「通行证与礼品」页「可购买」分段顶部加一张通行证卡,右侧写「N 个主题全部可玩」或「有效期至 …」。<br>服务端 `enabled` 为 true 才出现。 | `subpackageMember/market/passes/*` | before / after:`PS-0-passes-before`、`PS-0-passes-after`、`PS-0-passes-holder-after`。 | — |
| LP-11 确认订单 ¥0 | 已修 | 服务端报价 `platformPassCovered=true` 时:<br>• 顶部绿条「你的通行证覆盖这个主题 · 本单 ¥0」。<br>• 明细多一行「通行证 · 你已可直接游玩」,不再显示成「俱乐部会员减免」。<br>• 合计 ¥0,按钮「免费领取」。<br>• 不出通票行。<br>建单、零元单完成和防重沿用原来的链路,没有改动。 | `pages/topic/utils/checkout-view.js`、`self-play-checkout/index.wxml/wxss` | 单测:¥0 / 免费领取 / 明细 / 不出通票;未覆盖时与原来一致。原 `r2-store-checkout-view` 全过。 | **rig 未达**:rig 只有一个草稿期主题;要让收费主题真正可售,需要整套开售审核、合同快照和履约窗夹具,本 lane 没有造。所以这一屏没有截图 |
| LP-12 确认订单按原型卡片重排 | 部分 | 现有确认订单已经是 r2-store 按 Questo 卡结构重排的版本(主题头、单次购买卡、参与人、明细、信任行、同意、底栏)。本 lane 只加了持有者的差异,没有再动其余结构。 | — | — | 见待你决定 6 |
| LP-13 插图修复(跨 lane) | 已修 | 底座 `cy-illus` 的上层物件 SVG 在同一个标签里重复写了属性(共用描边串之后又写一次 `stroke-width` / `stroke`)。<br>原型里是内联 HTML,浏览器容忍这种写法;做成 data URI 后按 XML 解析,整张图都不显示。rig 和 Chromium 实测上层全空。<br>修法:在 `art.js` 里按 HTML「先写的生效」规则去掉重复属性。25 张 × 2 层全部通过 `xmllint`。 | `pages/play/components/cy-illus/art.js`(规范实现)、`subpackageMember/components/cy-illus/*`(逐字副本) | 新单测逐张检查没有重复属性;原底座单测 6 例全过。 | — |
| LP-15 启动依赖 | 已修 | CI「Isolated authenticated Redis recovery」按严格配置(禁止循环依赖)启动 jar 时失败。本机用同一份 `ci/upgrade-startup/application.properties` 复现出两个问题:<br>① `WechatPaymentOperations` 有门面和 SDK 网关两个实现,按类型注入时有歧义。<br>② 改注入门面又会形成「门面 → 回调路由 → 报名 → 通行证 → 门面」的循环。<br>rig 用的是常规配置,这两个问题被掩盖了。<br>现在和营销商品一样,用 `@Qualifier("wechatPaymentSdkGateway")` 直接注入 SDK 网关。 | `PlatformPassService` | 本机同配置启动,出现「城瘾API启动成功」;相关测试 89 例全过,含真库 IT 7 例。<br>修后 CI「isolated-authenticated-redis-recovery」在 `ed2e9b5dc` 上通过,PR 可合并状态 clean。 | — |
| LP-14 真库 IT | 已修 | `PlatformPassMySqlIT`(7 例),登记在 `marketing-db-acceptance`。<br>它发现了两个真问题,都已修:<br>① Map 结果的时间列,驱动返回 LocalDateTime;JVM 时区和连接时区不同时会差 8 小时,原来的强转还会直接抛错。现在改成显式 Date 结果映射。<br>② 退款后段前移按秒向下取整,会留不到 1 秒的空档。现在改成向上取整。 | `chengyinhub-admin/src/test/java/.../it/PlatformPassMySqlIT.java`、`ci/real-storage-it-registry.json`、`ci/test_real_storage_it.py`(批次合计 229 → 236) | Real Storage IT(manual)在 `b26998eb` 上:<br>• run 42858215860:**UTC,7/7 PASS**<br>• run 42858222820:**Asia/Shanghai,7/7 PASS**<br>前两次失败(42842593786、42852963366)就是上面两个问题。本机用 rig 的 MySQL 8 复跑也是 7/7。 | — |

## 开关(全部默认关闭,只能在部署配置里打开)

| 配置键 | 作用 | 默认 |
|---|---|---|
| `platform-pass.enabled` | 总开关:出售、¥0 报价、领取、主题库 | false |
| `platform-pass.jsapi-enabled` | 安卓 / 开发者工具的 JSAPI 渠道 | false |
| `platform-pass.xpay.enabled` | iOS 虚拟支付渠道 | false |
| `platform-pass.xpay.offer-id` | 虚拟支付 OfferID | 空 |
| `platform-pass.xpay.app-key` | 现网或沙箱 AppKey。**密钥,只能经环境变量注入**,例如 `PLATFORMPASS_XPAY_APPKEY`,不写进仓库 | 空 |
| `platform-pass.xpay.env` | 0 现网 / 1 沙箱 | 1 |

套餐上架:迁移把两个套餐写成 `status=0`。确认价格后在库里执行 `UPDATE platform_pass_plan SET status=1, xpay_product_id='<道具ID>' WHERE plan_code IN ('YEAR','MONTH')`,要走 chengyin-db-migrate。

## 你要在商户平台 / 公众平台做的事

1. **微信支付商户平台(JSAPI)**:沿用现有商户号和回调 URL(`/api/registration/callback`,退款回调同原配置),不用新建产品。请确认商户号的经营类目能卖「会员 / 虚拟服务」。
2. **小程序虚拟支付开通**:在公众平台 → 功能 → 虚拟支付申请开通。这需要企业主体、已认证,并按指引签约和填写结算信息。
3. **拿配置**:在虚拟支付「基本配置」里记下 OfferID、**沙箱 AppKey** 和 **现网 AppKey**。AppKey 只放进部署环境变量。
4. **建道具**:在「道具管理」里新建两个道具直购商品:年卡(29900 分)和月卡(3900 分)。道具 ID 回填到 `platform_pass_plan.xpay_product_id`。**道具价格必须和套餐价一致**,后端按套餐价签 `goodsPrice`。
5. **消息推送**:现有消息推送 URL 是 `https://<域名>/api/content-sec/callback`,明文 JSON,token 在 sys_config 的 `wx.msgpush.token`。请确认虚拟支付的发货推送和退款推送也走这个 URL。不要改成安全模式,本接口不解密密文。
6. **沙箱联调**:`env=1`,用沙箱 AppKey。沙箱不推送发货通知,前端支付成功后会调 `/confirm` 回查开通。
7. **上线**:切到 `env=0` 和现网 AppKey,打开 `platform-pass.enabled` 和 `xpay.enabled`。请确认 iOS 虚拟支付的分成 / 手续费,以及结算到账账户。
8. **服务类目和合规**:请确认小程序的服务类目允许售卖虚拟会员;用户协议 / 购买须知里写明「不自动续费、期间内官方主题 ¥0、已领取过主题不可整单退」。
9. **后台权限**:给需要退款的管理员角色授 `business:platformPass:refund`。本 lane 没有加菜单 SQL。

## 待你决定(括号里是已经实现的默认)

1. **覆盖范围**:哪些主题算「通行证内」?(官方主题,即内容槽 = PLATFORM,并且开放自玩、售票中或进行中。玩家 / 俱乐部发布的主题不在内。)
2. **持有者怎么玩**:(点「免费领取」生成一张 ¥0 自玩票,到期不晚于通行证本段,照常占自玩配额,只覆盖本人 1 席。)
3. **退款规则**:(只有没领取过任何主题的通行证能整单退;领取过就算已交付。部分退款没有做。)
4. **价格**:(年卡 ¥299 / 月卡 ¥39,和原型一致,上线前默认未上架。)
5. **¥0 单与商家结算**:官方主题里如果有商家站点,¥0 单按现有零元单口径,商家不产生分成。要不要从通行证收入里给商家补偿,请和 l-payout 一起定。(默认不补。)
6. **确认订单里的通票行**:原型说结账里不放通票。(持有者不显示;非持有者暂时保留 r2-store 已有的「覆盖本主题的城市通票」行,避免改掉已批准的 r2-store 行为。如果要全部去掉,只需要在 `checkout-view.js` 里让 `passes` 恒为空。)
7. **续买**:(从最后一段到期接着算,不重叠、不浪费。)
8. **iOS 没开虚拟支付**:(按钮置灰,写「当前设备暂不可开通」,不允许 iOS 走 JSAPI 买虚拟商品。)
9. **兑换码 / 礼品通行证**:不在本 lane 范围。(不做。)

## 跨 lane

- **qx-base / l-play / l-after**:`cy-illus/art.js` 的重复属性已经在规范实现里修好。其他分包如果已经复制了 cy-illus,需要重新复制一次,单测会检查副本和规范实现逐字一致。
- **qx-final(Gameplay HTTP E2E / chain acceptance)**:测试桩 `scripts/e2e/E2eWechatPayStub.java.stub` 的回调只认 CLM 和报名单号,PPO 回调在 rig 和链路验收里走不通。生产路径走 `WechatPaymentCallbackRouter`,已经有单测覆盖。如果要端到端验收,需要给这个桩加 PPO 分支,让它转交 `WechatPaymentCallbackRouter`。本 lane 只写了 rig 用的 `rig/rig-pay-platform-pass.diff` 作为参考,没有提交桩改动。
- **l-payout**:通行证属于「平台是卖方」的收入,和二级商户分账无关。待你决定 5 需要两边一起定。

## rig 与截图

- 环境:rig 分支 `claude/cloud-walk-20261101-rig`,打了三个已有补丁:`harness-patches.diff`、staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`。另外加了本 lane 的 `rig/rig-extra-args.diff`,用来给 rig 应用传 `--platform-pass.enabled=true --platform-pass.jsapi-enabled=true`。
- 后端:本分支 `141a8d45` 的 jar,真实 MySQL 8 和 Redis。
- 前端包:after 用本分支,before 用 `ca1f85b68`(launch 底座)。
- 夹具 `rig/seed-pass.sql.txt` 只写 rig 测试库:
  - 两个套餐上架。
  - 六个官方开放自玩主题。
  - 9004 有一笔「已付月卡 + 30 天有效期」,代替已验证的支付,只用来拍持有态。
- 截图共 12 张,都是 390×844、JPEG q70,每张我都看过:
  - `PS-0-passes-before` / `PS-0-passes-after` / `PS-0-passes-holder-after`
  - `PS-1-pass-after`(安卓可开通)/ `PS-1-pass-ios-after`(iOS 未开虚拟支付,置灰)/ `PS-1-pass-holder-after`
  - `PS-2-failed-after`:真实走了建单 → 拉支付参数 → rig 支付桩失败 → 服务端回查未付 → 红字「开通失败,请稍后再试」。没有冒充成功。
  - `PS-3-success-frames` / `PS-3-success-after`
  - `PS-4-library-after` / `PS-4-library-holder-after` / `PS-4-library-explore-after`
- 封面没有显示:rig 里主题封面用的是本地 URL,被产品的媒体审核输出门(`AppMediaOutputConfiguration`)按「未审核外链」清成了空串。这是产品本来就该有的行为,真数据里的已审核封面会正常显示。卡片在没有封面时退回到 photo-card 的底色。
- 安卓截图:rig 默认模拟 iOS。拍安卓状态时,用 eval 把 `wx.getSystemInfoSync().platform` 改成 android 再重开页面,这只是 rig 侧的改动。

## 门禁

- 后端:
  - `mvn -q -DskipTests package` 通过。
  - 相关单测 30 例全过:`PlatformPassServiceTest` 19、`XpaySignerTest` 5、`PlatformPassRouteTest` 3、`PlatformPassSelfPlayCoverageTest` 3。
  - 原有路由、自玩、金额合同测试全过。
- 真库 IT:`PlatformPassMySqlIT` 7/7,UTC 和 Asia/Shanghai 两次 dispatch 都通过。
- 迁移:`ci/migration-safety-check.sh`、`ci/migration-layout-check.sh` 通过;`ci/test_real_storage_it.py` 92 例通过。
- 小程序:
  - `UI-GATE-0:PASS`。
  - 台账重建后 5949 条,PASS。
  - 路由矩阵重建 167 → 169。
  - 台账、GET 权限、标题、路由的分母已同步,写入 GET 权限矩阵 4 条。
- 小程序全量单测:17159 例,**10 例失败,和底座 qx-base 记录的 10 例完全相同**:返回栈 settle、rank-board 选择器、settle 标题、keyframes 棘轮 169 > 146、rank-board ActionSheet、原生 toast、页面清理 settle、rank-board 按压类、减动效覆盖、故事文字动效。本分支新增的失败为 0。
- 本分支没有新增 keyframes,新样式全部用 transition,并有「减少动态效果」终态。
- 主包:只改了 `app.json` 的两行分包页;`wechat-release-contract` / `mainpkg-leaves-contract` 通过。新页面全部在 `subpackageMember` 分包。
- 管理端:没有改 `chengyinhub-ui`,没有跑 `npm run build:prod`。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。
- 推送前 diff 已检查,没有密钥。
- 共享文件改动:`app.json`(+2 页)、`scripts/uiaudit/action-ledger.json` 和 `route-matrix.json`(重建)、`build-action-ledger.js`(登记通行证开通这个多写控件)、`docs/design/miniprogram-page-cleanup-1009.md`(+2 行)。

STATUS: PARTIAL - 持有者「确认订单 ¥0 / 免费领取」这一屏 rig 未达(没有可售收费主题夹具),没有截图;虚拟支付和 JSAPI 的真实回调、退款都没有在真机或微信沙箱联调;确认订单按原型卡片重排沿用 r2-store 现有结构(待你决定 6)
