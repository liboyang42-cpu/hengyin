# SMOKE-500-R4: api-smoke BACKEND-ERROR triage and fixes

- Branch: `fix/smoke-500-r4`, cut from `origin/integration/backend-app-r3` (`81db27155`)
- Input: `origin/docs/smoke-backend-errors:docs/delivery/backend-app-r2/SMOKE-BACKEND-ERRORS.json`. The run had 1231 calls: OK 773, BUSINESS-REFUSAL-EXPECTED 262, BACKEND-ERROR 191, APP-CONTRACT-BUG 5. The file lists 187 BACKEND-ERROR rows.
- Not deployed. The smoke counts below are expected values. They have not been re-measured on TEST 8120.

## What the 187 rows are

None of the 187 rows is an uncaught crash. No response contains an exception class, a stack trace, SQL, or a null-pointer message. Every row is a business refusal. The rows split into two halves.

| Shape | Rows | What it means |
|---|---|---|
| HTTP 200 with `code` 500 | 94 | Business refusals (not found, no permission, bad input, state conflict) that reach the client as code 500. **This half is the fix target.** |
| HTTP 200/4xx with `code` 400/401/403/404/422, plus 501 and 520 | 93 | The backend already returns the right business code. Each refusal is correct for the IDs and inputs the harness sent (random request IDs, fixture rows missing on TEST, fake hashes, unconfigured WeChat subscription). No code change. These are **SEED-ONLY**: they turn OK only with fixture rows or valid harness inputs. |

## Root-cause groups (code-500 rows)

| Group | Root cause | Fix | Rows fixed |
|---|---|---|---|
| **G2** Controller literal | The controller detects "not found", "no permission", "bad input" or "state conflict" itself, then returns `error("…")`, which defaults to code 500. | `error(HttpStatus.NOT_FOUND / FORBIDDEN / BAD_REQUEST / CONFLICT, "…")` at each site | 28 |
| **G1** Code flattened by catch | The controller catches `IllegalArgumentException`, `IllegalStateException` or `ServiceException` and returns `error(e.getMessage())`. That forces code 500 and drops the global IAE→400 mapping and the ServiceException's own code, including several services that already threw `CONFLICT`. | New `AjaxResult.refusal(e)`: a coded ServiceException keeps its code and `data`; IAE → 400; ISE → 409; anything else stays 500. Applied only to the catch blocks on the rows' paths. | 20 |
| **G3** Uncoded service exception | A service throws `new ServiceException("…")` or a `RuntimeException` without a code, for not-found or state conditions. | Add the HttpStatus code at the throw site (or convert to a coded ServiceException) | 12 |
| **G4** Bean validation → 500 | `GlobalExceptionHandler` mapped `BindException` and `MethodArgumentNotValidException` (@Valid failures such as 活动结束时间不能为空) to 500. | Both now return 400 | 1 |

The fix changes only the code. Every `msg`, `data` and `errorCode` is unchanged. No table, migration, SQL, ledger or gitleaks file was touched, and no currency or reward logic changed (one currency, 积分, untouched).

### Fixed rows (61)

