# Round 5B（l8-safety）：上线前内容安全

分支 `claude/walk-fix-1103-l8-safety`，从 `origin/master` 切出，draft PR #721。逐项提交，一项一推送。
规则是用户提交的内容未经检查不得给别人看，客户端也不能在任何地方塞任意外链图片。

所有结论都来自读代码和单测。没有动生产库，没有部署，也没有在 rig 里跑真实 HTTP。所以这里只证明了单元层面的行为，下文会写明哪些没在真机或真库上验证。本轮没有改任何可见 UI 文案或样式，item 8 的替换图沿用仓库里已有的占位图。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 |
|---|---|---|---|---|
| 5B-1 评论图片 | fixed | 先核对了调用方：`pages/topic/index`（主题评价）和 `scene-play-activity-detail`（活动评价）确实会传 `img_arr`，`pages/square/detail` 传空，所以接 guard，没有整体拒绝。`img_arr` 改走 `AppMediaReferenceGuard.requireAttachments(member, …, false)`：外链和他人未审核的媒体返回 422；本人上传的媒体落库为 `media-asset:N`，读侧由既有 `AppMediaOutputConfiguration` 按审核状态出图（未审核只有本人能看到）。 | `ApiCommentController` | `ApiCommentMediaGuardTest`：外链被拒，且不发布、不送检；本人上传落成 `media-asset:41`。旧代码上这个测试是红的。 |
| 5B-2 旧广场 | fixed | 当前客户端的发帖、点赞、收藏、删除、举报和广场评论，都由 `utils/community-post-api.js` 映射到 `/posts`，已经不走旧入口。所以三个控制器里的 `community.post.legacy-writes-enabled` 默认值改为 `false`，旧入口回 426。 | `ApiCreativeSquareController` / `ApiCommentController` / `ApiTopicController` | `ApiCreativeSquareCommunityPolicyTest.legacyCommunityWritesDefaultOffBecauseCurrentClientUsesPostsApi`（旧代码上是红的）。 |
| 5B-3 先发后审门禁 | fixed | `ContentSafetyGateImpl` 在 POST_REVIEW 下遇到「要人工 / 审不了 / 异常 / 缺 openid / 检测关闭」，以前是打标后照常公开，现在改为 HOLD。带图但图片送不了检（总开关关或缺 openid）也 HOLD（`MEDIA_UNCHECKED`）。各调用方的处理：**主题**（create 与 update）复用内容安全留置，做法是 `user_status=0`、`check_status=2` 加原因，再挂一张复核上架单 `content_safety_relist`，审核台通过后由既有 `review()` 自动上架；草稿只留置，发布草稿时走既有的留置分支。**活动**的 create 与 update 都把图片交给门禁，HOLD 走既有待审（`preReview=true`）。**优惠券**被 HOLD 时以新的 `status=5`（待审核）保存，发券 SQL 排除 5 所以不能领取（0「未开始」仍按既有合同可发放），并挂进审核台，人工通过后由 `releaseHeldCoupon` 用 CAS 从 5 改为 1。`WxContentSecurityServiceImpl` 在检测关闭或缺 openid 时，原本静默跳过图片送检，现在会打 ERROR 日志。 | `ContentSafetyGateImpl`、`TopicTextSafety`、`CmsTopicServiceImpl.holdUncheckedTopicForReview`、`ApiTopicController`、`ApiActivityController`、`ApiCouponController`、`AuditTaskServiceImpl`、`SmsCouponMapper.xml`、`IWxContentSecurityService.canSubmitMedia` | `ContentSafetyGateImplTest`（5 个「审不了 → HOLD」，外加图片送不了检 → HOLD）、`TopicUncheckedHoldTest`、`CouponPublishReviewModeTest.heldDecisionSavesCouponAsNotIssuableAndQueuesReview`、`ApiTopicPostReviewTest`、`ActivityPublishReviewModeTest` / `ActivityUpdate*Test`（旧的「先公开 + 抽检单」断言已改为 HOLD）。机审明确通过时仍然直接发布：`cleanText_pass…`、`simpleTopicWithCleanText_publishesWithoutAnyAuditTask` 都通过。 |
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
| 优惠券发布 | 门禁 | — | `status=5` 不可领，等审核单 | 不公开，等人审 |
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
6. **后台优惠券页的状态名**：`chengyinhub-ui` 的 `statusLabel` 没有 5，待审核的券在后台会显示「状态未知」。本轮不改 UI。推荐默认：补一个「待审核」。
7. **门禁通过之后图片送检又失败**（token 或网络异常，`submitMediaCheckOne` 返回 null）：这时内容已经公开，只记了 ERROR，没有回滚。门禁在发布前用 `canSubmitMedia` 挡住了开关关闭和缺 openid 两种情况，挡不住瞬时故障。推荐默认：后续加一条「未送检媒体」的补偿作业。

