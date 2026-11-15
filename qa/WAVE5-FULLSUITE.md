# Wave 5 candidate: full backend suite

- Candidate: `origin/integration/backend-app-r3-wave5-candidate` @ `785d70908`. That is the r3 Wave 4 base `81db27155` plus the merges of `fix/security-r3`, `fix/acceptance-r3` and `fix/smoke-500-r4`, plus the E06_17 pin.
- Command: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B test -DargLine='-Xmx3g' -Dmaven.test.failure.ignore=true`. I added `failure.ignore` so a red module can't skip the modules that come after it. Exit 0, 7m16s.
- Machine: cloud sandbox, 4 CPU, 15 GB RAM, Java 21. No MySQL, Redis or VPS reachable. It did not run out of memory.

## Totals

| Run | Failures | Errors | Skipped |
|---|---|---|---|
| 19,909 | **0** | 34 | 713 |

All tests are in `chengyinhub-admin`. The other modules print "No tests to run".

**Real failures: none.** All 34 errors are MySQL `Communications link failure` (ENV-ONLY, below). No test failed on an assertion.

## ENV-ONLY

### A. Errored: `*MyBatisTest` that create their own MySQL schema (34 tests, 14 classes)

| Class | Errors |
|---|---|
| `AuditReportLaneMyBatisTest` | 5 |
| `ClubCrmCheckinDetailMyBatisTest` | 1 |
| `ClubDissolutionGateMyBatisTest` | 1 |
| `ClubTopicManageStatsMyBatisTest` | 1 |
| `ComplianceF7MyBatisTest` | 2 |
| `MerchantDashboardAggregationMyBatisTest` | 1 |
| `MerchantNumbersAgreeMyBatisTest` | 1 |
| `O08MemberOnlyTopicLocationMyBatisTest` | 2 |
| `PublicActivityScopeGateMyBatisTest` | 4 |
| `PublicTopicFeedGateMyBatisTest` | 1 |
| `PublicVisibilityMyBatisTest` | 5 |
| `RedemptionRewardClawbackMyBatisTest` | 6 |
| `TopicDraftVisibilityMyBatisTest` | 2 |
| `TopicSyncPostRestoreMyBatisTest` | 2 |

None of these 14 classes changed in the merged branches. They fail while connecting, before any SQL runs. `qa/SECURITY-FIXES-R3.md` records the same 34 errors on `fix/security-r3`.

### B. Skipped: MySQL integration tests gated by env var or assumption (711 of the 713 skips)

`*MysqlIntegrationTest`, `*MybatisIntegrationTest`, `longrun/*Test` (`@EnabledIfEnvironmentVariable(... _MYSQL_IT)`), `OfficialV1CapacityMySqlTest`, `MysqlItSchemaIsolationTest`, `StoreVerifyGrowthDeliveryTransactionTest`, `B02TopicEditDetailSnapshotMyBatisTest`, `OWN_C01_51Test$RealMysqlRepeatableRead`. Count of skipped tests per class:

```
114 business.service.RegistrationFundsMybatisIntegrationTest
36 business.service.CoopDepositRefundMybatisIntegrationTest
30 business.service.RefundPayoutMybatisIntegrationTest
26 business.service.GrowthMissionProofMybatisIntegrationTest
24 business.service.PlayTeamMybatisIntegrationTest
21 business.service.MerchantIdentityMybatisIntegrationTest
21 business.service.ContentInteractionMybatisIntegrationTest
21 business.service.ClubEditionCompensationMybatisIntegrationTest
20 business.service.ClubPayableMybatisIntegrationTest
19 web.controller.api.RoamSessionWriteMybatisIntegrationTest
18 business.service.TandianriBatch3FundsMybatisIntegrationTest
17 business.service.TandianriBatch0FundsMybatisIntegrationTest
16 business.service.PlayerWithdrawalCooldownMybatisIntegrationTest
16 business.service.CouponLifecycleMybatisIntegrationTest
14 business.service.impl.ImSendIdempotencyMybatisIntegrationTest
14 business.service.ClubCorporatePayeeMybatisIntegrationTest
11 business.service.ClubWaitlistTokenMybatisIntegrationTest
10 business.service.FirstReviewPointsClawbackMybatisIntegrationTest
9 business.service.RefundNotificationMybatisIntegrationTest
9 business.service.MerchantProfilePersistenceMybatisIntegrationTest
9 business.service.CmsTemplateLibraryAudioPatchMybatisIntegrationTest
9 business.service.CmsMemberTemplateAudioPatchMybatisIntegrationTest
9 business.longrun.E02_22Test
8 business.service.impl.CoopComplaintCloseMybatisIntegrationTest
8 business.service.ComplaintPeriodWithdrawalGateMybatisIntegrationTest
7 business.service.SquareEditFollowMybatisIntegrationTest
7 business.service.SpoilerAccessMybatisIntegrationTest
7 business.service.NearbyRunnerShopsMybatisIntegrationTest
7 business.service.FollowEditRaceMybatisIntegrationTest
7 business.service.CreativeSquarePublishIdempotencyMybatisIntegrationTest
7 business.service.B03AssetMybatisIntegrationTest
7 business.externalgame.ExternalGameReviewMysqlIntegrationTest
6 business.service.RegistrationEquityMybatisIntegrationTest
6 business.service.PhotoReviewProgressMybatisIntegrationTest
6 business.service.ClubSettlementSummaryMybatisIntegrationTest
6 business.mapper.RegistrationReadContractMybatisIntegrationTest
6 business.mapper.PlayerRunSessionOrderingMybatisIntegrationTest
5 business.service.CoopListReadOnlyMybatisIntegrationTest
5 business.mapper.ShareSnapshotRunSessionMybatisIntegrationTest
5 business.longrun.OWN_C06_30Test
5 business.longrun.E01_18Test
4 web.controller.api.B02TopicEditDetailSnapshotMyBatisTest
4 business.service.impl.ImFoundationBMybatisIntegrationTest
4 business.service.StoreVerifyGrowthDeliveryTransactionTest
4 business.service.SettlementGenerateBatchMybatisIntegrationTest
4 business.service.PlayTeamNearbyMybatisIntegrationTest
4 business.service.FollowConcurrencyMybatisIntegrationTest
4 business.longrun.OWN_C04_19Test
4 business.longrun.E02_20Test
4 business.longrun.E02_15Test
4 business.appgaps.ClubProxyEnrollMysqlIntegrationTest
4 business.appgaps.AppGapsPass2MysqlIntegrationTest
3 business.workshop.RewardsWorkshopMysqlIntegrationTest
3 business.service.TemplateUseNumFirstAdoptionMybatisIntegrationTest
3 business.service.SessionRefundClockMybatisIntegrationTest
3 business.service.MysqlItSchemaIsolationTest
3 business.longrun.E04_10Test
3 business.longrun.E02_16Test
3 business.longrun.E01_10Test
3 business.externalgame.ExternalGameMonetizationMysqlIntegrationTest
3 business.citygame.CityR491MysqlIntegrationTest
3 business.citygame.CityGameMysqlIntegrationTest
3 business.begapmisc.BeGapMiscMysqlIntegrationTest
3 business.appgaps.CreatorMaintenanceMysqlIntegrationTest
2 business.service.support.PlayerGapsMysqlIntegrationTest
2 business.service.OfficialV1CapacityMySqlTest
2 business.longrun.OWN_C06_10Test
2 business.longrun.E04_14Test
2 business.longrun.E04_13Test
2 business.longrun.E04_06Test
2 business.longrun.E01_09Test
2 business.longrun.E01_06Test
1 business.service.impl.OWN_C01_51Test$RealMysqlRepeatableRead
1 business.longrun.E02_18Test
1 business.longrun.E02_11Test
1 business.longrun.E02_10Test
1 business.longrun.E02_06Test
1 business.longrun.E02_01Test
1 business.citygame.SysCityGameMysqlIntegrationTest
```

### C. Skipped: Apple IAP SDK fixtures (2 skips)

`AppleSignedDataSdkTest` (2) needs `APPLE_IAP_TEST_ROOT` and `APPLE_IAP_TEST_FIXTURE_DIR`. `ci/backend-test.sh` downloads them. The two GitHub fixtures downloaded and matched their SHA. `www.apple.com/certificateauthority/AppleRootCA-G3.cer` returned 403 from the sandbox proxy, so these 2 tests could not run here.

### D. Not collected by surefire: `*IT` classes (254 files)

The surefire default includes don't match `*IT`. `ci/backend-test.sh` runs them by name against MySQL/Redis. None ran here. List:

`A02NpcDeleteBarrierMySqlIT`, `A03EndingSnapshotMySqlIT`, `AccountDeregistrationRaceMySqlIT`, `ActivityCancelFreeOrderMybatisIT`, `ActivityCancelPlayerProjectionMyBatisIT`, `AdvancedCommandFingerprintMySqlIT`, `AdvancedGameRuntimeMySqlIT`, `AiNotSentQuotaRecoveryMySqlRedisIT`, `AiQuotaRecoveryMySqlRedisIT`, `AiQuotaReservationRedisIT`, `AiThemeDraftIntentMySqlRedisIT`, `AppMediaAssetMySqlIT`, `AppOriginalEnvelopeMySqlIT`, `AppOriginalRequestLedgerMySqlIT`, `ApprovedTopicReleasesMySqlIT`, `AuditAppealRequestMySqlIT`, `AuditItemProblemMySqlIT`, `AuditTopicCurrentPublicationMySqlIT`, `AuditTrailProjectionMySqlIT`, `AuthorAttributeBindingMyBatisIT`, `AuthorWriteReceiptMySqlIT`, `B03CouponLifecycleMySqlIT`, `B04ScheduleReadbackMySqlIT`, `B05CommunityVisibilityMySqlIT`, `BeforeStartNoticeMySqlIT`, `CartSettlementWriteReceiptMySqlIT`, `CashWithdrawFundEvidenceMySqlIT`, `CashWithdrawTransferIntentMySqlIT`, `ChainPersistenceMySqlIT`, `ChannelsFourthPassMySqlIT`, `ChannelsGapImMySqlIT`, `ChannelsPlatformMySqlIT`, `ChannelsSecondPassMySqlIT`, `ChannelsThirdPassMySqlIT`, `ChannelsWebhookDispatchMySqlIT`, `ChapterApplicationOutcomeMySqlIT`, `ChapterApplicationRoundMySqlIT`, `CircleThemeSeedMySqlIT`, `CityDirectorConsoleMySqlIT`, `CityNodeCompletionIdempotencyMySqlIT`, `CityNodePendingVisibilityMySqlIT`, `ClmCommercePaymentProofMySqlIT`, `ClubAttendanceTimeMappingMyBatisIT`, `ClubEventTransferCancellationMySqlIT`, `ClubGatherArrivalMySqlIT`, `ClubHistoricalSettlementAccessMySqlIT`, `ClubJoinGovernanceMySqlIT`, `ClubLeadChapterIntentMySqlIT`, `ClubLeadOpsSoldLockMySqlIT`, `ClubLeaderboardContributionMyBatisIT`, `ClubOriginalsMySqlIT`, `ClubPostLikeAuthorizationMySqlIT`, `ClubPostMediaReviewBudgetMySqlIT`, `ClubRoleHttpMySqlIT`, `ClubSeriesOriginalResultsMySqlIT`, `ClubSessionMerchantFeeMySqlIT`, `CommittedOfficialNoticeMySqlIT`, `CommunityActivityReferenceMySqlIT`, `CommunityMediaEditMySqlIT`, `CommunityPostCommentIdempotencyMySqlIT`, `ComplaintCloseReporterNoticeMySqlIT`, `ComplaintPickerOwnerType3RealDbIT`, `CompletedRouteFactMapperMySqlIT`, `ConsentRecordIdempotencyMySqlIT`, `ContentLandingMetadataVersionMySqlIT`, `ContentLandingReviewConcurrencyMySqlIT`, `ContentSearchCityMyBatisIT`, `CoopClubApplyInboxMySqlIT`, `CoopComplaintCommittedNoticeMySqlIT`, `CoopDepositSettlementAttemptMySqlIT`, `CoopInviteOriginalRequestMySqlIT`, `CoopMapperMyBatisIT`, `CoopOrderStationFulfillmentMySqlIT`, `CoopServiceRealDbIT`, `CoopSettlementManualCaseReadMySqlIT`, `CoopSupplyStationMySqlIT`, `CouponCommandReceiptsMySqlIT`, `CouponStockConservationMySqlIT`, `CreatorIncomeDetailIdempotencyMySqlIT`, `CrossDomainCombinedLoadMySqlIT`, `CrossDomainFaultIsolationMySqlIT`, `Doc2L8MapperMyBatisIT`, `E02_25MySqlIT`, `E04_07MySqlIT`, `EcomCombineMapperMySqlIT`, `EcomPayoutMapperMySqlIT`, `EcomPayoutWiringMySqlIT`, `ExplorationAnniversaryMySqlIT`, `ExploreAllianceMvpRealDbIT`, `ExploreAllianceRedeemRealDbIT`, `ExploreScanReplayRealDbIT`, `FreeExploreBundleLedgerMySqlIT`, `FreeExploreNodeSupplyMySqlIT`, `FreeExploreSelfPlayPriceGateMySqlIT`, `FreeSelfPlayCreateRealDbIT`, `FreezeGameHttpE2eIT`, `FreezeReuseThemeHttpE2eIT`, `FriendFoundationSchemaMySqlIT`, `FundsChainO07PinMySqlIT`, `FundsProcessRecoveryMySqlIT`, `FunnelMaxDateMySqlIT`, `GameSessionCityEndMySqlIT`, `GameSessionIndependentRevisionMySqlIT`, `GameSessionScanLegalMySqlIT`, `GameplayReskinMySqlIT`, `GenericGameplayHttpE2eIT`, `GenericRouteRuntimeMySqlIT`, `GuardWorkbenchScopeMySqlIT`, `HangoutAuditLockMyBatisIT`, `ImActivityTeamRevocationMySqlIT`, `ImAdminSendIdempotencyMySqlIT`, `ImClubConversationBatchMySqlIT`, `ImConversationBatchReadMySqlIT`, `ImConversationQueryScopeMySqlIT`, `ImLegacyReadLockMySqlIT`, `ImModerationUnreadMySqlIT`, `InGameChannelMySqlIT`, `JourneyFullFlowMySqlIT`, `JourneyThoughtProgressMySqlIT`, `LaneW10bReadbackMySqlIT`, `LegacyCategoryForwardGuardMySqlIT`, `LegacyReplyCountMySqlIT`, `MapPublicLayersMySqlIT`, `MarketingBundleRefundMySqlIT`, `MarketingCashOriginalRefundMySqlIT`, `MarketingChannelVoucherMySqlIT`, `MarketingProductGiftClaimMySqlIT`, `MarketingPromotionRefundLedgerMySqlIT`, `MarketingRevenueAllocationMySqlIT`, `MarketingRevenueReversalQueueMySqlIT`, `MemberPasswordWidthMySqlIT`, `MemberPrivacyMyBatisIT`, `MerchantCrmContactRevealMySqlIT`, `MerchantCrmFreeExploreRosterMySqlIT`, `MerchantGapMySqlIT`, `MerchantGeoSortMyBatisIT`, `MerchantOfferOwnerNoticeIntentMySqlIT`, `MerchantReferralBountyDeadlockRetryMySqlIT`, `MerchantRegistrationCancellationMySqlIT`, `MissingBusinessSeedsMySqlIT`, `MonitorMissingJobsMySqlIT`, `MybatisItSchemaIsolationIT`, `NodeNpcMapperMyBatisIT`, `NpcAsrIntentMySqlIT`, `NpcChatRetentionMyBatisIT`, `NpcChatUserDataMyBatisIT`, `NpcCurrentRegistrationMySqlIT`, `NpcGeneratedAudioMySqlIT`, `NpcGovernanceMapperMyBatisIT`, `NpcVoiceEnrollmentMySqlIT`, `O14CityCodeBackfillMySqlIT`, `OWN_C04_20MySqlIT`, `OWN_C04_21MySqlIT`, `OWN_C05_25MySqlIT`, `OfficialBroadcastDeliveryMySqlIT`, `OfficialBroadcastDeliveryMySqlIT`, `OfficialEventAttendanceMySqlIT`, `OmsOrderCommerceGuardMySqlIT`, `OrientRouteSettlementMySqlIT`, `OrientScoringFlowMySqlIT`, `P0CityRunChainMySqlIT`, `P0CrossAccountMySqlIT`, `P0DeregistrationMySqlIT`, `P0FreeExploreSupplyGateMySqlIT`, `P0FreeExploreSupplyRecoveryMySqlIT`, `P0RedemptionChainMySqlIT`, `PendingRecruitTerminationMyBatisIT`, `PersonalExplorationExportMySqlIT`, `PersonalExplorationGoalMySqlIT`, `PhotoCheckIntentFreshReadMySqlIT`, `PhotoCheckIntentLockReadMySqlIT`, `PhotoCheckIntentMySqlIT`, `PhotoCheckIntentRecoveryMySqlIT`, `PlatformPassMySqlIT`, `PlayHelpRunQuotaConcurrencyMySqlIT`, `PlayHintUnlockMySqlIT`, `PlayLeadAdmissionFailureMySqlIT`, `PlayerGrowthConcurrencyMySqlIT`, `PlayerMarketingReadMySqlIT`, `PostGameOutcomeMapperMySqlIT`, `PredictLateBetRaceMySqlIT`, `PredictSettleAtomicityMySqlIT`, `PrivacyVisibilityExportMySqlIT`, `PublicContentCatalogMyBatisIT`, `PublicMemberFriendCountMySqlIT`, `PublicTopicStationCountMySqlIT`, `RedeemExploreReadViewMySqlIT`, `RefundRetryAndRemainderMySqlIT`, `RegistrationCallbackIntentMySqlIT`, `RegistrationSeatInvitationMySqlIT`, `RegistrationSeatMySqlIT`, `RegistrationStationFulfillmentMySqlIT`, `RegistrationTopicFenceMySqlIT`, `RestoreMoneyDispatchMySqlIT`, `RoamExploreDayCandidateMyBatisIT`, `RoamLocationRetentionMySqlIT`, `RoamPassportReadMySqlIT`, `RoamPoiPlayerVisibilityMyBatisIT`, `RoamPresenceRevokeDurabilityMySqlIT`, `RoamPresenceRevokeJobMySqlIT`, `RoamShareSnapshotConcurrencyMySqlIT`, `SchedulerHandoffBoundaryMySqlIT`, `SchedulerSessionLockMySqlIT`, `SchedulerWorkFenceTwoProcessMySqlIT`, `ScopedLeaderboardMySqlIT`, `ShopDayMerchantDataBoundaryMyBatisIT`, `ShopDayOneTicketMySqlIT`, `SignupNoticeAfterCommitPersistenceMySqlIT`, `SupportTicketReadsMySqlIT`, `SysAinpcMySqlIT`, `SysRandomMySqlIT`, `TeamEventCommandMySqlIT`, `TemplateAdoptionReceiptMyBatisIT`, `TemplateAdoptionRoundTripMySqlIT`, `TemplateClearFieldsMySqlIT`, `TemplateD89D90MigrationsMySqlIT`, `TemplateMarketLifecycleMySqlIT`, `TemplatePreferenceJsonMySqlIT`, `ThemeCharacterMapperMyBatisIT`, `ThemeCompletionRewardCapMySqlIT`, `ThemeRuntimeMySqlIT`, `ThemeStateKeyDeclarationMySqlIT`, `TopicContentBudgetMySqlIT`, `TopicContentCopyMySqlIT`, `TopicContentGraphMySqlIT`, `TopicContentRevisionMySqlIT`, `TopicDeliveryMetadataMySqlIT`, `TopicEditionReopenBlocksColumnMyBatisIT`, `TopicInsertColumnCoverageMyBatisIT`, `TopicLikeDesiredStateMySqlIT`, `TopicPublicationBindingMySqlIT`, `UmsMemberRankMySqlIT`, `UsageConsentRecordsMySqlIT`, `UserInvoiceLifecycleMySqlIT`, `VerificationRiskQueueMySqlIT`, `VideoBountyGroupCapMySqlIT`, `WeatherEventRuleMySqlIT`, `WechatMemberRegistrationMySqlIT`, `WechatPaymentIntentMySqlIT`, `WechatVerifiedPaymentFactMySqlIT`, `WorkshopAppleIapMyBatisIT`, `WorkshopPurchaseMyBatisIT`, `Wp04ApprovedRefundFenceMySqlIT`, `Wp04CashEvidenceExpandMySqlIT`, `Wp04CoopOrderScopeMySqlIT`, `Wp04CoopSettlementEvidenceMySqlIT`, `Wp04CurrentCashBasisMySqlIT`, `Wp04OriginalProducerMySqlIT`, `Wp04OriginalRefundMySqlIT`, `Wp04PaidHalfStateMySqlIT`, `Wp04TopicCashFenceMySqlIT`, `Wp08DeliveryJobsMySqlIT`, `WxContentCallbackFencingMySqlIT`, `WxPaymentCallbackFencingMySqlIT`

### What the VPS still has to run for this candidate

The merged branches change three MyBatis XML mappers: `AccountDeregistrationMapper.xml`, `ClubMapper.xml` and `RoamMapper.xml`. Only the real-MySQL suites can prove them. Run sections A, B and D on the test VPS, at least the club, roam, deregistration and IM ones, plus `InGameChannelMySqlIT` and `ExternalGameMonetizationMysqlIntegrationTest`. The H2-mode `AccountDeregistrationR3ErasureMyBatisTest` passed here (1/1).

## Fixed failures

None. Nothing failed that came from the merged branches. Their regression tests passed here, including `ExternalGameSecurityR3Test` (10), `InGameChannelSecurityR3Test` (6), `AccountDeregistrationR3ErasureTest` (2), `AccountDeregistrationR3ErasureMyBatisTest` (1), `RoamTerritoryClaimTargetTest` (1), `CityR3RateLimitContractTest` (1) and `E06_17Test` (5).

## Pre-existing failures

None outside the environment. The only red tests are the 34 MySQL connection errors in section A, which already errored before these merges.
