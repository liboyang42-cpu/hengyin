# 141 个原用例逐项接线矩阵

这是源码清单和未来接线合同，未运行。原case/body SHA256、可见入口及反射方法在同名 JSON；直接调用清单不追踪test helper的动态调用，不冒充覆盖率。

## ChapterFlowWriteServiceTest（44）

原公开V1/V2门面入口保留；接入真实graph/guard/reader，共用原mapper mock；玩家读取投影与审核stub保持原owner。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `storyGameStoresExplicitLocationIntentAndRejectsMissingTemplateOrCoordinates` | 经原test helper；不替换其业务断言 |
| `storyGameRejectsArrivalTemplateBeforeReplacingChapters` | `updateTopicByOwnerV2` |
| `submittedV2EditFreezesAfterRebuildAndRefreshesOneTopicTask` | `updateTopicByOwnerV2` |
| `escalatedAuditTaskReadbackIsReportedAsEscalated` | `updateTopicByOwnerV2WithReview` |
| `auditTaskReadbackThatIsNotAnOpenTaskFailsTheSubmission` | `updateTopicByOwnerV2WithReview` |
| `submittedSimpleDraftPublishesWhileSavingItKeepsItPrivate` | `updateTopicByOwnerV2WithReview` |
| `openBundleReviewCannotBeBypassedBySwitchingToSimpleMode` | `updateTopicByOwnerV2` |
| `simpleV2PublishCannotBypassReviewWithAPrivateDraftTemplate` | `updateTopicByOwnerV2` |
| `nonBlockSimpleV2PublishCannotBypassReviewWithAPrivateDraftTemplate` | `updateTopicByOwnerV2` |
| `blockOrderDrivesGlobalSortAndSameChapterNodeIds` | `updateTopicByOwnerV2`, `reflect:isPrevDone`, `reflect:isPrevDone` |
| `descriptionIsOnlyTextBeforeFirstNodeAndNewlinesStayInsideTextBlocks` | `updateTopicByOwnerV2` |
| `narrativeBlocksBindToStationAndStayOutOfDescription` | `updateTopicByOwnerV2` |
| `narrativeQuestionWithoutAnswerIsRejectedAtSave` | 经原test helper；不替换其业务断言 |
| `nodeIndexAtNodesLengthHitsTheBoundsGate` | 经原test helper；不替换其业务断言 |
| `duplicateNodeIndexHitsTheUniquenessGate` | `updateTopicByOwnerV2` |
| `orphanNodeHitsTheCoverageGate` | 经原test helper；不替换其业务断言 |
| `optionalChapterCannotEnterStoryFlow` | 经原test helper；不替换其业务断言 |
| `omittedRequiredIsMaterializedAsRequired` | `updateTopicByOwnerV2` |
| `freeExploreV2MaterializesBlocks` | `updateTopicByOwnerV2` |
| `freeExploreV2RequestWithoutBlocksKeepsLegacyShape` | `updateTopicByOwnerV2` |
| `legacyPathPersistsNoBlocks` | `updateTopicByOwner` |
| `legacyPathRefusesToOverwriteMaterializedTopic` | `updateTopicByOwner` |
| `legacyEndpointRejectsV2PayloadInsteadOfSilentlyDroppingIt` | `updateTopicByOwner` |
| `blockCountLimitRejectsInsteadOfTruncating` | `updateTopicByOwnerV2` |
| `textLengthLimitRejectsInsteadOfTruncating` | `updateTopicByOwnerV2` |
| `serializedSizeLimitUsesUtf8Bytes` | `updateTopicByOwnerV2` |
| `exactPerItemLimitsRemainAccepted` | `updateTopicByOwnerV2` |
| `descriptionProjectionOverColumnLimitIsRejectedBeforeWrite` | `updateTopicByOwnerV2` |
| `descriptionProjectionAtColumnLimitRemainsAccepted` | `updateTopicByOwnerV2` |
| `schemaAndBlockGrammarAreExplicitlyValidated` | `updateTopicByOwnerV2` |
| `mediaAltTextJsonRoundTripSurvivesWriteAndPlayerProgressProjection` | `updateTopicByOwnerV2` |
| `mediaAltTextMissingNullAndEmptyKeepExistingMediaCompatible` | `updateTopicByOwnerV2` |
| `mediaAltTextAcceptsLimitAndRejectsOversizeBeforeWrites` | `updateTopicByOwnerV2` |
| `mediaBlocksAreMaterializedWithUrl` | `updateTopicByOwnerV2` |
| `audioStaysAValidBlockType` | `updateTopicByOwnerV2` |
| `chapterAudioUrlIsCarriedOntoTheChapterEntity` | `updateTopicByOwnerV2` |
| `mediaBlocksNeitherProjectIntoDescriptionNorCloseTheProjectionWindow` | `updateTopicByOwnerV2` |
| `mediaBlockFieldShapeIsGated` | `updateTopicByOwnerV2` |
| `blockIdentifiersSurviveV2SaveReadAndReordering` | `updateTopicByOwnerV2` |
| `duplicateBlockIdentifiersCannotAmbiguouslyResume` | 经原test helper；不替换其业务断言 |
| `openingIsSavedSeparatelyAndExistingStorySurvivesAnEditorThatOmitsIt` | `updateTopicByOwnerV2` |
| `openingCannotBeARegularStationOrAppearAfterTheFirstChapter` | 经原test helper；不替换其业务断言 |
| `existingOpeningSurvivesOmittedFlagAndCanBeExplicitlyRemoved` | `updateTopicByOwnerV2` |
| `topicAddressComesFromFirstRealStationAfterTheOpening` | `updateTopicByOwnerV2` |