## 未验证

- 没在 rig 里跑跨角色的真实 HTTP 回读，比如主题留置后玩家端看不到、审核台通过后自动上架。原因是本轮时间和 VPS 限制；单测覆盖到了服务和控制器两层。
- MySQL 集成测试（`*MyBatisTest`、`*MySqlIT`）在本容器里没有数据库连不上，没有跑。新增的 SQL 只有两处：`SmsCouponMapper.releaseHeldCoupon`，以及 `tryIncrementIssuableReceiveCount` 排除 status 5（`CouponLifecycleMybatisIntegrationTest` 补了 status=5 拒发，由 CI 的 MySQL IT 跑）；`WxContentCallbackInboxMapper.lockBusinessTarget` 新增 `bizType == 7` 分支。都是简单的等值条件，不会撞 Druid wall。
- 真机：没验证。只在静态层面确认了 8 号图片替换，没有截图。

## 测试

- Java：`mvn -q -o -pl chengyinhub-admin -Dtest=… test`，相关测试类合计 0 failure（门禁、主题、活动、优惠券、审核、内容安全回调、举报、各个 6 号入口；连不上库的 MyBatis 集成测试已排除）。
- 小程序：`node --test` 跑本轮相关的 5 个测试文件全部通过；`node chengyinhub-xcx/tests/lint/ui-integrity.js` 输出 `UI-GATE-0:PASS`。

（Round 5B 原状态：PARTIAL，见上。以下 Round 5C 覆盖 5B 中「人工复核」的做法，最终状态见文末。）

# Round 5C（l8-safety）：内容安全全自动

