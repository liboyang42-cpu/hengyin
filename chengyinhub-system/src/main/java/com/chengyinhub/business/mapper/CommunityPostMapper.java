package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface CommunityPostMapper {
    String selectMemberWxOpenId(@Param("memberId") Long memberId);
    Map<String, Object> selectMediaByOwnerRequest(@Param("ownerMemberId") Long ownerMemberId,
                                                   @Param("uploadRequestId") String uploadRequestId);
    Map<String, Object> selectMediaByReceiptHash(String uploadReceiptHash);
    int insertMedia(@Param("ownerMemberId") Long ownerMemberId, @Param("uploadRequestId") String uploadRequestId,
                    @Param("mediaType") String mediaType, @Param("objectKey") String objectKey,
                    @Param("mimeType") String mimeType, @Param("byteSize") Long byteSize,
                    @Param("width") Integer width, @Param("height") Integer height,
                    @Param("durationMs") Integer durationMs, @Param("altText") String altText,
                    @Param("contentHash") String contentHash, @Param("uploadReceiptHash") String uploadReceiptHash,
                    @Param("now") Date now);
    CommunityPost selectById(@Param("id") Long id, @Param("viewerId") Long viewerId);
    CommunityPost selectByIdForUpdate(Long id);
    CommunityPost selectByAuthorRequest(@Param("authorId") Long authorId,
                                        @Param("clientRequestId") String clientRequestId);
    int countRecentPosts(@Param("authorId") Long authorId, @Param("since") Date since);
    int countActiveMemberEnforcement(@Param("memberId") Long memberId,
                                     @Param("types") List<String> types, @Param("now") Date now);
    List<CommunityPost> selectFeed(CommunityPostFeedQuery query);
    List<CommunityPost> selectBookmarkedFeed(CommunityPostFeedQuery query);
    List<CommunityPost> selectOwnPosts(CommunityPostFeedQuery query);
    List<String> selectTopicCodes(@Param("postId") Long postId);
    List<CommunityPost> selectAuthorDrafts(@Param("authorId") Long authorId,
                                           @Param("cursor") Long cursor, @Param("limit") int limit);
    int insertPost(CommunityPost post);
    int updateContentCas(@Param("post") CommunityPost post,
                         @Param("expectedVersion") int expectedVersion,
                         @Param("targetLifecycle") String targetLifecycle,
                         @Param("now") Date now);
    int transitionCas(@Param("id") Long id,
                      @Param("expectedLifecycle") String expectedLifecycle,
                      @Param("targetLifecycle") String targetLifecycle,
                      @Param("expectedVersion") int expectedVersion,
                      @Param("publishedAt") Date publishedAt,
                      @Param("deletedAt") Date deletedAt,
                      @Param("recommendationEligible") Integer recommendationEligible,
                      @Param("searchEligible") Integer searchEligible,
                      @Param("interactionEnabled") Integer interactionEnabled,
                      @Param("now") Date now);
    int insertRevision(@Param("postId") Long postId,
                       @Param("editorMemberId") Long editorMemberId,
                       @Param("snapshotVersion") int snapshotVersion,
                       @Param("snapshotJson") String snapshotJson,
                       @Param("editReason") String editReason,
                       @Param("reviewState") String reviewState,
                       @Param("now") Date now);
    List<Map<String, Object>> selectRevisions(@Param("postId") Long postId,
                                               @Param("viewerId") Long viewerId);
    int attachMedia(@Param("postId") Long postId,
                    @Param("ownerMemberId") Long ownerMemberId,
                    @Param("mediaId") Long mediaId,
                    @Param("sortOrder") int sortOrder,
                    @Param("now") Date now);
    int detachMedia(@Param("postId") Long postId, @Param("ownerMemberId") Long ownerMemberId,
                    @Param("retainedMediaIds") List<Long> retainedMediaIds, @Param("now") Date now);
    int orphanMedia(@Param("postId") Long postId, @Param("deleteAfter") Date deleteAfter,
                    @Param("now") Date now);
    List<Map<String, Object>> selectMedia(Long postId);
    List<Map<String, Object>> selectMediaForSafety(Long postId);
    int updateMediaReviewState(@Param("postId") Long postId, @Param("reviewState") String reviewState,
                               @Param("now") Date now);
    int deleteTopics(Long postId);
    int insertTopic(@Param("postId") Long postId, @Param("topicCode") String topicCode,
                    @Param("now") Date now);
    int deleteMentions(Long postId);
    int insertMention(@Param("postId") Long postId, @Param("memberId") Long memberId,
                      @Param("now") Date now);
    List<Long> selectMentionedMemberIds(@Param("postId") Long postId);
    int insertReference(@Param("postId") Long postId, @Param("referenceType") String referenceType,
                        @Param("referenceId") Long referenceId, @Param("snapshotJson") String snapshotJson,
                        @Param("privacySnapshot") String privacySnapshot, @Param("now") Date now);
    int countReferenceTarget(@Param("referenceType") String referenceType,
                             @Param("referenceId") Long referenceId,
                             @Param("memberId") Long memberId);
    Map<String, Object> selectReferenceSnapshot(@Param("referenceType") String referenceType,
                                                 @Param("referenceId") Long referenceId,
                                                 @Param("memberId") Long memberId);
    List<Map<String, Object>> selectReferenceOptions(@Param("referenceType") String referenceType,
                                                      @Param("memberId") Long memberId,
                                                      @Param("limit") int limit);
    int deleteReferences(Long postId);
    List<Map<String, Object>> selectReferences(Long postId);
    int insertMemberAction(@Param("postId") Long postId, @Param("memberId") Long memberId,
                           @Param("actionType") String actionType, @Param("requestId") String requestId,
                           @Param("source") String source, @Param("now") Date now);
    int countMemberAction(@Param("postId") Long postId, @Param("memberId") Long memberId,
                          @Param("actionType") String actionType);
    int deleteMemberAction(@Param("postId") Long postId, @Param("memberId") Long memberId,
                           @Param("actionType") String actionType);
    Map<String, Object> selectCommandReceipt(@Param("memberId") Long memberId,
                                              @Param("requestId") String requestId);
    int insertCommandReceipt(@Param("memberId") Long memberId, @Param("requestId") String requestId,
                             @Param("commandType") String commandType, @Param("aggregateId") Long aggregateId,
                             @Param("resultJson") String resultJson, @Param("now") Date now);
    int recomputeCounts(Long postId);
    int insertOutbox(@Param("eventId") String eventId, @Param("aggregateId") Long aggregateId,
                     @Param("eventType") String eventType, @Param("payloadJson") String payloadJson,
                     @Param("now") Date now);
    int insertTypedOutbox(@Param("eventId") String eventId, @Param("aggregateType") String aggregateType,
                          @Param("aggregateId") Long aggregateId, @Param("eventType") String eventType,
                          @Param("payloadJson") String payloadJson, @Param("now") Date now);
    List<Map<String, Object>> selectDueOutbox(@Param("now") Date now, @Param("limit") int limit);
    int claimOutbox(@Param("id") Long id, @Param("now") Date now);
    int completeOutbox(@Param("id") Long id, @Param("now") Date now);
    int retryOutbox(@Param("id") Long id, @Param("errorCode") String errorCode,
                    @Param("nextAttemptAt") Date nextAttemptAt, @Param("now") Date now);
    int upsertSearchDocument(@Param("postId") Long postId, @Param("visibilityState") String visibilityState,
                             @Param("now") Date now);
    int upsertMemberGovernanceProjection(@Param("memberId") Long memberId,
                                         @Param("eventType") String eventType,
                                         @Param("payloadJson") String payloadJson,
                                         @Param("now") Date now);
    int insertNotification(@Param("recipientMemberId") Long recipientMemberId, @Param("eventId") String eventId,
                           @Param("postId") Long postId, @Param("actorMemberId") Long actorMemberId,
                           @Param("commentId") Long commentId,
                           @Param("notificationType") String notificationType,
                           @Param("aggregateKey") String aggregateKey, @Param("payloadJson") String payloadJson,
                           @Param("now") Date now);
    int insertNotificationActor(@Param("recipientMemberId") Long recipientMemberId,
                                @Param("notificationType") String notificationType,
                                @Param("aggregateKey") String aggregateKey,
                                @Param("actorMemberId") Long actorMemberId,
                                @Param("eventId") String eventId, @Param("now") Date now);
    int recomputeNotificationActorCount(@Param("recipientMemberId") Long recipientMemberId,
                                        @Param("notificationType") String notificationType,
                                        @Param("aggregateKey") String aggregateKey,
                                        @Param("now") Date now);
    int countNotificationDeliverable(@Param("recipientMemberId") Long recipientMemberId,
                                     @Param("postId") Long postId,
                                     @Param("actorMemberId") Long actorMemberId,
                                     @Param("commentId") Long commentId);
    int countMemberNotificationDeliverable(@Param("recipientMemberId") Long recipientMemberId,
                                           @Param("actorMemberId") Long actorMemberId);
    int insertErasureJob(@Param("subjectMemberId") Long subjectMemberId, @Param("postId") Long postId,
                         @Param("triggerType") String triggerType,
                         @Param("requestId") String requestId, @Param("checklistJson") String checklistJson,
                         @Param("now") Date now);
    int prepareAccountPostsForErasure(@Param("memberId") Long memberId, @Param("now") Date now);
    int scrubAccountPostRevisions(@Param("memberId") Long memberId);
    int scrubAccountAppeals(@Param("memberId") Long memberId);
    int scrubAccountGovernanceContent(@Param("memberId") Long memberId);
    int anonymizeAccountAppeals(@Param("memberId") Long memberId);
    int scrubAccountOutbox(@Param("memberId") Long memberId);
    int anonymizeAccountPostAuthors(@Param("memberId") Long memberId,
                                    @Param("now") Date now);
    int anonymizeAccountComments(@Param("memberId") Long memberId, @Param("now") Date now);
    int clearAccountReplyTargets(@Param("memberId") Long memberId, @Param("now") Date now);
    int deleteAccountPostActions(@Param("memberId") Long memberId);
    int deleteAccountCommentActions(@Param("memberId") Long memberId);
    int deleteAccountMutes(@Param("memberId") Long memberId);
    int deleteAccountNotifications(@Param("memberId") Long memberId);
    int deleteAccountNotificationActors(@Param("memberId") Long memberId);
    int reconcileNotificationsAfterActorErasure(@Param("memberId") Long memberId, @Param("now") Date now);
    int deleteAccountNotificationPreferences(@Param("memberId") Long memberId);
    int deleteAccountGovernanceState(@Param("memberId") Long memberId);
    int deleteAccountCommandReceipts(@Param("memberId") Long memberId);
    int deleteAccountGuidelineAcks(@Param("memberId") Long memberId);
    int deleteAccountMentions(@Param("memberId") Long memberId);
    int orphanAccountMedia(@Param("memberId") Long memberId, @Param("deleteAfter") Date deleteAfter,
                           @Param("now") Date now);
    int enqueueAccountErasureJobs(@Param("memberId") Long memberId, @Param("requestPrefix") String requestPrefix,
                                  @Param("now") Date now);
    int withdrawPostLocation(@Param("postId") Long postId, @Param("authorId") Long authorId,
                             @Param("expectedVersion") int expectedVersion, @Param("now") Date now);
    List<Map<String, Object>> selectDueErasureJobs(@Param("now") Date now, @Param("limit") int limit);
    int claimErasureJob(@Param("id") Long id, @Param("now") Date now);
    Date selectNextMediaDeleteAfter(@Param("postId") Long postId);
    List<Map<String, Object>> selectDueMediaForErasure(@Param("postId") Long postId, @Param("now") Date now);
    int countLiveMediaObjectRefs(@Param("objectKey") String objectKey, @Param("excludeId") Long excludeId);
    int eraseDueMedia(@Param("postId") Long postId, @Param("now") Date now);
    int deletePostSearchDocument(@Param("postId") Long postId);
    int deletePostNotifications(@Param("postId") Long postId);
    int deletePostNotificationActors(@Param("postId") Long postId);
    int completeErasureJob(@Param("id") Long id, @Param("now") Date now);
    int retryErasureJob(@Param("id") Long id, @Param("state") String state,
                        @Param("errorCode") String errorCode, @Param("nextAttemptAt") Date nextAttemptAt,
                        @Param("now") Date now);
    Map<String, Object> selectActiveGuideline();
    int countGuidelineVersion(@Param("guidelineVersionId") Long guidelineVersionId);
    int countGuidelineRule(@Param("guidelineVersionId") Long guidelineVersionId,
                           @Param("ruleCode") String ruleCode);
    int countGuidelineAck(@Param("memberId") Long memberId, @Param("guidelineVersionId") Long guidelineVersionId);
    List<Long> selectGuidelineNoticeRecipients(@Param("guidelineVersionId") Long guidelineVersionId,
                                               @Param("aggregateKey") String aggregateKey,
                                               @Param("limit") int limit);
    int insertGuidelineAck(@Param("memberId") Long memberId, @Param("guidelineVersionId") Long guidelineVersionId,
                           @Param("scene") String scene, @Param("requestId") String requestId,
                           @Param("now") Date now);
    int insertMute(@Param("memberId") Long memberId, @Param("mutedMemberId") Long mutedMemberId,
                   @Param("requestId") String requestId, @Param("now") Date now);
    Map<String, Object> selectMuteByRequest(@Param("memberId") Long memberId, @Param("requestId") String requestId);
    int deleteMute(@Param("memberId") Long memberId, @Param("mutedMemberId") Long mutedMemberId);
    int countFollow(@Param("memberId") Long memberId, @Param("authorId") Long authorId);
    int countCommunityMember(@Param("memberId") Long memberId, @Param("communityId") Long communityId);
    int countMention(@Param("postId") Long postId, @Param("memberId") Long memberId);
    Date selectLatestCommentTime(@Param("postId") Long postId, @Param("authorId") Long authorId);
    int insertComment(@Param("postId") Long postId, @Param("authorId") Long authorId,
                      @Param("rootId") Long rootId, @Param("parentId") Long parentId,
                      @Param("repliedToMemberId") Long repliedToMemberId,
                      @Param("clientRequestId") String clientRequestId, @Param("body") String body,
                      @Param("lifecycle") String lifecycle, @Param("approvalState") String approvalState,
                      @Param("now") Date now);
    Map<String, Object> selectCommentByAuthorRequest(@Param("authorId") Long authorId,
                                                     @Param("clientRequestId") String clientRequestId);
    Map<String, Object> selectCommentByAuthorRequestForUpdate(@Param("authorId") Long authorId,
                                                              @Param("clientRequestId") String clientRequestId);
    Map<String, Object> selectCommentForReply(@Param("commentId") Long commentId,
                                              @Param("postId") Long postId,
                                              @Param("viewerId") Long viewerId);
    Map<String, Object> selectCommentForAudit(Long commentId);
    int updateCommentLifecycle(@Param("commentId") Long commentId, @Param("expectedLifecycle") String expectedLifecycle,
                               @Param("targetLifecycle") String targetLifecycle,
                               @Param("expectedVersion") Integer expectedVersion, @Param("now") Date now);
    int approveComment(@Param("postId") Long postId, @Param("commentId") Long commentId,
                       @Param("postAuthorId") Long postAuthorId, @Param("expectedVersion") int expectedVersion,
                       @Param("now") Date now);
    int deleteComment(@Param("postId") Long postId, @Param("commentId") Long commentId,
                      @Param("operatorMemberId") Long operatorMemberId, @Param("expectedVersion") int expectedVersion,
                      @Param("now") Date now);
    int incrementShareCount(@Param("postId") Long postId, @Param("now") Date now);
    int insertCommentAction(@Param("commentId") Long commentId, @Param("memberId") Long memberId,
                            @Param("requestId") String requestId, @Param("now") Date now);
    Map<String, Object> selectCommentActionByRequest(@Param("memberId") Long memberId,
                                                     @Param("requestId") String requestId);
    int deleteCommentAction(@Param("commentId") Long commentId, @Param("memberId") Long memberId);
    int recomputeCommentLikes(Long commentId);
    List<Map<String, Object>> selectComments(@Param("postId") Long postId,
                                             @Param("viewerId") Long viewerId,
                                             @Param("cursor") Long cursor,
                                             @Param("limit") int limit);
    int insertFeatured(@Param("postId") Long postId, @Param("collectionCode") String collectionCode,
                       @Param("cityCode") String cityCode, @Param("reason") String reason,
                       @Param("operatorUserId") Long operatorUserId, @Param("startsAt") Date startsAt,
                       @Param("endsAt") Date endsAt, @Param("now") Date now);
    int insertEnforcement(@Param("postId") Long postId, @Param("targetMemberId") Long targetMemberId,
                          @Param("enforcementType") String enforcementType, @Param("ruleCode") String ruleCode,
                          @Param("guidelineVersionId") Long guidelineVersionId,
                          @Param("reasonCode") String reasonCode, @Param("evidenceJson") String evidenceJson,
                          @Param("previousLifecycle") String previousLifecycle,
                          @Param("operatorUserId") Long operatorUserId, @Param("startsAt") Date startsAt,
                          @Param("endsAt") Date endsAt, @Param("requestId") String requestId,
                          @Param("now") Date now);
    Map<String, Object> selectEnforcement(Long id);
    Map<String, Object> selectEnforcementByRequest(@Param("operatorUserId") Long operatorUserId,
                                                    @Param("requestId") String requestId);
    List<Map<String, Object>> selectEnforcements(Long postId);
    List<Map<String, Object>> selectMemberEnforcements(@Param("memberId") Long memberId,
                                                        @Param("cursor") Long cursor,
                                                        @Param("limit") int limit);
    int insertAppeal(@Param("enforcementId") Long enforcementId,
                     @Param("appellantMemberId") Long appellantMemberId,
                     @Param("reason") String reason, @Param("evidenceJson") String evidenceJson,
                     @Param("requestId") String requestId, @Param("now") Date now);
    Map<String, Object> selectAppeal(Long id);
    List<Map<String, Object>> selectAppeals(@Param("status") String status,
                                            @Param("cursor") Long cursor, @Param("limit") int limit);
    int reviewAppealCas(@Param("id") Long id, @Param("decision") String decision,
                        @Param("reason") String reason, @Param("reviewerUserId") Long reviewerUserId,
                        @Param("expectedVersion") int expectedVersion, @Param("now") Date now);
    int reverseEnforcement(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
                           @Param("now") Date now);
    List<String> selectActivePostEnforcementTypes(@Param("postId") Long postId, @Param("now") Date now);
    String selectActiveEnforcementBaseline(@Param("postId") Long postId, @Param("now") Date now);
    String selectEnforcementRestoreLifecycle(@Param("postId") Long postId);
    List<Long> selectEnforcementBoundaryPostIds(@Param("now") Date now, @Param("limit") int limit);
    int activateDueEnforcements(@Param("postId") Long postId, @Param("now") Date now);
    int expireDueEnforcements(@Param("postId") Long postId, @Param("now") Date now);
    int reconcilePostEnforcementCas(@Param("postId") Long postId, @Param("expectedVersion") int expectedVersion,
                                    @Param("lifecycle") String lifecycle,
                                    @Param("recommendationEligible") int recommendationEligible,
                                    @Param("searchEligible") int searchEligible,
                                    @Param("interactionEnabled") int interactionEnabled,
                                    @Param("now") Date now);
    List<Map<String, Object>> selectNotifications(@Param("memberId") Long memberId,
                                                  @Param("cursor") Long cursor,
                                                  @Param("limit") int limit);
    Map<String, Object> selectNotificationPreferences(@Param("memberId") Long memberId);
    int upsertNotificationPreferences(@Param("memberId") Long memberId,
                                      @Param("interactionEnabled") boolean interactionEnabled,
                                      @Param("mentionEnabled") boolean mentionEnabled,
                                      @Param("socialEnabled") boolean socialEnabled,
                                      @Param("now") Date now);
    int markNotificationRead(@Param("id") Long id, @Param("memberId") Long memberId,
                             @Param("now") Date now);
}
