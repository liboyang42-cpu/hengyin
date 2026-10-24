# 专属测试源码清单（未执行）

新增9 unit+11 MySQL=20预期调用；保留3 unit+4 MySQL=7，四类合计27。仅这四类均无参数/重复/动态测试，不是全Backend数量。

## CompletedRouteProgressPageReaderTest.java

`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/CompletedRouteProgressPageReaderTest.java`

| case | 分类 |
| --- | --- |
| invalidOrForeignScopesAreRejectedBeforeAnyQuery | 新增、NOT_RUN |
| emptyPageDoesNotQueryOrFallBackToAllRows | 新增、NOT_RUN |
| everyInputIncludingMissingSourceIsReturnedInOriginalOrderInOneCall | 新增、NOT_RUN |
| photoPendingSolvedAndRevealedNeverBecomeVerifiedTaskReceipts | 新增、NOT_RUN |
| exactDefaultReplayAndCurrentRemainSeparateAndUnknownVersionsStayNull | 新增、NOT_RUN |
| omittedDuplicateOrUnrequestedInputCannotBeSilentlyAccepted | 新增、NOT_RUN |
| wrongSourceMemberActivityTopicInstanceAndDeletedRowsFailClosed | 新增、NOT_RUN |
| originalQueryFailurePropagatesWithoutDefaultOrOlderSourceFallback | 新增、NOT_RUN |
| oneHundredInputsStillUseOneMapperCall | 新增、NOT_RUN |

## CompletedRouteProgressPageMySqlIT.java

`chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CompletedRouteProgressPageMySqlIT.java`

| case | 分类 |
| --- | --- |
| everyInputHasBidirectionallyEqualLegacyRowsAndCompletionObservation | 新增、NOT_RUN |
| currentUsesNewestBeforeStatusWhileExactOldRunStaysReadable | 新增、NOT_RUN |
| scopeAndInstanceDifferencesCannotBorrowAnotherSource | 新增、NOT_RUN |
| photosSolvedAndRevealedRemainDistinctWithoutInventingTaskVerification | 新增、NOT_RUN |
| completedRouteWithoutProgressDoesNotBecomeCompletedNodeFallback | 新增、NOT_RUN |
| personalAndSharedBasicArrivalsStayMemberFactsWithNoTeamCompletionClaim | 新增、NOT_RUN |
| oneAndHundredInputsEachUseOneActualMyBatisStatement | 新增、NOT_RUN |
| invalidScopeEmptyAndOversizedPagesIssueNoSql | 新增、NOT_RUN |
| repeatedReadsLeaveAllProgressSessionsAndReceiptsUnchanged | 新增、NOT_RUN |
| aPartialInputUniverseMustNotBeAdvertisedAsTheWholeCompanionCount | 新增、NOT_RUN |
| directMapperEmptyOrForeignInputsCannotBecomeUnscopedRead | 新增、NOT_RUN |

## CompletedRouteFactReaderTest.java

`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/CompletedRouteFactReaderTest.java`

| case | 分类 |
| --- | --- |
| missingOrAnonymousMemberDoesNotBecomeAnUnscopedQuery | 原case逐字保留、NOT_RUN |
| routeOnlyFactsAreDiscoveredInOneQueryWithFrozenIdentityIntact | 原case逐字保留、NOT_RUN |
| sourceDifferencePreservesActivityAndTopicInsteadOfFlatteningByTopic | 原case逐字保留、NOT_RUN |

## CompletedRouteFactMapperMySqlIT.java

`chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CompletedRouteFactMapperMySqlIT.java`

| case | 分类 |
| --- | --- |
| currentCompletedSetExcludesOldSuccessRunningEndedAndOtherMembers | 原case逐字保留、NOT_RUN |
| zeroNodeRowsStillDiscoverRouteCompletionAndPreserveFrozenVersion | 原case逐字保留、NOT_RUN |
| missingMemberNeverFallsBackToAllOwners | 原case逐字保留、NOT_RUN |
| repeatedReadLeavesSessionsAndSourceReceiptsUnchanged | 原case逐字保留、NOT_RUN |