owner 2026-11-03 的更正：系统自动识别敏感词和违规图片并自动下架，不靠人。这一条覆盖 Round 5B 里所有「转人工复核」的做法，包括 5B-3 和 5B-4。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 |
|---|---|---|---|---|
| 5C-文本违规 | 维持 | 提交时拒绝，和以前一样。Round 5D 又在微信之前加了一层自建词库。 | — | 既有用例 |
| 5C-上传违规图 | 维持 | 上传时拒绝，和以前一样（`img_sec_check` 同步检测，检测不可用也拒）。Round 5D 又在落库前加了已知违规图比对。 | — | 既有用例 |
| 5C-异步 risky | fixed | 所有类型都走既有的自动下架加作者通知。据点互动模板（bizType 7）在 5B-4 里还会挂一张 TEMPLATE 人工单，这张单已删掉，现在只下架并通知。 | `WxContentSecurityServiceImpl` | `WxContentSecurityServiceImplTest.riskyNodeTemplateImageIsTakenDownAndAuthorNotifiedWithoutHumanQueue`，断言 `verifyNoInteractions(audit)`，在 5B 代码上是红的 |
| 5C-异步 review | fixed | `suggest=review` 和 `risky` 走同一条路径（自动下架加通知），删掉了 5B-4 的 `queueMediaReview`。 | 同上 | `reviewSuggestionIsHandledExactlyLikeRisky` |
| 5C-审不了（检测关闭、繁忙、缺 openid、异常） | fixed | 新增自动复检队列 `content_safety_recheck` 和 Quartz 任务 `contentSafetyRecheckTask`（每 5 分钟一轮，每轮最多 50 条）。门禁 HOLD 的原因如果属于「机审没结论」（`IDENTITY_REVIEW / TEXT_REVIEW / DETECT_ERROR / DETECT_DISABLED / MEDIA_UNCHECKED`），或者是先审带图（`MEDIA_PENDING`），就进这个队列。复检期间内容只有作者自己看得到：主题是 `user_status=0` 的留置，活动待审，优惠券 `status=5`，帖文是 `CHECKING`。复检时取作者最新的 openid 重新调 `checkText`。通过：如果带图，补送异步图片检测，然后以系统身份（operator 0）走既有的 `review()` 自动通过，各业务原有的上架口会把内容放出来。违规，或者微信回 `review`：自动驳回，内容保持不公开，作者收到既有的「审核未通过」系统通知。审不了：按退避重试。重试用完：自动驳回，原因写「自动检测多次未能完成，内容未公开，请稍后重新提交」，同样走通知。 | `ContentSafetyRecheckServiceImpl`、`ContentSafetyRecheckTask`、`ContentSafetyGateImpl`（门禁内带 contentId 的自动进队）、`ApiTopicController` / `ApiActivityController` / `ApiCouponController` / `ApiOfficialEventController` / `OfficialEventV2ServiceImpl` / `ClubSessionServiceImpl`（调用方落库后进队）、`CommunityPostServiceImpl.applyAuditDecision`（自动通过后补送帖文图片）、`migration_20261103_content_safety_recheck.sql` | `ContentSafetyRecheckServiceImplTest`，13 例：通过后自动发布、违规自动驳回、`review` 按违规处理、微信宕机时退避、检测关闭和缺 openid 时重试、用完后驳回并说明原因、业务审批留给人、带图先补送检、图片送不了检时重试、单行失败不拖垮整批、重复进队只刷新、先审带结构化字段标为业务审批、只有「没结论」的 HOLD 进队。另有 `ContentSafetyGateImplTest.uncheckedHoldIsQueuedForAutomaticRecheckButStructuredReviewIsNot` |
| 5C-先审 PRE_REVIEW | listed | 见下方清单。文本和图片安全改成自动；价格、地点、报名等业务审批没动。 | — | — |
| 5C-举报 | reverted | 不建举报处置路径。5B-7 的代码（成员、商家、俱乐部举报的账号级处置）已经整体 revert（commit `8f53f487`），只保留 5B 结果里那份「没有举报入口的面」清单。 | `CommunityTrustServiceImpl`、`CommunityPostServiceImpl` | revert 后由 `CommunityReportEnforcementBridgeTest` 回到 master 的版本 |
| 5C-随包图片 | reverted | 5B-8 的改动整体 revert（commit `74bea940`），四张图和所有引用都恢复了。 | 见 commit | `mainpkg-leaves-contract`、`badge-3d-fallback-state-contract`、`badge-render-recovery`、`screenshot-fixture-completeness` 共 43 例通过；`UI-GATE-0:PASS` |

### 重试节奏，以及微信连续几小时不可用时会怎样

进队 5 分钟后做第一次检查。之后的间隔是 10、20、40、80 分钟，然后每 2 小时一次，默认一共 12 次，覆盖大约 16.5 小时（`content.safety.recheck.max-attempts` 可调，表里封顶 20 次）。

- **微信停几个小时（少于大约 16 小时）**：这段时间提交的内容都只有作者自己看得到，任何时候都不公开。微信恢复后，下一轮复检（最多再等 2 小时）自动发布通过的内容，下架违规的内容。不需要任何人操作。
- **超过大约 16.5 小时**：这批内容会自动驳回，保持不公开，作者收到通知，原因是「自动检测多次未能完成」，可以重新提交。重新提交就重新进队。
- 微信停的这段时间里，Round 5D 的自建词库照常拦截命中的文本，因为它不依赖微信。

### 现在还停在人工的先审（PRE_REVIEW）路径

文本和图片安全已经改成自动；下面这些单子之所以还留给人，是因为业务审批本来就是人做的，本轮没动。