## ChapterFlowTransactionTest（2）

保留原Spring门面事务代理/H2测试；新增真实graph代理使用同一manager和原JDBC mapper adapter；不能升级称为MySQL证据。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `blocksWriteFailureRollsBackDeletedAndInsertedFlowRows` | `updateTopicByOwnerV2` |
| `auditTaskFailureRollsBackTheCompletedV2Rebuild` | `updateTopicByOwnerV2` |

## CmsTopicDraftBindingsTest（9）

保留原门面与真实runtime配置算法，接真实graph/guard/reader；原copy相关接线继续使用冻结copy owner。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `createPersistsTwoBindingsUsingGeneratedIdsOnly` | 经原test helper；不替换其业务断言 |
| `v2BlockFlowUsesTheSameClientMapping` | `createTopicV2` |
| `danglingClientKeyRollsBackTopicAndNodes` | `createTopic` |
| `updateWithoutConfigKeepsRealBindingsAcrossReorder` | `updateTopicByOwner` |
| `updateCanBindANewNodeAlongsideRealNodeBindings` | `updateTopicByOwner` |
| `existingNodeCannotBeReboundUsingAClientAlias` | `updateTopicByOwner` |
| `deletingBoundNodeRejectsUnlessBindingIsExplicitlyRemoved` | `updateTopicByOwner` |
| `duplicateClientKeysRollBackAndCopyStillUsesRealIds` | `createTopic` |
| `persistedParserStillRejectsClientKeysAndOwnershipIsUnchanged` | `updateTopicByOwner` |

## TopicUpdateByOwnerTest（38）

