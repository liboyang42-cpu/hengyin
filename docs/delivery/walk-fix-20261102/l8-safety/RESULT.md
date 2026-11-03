# Round 5B（l8-safety）：上线前内容安全

分支 `claude/walk-fix-1103-l8-safety`，从 `origin/master` 切出，draft PR #721。逐项提交，一项一推送。
规则是用户提交的内容未经检查不得给别人看，客户端也不能在任何地方塞任意外链图片。

所有结论都来自读代码和单测。没有动生产库，没有部署，也没有在 rig 里跑真实 HTTP。所以这里只证明了单元层面的行为，下文会写明哪些没在真机或真库上验证。本轮没有改任何可见 UI 文案或样式，item 8 的替换图沿用仓库里已有的占位图。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 |
|---|---|---|---|---|
| 5B-1 评论图片 | fixed | 先核对了调用方：`pages/topic/index`（主题评价）和 `scene-play-activity-detail`（活动评价）确实会传 `img_arr`，`pages/square/detail` 传空，所以接 guard，没有整体拒绝。`img_arr` 改走 `AppMediaReferenceGuard.requireAttachments(member, …, false)`：外链和他人未审核的媒体返回 422；本人上传的媒体落库为 `media-asset:N`，读侧由既有 `AppMediaOutputConfiguration` 按审核状态出图（未审核只有本人能看到）。 | `ApiCommentController` | `ApiCommentMediaGuardTest`：外链被拒，且不发布、不送检；本人上传落成 `media-asset:41`。旧代码上这个测试是红的。 |
| 5B-2 旧广场 | fixed | 当前客户端的发帖、点赞、收藏、删除、举报和广场评论，都由 `utils/community-post-api.js` 映射到 `/posts`，已经不走旧入口。所以三个控制器里的 `community.post.legacy-writes-enabled` 默认值改为 `false`，旧入口回 426。 | `ApiCreativeSquareController` / `ApiCommentController` / `ApiTopicController` | `ApiCreativeSquareCommunityPolicyTest.legacyCommunityWritesDefaultOffBecauseCurrentClientUsesPostsApi`（旧代码上是红的）。 |
| 5B-3 先发后审门禁 | fixed | `ContentSafetyGateImpl` 在 POST_REVIEW 下遇到「要人工 / 审不了 / 异常 / 缺 openid / 检测关闭」，以前是打标后照常公开，现在改为 HOLD。带图但图片送不了检（总开关关或缺 openid）也 HOLD（`MEDIA_UNCHECKED`）。各调用方的处理：**主题**（create 与 update）复用内容安全留置，做法是 `user_status=0`、`check_status=2` 加原因，再挂一张复核上架单 `content_safety_relist`，审核台通过后由既有 `review()` 自动上架；草稿只留置，发布草稿时走既有的留置分支。**活动**的 create 与 update 都把图片交给门禁，HOLD 走既有待审（`preReview=true`）。**优惠券**被 HOLD 时以 `status=0` 保存，不能领取（发券 SQL 已排除 0），并挂进审核台，人工通过后由 `releaseHeldCoupon` 用 CAS 从 0 改为 1。`WxContentSecurityServiceImpl` 在检测关闭或缺 openid 时，原本静默跳过图片送检，现在会打 ERROR 日志。 | `ContentSafetyGateImpl`、`TopicTextSafety`、`CmsTopicServiceImpl.holdUncheckedTopicForReview`、`ApiTopicController`、`ApiActivityController`、`ApiCouponController`、`AuditTaskServiceImpl`、`SmsCouponMapper.xml`、`IWxContentSecurityService.canSubmitMedia` | `ContentSafetyGateImplTest`（5 个「审不了 → HOLD」，外加图片送不了检 → HOLD）、`TopicUncheckedHoldTest`、`CouponPublishReviewModeTest.heldDecisionSavesCouponAsNotIssuableAndQueuesReview`、`ApiTopicPostReviewTest`、`ActivityPublishReviewModeTest` / `ActivityUpdate*Test`（旧的「先公开 + 抽检单」断言已改为 HOLD）。机审明确通过时仍然直接发布：`cleanText_pass…`、`simpleTopicWithCleanText_publishesWithoutAnyAuditTask` 都通过。 |
| 5B-4 review 回调和据点模板 | fixed | 回调里 `suggest=review` 以前回 400，内容继续公开；现在按 risky 走既有下架或隐藏分支，再由 `queueMediaReview` 挂进审核台（主题挂复核上架单，其它类型挂 `submitSafetyHold`）。据点模板（bizType 7）的 risky 以前被 `WxContentCallbackMutationService` 记成「人工」后丢掉，现在照常锁行、核对当前媒体，然后下架（`status=2`、`publish_status=0`、`check_status=2`），并挂 TEMPLATE 审核单。 | `WxContentSecurityServiceImpl`、`WxContentCallbackMutationService`、`WxContentCallbackInboxMapper.xml` | `WxContentSecurityServiceImplTest.reviewSuggestionHidesContentAndQueuesHumanReview`、`reviewedActivityImageIsQueuedOnTheAuditWorkbench`、`WxContentCallbackMutationServiceTest.nodeTemplateLocksTargetAndMutatesInsteadOfDiscardingRisky`。 |
| 5B-5 media-asset 当成 URL 送检 | fixed | 在送检的唯一出口 `submitMediaCheckOne` 统一解析：`media-asset:N` 先用 `AppMediaAssetService.securityCheckFetchUrl` 换成可抓取的地址（本地图用发布地址，OSS 用 1 小时短签名）再送微信；回调身份仍是库里的引用，用来比对哈希。解析失败就不送检，只记 ERROR。俱乐部资料、俱乐部帖文 outbox 和评论都走这一个出口。 | `WxContentSecurityServiceImpl`、`AppMediaAssetService` | `WxContentSecurityServiceImplTest.mediaAssetReferenceIsResolvedToFetchableUrlBeforeSubmitting` 断言送给微信的值是解析后的 URL，不是 `media-asset:9`，inbox 登记的身份是 `media-asset:9`。 |
| 5B-6 其它缺检查的入口 | fixed | 新增 `UgcSubmissionSafety`，只把既有的 `checkText` 和 `AppMediaReferenceGuard` 组合起来用。接入点见下方入口表。 | 见入口表 | `UgcSubmissionSafetyTest`、`MerchantRegistrationEditGateTest`（外链拒绝 + 文本送检）、`CircleExploreServiceTest.recordNoteThatFailsTextCheckIsRefusedBeforeInsert`、`ApiCreatorCenterControllerTest.applyWithExternalAvatarIsRefusedBeforeInsert`。 |
| 5B-7 举报处理不了 | fixed | `CommunityTrustServiceImpl.transitionReport` 以前拒绝处置成员、商家、俱乐部的举报，现在可以裁定账号级处置（`POST_RATE_LIMIT / COMMENT_TIMEOUT / READ_ONLY / TEMP_SUSPEND / PERMANENT_BAN / WARNING`）。处置对象：成员是本人，商家是入驻账号，俱乐部是创建者。处置复用 `community_post_enforcement`（`post_id` 为空），发帖和评论的闸 `countActiveMemberEnforcement` 会立即生效。 | `CommunityTrustServiceImpl`、`CommunityPostServiceImpl.applyAccountEnforcement` | `CommunityReportEnforcementBridgeTest` 新增 3 例（成员、商家、俱乐部）；商家已不存在或活动类举报仍回 400。 |
| 5B-8 随包图片 | fixed | 真实地点和首页兜底图从 `nearby-card.jpg` / `reco-hero.jpg` 换成已有的 `/images/home-route-city-placeholder.jpg`（深色路线占位图），涉及 `pages/roam/index.js` 的 `MOCK_IMG`、`pages/index/index.wxml:87/136/173`、`templatedetail.wxss`。默认徽章从 `badge-demo.png` 换成应用标识 `/images/cy_logo_mark.png`，涉及 `DEFAULT_BADGE_ASSET` 和 `xr-badge` 默认值。俱乐部 mock 改用已有的 `club_cover_default.png`。四个文件都已删除，同时删掉 `mainpkg-leaves.json` 里 d_frametu 那条迁移记录。Memoji 头像、banner 和库存照片都没动。 | 见左 | `tests/unit/round5b-bundled-images.test.js`（文件已删，且源码无引用；兜底图都存在），以及 `mainpkg-leaves-contract`、`badge-3d-fallback-state-contract`、`badge-render-recovery`、`screenshot-fixture-completeness`。`UI-GATE-0:PASS`。 |