| 路径 | 门禁原因 | 现在谁批 | 本轮之后 |
|---|---|---|---|
| 社区帖文带位置（`hasGeo`） | `STRUCTURED_REVIEW` | 后台审核工作台的平台审核员（`business:audit:*`） | 文本没结论时自动复检；违规自动驳回。复检通过后单子留给审核员批位置。 |
| 社区帖文带图（无位置） | `MEDIA_PENDING` | 以前是审核工作台的人 | **改成自动**：复检文本，补送异步图片检测，然后自动通过（图片在上传时已同步检测过一次） |
| App 活动的先审模式（高风险类目、商家未认证、总闸） | `STRUCTURED_REVIEW`（活动都带报名） | 审核工作台的平台审核员 | 安全部分自动；价格、报名、地点仍由人批 |
| 俱乐部开团场次的先审模式 | `STRUCTURED_REVIEW` | 同上 | 同上 |
| 官方活动 V1 发布、V2 提交 | 一律待审 | 平台审核员（`applyAuditDecision` 排期） | 文本没结论时自动复检，违规自动驳回；排期和公开仍由人批 |
| 主题售卖、项目商业审核（`TOPIC_SALE_REVIEW` / `TOPIC_PROJECT_REVIEW`） | 商业审核 | 平台审核员 | 没动，报给 owner |
| 商家入驻、创作者申请 | 资质审核 | 平台管理员 | 没动；「要人工」的文本仍放行给后面的人审 |
| 主题图片违规下架后，作者申请重新上架（`content_safety_relist`） | 恢复上架 | 审核员 | 下架是自动的；恢复要人。owner 没要求改恢复，没动 |
| 社区帖文图片违规（`applyRiskyMediaResult`） | 下架后挂复核单 | 审核员 | 下架是自动的；恢复要人。没动 |

## 内容安全一页表（Round 5C 和 5D 之后）

| 内容类型 | 文本怎么检 | 图片怎么检 | 违规时自动发生什么 | 还要人做什么 |
|---|---|---|---|---|
| 主题 | 自建词库，然后微信 `checkText`（门禁先发后审）；没结论就留置、仅作者可见、自动复检 | 上传时：已知图比对加微信同步检测；发布后：微信异步检测 | 提交时拒绝；复检违规时自动驳回加通知；异步 risky 或 review 时自动下架留置加通知 | 违规下架后要重新上架时，由人复核 |
| 活动（App） | 同上；先审模式进待审 | 同上 | 同上 | 先审模式下的业务审批（报名、价格） |
| 俱乐部开团场次 | 同上 | 用主题的封面 | 同上 | 同上 |
| 优惠券 | 自建词库，然后微信；没结论时 `status=5` 不可领，自动复检 | 无图 | 拒绝，或自动驳回（`status=3`）加通知 | 无 |
| 社区帖文 | 自建词库，然后微信（先审）；没结论或带图时自动复检 | 上传同步检测；自动通过后补送异步检测 | 拒绝，或自动驳回加通知；异步违规时自动隐藏 | 带位置的业务审批；违规隐藏后的恢复 |
| 社区回复 | 同上（门禁） | 无 | 同上 | 无 |
| 评论 | `checkText`（自建词库加微信），fail-close | 只收本站登记的媒体，异步检测 | 拒绝；异步违规时自动隐藏 | 无 |
| 俱乐部动态 | `checkText` | outbox 送检 | 拒绝；异步违规时自动下架加通知 | 无 |
| 俱乐部资料、商家资料 | `checkText` | 异步检测 | 拒绝；图片违规时自动清图。存量复查对资料文字只报告 | 资料文字被存量复查命中后怎么处理（见 needs owner） |
| 官方活动 | 门禁先审；没结论时自动复检 | 封面异步检测 | 拒绝，或自动驳回；封面违规时自动下线 | 排期和公开的业务审批 |
| 据点互动模板 | 微信 `msgSecCheck`（经 `checkText`，含自建词库） | 异步检测 | risky 或 review 时自动下架加通知（本轮去掉了人工单） | 无 |
| 组局、IM、投票 | `checkText` | 异步检测 | 拒绝；图片违规时自动隐藏 | 无 |
| 商家商品、据点报名、同行记录、漫游地名 | `checkText`（5B-6） | 只收本站媒体 | 拒绝（漫游地名改存「未知地点」） | 无 |
| 商家入驻、创作者申请 | `checkText`；「要人工」时放行 | 头像只收本站媒体 | 拒绝 | 资质审核 |
| 用户资料、集邮票 | `checkText` | 异步检测 | 自动清图或隐藏 | 无 |