保留owner/edit-window/商业/票务/壳的门面断言；只将图mapper依赖装配到真实graph，不stub掉重建。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `draftOwner_updatesInPlace_neverInsertsANewTopic` | `updateTopicByOwner` |
| `publishedFreeTopic_editThatMakesItCommercial_isRejected` | `insertOmsTicket`, `updateTopicByOwner` |
| `publishedFreeTopic_addingRecruitDeadlineOnly_isRejected` | `updateTopicByOwner` |
| `clubBoundTopic_editCannotOpenPublicClubPool` | `updateTopicByOwner` |
| `independentTopic_editHonorsExplicitOpenClubPool` | `updateTopicByOwner` |
| `draftEdit_doesNotFlipAuditOrOnlineState` | `updateTopicByOwner` |
| `draftSave_neverSubmitsCommercialReview` | `updateTopicByOwner` |
| `pendingReviewSave_stillSubmitsCommercialReview` | `insertOmsTicket`, `updateTopicByOwner` |
| `draftSave_neverBuildsSelfHostActivityShell` | `updateTopicByOwner` |
| `pendingSave_publishesSelfHostActivityShell` | `updateTopicByOwner` |
| `rejectedSave_republishesSelfHostActivityShell` | `updateTopicByOwner` |
| `rejectedTopic_republishesAndResubmitsAudit` | `updateTopicByOwner` |
| `draftEdit_persistsNormalizedJourneyRules` | `updateTopicByOwner` |
| `draftEdit_withoutJourneyRules_leavesColumnUntouched` | `updateTopicByOwner` |
| `draftEdit_illegalJourneyRules_rejectedBeforeAnyWrite` | `updateTopicByOwner` |
| `otherMember_cannotEdit` | `updateTopicByOwner` |
| `draftWithPastStartDate_isStillFullyEditable` | `updateTopicByOwner` |
| `startedTopic_isReadOnly` | `updateTopicByOwner` |
| `paidRegistration_blocksStructuralRebuild` | `updateTopicByOwner` |
| `approvedWhitelistRejectsUnknownOrOwnHeldMediaBeforeAnyWrite` | `updateTopicByOwner` |
| `foreignWhitelistOwnerIsRejectedBeforeMediaLookupOrWrite` | `updateTopicByOwner` |
| `approvedTopic_appliesWhitelistTextAndImagesOnly` | `insertOmsTicket`, `updateTopicByOwner` |
| `approvedTopic_nullRecruitDeadline_rejectsNewDeadlineAndChangesNothing` | `updateTopicByOwner` |
| `approvedTopic_rejectsChangedRecruitDeadlineAndChangesNothing` | `updateTopicByOwner` |
| `approvedTopic_sameRecruitDeadline_allowsWhitelistTextUpdate` | `updateTopicByOwner` |
| `approvedTopic_omittedRecruitDeadline_keepsPatchSemantics` | `updateTopicByOwner` |
| `approvedTopic_rejectsDateChange_andChangesNothing` | `updateTopicByOwner` |
| `approvedTopic_rejectsChapterStructureChange_andChangesNothing` | `updateTopicByOwner` |
| `approvedTopic_rejectsTicketPriceChange_andLeavesTicketsUntouched` | `insertOmsTicket`, `updateTopicByOwner` |
| `approvedTopic_listsEveryLockedFieldItRejects` | `updateTopicByOwner` |
| `missingId_rejected` | `updateTopicByOwner` |
| `deletedTopic_rejected` | `updateTopicByOwner` |
| `fullRebuild_clearsOldChildrenFirst` | `updateTopicByOwner` |
| `legacyEditWithoutRouteFieldsRemapsExistingBranchGraphToRebuiltNodeIds` | `updateTopicByOwner` |
| `fullRebuild_keepsBackOfficeHiddenNodeHidden_andLeavesVisibleAndNewNodesVisible` | `updateTopicByOwner` |
| `partialBody_doesNotSilentlyWipeChapters` | `updateTopicByOwner` |
| `partialBody_alsoProtectsTicketsCollaboratorsAndEggs` | `updateTopicByOwner` |
| `explicitEmptyArray_isHonoredAsDeliberateClear` | `updateTopicByOwner` |

## TopicDraftPartialSaveGateTest（19）

保留create/update/publish门面及所有完整度差异断言；publish不迁；图成功路径接真实graph。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `nameOnlyAiSimpleDraftSaves` | `createTopic` |
| `nameOnlyDraftWithNullChaptersSaves` | `createTopic` |
| `draftStillRequiresTopicName` | `createTopic` |
| `draftRejectsFilledButIllegalCoordinate` | `createTopic` |
| `draftRejectsNegativeTicketPrice` | `createTopic`, `insertOmsTicket` |
| `draftAllowsTicketWithoutTimeWindow` | `createTopic`, `insertOmsTicket` |
| `incompleteDraftPublishIsRejected` | `publishDraft` |
| `completeDraftPublishesWithTheSameId` | `publishDraft` |
| `persistedReversedSaleWindowBlocksPublication` | `publishDraft` |
| `draftDisappearingBeforeLockedPublishDoesNotBecomePublic` | `publishDraft` |
| `directPublishStillRequiresAiSimpleLocation` | `createTopic` |
| `directPublishStillRequiresRecruitDeadlineForFreeExplore` | `createTopic` |
| `directPublishStillRejectsTicketWithoutTimeWindow` | `createTopic`, `insertOmsTicket` |
| `draftUpdateSkipsPublishOnlyCompleteness` | `updateTopicByOwner` |
| `pendingReviewUpdateStillRequiresRecruitDeadline` | `updateTopicByOwner` |
| `draftUpdateRejectsBlankedName` | `updateTopicByOwner` |
| `directPublishStillBuildsSelfHostShell` | `createTopic` |
| `publishDoesNotRewriteDraftFlag` | `publishDraft` |
| `directPublishRequiresName` | `createTopic` |

