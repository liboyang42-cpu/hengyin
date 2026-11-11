# Launch audit gaps (2026-11-11)

Source: lead's audit of the owner's private spec documents against origin/launch/questo-20261110 (907 items; 418 done, 161 partial, 240 not done — most not-done are deferred §5 refactors/gated features). Below: launch blockers and fix groups. IDs refer to the owner's specs; evidence paths are on the launch branch. Owner decision already made: W2-16 merchant first-run uses the PREVIOUS version, not the building-block logo (owner: 「不要用积木的这个啦」). Owner already accepted the StoreKit app-store-server-library dependency (accepted the architecture-gate red on 2026-11-09).

## 三、上线阻断（合并去重，按优先级）

### A. 资金与安全（必须在上线前处理）

1. **ACTION-01 旧微信支付私钥证书未撤销**：资金安全，需业主或商户负责人操作。
2. **分账与通行证资金路径没做过真机验收**：
   - l-payout2 和 l-payout3 改了下单、回调、退款、结算，开关默认全关；通行证有 iOS 虚拟支付和 StoreKit。
   - 实施文档一的 25 张 P0 真实角色验收卡（资金、核销、越权、售后、注销）一张没跑。
   - 上线前至少要在开关全关和目标开关状态下，各跑一遍支付、退款、对账的真机验收，并确认资金流向不是平台代收再分（二清）。
3. **生产审核回调 token 未配（F-L5C-08）**：先发后审模式下，违规图片不会被自动下架。
4. **新依赖 `app-store-server-library` 没有报备**（`chengyinhub-system/pom.xml`，5d7cdf82f）。AGENTS.md 要求引入新依赖先问业主。它服务于通行证的 iOS 购买，需在合并说明里请业主确认。
5. **P0-11 俱乐部快提**：开关 `withdrawal.fast-withdraw.enabled` 必须保持关闭，直到业主定下提现口径（实付，还是应结+调整）。
6. **P0-17 收益明细表**：`creator_income_detail` 还没有幂等唯一键，现在没有任何写入路径。不能新增写入。

### B. 业主或运维配置（不做功能直接坏）

1. **E-4 / G-6 / P-59 全站二维码空白**：要核对应用机的 AppSecret、OSS、正式版页面、Redis。邀请码、入场码、票夹码、分享卡都受影响。
2. **E-1 腾讯地图 Key**：影响首页城市识别、漫游和编辑器的地点搜索。
3. **G-12 / P-45 深色地图 subkey**；**P-60 剧情字体要上传 OSS，并配置 CORS 和 downloadFile 合法域名**。
4. **0.1 版本对齐**：真机包和应用机 jar 都必须来自同一个 launch 头。
5. **生产 ALARM_WEBHOOK 是否注入**（O-11），没有证据。
6. **生产迁移**：
   - 按差异逐文件核对，不能重跑旧的整套清单。
   - 范围包括 D-PAYEE、通行证、分账、合单的新迁移，以及 M-31 `migration_20261027_merchant_brand_images_to_gallery.sql`、`migration_20261029_unlist_test_topics.sql`。
   - D1 积分配置是可选项。
   - 本候选还没有生成 release-manifest。
7. **线上脏数据**：E-2 删除玩家账号的商家行，E-3 / P-09 清理测试主题和 QA 分群。需业主授权。

### C. 业主明确要求、但上线候选上还缺的

1. **B v2 图标没有进入任何远端分支**（l-icons / l-iconsB 在 origin 上不存在）。业主 11-11 04:53Z 已批（"both ok"）。涉及 V3-25、V-19、W2-17，以及商家「发现」tab 仍用握手图标。
2. **游戏核心没有真机复测**：V3-04 摇一摇、V3-05 进度游标、V3-07/08/09 通关回看与选章（R29 记 PARTIAL）、V3-11 相册全屏、V-03 黑屏。业主把「游戏能玩」列为重点。

### D. 合规（建议上线前处理，或由业主或法务明确放行）

1. **AIGC 显式标识和备案号公示没做**（P0-14 / P2-020）。小程序能用 AI 生成主题（`pages/publish/simple`）；AI 生成日志没有留存期和清理（P0-10）；NPC 对话 token 计数恒为 0（P0-30）。
2. **OFF-AI-DEL-01**：AI 对话记录的删除只能在 NPC 聊天面板里做，设置页（`pages/shezhi/shezhi.wxml`）没有入口。
3. **T-37 SOS**：户外游戏没有紧急求助入口。
4. **free-explore 套票页**向玩家显示 ID 和「回读」「原请求」「不代表…」等内部用语，违反 10-23 文案规则。套票开关一开玩家就会看到。
5. **众筹送礼**：后端 `/api/gift/list` 仍在，小程序没有入口（P0-69 / GIFT-01），是否下线需业主决定。

## 四、缺口分组（每组可作为一条修复线，按优先级排序）