## 入口终态表

| 入口 | 文本 | 图片 | 审不了时 | 终态 |
|---|---|---|---|---|
| `/api/comment/add` 评论 | `checkText`，fail-close | guard，落库为 `media-asset:N` | 拒绝 | 已检 |
| `/api/creativesquare/*` 与 owner_type=3 的评论（旧广场） | — | — | — | 默认 426 关闭 |
| 主题 → 广场同步帖（`ApiTopicController`） | — | — | — | 随开关关闭 |
| 主题 create / update | 门禁 POST_REVIEW | 送不了检则 HOLD，能送就异步检测 + 回调下架 | 留置 + 复核上架单 | 不公开，等人审 |
| 主题发布草稿 | 草稿期已留置 | 同上 | 既有留置分支挂单 | 不公开，等人审 |
| 活动 create / update（App） | 门禁 | guard（拒外链）+ 门禁 MEDIA_UNCHECKED | 待审 | 不公开，等人审 |
| 优惠券发布 | 门禁 | — | `status=0` 不可领，等审核单 | 不公开，等人审 |
| 官方活动 V1 publish / V2 submit | 门禁 PRE_REVIEW（原有） | V2 草稿封面接 guard | 原本就是先审 | 已检 |
| 社区帖文 `/posts` | 门禁 PRE_REVIEW（原有） | 原有 | 原有 | 已检（未改） |
| 据点报名编辑（`MerchantRegistrationEditServiceImpl`） | `checkText`，严格 | guard | 拒绝 | 已检 |
| 商家入驻（`/merchant_registration`） | `checkText`；「要人工」放行，后面还有平台人审 | 营业执照是私有资料，未接 | 繁忙或不可用时拒绝 | 已检 |
| 商家商品（`MerchantDeskServiceImpl`） | `checkText`，严格（新增） | guard（原有） | 拒绝 | 已检 |
| 创作者申请 | `checkText`；「要人工」放行，后面还有平台人审 | 头像接 guard | 拒绝 | 已检 |
| 同行记录（`CircleExploreServiceImpl.record`） | `checkText` | — | 拒绝 | 已检 |
| 漫游点亮地点名（`ApiRoamController.checkin`） | `checkText` | — | 不拒绝打卡，地点名改存「未知地点」 | 已检 |
| 据点互动模板图片 | 原有 msgSecCheck | 异步检测；risky 或 review 时下架并挂单 | — | 已检 |
| 图片回调 `suggest=review` | — | 隐藏并挂审核单 | — | 已处理 |
| 俱乐部资料、俱乐部帖文、评论图片送检 | — | `media-asset` 先解析为 URL | — | 已处理 |