所有上传图片都会在落库前做已知违规图比对（Round 5D）；新图只能靠微信判断。

## 测试

- Java（`mvn -q -pl chengyinhub-admin -Dtest=… test`）：`ContentSafetyRecheckServiceImplTest` 13 例，`ContentSafetyGateImplTest` 24 例，`WxContentSecurityServiceImplTest` 51 例，`WxContentCallbackMutationServiceTest` 6 例，另有 `TopicUncheckedHoldTest`、`CouponPublishReviewModeTest`、`ApiTopicPostReviewTest`、`ActivityPublishReviewModeTest`、`ActivityUpdate*Test`、`OfficialEvent*Test`、`ClubSession*Test`、`CommunityPost*Test`，全部 0 失败。
- 在旧代码上是红的：新测试依赖的类（`ContentSafetyRecheckServiceImpl`、`IContentSafetyRecheckService`、`findOpenContentTaskId`）在旧代码里不存在，所以编译不过。语义变化的两例（据点模板不再碰审核单、`review` 等同 `risky`）在 5B 代码上会断言失败。
- 小程序：`UI-GATE-0:PASS`；随包图片相关的 4 个测试文件共 43 例通过。

## 没验证的

- 没有在 rig 里用真实 HTTP 和真库跑过「审不了 → 仅作者可见 → 复检通过后自动上架」这条跨角色链路。`content_safety_recheck` 的 Mapper SQL 也没在 MySQL 上跑过：容器里没有库，SQL 只用了等值条件、`<=` 和 `LIMIT`，没有 Druid wall 拦的写法。
- 以系统身份（operator 0）调用 `review()`，单测里用的是 mock 的审核服务；真实的 `review()` 链路对每种类型的放行口，只按代码读过。

# Round 5D（l8-safety）：自建敏感内容库

owner：后端自己有一套库，自动识别敏感词和敏感图片，叠加在微信检测之前，不替代微信。纯 Java，没加依赖。

## 词

- **表**：`migration_20261103_sensitive_content_library.sql`（已登记到 `ci/migration-dependencies.json`）建了四张表：`content_sensitive_word`（词、分类、动作 `BLOCK` 或 `LOG`、启用状态）、`content_sensitive_allow`（放行短语）、`content_sensitive_image`、`content_sensitive_hit`。
- **匹配器**：`SensitiveWordMatcher` 是不可变的字典树。`SensitiveContentLibraryImpl` 持有一份快照，每次使用前，如果距上次核对超过 30 秒，就读一次三张表的「行数加最后修改时间」指纹，指纹不同才整份重载。多实例之间靠同一张表的指纹同步，不需要 Redis 广播；后台改动后本实例立即重载。**加载失败时保留上一份好的快照继续用，并记 ERROR**；从没成功加载过时也记 ERROR，下一次使用会再试。检查不会被悄悄关掉，后面还有微信。
- **归一**：全角转半角，大小写统一，常用繁体转简体（内置约 400 个高频字对照，不用三方库，漏掉的字由微信兜底），并丢掉夹在字之间的符号、空白、换行和表情。只保留字母、数字和汉字。
- **接入点**：`IWxContentSecurityService.checkText`（仓库里唯一的 `msgSecCheck` 实现，所有直接送检的路径都经过它）和 `ContentSafetyGateImpl`，都在调微信之前先查库；自动复检也一样。命中就按既有的违规文案拒绝（「内容包含违规信息，请修改后重试」），绝不显示命中词，日志里也没有命中词。**微信关闭或宕机时照样生效**，有测试。
- **绕过这两处的文本路径**：用 grep 核对过，所有用户端的公开文本写入口都会调 `checkText` 或门禁。不经过这两处的有：后台管理员在 RuoYi 里录入的内容、平台官方广播、AI 生成的 NPC 输出（AI 网关有自己的安全预检）、作者私有的草稿、治理和举报原因（不公开）。另外，`AdminFileUploadService` 的后台上传不做已知图比对。

## 图片

