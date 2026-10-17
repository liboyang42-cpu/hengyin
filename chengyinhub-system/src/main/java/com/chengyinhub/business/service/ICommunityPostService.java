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
    void reviewAppeal(Long operatorUserId, Long appealId, CommunityPostRequests.AppealReview request);
    List<Map<String, Object>> enforcements(Long postId);
    Map<String, Object> appeals(String status, Long cursor, Integer limit);
    void applyAuditDecision(Long postId, boolean approved);
    void applyCommentAuditDecision(Long commentId, boolean approved);
    Map<String, Integer> runMaintenance(Integer limit);
}
