# RV1 独立复审：batch2 集成候选 #486（2026-10-22）

- 复审对象：`integrate/closeout-batch2-20261022` @ `2b089767`，对 `master` @ `f0b43d62`，`git diff origin/master...origin/integrate/closeout-batch2-20261022`（334 个文件，+20889 / −7161）。
- 方式：只读审阅源码与测试；没有构建、没有跑测试、没有连任何数据库；没有在任何 PR 上评论、批准、合并或关闭。
- 每条结论都回到合并树上的代码核对过；核对不了的不列。
- 计数：**blocker 1 · should-fix 2 · nit 5**。另有 4 项属于 owner 待决事项，见第 3 节，不计入。

## 1 发现

### B1 [blocker] 俱乐部月历把「仅俱乐部成员可见」主题的场次公开给非成员

- 位置：`chengyinhub-system/src/main/resources/mapper/business/ClubCalendarMapper.xml:26-32`（公开分支）。调用方是 `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubCalendarController.java:62-66`。
- 问题：公开视角只核对父主题 `del_flag/status/user_status`，没有带 `club_member_only`。`/api/club/topics` 用的是 `CmsTopicMapper.clubMemberOnlyGate`（`CmsTopicMapper.xml:534-560`），那段注释写明「闸必须放在共用 SQL 上……只堵俱乐部 tab 等于没堵」。新端点没有走这道闸。
- 场景：一个公开俱乐部（`public_visible≠0`）下有一个主题，设置了 `club_member_only=1`，主理人给它开了场次（`cms_activity.club_id=该俱乐部`，`topic_id=该主题`）。任何已登录的非成员 `POST /api/club/calendar/sessions {clubId, month}`，因为 `canViewClub=true` 且 `governor=false`，就能拿到这些场次的 `activityId/topicId/name/startDate/endDate`。主题设置页对主理人承诺的是「开启后非成员看不到这条主题」，这里破了。
- 最小修复：控制器把 `viewer` 和「是否成员/主理人」传给 mapper。公开分支里，非成员时在 `live_parent` 子查询加上 `and coalesce(live_parent.club_member_only, 0) = 0`（或者在 `exists` 里复用 `clubMemberOnlyGate` 的判据，别名改成 `live_parent`）。再补一条负控单测：非成员看不到仅成员主题的场次，成员能看到。

### S1 [should-fix] 门店 AI 形象的头像审核回执读错了送检类型（W2 × W10b 合流语义冲突）

- 位置：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ProfileReviewService.java:153`。对照 `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMerchantController.java:849-855`。
- 问题：#473（W2，`6f9ccc53`）把门店分身头像送检改成了 `BIZ_TYPE_MERCHANT_NPC(18)`，`bizId=npc_profile.id`。#477（W10b，`e03cb9ad`）的回执仍按 `BIZ_TYPE_MERCHANT_PROFILE`、`bizId=merchantId` 去查 `wx_content_callback_inbox`。两条 lane 各自都是绿的，合在一起就对不上了。`ProfileReviewServiceTest.java:162` 把错误的类型钉成了预期。
- 场景：商家上传自定义头像并保存。inbox 里写入的是 `(18, npcId, hash)`，`/api/merchant/npc/save` 的 `receipt.avatarReview` 和 `GET /api/merchant/npc/review` 却按 `(MERCHANT_PROFILE, merchantId, hash)` 查，结果永远是 `NOT_SUBMITTED`。头像被判违规时（回调已经把 `audit_status` 打回 2），`avatarReview` 也不会显示 `REJECTED`。如果商家刚好用 logo 同一张图当头像，还会读到 logo 的复核结论。回执给商家的是错误信息。安全闸本身没有被绕过：`audit_status` 照常生效。
- 最小修复：`mediaState(IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC, npc.getId(), sha256Hex(avatar.trim()))`，同时把测试 `:162` 的预期改为 18/npcId。

### S2 [should-fix] 活动合作者可以写入任意会员，公开详情会带出对方昵称、头像和脱敏实名

- 位置：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsActivityServiceImpl.java:996-1012`（`saveActivityCollaborators`，#474 W6 F0258）。读出口是 `ApiActivityController.java:1215-1224` → `PublicCollaboratorVO.from`，带 `memberNickname/memberAvatar/memberRealName`（实名经 `ViewCollaboratorsServiceImpl:78` 脱敏）。
- 问题：改动前活动合作者行写的是发布者本人（是 bug，但不外泄）。改动后写入请求里给的任意会员 ID，只校验「会员存在」，不要求对方同意，也不要求和发布者有关系。
- 场景：发布者 A 发活动时 `collaborators=[1,2,3,…]`。默认 `pre_review_all=false`，先发后审，活动立刻公开。之后任何人打开活动详情，都能看到这些会员的头像、昵称和脱敏实名（如「张*」），并且他们被公开署名在一个自己没参与的活动上。这样也可以按 ID 批量枚举会员的脱敏实名。
- 说明：主题合作者（`CmsTopicServiceImpl.saveCollaborators:6017`）早已是同样的口径，问题本身是既有的，这一批把它扩到了活动。所以列为 should-fix，不阻断合并。
- 最小修复（二选一，需要 owner 定）：(a) 服务端把候选限制在发布者可选的范围内（与前端选择器同源，比如俱乐部成员或互关），不在范围内就 400；(b) 公开投影去掉 `memberRealName`，合作者改为「对方确认后才公开」。