- `content_sensitive_image` 存 SHA-256 和 64 位 dHash（`ImageIO` 缩成 9×8 灰度图后做差值）。汉明距离不超过 6 就算同一张图的副本。`ImageIO` 读不了的格式（比如 webp）只能按 SHA-256 认。原图不落盘，也不进对象存储。
- **上传**：`AppUploadService` 在落库前比两次：一次用原始字节，一次用去元数据后的副本。命中就拒绝（「图片未通过内容安全检查，请更换后重试」），存储和微信都不会被调用。
- **异步图片路径**：`submitMediaCheckOne` 在库里有图片条目时，先抓回图片（最多 10MB，有超时）比对。命中就走与微信 risky 回调同一条自动下架路径（`takeDownForSafety`），并记录命中。
- **说实话**：这只能认出**已登记图片的副本**（完全相同，或者缩放、压缩、轻微改动过的）。新图仍然只靠微信的同步和异步检测。

## 存量内容

- Quartz 任务 `sensitiveContentRescanTask`（每 10 分钟一轮，注册时为暂停）。词库指纹变了才扫。扫描范围：评论、社区帖文、俱乐部动态、主题、活动、商家资料、俱乐部资料的公开文本。按类型和 id 游标分批，**每轮最多 500 条**，游标存在 Redis。
- **幂等**：同一版本、同一内容、同一模式只记一次；扫完以后，同一版本再跑一轮什么都不做。
- **开关**：`sys_config` 的 `content.sensitive.rescan.remove`，**默认 `false`，只报告**。切到 `true` 后，会按当前版本重扫一遍，命中的内容走 Round 5C 的同一条自动下架路径：主题和活动下架留置，评论和俱乐部动态隐藏，社区帖文隐藏，并通知作者。商家资料和俱乐部资料没有「只下架文字」的现成路径（现有路径会清掉资料图），所以这两类只报告。

## 管理

- 后台页面 `chengyinhub-ui/src/views/business/sensitive/index.vue`，API 在 `src/api/business/sensitive.js`。四个页签：敏感词（列表、新增、停用、批量导入）、放行短语（同样四项）、违规图片（上传登记，只存指纹；可停用）、最近命中。
- 菜单「敏感内容库」挂在审核中心下，按钮权限是 `business:sensitive:list` 和 `business:sensitive:edit`。迁移只建菜单，**不授权给任何角色**。接口都有 `@PreAuthorize`，操作日志不记录请求体，所以导入的词不会进 `sys_oper_log`。
- 命中记录只存来源、内容类型、内容 id、分类和动作，**不存命中词，不存个人数据**。

## 词表

**仓库里没有提交任何词表**，测试只用编造的记号（如「测敏甲乙」「zqxbad」）。

- **导入格式**（后台「批量导入」和脚本通用）：UTF-8 纯文本，一行一条，写成 `词条,分类,动作`。分类可选 `POLITICS / PORN / GAMBLING / DRUGS / VIOLENCE / FRAUD / ABUSE / AD / OTHER`，缺省是 `OTHER`；动作是 `BLOCK`（默认，提交即拒）或 `LOG`（只记命中不拦，用来试运行）。`#` 开头的行是注释。放行短语一行一个。一次最多 5000 行。
- **误伤报告脚本**：`scripts/sensitive/run.sh <词表> [放行短语]`。它用与线上同一个匹配器，扫 `chengyinhub-admin/sql/seed*.sql` 和小程序随包的 `.wxml / .js / .json`（排除测试、脚本和 npm 目录），打印每个命中的文件:行号、命中词和原文片段，最后汇总计数。只读，不连库，不联网。
- **本轮的数字**：手头没有真实词表，所以拿 4 个通用演示词（成人、血、杀、赌）跑了一遍，用来说明放行短语的作用：

| 运行 | 扫描文件 | 扫描行 | 命中行 | 分词命中 |
|---|---|---|---|---|
| 4 个演示词，不带放行短语 | 1673 | 233,850 | 25 | 成人 2、杀 7、血 12、赌 5 |
| 加上放行短语「成人票」「血量」 | 1673 | 233,850 | 18 | 成人 2、杀 7、血 5、赌 5 |

  拿到真实词表后，owner 按同样的命令跑一遍，就能看到真实的误伤数字，再决定要加哪些放行短语。

## 测试