| Group | Call | Controller | msg | Code |
|---|---|---|---|---|
| G1 | `POST /api/club/join-request/approve` | ApiClubController.approveJoinRequest | 入会申请不存在或已处理 | 500 → 400 |
| G1 | `POST /api/club/join-request/reject` | ApiClubController.rejectJoinRequest | 入会申请不存在或已处理 | 500 → 400 |
| G1 | `POST /api/marketing/product/activate` | ApiMarketingProductController.activate | 该主题不是首次使用型,已在成交时生效 | 500 → 409 |
| G1 | `POST /api/user/deregister/cancel` | ApiAccountDeregistrationController.cancel | 当前没有可撤销的注销申请 | 500 → 409 |
| G1 | `POST /api/activity/cancel` | ApiActivityController.activityCancel | 原取消生命周期已变化，请人工核对原结果 | 500 → 409 |
| G1 | `POST /api/registration/cancel-refund` | ApiRegistrationController.cancelRefund | 支付现金快照未确认，请通过后台退款工单核对后处理 | 500 → 409 |
| G1 | `POST /api/registration/cancel-by-owner` | ApiRegistrationController.cancelByOwner | 支付现金快照未确认，请通过后台退款工单核对后处理 | 500 → 409 |
| G1 | `POST /api/invoice/request` | ApiUserInvoiceController.originalRequest | 暂未查到原申请 | 500 → 404 |
| G1 | `POST /api/merchant/commerce/order/status` | ApiMerchantCommerceController.orderStatus | 无权查看该订单 | 500 → 400 |
| G1 | `POST /api/merchant/predict/settle` | ApiPredictSettleController.settle | 这个点位不是预测玩法 | 500 → 400 |
| G1 | `POST /api/topic/pricing/confirm` | ApiPricingController.confirm | 主题未在定价阶段,不能确认终价 | 500 → 409 |
| G1 | `POST /api/play/tag/{}/confirm` | ApiPlayProgressController.confirmPreferenceTag | 标签不存在或已撤回 | 500 → 400 |
| G1 | `POST /api/play/tag/{}/correct` | ApiPlayProgressController.correctPreferenceTag | 标签不存在 | 500 → 400 |
| G1 | `POST /api/play/tag/{}/revoke` | ApiPlayProgressController.revokePreferenceTag | 标签不存在 | 500 → 400 |
| G1 | `POST /api/play/journey/ask` | ApiPlayJourneyActionController.ask | 参数有误 | 500 → 400 |
| G1 | `POST /api/play/journey/thought/sync` | ApiPlayJourneyActionController.syncThoughts | 请先购买自玩通行证 | 500 → 409 |
| G1 | `POST /api/play/journey/opening/complete` | ApiPlayJourneyActionController.completeOpening | 开局流程不匹配 | 500 → 400 |
| G1 | `POST /api/topic/chapter/supply-terms` | ApiTopicController.setChapterSupplyTerms | 主题已开售，这一站的档位已冻结 | 500 → 409 |
| G1 | `POST /api/topic/cancel` | ApiTopicController.topicCancel | 俱乐部主题请在俱乐部「主题设置 → 结束主题」里取消 | 500 → 409 |
| G1 | `POST /api/topic/cancel_preview` | ApiTopicController.topicCancelPreview | 俱乐部主题请在俱乐部「主题设置 → 结束主题」里取消 | 500 → 409 |
| G2 | `POST /api/club/remove-member` | ApiClubController.removeMember | 俱乐部不存在 | 500 → 404 |
| G2 | `POST /api/club/quit` | ApiClubController.quit | 主理人不能直接退出；请先在成员治理中转让主理人，或解散俱乐部 | 500 → 409 |
| G2 | `POST /api/club/badge/grant` | ApiClubController.badgeGrant | 勋章不存在或不属于本俱乐部 | 500 → 404 |
| G2 | `POST /api/club/lead/release-station` | ApiClubLeadController.releaseStation | 该成员不在本场 | 500 → 404 |
| G2 | `POST /api/coop/complaint/report` | ApiCoopController.complaintReport | 你对该主题已有处理中的投诉 | 500 → 409 |
| G2 | `POST /api/coop/invite` | ApiCoopController.invite | 邀请类型与受邀方类型不匹配 | 500 → 400 |
| G2 | `POST /api/coop/review/summary` | ApiCoopController.reviewSummary | 缺少被评人 | 500 → 400 |
| G2 | `GET /api/official/v2/events/{}/publish-check` | ApiOfficialEventController.v2PublishCheck | 无权查看该活动发布检查 | 500 → 403 |
| G2 | `POST /api/user/deregister/apply` | ApiAccountDeregistrationController.apply | 验证码输入有误 | 500 → 400 |
| G2 | `POST /api/merchant/finance/public-transfer-batch-detail` | ApiMerchantController.financePublicTransferBatchDetail | 记录不可见 | 500 → 404 |
| G2 | `POST /api/merchant/finance/redemption-detail` | ApiMerchantController.financeRedemptionDetail | 记录不可见 | 500 → 404 |
| G2 | `POST /api/merchant/merchant_registration` | ApiMerchantController.merchantRegistration | 已提交过商家入驻申请，请勿重复提交 | 500 → 409 |
| G2 | `POST /api/merchant/shop/apply` | ApiMerchantLegacyAliasController.shopApply | 已提交过商家入驻申请，请勿重复提交 | 500 → 409 |
| G2 | `POST /api/merchant/npc/voice/enroll` | ApiMerchantController.npcVoiceEnroll | 请先确认声音为本人所有或已获得授权 | 500 → 400 |
| G2 | `POST /api/merchant/update` | ApiMerchantController.updateUserInfo | 请选择有效的经营日与时间 | 500 → 400 |
| G2 | `POST /api/merchant/city-node/offline` | ApiMerchantNodeController.offline | 据点不存在或无权操作 | 500 → 404 |
| G2 | `POST /api/merchant/city-node/save` | ApiMerchantNodeController.save | 已上线据点不能复用投放申请入口 | 500 → 409 |
| G2 | `POST /api/merchant/chapter-node/submit` | ApiChapterMerchantNodeController.submit | 缺少章节 | 500 → 400 |
| G2 | `POST /api/merchant/chapter-node/npc/voice/enroll` | ApiChapterMerchantNodeController.npcVoiceEnroll | 请先录一段话 | 500 → 400 |
| G2 | `GET /api/play/preference/{}` | ApiPlayProgressController.preference | 节点不存在 | 500 → 404 |
| G2 | `GET /api/play/history` | ApiPlayProgressController.history | 本局不存在或无权查看 | 500 → 404 |
| G2 | `POST /api/play/puzzle/hint` | ApiPlayProgressController.puzzleHint | 队伍集合中,等队长开始行程 | 500 → 400 |
| G2 | `POST /api/roam/share/snapshot` | ApiRoamShareController.publish | 足迹数据无效 | 500 → 400 |
| G2 | `GET /api/roam/share/snapshot` | ApiRoamShareController.read | 这条足迹已不再公开 | 500 → 404 |
| G2 | `POST /api/topic/beta/graduate` | ApiTopicController.betaGraduate | 该主题不在 Beta 试玩期 | 500 → 409 |
| G2 | `POST /api/topic/merchant-recruitment-chapters` | ApiTopicController.merchantRecruitmentChapters | 仅自由探索主题支持章节承接 | 500 → 409 |
| G2 | `POST /api/topic/chapter/recruit` | ApiTopicController.setChapterRecruit | 本章已结束，不能再改商家承接 | 500 → 409 |
| G2 | `POST /api/topic/report` | ApiTopicController.topicReport | 主题不存在 | 500 → 404 |
| G3 | `POST /api/club/personal-receiver/bind` | ApiClubController.bindPersonalReceiver | PERSONAL_RECEIVER_CONSENT_REQUIRED | 500 → 400 |
| G3 | `POST /api/cart/update` | ApiCartController.cartUpdate | 购物车信息不存在 | 500 → 404 |
| G3 | `POST /api/cart/settlement` | ApiCartController.cartSettlement | 购物车数据异常 | 500 → 404 |
| G3 | `POST /api/registration/quote` | ApiRegistrationController.quote | 主题活动已下架 | 500 → 404 |
| G3 | `POST /api/merchant/city-node/template/submit` | ApiMerchantNodeController.templateSubmit | 据点模板不存在或无权修改 | 500 → 404 |
| G3 | `POST /api/merchant/chapter-application/invite/respond` | ApiMerchantChapterApplicationController.respondInvite | 申请不存在 | 500 → 404 |
| G3 | `POST /api/merchant/chapter-application/invite/revoke` | ApiMerchantChapterApplicationController.revokeInvite | 邀请不存在 | 500 → 404 |
| G3 | `POST /api/play/hint/unlock` | ApiPlayProgressController.unlockHint | 队伍集合中,等队长开始行程 | 500 → 400 |
| G3 | `GET /api/play/theme/card` | ApiThemeRuntimeController.card | 本局尚未结束 | 500 → 409 |
| G3 | `POST /api/play/theme/replay` | ApiThemeRuntimeController.replay | 此主题未开放重玩 | 500 → 409 |
| G3 | `POST /api/roam/session/records` | ApiRoamController.archiveRecords | 采集时间不属于原行程 | 500 → 400 |
| G3 | `POST /api/topic/delete` | ApiTopicController.topicDelete | 该主题的场次仍有待支付或已支付报名，请先处理场次报名，再删除主题 | 500 → 409 |
| G4 | `POST /api/activity/update` | ApiActivityController.update | 活动结束时间不能为空 | 500 → 400 |