### N1 [nit] 公开帖子列表的引用卡可见性判定是逐 ID 查询

- `ApiClubController.java:1587-1588` 对每个不同的引用 ID 调用 `isPubliclyVisible`，活动类最多查 2 次。数量受分页上限约束，不是正确性问题。可以改成一次 `IN (...)` 批量判定。

### N2 [nit] AI 草稿 requestId 没有绑定请求内容

- `AiThemeDraftRequestService.java:78-94`：同一会员用同一 requestId 但换了 `idea`，会原样回放第一次的结果（`replayed=true`）。小程序每次「生成」都会新开 UUID，现网路径碰不到。为了和 consent 的 `CONSENT_REQUEST_CONFLICT` 口径一致，可以在 PROCESSING 占位里存 `idea+productType` 的摘要，不一致时回 409。

### N3 [nit] 草稿发布补挂抽检单的快照 JSON 是手拼的

- `CmsTopicServiceImpl.java:3036-3037`（照抄 `ApiTopicController.java:999 flagForSpotCheck` 的写法）只把 `"` 换成了 `'`。主题名里有 `\`、换行或控制字符时，`snapshot_json` 就不是合法 JSON。建议用 `JSONObject` 构造。

### N4 [nit] 历史路线卡的落库摘要没有回收

- `ImServiceImpl.java:743` 起，新路线卡摘要只写 `[路线]`。已经落库的旧消息 `content` 仍是「[路线] 主题名」，主题之后转私密或下架时，会话列表预览照样显示名称。这一批没有回填，也没有说明。可以按需出一个只读预检 + 回填 migration，或者在会话列表读侧按 `ROUTE` 类型统一投影。

### N5 [nit] 资料版本号用 INSERT…SELECT max+1，并发时是死锁而不是唯一键冲突

- `UmsMemberProfileVersionMapper.xml:21-26`：RR 下 `INSERT … SELECT … FROM 同表` 会对扫描行加 S 锁。同一会员两次并发保存更可能触发 1213 死锁，而 `ProfileReviewService.recordMemberSubmission` 只对 `DuplicateKeyException` 重试，死锁会落到通用分支返回 `profileVersion=null`。只影响回执，资料保存本身不受影响。

## 2 按复审重点逐项结论（无发现的也写明）

1. **资金路径**
   - #479 支付回调 inbox：`register` 对同一 hash 换了业务身份会抛 `IdentityConflictException`，`noRollbackFor` 保住了外层；`process` 里的 `isCurrent` 二次核对身份；业务失败时 `WxPaymentCallbackMutationService.apply` 抛异常，整笔回滚并 release；入口加了 `callback_inside_transaction` 闸。两个调用方（`WechatPayServiceImpl.handlePayCallback`、`OrderCloseTask.reconcileExpiredPaying`）都不在事务里。原来在事务内调用的 `TeamGroupServiceImpl:885` 已改走 `applyConfirmedWechatPaymentWithinTransaction`（`MANDATORY`，CAS 保证只补一次）。`lockBusinessTarget` 支持 bizType=1。旧版本留下的 WAITING 行在重投时会被正常领取，`completePaymentSuccess` 对已支付是幂等的。**无发现。**
   - 俱乐部条件更新：`updateOpsFieldsIfUnsold` 把「未售出」判断和写入合成一条 UPDATE，判据和 `countOpenSaleRegistrations` 一致（payment_status ∈ {0,1,2}），隔离级别钉在 RR，有 `ClubLeadOpsSoldLockMySqlIT` 覆盖两种交错。**无发现。**
   - 券有效期不可变：两个 XML 改成 `start_time = start_time`，并在 WHERE 里断言传入值等于现值。调用方 `toAjax` 在 0 行时报错，不会静默成功。**无发现。**
   - #478 传感器奖励上限：`rewardCappedToXpBadge` 和 `completeGate` 走同一个 `existingSession` 查找，节点奖励正确封顶为 XP_BADGE_ONLY；attemptId 绑定本人、单次消费、会过期。主题通关奖和碎片文按 PR 自述不在这一刀里，见第 3 节。
2. **授权与归属**
   - 新端点：`/api/ai/theme/draft/request`（键按会员隔离）、`/api/ai/npc/chat/request`（本人 + 原会话）、`/api/club/compensation/editions/empty-reason`（非归属人只回 NO_PERMISSION）、`/api/merchant/npc/review`（PROFILE_WRITE + 登录商家）、`/api/user/profile/review`（只认登录态）、`/api/roam-share/revoke-token`（`member_id` 在 WHERE 里）、`/api/club/calendar/sessions`（见 B1）。
   - 改动端点：`deleteActivityCollaboratorsByOwner` 带 owner 子查询；`/api/registration` 两处补了登录判空；商户评价筛选仍按 `merchant_row_id` 隔离；模板上架复检绑定锁内快照，fail-closed。
   - 除 B1 / S2 外**无 IDOR**。
3. **隐私与可见性**
   - 商品详情按公开 + 商家可用过滤，白名单投影，`detailHtml` 过 `HTMLFilter`。帖子引用写时校验本人报名成功 + 公开可见，读时按匿名口径重判。IM 路线卡按匿名口径判发送资格，摘要不写名称（旧数据见 N4）。分享令牌撤销与版本 CAS 都在会员行锁内。资料回执只读本人。
   - 发现见 B1、S1、S2。
4. **内容安全审核路径**
   - #473：类型 18 有 `lockBusinessTarget` 分支（`scope_type=2`），有 `isCurrentSubmittedMedia` 分支，有幂等的驳回 UPDATE；会员资料的「联系二维码」纳入送检，违规时按字段清除。
   - #471：`review_flag` 草稿补挂抽检单与发布同事务；升级单的迟到决定被 `AUDIT_REVIEW_STALE_LEVEL` 拒绝，后台 UI（`audit/index.vue:504`）本来就带 `expectedVersion`。
   - **无阻断**，只有 N3。
5. **幂等与 UNKNOWN**
   - AI 草稿：Redis 占位 + 租约 → UNKNOWN，存储不可用时 fail-closed。小程序在 HTTP 5xx 时回落到 `fail`，锁住只读回读，不会重放。
   - NPC 回读：只读，范围不符一律 INVALID。
   - 据点核销：持久 UNKNOWN 写闸，成功判定逐字段对上后端回执（`ApiVerifyController:248-253` 已核对）。
   - 收藏取消：结果未知时先回读 `like_list`。
   - 只有 N2。
6. **迁移**
   - 3 个都是新文件，没有改动既有 migration。`IF NOT EXISTS`、`information_schema` 判列、`NOT EXISTS` 种子，都可以重复执行，也都不破坏现有数据。
   - `ci/migration-dependencies.json` 已登记拓扑：roam_share_version 依赖 20260917_roam_share_snapshot。
   - 部署顺序（迁移先于 jar）在 integration-batch2.md §5 写明。**无发现。**
7. **测试是否被削弱**
   - 逐个看了带删除行的 Java/JS 测试：`RegistrationFundsMybatisIntegrationTest`、`RegistrationPaymentAtomicityContractTest`、`SignupNoticeAfterCommitPersistenceMySqlIT`、`ClubLeadLeaderResolutionTest`、`ImServiceImplTest`、`ImRouteProjectionTest`、`NpcProfileServiceTest`、`AuditTaskAppealFlowTest`、`TemplatePublishAuditQueueTest`、`longrun-e05-07`、`nav-route-exists`。
   - 删除行都是随语义变化改了断言形态（比如「抛异常」改成「回 FAIL」），落库副作用的断言（积分、状态回滚）都保留着。`funds_it_gate` 的 IT 计数从 5 改到 6，是加了一条用例。
   - 唯一需要留意的一处，本身不算削弱：`RegistrationPaymentAtomicityContractTest` 原来断言 `handleWechatPayCallback` 带 `@Transactional`，现在改为断言 `WxPaymentCallbackMutationService.apply` 带 `@Transactional`。这是源码字符串匹配（`"@Transactional\n    public boolean apply("`），对格式变化很脆。**无削弱。**
8. **小程序保留文件**
   - `get-auth-matrix-contract.test.js`：只加了矩阵行，计数 65 → 70，每处新增 GET 都有对应矩阵行和注释。
   - `action-ledger.json`：在合并树上重建。`action-ledger.test.js`：只加断言、按实测更新计数。`ui-integrity-baseline.js`：未改。
   - `build-action-ledger.js` 新增了 `EXPLICIT_READ_APIS`，只有一条 `/api/publish/home`，后端 `ApiPublishController#home` 只读，已核对。
   - 孤儿页门禁和 `audit-page-cleanup.js` 改成了更严的「真实导航消费者」口径，并带正、负控。
   - 结论：**没有放宽任何门禁**。

## 3 owner 待决（不计入发现，合并前需要拍板）

- #478 W8：主题通关奖（`TopicCompletionAwardService.grantOnce`）以及通用/队伍任务结算，没有接 `rewardCappedToXpBadge`。设备自报的传感器局仍可能拿到 FULL 的主题通关奖。PR 已自述，需要 owner 定是否封顶。
- #476 W7：暂停时归零 HP 和挑战截止时间顺延；取消、退队不再写 HP_ZERO（会改变游戏结局）。PR 自述 NEEDS_OWNER_APPROVAL。
- #473 W2：二维码上传归属校验没有移植。#472 W4：#11 R2 没有移植。
- 部署：#478 需要 jar 和小程序同发；3 个迁移需要先于 jar 落库，走 chengyin-db-migrate 只读预检 + 用户确认。

## 4 建议

修完 B1 再合 #486。S1 是两条 lane 合流后才出现的回归，改动只有一行，建议同批修掉。S2 需要 owner 先定口径。nit 可以放到后续批次。
