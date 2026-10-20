package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;

import java.util.List;
import java.util.Map;

/** 广场帖文深模块：控制器只表达命令，事务、审核、可见性与审计由此统一收口。 */
public interface ICommunityPostService {
    Map<String, Object> capabilities();
    Map<String, Object> registerMedia(Long memberId, CommunityPostRequests.MediaRegister request);
    Map<String, Object> createDraft(Long memberId, CommunityPostRequests.Upsert request);
    Map<String, Object> edit(Long memberId, Long postId, CommunityPostRequests.Upsert request);
    Map<String, Object> publish(Long memberId, String openid, Long postId, CommunityPostRequests.State request);
    void delete(Long memberId, Long postId, CommunityPostRequests.State request);
    void withdrawLocation(Long memberId, Long postId, CommunityPostRequests.State request);
    void copyrightRemove(Long operatorUserId, Long postId, CommunityPostRequests.State request);
    Map<String, Object> detail(Long viewerId, Long postId);

    /** Existing audit-read permission must be checked by the caller. Never substitutes newer content. */
    Map<String, Object> auditMediaPreview(com.chengyinhub.business.domain.AuditTask task);
    String auditSnapshotForRead(String snapshotJson);
    List<Map<String, Object>> revisions(Long viewerId, Long postId);
    List<Map<String, Object>> filterPublicCompletedReferences(Long memberId, List<Map<String, Object>> completed);
    List<Map<String, Object>> referenceOptions(Long memberId, String referenceType, Integer limit);
    Map<String, Object> feed(Long viewerId, CommunityPostFeedQuery query);
    Map<String, Object> drafts(Long memberId, Long cursor, Integer limit);
    Map<String, Object> bookmarks(Long memberId, Long cursor, Integer limit);
    Map<String, Object> ownPosts(Long memberId, Long cursor, Integer limit);
    Map<String, Object> act(Long memberId, Long postId, CommunityPostRequests.Action request);
    Map<String, Object> unact(Long memberId, Long postId, String actionType, String requestId);
    Map<String, Object> comment(Long memberId, Long postId, CommunityPostRequests.Comment request);
    Map<String, Object> comments(Long viewerId, Long postId, Long cursor, Integer limit);
    void approveComment(Long memberId, Long postId, Long commentId, CommunityPostRequests.State request);
    void deleteComment(Long memberId, Long postId, Long commentId, CommunityPostRequests.State request);
    void recordShare(Long memberId, Long postId, String requestId);
    void likeComment(Long memberId, Long commentId, String requestId);
    void unlikeComment(Long memberId, Long commentId, String requestId);
    Map<String, Object> activeGuideline();
    void acknowledgeGuideline(Long memberId, CommunityPostRequests.GuidelineAck request);
    void mute(Long memberId, Long mutedMemberId, String requestId);
    void unmute(Long memberId, Long mutedMemberId, String requestId);
    void notifyFollow(Long followedMemberId, Long actorMemberId);
    Object report(Long memberId, CommunityReportSubmitRequest request);
    void appeal(Long memberId, CommunityPostRequests.Appeal request);
    Map<String, Object> memberEnforcements(Long memberId, Long cursor, Integer limit);
    Map<String, Object> notifications(Long memberId, Long cursor, Integer limit);
    Map<String, Object> notificationPreferences(Long memberId);
    Map<String, Object> updateNotificationPreferences(Long memberId,
                                                       CommunityPostRequests.NotificationPreferences request);
    void readNotification(Long memberId, Long notificationId, String requestId);
    void feature(Long operatorUserId, Long postId, CommunityPostRequests.Featured request);
    void enforce(Long operatorUserId, Long postId, CommunityPostRequests.Enforcement request);
    /** 评论下架处置(仅 REMOVE),返回处置 id。 */
    Long enforceComment(Long operatorUserId, Long commentId, CommunityPostRequests.Enforcement request);
    /** 举报单裁定违规后落处置并回指举报单(targetType = CONTENT / COMMENT),返回处置 id。 */
    Long enforceReportedTarget(Long operatorUserId, Long reportCaseId, String targetType, Long targetId,
                               CommunityPostRequests.Enforcement request);
    void reviewAppeal(Long operatorUserId, Long appealId, CommunityPostRequests.AppealReview request);
    /** 审核员认领/打开申诉：SUBMITTED → REVIEWING(按 expectedVersion CAS)；本人重复打开幂等。 */
    Map<String, Object> claimAppeal(Long operatorUserId, Long appealId, Integer expectedVersion);
    List<Map<String, Object>> enforcements(Long postId);
    Map<String, Object> appeals(String status, Long cursor, Integer limit);
    void applyAuditDecision(Long postId, boolean approved);
    void applyCommentAuditDecision(Long commentId, boolean approved);
    /** 帖文图片异步机审命中风险:按生命周期 HIDE(隐藏待复核)并建人工复核单;已隐藏/待审/已终态视为已落地。 */
    boolean applyRiskyMediaResult(Long postId);
    /** 帖文当前 ACTIVE 媒体的 OSS 对象 key(稳定身份,不是会过期的预签名 URL),供图片回调核对「仍是当初送检的那张」。 */
    List<String> activeMediaKeys(Long postId);
    Map<String, Integer> runMaintenance(Integer limit);
}