## 没有举报入口的面（只列出，没有加按钮，按指示由 owner 决定）

- **集邮票（roam stamp）**：没有举报入口，只有图片机审回调。
- **分享卡 / 完成分享卡**：没有举报入口。分享卡由服务端合成，内容来自主题和成员资料。
- **据点互动模板（node template）**：没有举报入口。本轮补上了图片 risky / review 时的自动下架。
- **据点报名（site applications）**：没有举报入口，内容由主办方审核。
- 社区举报里的**活动（ACTIVITY）**类举报仍然不能直接处置（回 400）。活动举报另有一条通道（审核台 `reportContent`，ACTIVITY 在独立的举报槽里）。

## needs owner（附推荐默认）

1. **主题发布页的「同步到广场」开关**：5B-2 关闭旧写入口后，这个开关就不起作用了。这些同步帖本来只写进 `ums_creative_square`，新客户端的 `/posts` 信息流读不到。推荐默认：之后删掉这个开关，或者改成调用 community post 的发帖接口；本轮不碰 UI。
2. **`suggest=review` 的作者通知**：复用了既有 risky 下架通知，文案写的是「图片未通过内容安全检测已下架」，对「建议人工复核」这种情况说重了。推荐默认：加一句「待人工复核」的通知文案。按规则这算新文案，本轮没加。
3. **账号级处置通知**：成员、商家、俱乐部举报的处置只落了处置行和举报单时间线，没有给被处置账号发系统消息。原因是 `notifyGovernance` 依赖 `postId`，账号级处置没有这个值。推荐默认：补一条不带帖文的治理通知。
4. **商家和俱乐部本身的下架**：举报成立时处置落在负责人账号上，商家或俱乐部本身没有自动下架。下架仍然走后台商家和俱乐部管理。推荐默认：维持现状，由管理员人工判断。
5. **管理后台没有举报处置的 UI**：处置走 `CommunityTrustPlatformController` 的平台接口。
6. **门禁通过之后图片送检又失败**（token 或网络异常，`submitMediaCheckOne` 返回 null）：这时内容已经公开，只记了 ERROR，没有回滚。门禁在发布前用 `canSubmitMedia` 挡住了开关关闭和缺 openid 两种情况，挡不住瞬时故障。推荐默认：后续加一条「未送检媒体」的补偿作业。

## 未验证

- 没在 rig 里跑跨角色的真实 HTTP 回读，比如主题留置后玩家端看不到、审核台通过后自动上架。原因是本轮时间和 VPS 限制；单测覆盖到了服务和控制器两层。
- MySQL 集成测试（`*MyBatisTest`、`*MySqlIT`）在本容器里没有数据库连不上，没有跑。新增的 SQL 只有两处：`SmsCouponMapper.releaseHeldCoupon`，以及 `tryIncrementIssuableReceiveCount` 排除 status 0；`WxContentCallbackInboxMapper.lockBusinessTarget` 新增 `bizType == 7` 分支。都是简单的等值条件，不会撞 Druid wall。
- 真机：没验证。只在静态层面确认了 8 号图片替换，没有截图。

## 测试

- Java：`mvn -q -o -pl chengyinhub-admin -Dtest=… test`，相关测试类合计 0 failure（门禁、主题、活动、优惠券、审核、内容安全回调、举报、各个 6 号入口；连不上库的 MyBatis 集成测试已排除）。
- 小程序：`node --test` 跑本轮相关的 5 个测试文件全部通过；`node chengyinhub-xcx/tests/lint/ui-integrity.js` 输出 `UI-GATE-0:PASS`。

STATUS: PARTIAL - 8 项代码与单测都已完成，但跨角色的 rig 真实 HTTP 回读、MySQL 集成测试和真机截图没跑；needs owner 7 条待定