### Code-500 rows not changed (33)

| Category | Rows | Calls | Why |
|---|---|---|---|
| CONTRACT (client depends on 500) | 14 | `/api/verify/citynode/receipt`, `/api/verify/citynode/redeem`, `/api/verify/groupcode/redeem`, `/api/registration/scan_dynamic_code`, `/api/registration/scan_group_member_ticket`, `/api/registration/scan_qr_code`; `/api/game/session/{recap/export, shared/export, shared/export/anchor, shared/preview, command}`; `/api/play/scan-entry`, `/api/play/sensor-result`, `/api/play/preference/{}/submit` | The mini-program reads "code 500 + data.notRedeemed=true" as "definitely not redeemed" (`utils/merchant-verification.js`, `pages/merchant/utils/citynode-redeem-outcome.js`). Game-session rows depend on "500 + reasonCode" (`utils/game-session-client.js:485`). The play rows use the `PlayCommandErrorContract` intent shape (`retryable`/`intentNotExecuted`). Changing these codes would turn definite refusals into "unknown" on the client. |
| SEED-ONLY | 9 | `/api/merchant/chapter-application/{apply,audit,invite}` (开放商家承接的章节必须选择适合商家品类); `/api/merchant/chapter-node/poster-code` (该点位还没有打卡码); `/api/play/advanced/{start,leaderboard}` (高级玩法配置版本不支持); `/api/play/journey/stage-end` (本主题未配置剧情); `/api/activity/publish` (已达到当前身份活动数量上限（3）: earlier smoke runs used up the fixture club's quota); `/api/invoice/apply` (原订单现金或退款状态无法确认: the fixture order has no cash snapshot) | The fixture rows on the TEST database lack the required configuration or state. The backend's refusal is correct. |
| ENV-ONLY | 3 | `/api/merchant/commerce/order` (支付服务暂不可用), `/api/ai/theme/draft/prepare` (AI draft state unavailable), `/api/template/updateLibraryStatus` (上架复检暂不可用) | No external provider or review dependency is configured on TEST. The 500 deliberately means "service unavailable / outcome unknown". |
| Input-only | 2 | `/api/topic/create`, `/api/topic/v2/create` | The harness body has no chapters or nodes, so the topic compiler rejects it with `data.issues`. Fix the harness input. |
| Deferred | 5 | `/api/coop/deposit/refund/retry`; `/api/official/events/{}/complete`, `/api/official/events/{}/signup`, `/api/official/v2/organizer-invites/{}/{}`, `/api/official/v2/parties/{}/{}` | coop retry: the service throws one `IllegalStateException` for permission, state and persistence failures. `ApiCoopRefundAlertTest` requires the persistence failure to stay 500, and `utils/coop-invite-request.js` reads any 4xx as a definite failure. Official events: the service returns plain strings that mix not-found, no-permission and state; each string needs its own classification. Both need a separate, narrower change. |

### Non-500 rows (93, all SEED-ONLY or input-only, no change)

| HTTP/code | msg | Rows | Calls |
|---|---|---|---|
| 200/400 | 本次起草暂不能继续 | 1 | `POST /api/ai/theme/draft/execute` (ApiAiController.themeDraftExecute) |
| 200/400 | 原到达请求号无效 | 1 | `POST /api/audit/guard/appeal` (ApiAuditController.guardAppeal) |
| 200/400 | 兑换码无效 | 1 | `POST /api/channel/redeem` (ApiChannelRedemptionController.redeem) |
| 200/400 | 动态不存在 | 1 | `POST /api/club/post/history` (ApiClubController.postHistory) |
| 200/400 | 读回参数无效 | 5 | `POST /api/club/open-settings/welcome-note-receipt` (ApiClubController.welcomeNoteReceipt); `POST /api/club/event-ops/series/original-result` (ApiClubEventOpsController.seriesOriginalResult); `POST /api/club/roles/assign-receipt` (ApiClubRoleController.assignReceipt); `POST /api/club/roles/revoke-receipt` (ApiClubRoleController.revokeReceipt); `GET /api/common/uploadOSS/receipt` (ApiCommonController.uploadOSSReceipt) |
| 200/400 | 排期类型仅支持 ONCE/WEEKLY/CUSTOM_DATES | 1 | `POST /api/club/events/save` (ApiClubGapController.eventsSave) |
| 200/400 | 参数缺失 | 2 | `POST /api/coupon/command-receipt` (ApiCouponController.commandReceipt); `POST /api/coupon/stop` (ApiCouponController.stopIssuing) |
| 200/400 | INVALID_FIELD | 1 | `POST /api/team/original-request` (ApiFriendController.teamCommand) |
| 200/400 | 推广凭据与订单商品不匹配 | 1 | `POST /api/marketing/attribution/claim` (ApiMarketingAttributionController.claim) |
| 200/400 | 缺少报价签名，请重新报价 | 1 | `POST /api/marketing/group-checkout/create` (ApiMarketingGroupCheckoutController.create) |
| 200/400 | 所选客户现在都收不到(未同意接收商家消息或今天已收过)，请换选已同意的客户 | 1 | `POST /api/merchant/crm/broadcast` (ApiMerchantCrmController.broadcast) |
| 200/400 | merchantRowId 不合法 | 1 | `POST /api/merchant/reviews/{}` (ApiMerchantReviewController.create) |
| 200/400 | 缺少或非法的幂等键(idempotencyKey,8-64 位) | 1 | `POST /api/orient/task/alternative/submit` (ApiOrientController.submitAlternative) |
| 200/400 | 原检定参数有误 | 1 | `GET /api/play/check/receipt` (ApiPlayCheckController.receipt) |
| 200/400 | 队伍集合中,等队长开始行程 | 4 | `GET /api/play/help/receipt` (ApiPlayHelpController.receipt); `POST /api/play/help/skip` (ApiPlayHelpController.skip); `POST /api/play/help/use` (ApiPlayHelpController.use); `GET /api/play/help` (ApiPlayHelpController.view) |
| 200/400 | 原局不存在或无权访问 | 1 | `GET /api/play/help/advanced/receipt` (ApiPlayHelpController.receiptAdvanced) |
| 200/400 | 请先补全参与人姓名，再购买 | 1 | `POST /api/registration/create` (ApiRegistrationController.createRegistration) |
| 200/400 | 参数不合规 | 1 | `POST /api/registration/explore-completion` (ApiRegistrationController.exploreCompletion) |
| 200/400 | 模板不存在或无权提交 | 1 | `POST /api/template/{}/market-submit` (ApiTemplateController.submitTemplateToMarket) |
| 200/400 | 物资动作不接受新奖励或任务选项 | 1 | `POST /api/play/theme/action` (ApiThemeRuntimeController.action) |
| 200/400 | 动作参数不正确 | 1 | `POST /api/play/theme/gameplay-action` (ApiThemeRuntimeController.gameplayAction) |
| 200/400 | 来源修订与当前发布快照不一致 | 2 | `POST /api/topic/transfer-to-club` (ApiTopicController.transferToClub); `POST /api/template/topic-template/save-as` (ApiTopicTemplateController.saveAs) |
| 400/400 | 缺少字段 | 1 | `POST /api/approved-topic-release/v1/status` (ApiAppGapsApprovedTopicReleaseController.status) |
| 400/400 | 编号不合法 | 1 | `POST /api/guilds/{}/{}/channels` (ApiChannelPlatformController.create) |
| 400/400 | 行动请求无效 | 1 | `POST /api/city-game/game/action` (ApiCityGameController.action) |
| 400/400 | 参与指令无效 | 1 | `POST /api/city-game/team-participation/command` (ApiCityGameController.command) |
| 400/400 | 占领指令无效 | 1 | `POST /api/city-game/acquisition/command` (ApiCityGameR491Controller.acquisitionCommand) |
| 400/400 | 到店指令无效 | 1 | `POST /api/city-game/arrival-evidence/command` (ApiCityGameR491Controller.arrivalCommand) |
| 400/400 | 任务指令无效 | 1 | `POST /api/city-game/onsite-tasks/command` (ApiCityGameR491Controller.onsiteCommand) |
| 400/400 | 请求参数格式有误 | 1 | `POST /api/marketing/product/order` (ApiMarketingProductController.order) |
| 400/400 | {"msg":"UPSTREAM_UNAVAILABLE","code":400,"data":{"outcome":" | 1 | `GET /api/merchant/authoring/jobs` (ApiMerchantAuthoringController.job) |
| 400/400 | US_APPLE_INVALID_REQUEST | 1 | `POST /api/us/auth/apple/exchange` (ApiUsAppleAuthController.exchange) |
| 400/400 | hash invalid | 2 | `POST /api/workshop/purchased/professional-operations/cancel` (ApiWorkshopProfessionalController.cancel); `POST /api/workshop/purchased/professional-operations/submit` (ApiWorkshopProfessionalController.submit) |
| 401/401 | US_SESSION_INVALID | 1 | `GET /api/us/auth/session` (ApiUsAppleAuthController.session) |
| 200/403 | 已达到当前身份活动数量上限（3） | 1 | `POST /api/activity/publish_precheck` (ApiActivityController.publishPrecheck) |
| 200/403 | NPC 自由追问未开放 | 1 | `POST /api/ai/npc/chat` (ApiAiNpcController.chat) |
| 200/403 | WORKSHOP_PAID_SOURCE_PRIVATE | 1 | `POST /api/template/topic-template/use` (ApiTopicTemplateController.use) |
| 200/404 | 尚未查到原请求回执，结果仍待确认 | 1 | `GET /api/play/advanced/receipt` (ApiAdvancedPlayController.receipt) |
| 200/404 | 同行邀请码不存在或会话已结束 | 1 | `POST /api/circle-theme/session/join` (ApiCircleThemeController.joinSession) |
| 200/404 | 引用的记录不存在或当前不可公开 | 1 | `POST /api/club/post/create` (ApiClubController.postCreate) |
| 200/404 | 处置记录不存在 | 1 | `POST /api/v1/community/appeals` (ApiCommunityPostController.appeal) |
| 200/404 | 帖文不存在或不可见 | 1 | `POST /api/v1/community/reports` (ApiCommunityPostController.report) |
| 200/404 | 草稿不存在 | 1 | `POST /api/content-draft/publish` (ApiContentDraftController.publish) |
| 200/404 | 草稿不存在或已结束 | 1 | `POST /api/content-draft/restore` (ApiContentDraftController.restore) |
| 200/404 | FRIEND_RECEIPT_NOT_FOUND | 5 | `GET /api/friend/metadata/receipt` (ApiFriendController.metadataReceipt); `POST /api/friend/metadata/settle` (ApiFriendController.metadataSettle); `GET /api/friend/receipt` (ApiFriendController.receipt); `GET /api/friend/removal/receipt` (ApiFriendController.removalReceipt); `POST /api/friend/removal/settle` (ApiFriendController.removalSettle) |
| 200/404 | FRIEND_REQUEST_NOT_FOUND | 2 | `POST /api/friend/accept` (ApiFriendController.write); `POST /api/friend/reject` (ApiFriendController.write) |
| 200/404 | FRIEND_UNAVAILABLE | 1 | `POST /api/friend/cancel` (ApiFriendController.write) |
| 200/404 | 该路线暂不可用 | 1 | `POST /api/im/route-share-source` (ApiImController.routeShareSource) |
| 200/404 | 评价不存在或无权访问 | 1 | `POST /api/merchant/reviews/report` (ApiMerchantReviewController.report) |
| 200/404 | 任务不存在 | 3 | `POST /api/orient/task/alternative` (ApiOrientController.configureAlternative); `GET /api/orient/task/state` (ApiOrientController.taskState); `GET /api/orient/task/alternative/receipt` (ApiOrientTaskReceiptController.receipt) |
| 200/404 | 没有这条路线动作 | 1 | `GET /api/play/route-action` (ApiPlayerGapsController.routeAction) |
| 200/404 | 作品不存在或未公开 | 1 | `POST /api/public-catalog/info` (ApiPublicContentCatalogController.info) |
| 200/404 | 邀请不存在 | 2 | `POST /api/play/seat-invitations/{}/cancel` (ApiRegistrationSeatInvitationController.cancel); `GET /api/play/seat-invitations/{}` (ApiRegistrationSeatInvitationController.status) |
| 200/404 | 这一项不存在。 | 1 | `DELETE /api/topic/state-keys/named` (ApiTopicAttributeController.deleteNamed) |
| 200/404 | 主题活动不存在 | 1 | `POST /api/topic/info-to-user` (ApiTopicController.topicInfo) |
| 200/404 | 模板不存在 | 1 | `POST /api/template/topic-template/info` (ApiTopicTemplateController.info) |
| 404/404 | 没有该送审请求 | 2 | `POST /api/approved-topic-release/v1/review/current` (ApiAppGapsApprovedTopicReleaseController.reviewCurrent); `POST /api/approved-topic-release/v1/review/status` (ApiAppGapsApprovedTopicReleaseController.reviewStatus) |
| 404/404 | 没有这条请求的回执 | 4 | `GET /api/city-game/game/action/{}` (ApiCityGameController.actionReceipt); `GET /api/city-game/{}/request/{}` (ApiCityGameController.originalReceipt); `GET /api/city-game/acquisition/request/{}` (ApiCityGameR491Controller.acquisitionReceipt); `GET /api/city-game/onsite-tasks/request/{}` (ApiCityGameR491Controller.onsiteReceipt) |
| 404/404 | 报价不存在 | 1 | `GET /api/city-game/acquisition/quote/{}` (ApiCityGameR491Controller.acquisitionQuote) |
| 404/404 | 到店凭证不存在 | 1 | `GET /api/city-game/arrival-evidence/evidence/{}` (ApiCityGameR491Controller.arrivalEvidence) |
| 404/404 | 结果不存在 | 1 | `GET /api/city-game/onsite-tasks/result/{}` (ApiCityGameR491Controller.onsiteResult) |
| 404/404 | 到访不存在 | 1 | `POST /api/city-value/visits/feedback` (ApiCityGameR491Controller.valueFeedback) |
| 404/404 | NOT_FOUND | 1 | `POST /api/merchant/authoring/jobs/cancel` (ApiMerchantAuthoringController.cancel) |
| 404/404 | 没有找到该请求 | 2 | `GET /api/progress/requests/{}` (ApiProgressPrivacyController.progressReceipt); `GET /api/progress/requests` (ApiProgressPrivacyController.settingsReceipt) |
| 200/422 | 媒体来源未经审核，请重新上传 | 1 | `POST /api/topic/publish-draft` (ApiTopicController.publishDraft) |
| 200/501 | WECHAT_SUBSCRIPTION_UNAVAILABLE: 订阅模板或授权未接入 | 3 | `POST /api/club/event-notification/preview` (ApiClubEventNotificationController.preview); `POST /api/club/event-notification/send` (ApiClubEventNotificationController.send); `POST /api/club/notices/send` (ApiClubGapController.noticeSend) |
| 200/520 | 章节命令结果待确认 | 1 | `GET /api/club/lead/unlock-chapter-receipt` (ApiClubLeadController.unlockChapterReceipt) |
| 200/520 | 原命令结果待确认，请稍后查询 | 1 | `GET /api/play/action-receipt` (ApiPlayProgressController.basicActionReceipt) |

## Side effects on the same paths (intended)

Because G1 now keeps codes, some other refusals on the same handlers change too:

- `/api/topic/cancel`, `/api/topic/cancel_preview`, `/api/topic/delete` now keep the codes the service already set (403 not the host, 409, 404) instead of returning 500.
- `/api/topic/pricing/confirm` returns 409 for every `IllegalStateException`, for example 每个必选章至少需要 1 家生效商家供给 (`FreeExplorePriceLockAcceptanceTest` updated).
- Activity cancel: a 403 for a role without permission ("当前岗位没有这个权限") now stays 403 (`ActivityPublishRoleGateTest` updated).
- `/api/merchant/commerce/order/status` returns 400, not 403: the service throws the same IAE for "not found" and "not yours", and the mini-program treats 403 as "switch page".

Before changing each endpoint's code, we grepped the mini-program (`chengyinhub-xcx`, non-test) for that endpoint. No changed endpoint branches on code 500. The iOS app is not in this repository and was not checked; if it compares against 500 for any of these endpoints, it needs the same review.

## Expected smoke effect

- **OK: expected 773, unchanged.** A status-code fix makes a refusal correct; it does not make it succeed. The rows that could turn OK are the SEED-ONLY rows, and only after fixture work.
- **BACKEND-ERROR: expected at most 191 − 61 = 130.** The 61 fixed rows should move to BUSINESS-REFUSAL-EXPECTED (expected 262 + 61 = 323), assuming the classifier treats them like the earlier `fix/api-smoke-500-r2` 4xx conversions. The classifier is not in this repository. Some rows that already return 4xx are still classified BACKEND-ERROR, so a few of the 61 may stay red even with a correct 4xx. Confirm on the next TEST 8120 run.

## Tests

New or updated unit tests, one or more per fixed group and controller:
`AjaxResultRefusalTest`, `GlobalExceptionHandlerErrorMappingTest` (G4), `SmokeR4BusinessRefusalCodeTest`, `MerchantSmokeRefusalCodeTest`, `PlaySmokeRefusalCodeTest`, `ApiTopicSmokeRefusalCodeTest`, `ApiPlayJourneyActionRefusalCodeTest`, `ApiChapterMerchantNodeAccessTest`, `ApiMerchantNodeOperatorAccessTest`, `ApiMerchantRegistrationStateGateTest`, `ApiMerchantEditProfileTest`, `ApiMerchantNpcVoiceTest`, `ApiMerchantOperatorEndpointTest`, `MerchantChapterApplicationServiceImplTest`, `EcommercePersonalReceiverServiceTest`, `ApiAccountDeregistrationControllerTest`, `ApiCartControllerSettlementTest`, `ApiCoopComplaintReportTest`, `ApiCoopControllerInviteAuthzTest`, `ActivityParentTopicSignupGateTest`, `ActivityPublishRoleGateTest`, `PersonalGameplayRuntimeServiceTest`, `RoamSessionArchiveTest`, `ApiRoamShareControllerTest`, `PlayLeadAdmissionFailureTest`, `TopicBetaGraduateTest`, `TopicDeletePaidGateTest`, `HostWeatherCancelTest`, `ApiTopicMerchantRecruitmentControllerTest`, `FreeExplorePriceLockAcceptanceTest`.

1. Targeted run: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q test -pl chengyinhub-admin -am -Dtest=<the 29 classes above, without FreeExplorePriceLockAcceptanceTest>` → **29 classes, 418 tests, 0 failures, 0 errors**.
2. Full suite: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B test` → **Tests run 12782, Failures 1, Errors 34, Skipped 569**.
   - The 1 failure was `FreeExplorePriceLockAcceptanceTest.controller_ownerSeesTheExactRefusalReasonFromTheRealService`, which asserted the old 500 for a pricing state refusal. The expectation was updated to 409, and that class plus `MerchantSmokeRefusalCodeTest` were re-run: **21 + 3 tests, 0 failures**. The full suite was not re-run after this one-line test change.
   - The 34 errors are all MySQL-only integration tests failing with `Communications link failure` (no MySQL in this container). Ignored as environment failures:
- `AuditReportLaneMyBatisTest`
- `ClubCrmCheckinDetailMyBatisTest`
- `ClubDissolutionGateMyBatisTest`
- `ClubTopicManageStatsMyBatisTest`
- `ComplianceF7MyBatisTest`
- `MerchantDashboardAggregationMyBatisTest`
- `MerchantNumbersAgreeMyBatisTest`
- `O08MemberOnlyTopicLocationMyBatisTest`
- `PublicActivityScopeGateMyBatisTest`
- `PublicTopicFeedGateMyBatisTest`
- `PublicVisibilityMyBatisTest`
- `RedemptionRewardClawbackMyBatisTest`
- `TopicDraftVisibilityMyBatisTest`
- `TopicSyncPostRestoreMyBatisTest`
