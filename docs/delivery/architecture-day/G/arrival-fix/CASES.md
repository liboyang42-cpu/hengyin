# 全部普通@Test源码清单（未执行）

公共基线4cf；negative IT34→36；positive四类总57→66。原test方法体逐字保留；negative两项仅加真实owner装配。以下不是运行报告。

## ShopDayProofServiceTest.java

`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ShopDayProofServiceTest.java`

| case | 来源 |
| --- | --- |
| arrivalCreatesPendingProgressWithoutPretendingCompletion | 原case，断言/方法体不变 |
| photoRequiresArrivalAndKeepsProgressPending | 原case，断言/方法体不变 |
| photoBeforeArrivalFailsClosed | 原case，断言/方法体不变 |
| repeatedPhotoReadsBackTheStoredCounterpartInsteadOfReportingFalseFailure | 原case，断言/方法体不变 |
| differentSecondPhotoCannotOverwriteOriginalProof | 原case，断言/方法体不变 |
| arrivalAndSiblingsKeepTheOneResolvedInstanceWhenLatestChanges | 本批新增边界 |
| duplicateReadbackKeepsTheResolvedInstanceAndOriginalProof | 本批新增边界 |
| missingTopicFailsBeforeAnyArrivalLookupOrWrite | 本批新增边界 |
| anUnresolvedInstanceCannotFallBackToADefaultInsert | 本批新增边界 |

## ShopDayGameThenPhotoH2Test.java

`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ShopDayGameThenPhotoH2Test.java`

| case | 来源 |
| --- | --- |
| aPlayerWhoAnsweredTheGameCanStillSubmitTheOnsitePhoto | 原case，断言/方法体不变 |
| aSecondDifferentPhotoIsStillRefusedAfterTheGameWasAnswered | 原case，断言/方法体不变 |
| theSqlLayerAlsoRefusesToOverwriteAnExistingPhoto | 原case，断言/方法体不变 |
| replayingTheSamePhotoIsIdempotent | 原case，断言/方法体不变 |
| aPhotoWithoutArrivalIsStillRefused | 原case，断言/方法体不变 |
| answeringTheGameTwiceOnlyCountsOnceSoXpCannotBeDoubleGranted | 原case，断言/方法体不变 |
| aStoreWithoutAGameKeepsTheOriginalArriveThenPhotoPath | 原case，断言/方法体不变 |
| arrivalItselfStillStartsAtPending | 原case，断言/方法体不变 |
| repeatedArrivalUsesTheRealProgressUniqueKeyIdempotently | 原case，断言/方法体不变 |

## PlayerNodeArrivalSharingH2Test.java

`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/PlayerNodeArrivalSharingH2Test.java`

| case | 来源 |
| --- | --- |
| sameChapterSameShop_siblingNodeIsArrivedWithoutScanning | 原case，断言/方法体不变 |
| sharedArrivalIsScopedToMemberAndActivity | 原case，断言/方法体不变 |
| crossChapterSameShopDoesNotShare | 原case，断言/方法体不变 |
| differentShopInSameChapterDoesNotShare | 原case，断言/方法体不变 |
| hostNodesFallBackToAddressPlusThirtyMeters | 原case，断言/方法体不变 |
| mixedMerchantHostPairFallsBackToAddressPlusDistance | 原case，断言/方法体不变 |
| repeatedScanDoesNotDuplicateSharedRows | 原case，断言/方法体不变 |
| withdrawnMerchantNodesNeverInheritHostArrival | 原case，断言/方法体不变 |
| completingGameplayAloneNeverGrantsSiblingArrival | 原case，断言/方法体不变 |

## ThemeRuntimeMySqlIT.java

`chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ThemeRuntimeMySqlIT.java`

