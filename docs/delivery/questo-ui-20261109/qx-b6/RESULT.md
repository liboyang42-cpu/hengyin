# qx-b6 · 管理后台（chengyinhub-ui）结果

分支 `claude/qx-b6-20261109`，基于 `origin/integrate/questo-ui-20261110`（449fb80d）。合同来源：`docs/questo-teardown/UI优化施工清单.md`。

## 证据怎么来的

- **Rig：** 用 `claude/cloud-walk-20261101-rig` 的 `up.sh`，候选指向本分支 HEAD，真 MySQL 8 + Redis 7、本分支的 jar（包括新端点、新迁移）。管理后台是 `npm run build:prod` 的产物，用一个只做静态文件和 `/prod-api` 转发的小服务托管（rig 工具放在 scratchpad，不进产品代码）。
- **数据：** 截图里的数据全部通过真实端点写入：管理端建活动并启用、作者端（9003）投稿、管理端审核、核验组、录证据、重算；作者落地信息和译文走 `/api/content-landing/*`；渠道主体走管理端登记；漫游会话走 `/api/compliance/consents` 和 `/api/roam/reveal`。唯一直接改库的是给 rig 的 `admin` 和 `e2e_operator` 设置一次性密码，因为默认口令被产品拦截。
- **截图尺寸：** 管理后台是桌面网页，所以截图是 **1280 px 宽**，JPEG 质量 70，不是 390 px。390 px 的规则针对小程序；后台在手机宽度下没有可审的布局。
- **测试：**
  - 后台单测 `node --test tests/unit/**/*.test.js`：**618/618 通过**，其中新增 `questo-admin-display-contract.test.js` 16 例。
  - `npm run build:prod` 通过。
  - Java：`TopicWorkspaceServiceImplTest` 13、`ContentLandingTranslationReviewAuthorityTest` 16、`VideoBountyGroupVerificationTest` 12，全部通过。
  - 迁移门：`ci/migration-safety-check.sh` 和 `ci/migration-layout-check.sh` 通过；`ci/role_permission_contract.py` 通过。