## TopicDraftLegacyFieldPreservationTest（5）

保留原门面DTO保留/default语义；只增真实graph owner装配，不修改默认、null或票mode预期。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `omittedFieldsKeepExistingAuthorConfiguration` | 经原test helper；不替换其业务断言 |
| `freeExploreWithoutModeInLegacyPayloadKeepsItsProductType` | 经原test helper；不替换其业务断言 |
| `explicitConfigurationChangesStillApply` | 经原test helper；不替换其业务断言 |
| `oldTicketModeStillSelectsProductTypeWhenExplicitTypeIsAbsent` | 经原test helper；不替换其业务断言 |
| `explicitInvalidTeamValueIsStillRejectedBeforeWriting` | `updateTopicByOwner` |

## TopicJourneyStorySaveTest（4）

保留原门面story输入校验；真实graph执行chapter/node/story remap，原异常/原字段断言不变。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `draftEdit_persistsStoryAndRemapsChapterIdAfterRebuild` | `updateTopicByOwner` |
| `draftEdit_withoutStory_leavesColumnUntouched` | `updateTopicByOwner` |
| `draftEdit_illegalStory_rejectedBeforeAnyWrite` | `updateTopicByOwner` |
| `draftEdit_storyReferencingForeignChapter_isRejected` | `updateTopicByOwner` |

## TopicEditActivityShellTicketSyncChainTest（18）

保留真实原票务/活动同步链、条件SQL和库存断言；内容图仅接真实graph，其他域service/controller不迁。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `editingTopicKeepsSoldShellTicketAndRecomputesRemainingFromSoldSeats` | `updateTopicByOwner` |
| `editingTopicWithoutTouchingTicketsKeepsBothRowsInPlace` | `updateTopicByOwner` |
| `droppingASoldTicketFromTheEditFailsClosedWithoutDestroyingIt` | `updateTopicByOwner` |
| `droppingAnUnsoldTicketDeletesItsShellRowAtomically` | `updateTopicByOwner` |
| `topicTicketIdentityIsNeverBorrowedFromAnotherTopic` | `updateTopicByOwner` |
| `repricingASoldSessionIsRefusedRatherThanQuietlyRebuiltUnderTheSoldSeats` | `updateTopicByOwner` |
| `twoIdenticalSessionsStayEditableInsteadOfBrickingTheTopic` | `updateTopicByOwner` |
| `concurrentlyChangedTopicTicketFailsClosedInsteadOfSilentlyInsertingASecondOne` | `updateTopicByOwner` |
| `failedConditionalOrphanDeleteFailsClosed` | `updateTopicByOwner` |
| `shellTicketPointingAtAnotherTopicsTicketIsTreatedAsAnOrphanNotAsAMatch` | `updateTopicByOwner` |
| `anotherActivitysShellTicketIsNotTouchedByThisTopicSync` | `insertOmsTicket`, `updateTopicByOwner` |
| `adminAddingATicketOnAFreeExploreShellIsRefusedInsteadOfMintingAnUnkeyedOne` | `insertOmsTicket`, `updateTopicByOwner` |
| `aGenuinelyUnkeyedShellRowStillForcesUnknownInventoryAndBlocksSync` | 经原test helper；不替换其业务断言 |
| `addingATicketOnASelfHostClassicShellIsRefused` | `insertOmsTicket` |
| `selfHostClassicShellTicketMustNotBrickTheNextTopicEdit` | `insertOmsTicket`, `updateTopicByOwner` |
| `addingATicketOnAMerchantRunClassicActivityStaysAllowed` | `insertOmsTicket` |
| `addingATicketOnAShellOfATopicWithoutAnOwnerStaysAllowed` | `insertOmsTicket` |
| `addingATicketOnAnActivityWithoutATopicStaysAllowed` | `insertOmsTicket` |

## TopicDraftSubmittedOrderTest（2）

原门面create/edit入口，共用原chapter/node mapper；图owner实际记录顺序，不新增排序或去重。

| 原 case（全部保留） | 可见入口 / 反射目标 |
|---|---|
| `createDraftAssignsChapterAndPerChapterNodeOrderFromSubmittedArrays` | `createTopic` |
| `rebuildDraftUsesNewSubmittedOrderAndRestartsNodeOrderForEachChapter` | `updateTopicByOwner` |