| case | 来源 |
| --- | --- |
| oneTemplateResourceSlotsPublishFreezeAndChargeDifferentThemes | 原case，断言/方法体不变 |
| r5OutcomeOnlyTemplateAdoptsRealTasksWithoutTheRemovedCostTool | 原case，断言/方法体不变 |
| r5ChallengeTwoActualCoinSessionsRestoreOnceWithoutCompletingTheRegularTask | 原case，断言/方法体不变 |
| r5TamperedAuthorHpWriteCannotAcquirePublishedValidationMarker | 原case，断言/方法体不变 |
| r5PersonalZeroViewUsesCurrentMemberAndDoesNotPublishWeRunReadings | 原case，断言/方法体不变 |
| r5PersonalStepsUsesVerifiedBaselineReplaysAndRollsBackFailedRestore | 原case，断言/方法体不变 |
| r5TimeoutCompletedUsesPassPendingAndRetainsFixedProofWhenBalanceFalls | 原case，断言/方法体不变 |
| r5TimedStateSettlesBothSessionsOnceWithoutOpeningAnotherAttempt | 原case，断言/方法体不变 |
| r5TimerDatabaseFailureRollsBackAdvancedCasAndOutcomeFeeTogether | 原case，断言/方法体不变 |
| r5CheckUsesRealFrozenDiceAndRerollPaysOnceWithNoLegacyDamage | 原case，断言/方法体不变 |
| r5TrustedFailureChargesOnceAndPassWaitsForConfirmation | 原case，断言/方法体不变 |
| r5ZeroFailureFreezesFailureCardFromActualFeeTransaction | 原case，断言/方法体不变 |
| r5WaitReadRestoresOnceAndPersistsTheServerDeadline | 原case，断言/方法体不变 |
| scanOnlyTaskUsesTheValidatedRunReceiptWithoutInventingAnObjectiveQuiz | 原case，断言/方法体不变 |
| gpsOnlyTaskUsesTheValidatedRunReceiptAndFrozenStationCoordinates | 原case，断言/方法体不变 |
| textOnlyTaskSettlesFromControlledFrozenAnswerProof | 原case，断言/方法体不变 |
| choiceOnlyTaskSettlesFromControlledFrozenAnswerProof | 原case，断言/方法体不变 |
| personalOperationCommitsOnlyTheCurrentProofWithCostsEffectsAndReceipt | 原case，断言/方法体不变 |
| concurrentPersonalOperationCannotUseTheSameProofTwice | 原case，断言/方法体不变 |
| personalAnyTaskCommitsCostWholePackReceiptAndTerminalTogether | 原case，断言/方法体不变 |
| personalTaskDatabaseFailureRollsBackInventoryStateAndReceipt | 原case，断言/方法体不变 |
| concurrentPersonalRequestsCannotDoubleChargeBusinessSlot | 原case，断言/方法体不变 |
| mobileChapterConfigGrantsUsesAndReloadsWithoutRepeating | 原case，断言/方法体不变 |
| migrationAddsRunToBothUniqueKeys | 原case，断言/方法体不变 |
| lastItemConcurrentUseHasOneWinnerAndNoPartialWrites | 原case，断言/方法体不变 |
| ownReplayIsFreshAndKeepsOldResult | 原case，断言/方法体不变 |
| sameRequestReturnsReceiptAndCannotChangePayload | 原case，断言/方法体不变 |
| automaticBroadcastRetriesMessageWithoutRepeatingInventory | 原case，断言/方法体不变 |
| verifiedNodeCompletionGrantsAndFreezesFinalCard | 原case，断言/方法体不变 |
| negativeItemEndsAtomicallyAndOldResultIsReadOnly | 原case，断言/方法体不变 |
| rescueLimitReusesRecoveryAndDoesNotResetInventoryOrActions | 原case，断言/方法体不变 |
| draftAndPublishedConfigRoundTripKeepExistingRunFrozen | 原case，断言/方法体不变 |
| completedRouteWithInsufficientEndingIsNotSuccessfulCompletion | 原case，断言/方法体不变 |
| legacyRankNeedsCurrentRunClaimAndDoesNotReappearOnReplay | 原case，断言/方法体不变 |
| replayArrivalMustBeReadableFromTheCurrentPlayInstance | 原negative反例，仅新增owner装配 |
| replayArrivalMustNotCollideWithTheOldDefaultArrival | 原negative反例，仅新增owner装配 |
| repeatedArrivalPreservesAllStoredColumnsAndPhotoWithinOneReplay | 本批新增边界 |
| replaySiblingArrivalSharesOneInstanceWithoutMovingOldPhotosOrStatuses | 本批新增边界 |
| anExistingSameRunSiblingKeepsItsCompletionAndPhoto | 本批新增边界 |