| # | 线名 | 范围（条目） | 主要文件 | 要做什么 |
|---|---|---|---|---|
| 1 | 图标 B v2 落地 | V3-25、V-19、W2-17、V3-30 | `chengyinhub-xcx/components/cy/cy-illus*`（四份副本）、商家 tabBar 图标 | 把 l-icons / l-iconsB 推到 origin 并合入 launch；商家「发现」换新图形；复核图标居中 |
| 2 | 资金验收与证据 | ACTION-01、P0-07、P0-11、P0-17、通行证、分账、合单 | `WxPaymentCallbackMutationService.java`、`WechatPaymentSdkGateway.java`、`sys/payout/*`、`PayoutSwitches.java`、通行证 Mapper | 在合并头上跑开关全关、目标开关两种状态的下单、回调、退款、对账端到端对照；跑 25 张 P0 角色卡；给 `creator_income_detail` 加唯一键迁移（备将来用）；在合并说明中报备 app-store-server-library |
| 3 | 发布清单与迁移差异 | 实施文档一 G4、M-31、U-074 / P2-023 | `scripts/release-manifest.sh`、`chengyinhub-admin/sql/migration_*` | 生成本候选的 release-manifest，逐文件标「已应用 / 需授权 / 不适用」；重新生成基线 schema |
| 4 | 合规入口 | P0-14、P2-020、P0-10、P0-30、OFF-AI-DEL-01、T-37、TEAM-REPORT-01 | `pages/publish/simple`、主题详情、关于页、`pages/shezhi/shezhi.wxml`、`AiGenerationLog*` + Quartz、`subpackageRoam/nearby`、`my-teams` | 加「AI 生成」标识和备案号；设置页加「AI 对话记录」（导出 / 清除，复用 `/api/ai/npc/chat/data/delete`）；日志留存清理；记录真实 token；SOS 热线入口；附近队伍卡接上举报菜单 |
| 5 | 游戏回看、选章与真机复测 | V3-04、V3-05、V3-07、V3-08、V3-09、V3-11、V3-53、V-03 | `pages/play/index.js`、`utils/play-history`、章节选择器 | 补齐通关后上滑回看的呈现；确认选章控件可见和通关态；查顶部缺图；三条进入路径真机复测 |
| 6 | 帖文发帖入口 | P-20、M-33 | `subpackageMember/components/cy/profile/index.{wxml,js,json}`（`index.wxml:258-261`） | 本人主页帖文 tab（玩家和商家）加 `cy-post-compose` 入口，空态上方也要有 |
| 7 | 死页和入口 | P0-79、PRESET-02、1.1.6、P2-015、P0-108、P0-109、OFF-FAV-01 | `subpackageRoam/official-routes`、`official-route`、`subpackageRoam/poi-detail`、`pages/mylike` | 官方路线加入口，或从 app.json 下线；据点页加评价区和半屏形态；我的收藏加「据点」tab |
| 8 | 商家页收尾 | M-18、M-35、G-10、G-5、G-8、P-43、M-12、SPEC-3.4-doc11、P0-42、P1-016 | `pages/merchant/team/*`、商家可达且写死深色根的页面、`pages/merchant/marketing/ai-insight`、`subpackageB/pages/im/list`、`utils/identity-sync.js`、`utils/tag-library.js`、`pages/publish/simple`、`pages/merchant/index/index.js`、`utils/citynode-redeem-outcome`、`chengyinhub-ui/src/views/business/crm/index.vue` | 经营团队按门店信息版式重排（先出原型）；商家浅色逐页清查并加 lint；店铺参谋和消息页订阅身份同步；俱乐部和主题标签接入标签库；AI 快速配置页重做；核销台加「复核」态和 CONFLICT 中文提示；CRM 改为远程搜索 |
| 9 | 漫游地图补全 | P-32、V3-35、G-11 | `pages/roam/index.*`、`scripts/roam-catalog-import.js`、`components/cy/scene-share-invite`、`utils/page-motion.js` | 主地图加活动和队伍底部横滑卡；导入 33 个目录地点的坐标；二维码出现动效；漫游打开动效还原 |
| 10 | 套票玩家端（开售前） | T-07、T-08、T-09、T-16、T-17、SPEC-2.1-1、SPEC-8-1 + 文案 | `subpackageMember/free-explore/*` | 先清除 ID 和内部用语；开售前补凭证二维码与轮询、预约、退款进度、核销结果、「不限次」、预付提示 |
| 11 | 定向和 RPG 玩家闭环（按灰度） | T-25、T-26、T-28、T-36、PLAY-CHECK-01、PLAY-CALL-01、1.3.8、魔鬼交易入口、SPEC-7.3-1、P2-039、P2-009 | `pages/play/*`、`playkit-journey-check`、`journey-check-recovery.js`、`subpackageJourney` | 接上 orient 任务提交和状态接口、`/check/rest`、开场通话、交易入口、「换一种方式完成」 |
| 12 | 小修 | P-56、P-41、O-02、O-15、U-167、TOPIC-SK-01 | `pages/gerenziliao/gerenziliao.wxml:55`、官方模板种子迁移、`utils/geo.js` + `publish-stats.js`、publish-check | 手机号行换成修改图标；规则种子按步骤拆分；合并两份 haversine；revenue_share 校验；publish-check 加 warning 档 |
| 13 | 社区小功能（可延后） | P1-041、P0-96、P0-76、P0-75、P0-78、U-119 | `pages/square`、`pages/club/detail`、`ApiClub*`、`ApiCouponController.java` | 加「不感兴趣」；入组申请可撤回；贡献榜显示本人行；领券；成长任务；徽章分享 |
| 14 | 技术债（不影响上线） | §5.1 延后的 69 条 + §5.2 / §5.3 代码整理项 | `pages/roam/index.js`（`_fogToPoly` 5 处）、约 70 个文件的 isBizOk、116 处 `wx:key="*this"`、`ApiClubSettlementController.java:142` | 按模块拆小 PR |
| 15 | 业主待决清单 | 约 30 条，见下 | — | 整理成一页交业主逐条拍板，再拆线实现 |