| 要求 | 测试 |
|---|---|
| 词命中 | `SensitiveWordMatcherTest.plainWordHitReturnsOnlyCategoryNeverTheWord` |
| 用符号拆开 | `symbolsSpacesAndEmojiBetweenCharactersDoNotEvadeMatching`、`fullWidthCaseAndTraditionalAreNormalised` |
| 放行短语 | `allowListedPhraseCoveringTheWordIsNotAHit` |
| 微信宕机或关闭 | `SensitiveLibraryIntegrationTest.checkTextRefusesLibraryHitEvenWhenWechatIsOff`、`gateBlocksLibraryHitWithWechatDownAndNoOpenid`、`recheckRejectsLibraryHitWithoutWaitingForWechat` |
| 加载失败 | `SensitiveContentLibraryImplTest.failedReloadKeepsTheLastGoodCopy`、`firstLoadFailureDoesNotThrowAndIsRetriedOnNextUse`、`loadsFromTableAndRefreshesWhenTheVersionChanges` |
| 图片完全相同和近似副本 | `SensitiveContentLibraryImplTest.knownImageMatchesExactCopyAndNearDuplicateButNotADifferentImage`（PNG 放大并转 JPEG 后仍能认出，另一张图不误伤）、`AppUploadKnownBadImageTest`（落库前拒绝）、`asyncImagePathTakesDownKnownBadImageThroughTheRiskyPath` |
| 复查任务两种模式 | `rescanReportOnlyByDefaultRecordsHitsAndRemovesNothing`、`rescanRemoveModeTakesDownThroughSafetyPathAndIsIdempotent`、`rescanHonoursThePerRunLimit` |

这几组共 19 例，全部通过。上传相关的既有测试 `ApiCommonUpload*Test` 41 例，以及门禁和内容安全的既有测试也都通过。新测试依赖的类在旧代码里不存在，所以在旧代码上编译不过（红）。CI 门禁：`migration_gates.py sql / beans / dep-order`、`dup_create_table_gate`、`forbidden_hosts_gate`、`secret-file-gate` 都通过；`UI-GATE-0:PASS`。`chengyinhub-ui` 的 `test:unit` 有 15 个失败，不加本轮改动时也是同样 15 个，是既有失败（本地缺依赖），本轮没有新增。

## 没验证的

- 新表的 Mapper SQL 没在 MySQL 上跑过（容器里没有库）。`selectPublicText` 里各张表「公开」的口径（比如评论 `status=1`、活动 `publish_status=1`、主题 `user_status=1`）是照着现有列表 SQL 写的，没有在库里回读核对。
- 后台页面没有截图，只写了代码：rig 起不了后台，`chengyinhub-ui` 在本容器里也没装依赖。
- 繁转简只覆盖了内置的高频字，不是完整对照表。

## needs owner（附推荐默认）

1. **存量复查切到下架模式的时机**：默认只报告。推荐默认：先导入词表、启用任务，看一轮命中记录（后台「最近命中」）之后，再把 `content.sensitive.rescan.remove` 改成 `true`。
2. **商家资料和俱乐部资料的文字命中**：现在只报告。推荐默认：先由运营手工改；如果需要自动化，再加一条「只隐藏资料文字」的路径。
3. **复查下架的通知文案**：复用了图片违规下架通知，标题写的是图片违规，文字命中时不准确。推荐默认：加一条「内容未通过内容安全检测已下架」的通用文案（这算新文案，本轮没加）。
4. **自动复检用完次数时的通知文案**：复用了「审核未通过」，原因里写明是自动检测未完成。推荐默认：维持。
5. **两个新的 Quartz 任务注册为暂停**：`contentSafetyRecheckTask`、`sensitiveContentRescanTask`。推荐默认：新 jar 部署并读回以后，先启用前者；后者等词表导入后再启用。
6. **菜单权限**：没有授权给任何角色。推荐默认：只授给平台内容安全管理员。

STATUS: PARTIAL - Round 5C 和 5D 的代码、迁移和单测都已完成并推送；跨角色的 rig 真实 HTTP 回读、MySQL 上的 Mapper SQL 读回，以及后台页面截图都没做；needs owner 6 条