## 逐项结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证的 | 真机仍未验 |
|---|---|---|---|---|---|
| U03-04 | fixed | 工作台的「发布与治理上下文」加一张「内容版本」卡：公开版 `publishedConfigVersion`、编辑版 `configVersion`，并标明两者是否一致。为空时显示「尚未公开」或「编辑版本未知」，不补 0。后端 `Governance` 投影补这两个字段。 | `topic/workspace.vue`、`TopicWorkspaceDTO`、`TopicWorkspaceServiceImpl` | `U03-04-before/after.jpg`、Java 单测断言 7/5 和 null | 两人同时审核的冲突读回；被审版和公开版的内容差异（后端没有修订差异读面） |
| UG-12 | needs owner | 未做界面。后台读不到提示阶梯和局内明细：`/api/play/help*` 只有玩家端，没有管理端只读投影，也没有答案和秘密字段的隔离读面。默认：后台不出现任何入口。 | — | 核对过 `ApiPlayHelpController`；后台控制器里没有对应端点 | — |
| U07-05 | fixed（部分） | 结算汇总和对账卡片的未知值显示 —，不再写成 0；`status=1` 明细的合计从「已结算」改为「已对账」，净额改为「对账净额」，避免把确认对账读成已打款。营销分配（§19）另开只读页，见下一行。 | `settlement/index.vue` | `U07-05-before/after.jpg`、单测 | 父单、子票、账本引用的钻取：B8 `CreatorRevenueLedgerVO` 没有后台读端点 |
| §19（U07-05 关联） | fixed | 新页面「营销分配」，只读。可按订单或按作者查：状态按码显示（应计 / 可结算 / 已打款 / 部分冲回 / 全额冲回 / 待确认）；应计分项和退款冲回分开列；显示投递任务。不调用推进、应计、冲销等写端点。`data=null` 显示「收入尚未入账」，403 显示「该功能尚未对当前角色开放」。 | `revenue/index.vue`、`api/business/revenue.js` | `S19-revenue-empty.jpg`、单测（API 文件里没有 post 调用） | 有分配数据时的样子（fresh 库没有营销订单） |
| U08-03 | needs owner | 未做。「问题 → 原内容 → 修复草稿 → 复测 → 新版本 → 关闭」这条维护链后端没有（PROPOSED）。默认：不画入口。 | — | — | — |
| U08-04 + §27.4 | fixed | 新页面「有效复报」，接 `GET /business/analytics/post-game-outcome`。`repeat_signup_v1` 的付费和全部报名两组并列，分别显示分子、分母、未成熟人数、部分退和待审退、迟到事实；零成熟样本显示「未成熟/无可计算样本」，`available=false` 显示 `unknownReason`。V2 旧口径照旧显示，`mature=false` 时写「未成熟」。页面还显示水位、`basisVersion`、计算时间、「即时计算，不可历史回放」、「未排除（无服务端标记）」，客户端观测项标「客户端观测，非最终结果」。7 日和 90 日不显示 v1。 | `analytics/postgame.vue`、`api/business/analytics.js` | `U08-04-30d.jpg`、`U08-04-7d.jpg`、单测。rig 在漫游表为空时，页面显示服务端原文「ROAM_REVISIT 数据截止时间为空，拒绝把未知数据状态报告为 0」，不显示 0% | 有真实成熟样本时的数值 |
| U08-05 | needs owner | 未做。新玩法分数入榜属 PROPOSED，`LeaderboardRankPolicy` 不读分数，后台也没有榜单治理端点。默认：不加空榜。 | — | — | — |
| §27.7 | fixed | 原退款处理台已经把申请、批准、派发、渠道成功、人工复核分开显示（保留不动）。「发起售后」的「来源 ID」改为「交付行 ID」，指向真实交付的 registration 行；金额由服务端按冻结份额校验（`ensureWithinOrderCash`）。 | `finance/refund/index.vue` | `S27-7-before/after.jpg`、单测 | 组合包真实退款到账 |
| §18 E | fixed | 新页面「视频悬赏」，三个标签：<br>• 活动：费率、封顶、预算都标「参考值」，表单写「参考值，非已批准费率」；没有现金兑付开关，提交恒为 0；`RULE_LOCKED_BY_ACCRUAL` 和 `OWNER_LOCKED_BY_ACCRUAL` 会把对应字段置灰，并在字段下方用红字提示「请新建活动」。<br>• 投稿审核：通过时必须填发布时间，驳回时必须填理由；撤销必须填理由；可录外部证据（`sourceRef` 必填）；重算应计（只记录）。<br>• 应计：只读，状态为「已记录（未开放兑付）」，没有打款按钮；空和读取失败分开显示。<br>新页面「主持小费」：按主持会员只读，没有「标已付」。 | `videobounty/index.vue`、`hosttip/index.vue`、`api/business/{videobounty,hosttip}.js`、`utils/questo-admin.js` | `E18-campaigns`、`E18-submissions`、`E18-accruals`（¥25.60，已记录未开放兑付）、`E18-recompute-unverified`、`E18-hosttip-empty` | 真实平台播放数据 |
| §18 F | fixed | 「原视频组」区块显示作者自报（标「建议值，不参与封顶」）、核验组键、核验人、核验时间、证据引用、核验版本。核验必须填证据引用和理由，缺失时字段下方红字提示。<br>`GROUP_LOCKED_BY_ACCRUAL` 时输入框置灰，提示「该投稿已有应计，原视频组不可再变更」。<br>`GROUP_VERIFY_VERSION_CONFLICT` 时显示刷新按钮，不自动重试。<br>`ACCRUAL_GROUP_UNVERIFIED` 时提示「请先核验原视频组」。<br>列表显示「未核验，暂无应计」。<br>归因：`UNBOUND` 和 `OWNER_NOT_SET` 只显示原因，数字保持 null。<br>**后端缺陷已修：** 核验审计的 `domain_type` 原为 `VIDEO_BOUNTY_SUBMISSION`（23 字符），超出 `VARCHAR(16)`，在真库上**每次核验都 500**。现改为 `VIDEO_BOUNTY`，`target_type` 不变。 | 同上 + `VideoBountyServiceImpl.java:1063`、`VideoBountyGroupVerificationTest` | `F18-verify-locked.jpg`、`E18-submissions.jpg`（展开行）、Java 测试锁定三列长度、rig 真库复现后已修 | — |
| §20.1 | fixed | 新页面「落地译文审校」：列表只含已公开主题，团体和学校为 null 时显示「未声明」。打开后读取译文正文、状态和版本；审校前先取摘要，提交时原样回传，并且理由必填（≤200 字，必填提示用字段下红字）。409 显示「内容已被作者修改，请刷新后重新审校」，提供刷新、不自动重试；403 显示「该功能尚未对当前角色开放」。没有批量批准，也不编辑作者文字。<br>**后端补了一个只读端点：** `GET /business/content-landing/translation/detail`。原来后台只能拿到摘要哈希，看不到要审的正文和版本。它用同一个审校权限，只读，主题未公开时返回 409。 | `contentlanding/index.vue`、`api/business/contentLanding.js`、`ContentLandingMetadataAdminController`、`IContentLandingMetadataService`、`ContentLandingMetadataServiceImpl` | `S20-1-list`、`S20-1-translations`、`S20-1-review`；Java 新增 2 例（返回正文与版本、不写库；未公开 409） | — |
| §20.2 | fixed | 新页面「渠道兑换码」：<br>• 渠道主体：登记、启用和停用。<br>• 渠道订单：后台只填单号和引用，金额和商品由验证事实给出；失败按 reasonCode 显示（如 `PROVIDER_NOT_CONFIGURED` 显示「渠道未配置，暂不可验证」）。<br>• 发码：只能用已验证的记录，同一次发码沿用同一个 requestId；明文码只在首次响应显示一次，关闭即丢弃；`replayed` 或 `codeAvailable=false` 时只恢复元数据，不提供重新取码。<br>• 撤销：理由必填；`revokePending` 显示「待渠道退款确认」。<br>• 对账：只读，显示渠道验证金额和差异，不显示已结算或可提现。 | `channel/index.vue`、`api/business/channelVoucher.js` | `S20-2-accounts`、`S20-2-verify-unavailable`（服务端原文：渠道未启用、合同缺失或不在生效窗口）、`S20-2-reconciliation` | 真实 provider，以及成功发码的那一次显示（rig 里 provider 未配置，无法走到） |
| 菜单与权限 | fixed | 新迁移 `migration_20261109_questo_admin_menus.sql`：<br>• 新建 6 个页面菜单和 7 个按钮权限。<br>• 只写 `sys_menu`，**不写 `sys_role_menu`**；文件末尾的断言要求这些菜单的角色授权为 0。<br>• 在 `ci/migration-dependencies.json` 登记，并依赖全部旧的按模式批量授权迁移（`web_admin_roles_20260719` 等）。原因：全新建库时，那些迁移若排在本迁移之后，会把新菜单自动授给 4 个角色（rig 上实测出现过 24 行，加依赖后为 0）。 | 迁移 + 依赖 json | rig 全新建库读回授权为 0；用 `e2e_operator` 访问新页面得到「找不到这个页面」（`AUTH-operator-*.jpg`）；迁移重复执行两次断言仍为真 | 生产库上的执行（需走 db-migrate 预检和 owner 确认） |
| 共用 | fixed | `request.js` 增加一个可选项 `rawBusinessError`：只在业务失败（code≠200）时把带 `reasonCode` 的原响应交给调用方，由页面就地用红字显示；401 仍先走重新登录，其它调用不受影响。 | `utils/request.js` | 单测断言顺序；rig 上先抓到一次成功响应被误判失败，已修 | — |

## Cross-lane

- 后端（Questo 后端负责人）：UG-12 需要一个后台只读读面，包含提示配置摘要、局内提示/跳过/净分逐笔明细和回执号，并且答案字段要隔离。U08-03 的维护单链和 U08-05 的榜单治理同样缺读面。
- 后端：`VideoBountyServiceImpl` 的审计 `domain_type` 超长已在本分支修复，见上表。如果其它分支也用 `business_audit_event` 写长于 16 字符的 domain，会以同样方式 500，建议统一检查。
- 管理端遗留：`/business/videobounty/submission/list` 返回的 `sourceUrl` 为空串（服务端隐去），页面改为显示 `canonicalVideoKey`。

## Needs owner（默认做法已实现）

1. 新菜单授给哪些角色：默认不授，只有超管能用。
2. UG-12、U08-03、U08-05：默认不出现入口，等后端读面。
3. 管理后台截图宽度：默认 1280 px（桌面后台），不是 390 px。

STATUS: PARTIAL - UG-12、U08-03、U08-05 缺后端读面，未做界面（needs owner）；§20.2 成功发码、§19 有分配数据两种状态，因 rig 里没有 provider 和营销订单，未截到
