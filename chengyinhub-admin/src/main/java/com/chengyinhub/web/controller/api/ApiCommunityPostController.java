package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "专业社区广场")
@RequestMapping("/api/v1/community")
public class ApiCommunityPostController extends BaseController {
    private final ICommunityPostService service;
    private final CommunityPostFeatureGate featureGate;

    public ApiCommunityPostController(ICommunityPostService service, CommunityPostFeatureGate featureGate) {
        this.service = service;
        this.featureGate = featureGate;
    }

    @GetMapping("/capabilities")
    @Anonymous
    public AjaxResult capabilities() { return success(service.capabilities()); }

    @PostMapping("/media/register")
    public AjaxResult registerMedia(@RequestBody(required = false) CommunityPostRequests.MediaRegister request) {
        return withWritePrincipal(id -> success(service.registerMedia(id, request)));
    }

    @PostMapping("/posts")
    public AjaxResult create(@RequestBody(required = false) CommunityPostRequests.Upsert request) {
        return withWritePrincipal(id -> success(service.createDraft(id, request)));
    }

    @PatchMapping("/posts/{id}")
    public AjaxResult edit(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.Upsert request) {
        return withWritePrincipal(memberId -> success(service.edit(memberId, id, request)));
    }

    @PostMapping("/posts/{id}/publish")
    public AjaxResult publish(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.State request) {
        return withWritePrincipal(memberId -> success(service.publish(memberId, null, id, request)));
    }

    @DeleteMapping("/posts/{id}")
    public AjaxResult delete(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.State request) {
        return withAuthenticatedPrincipal(memberId -> { service.delete(memberId, id, request); return success(); });
    }

    @DeleteMapping("/posts/{id}/location")
    public AjaxResult withdrawLocation(@PathVariable Long id,
                                       @RequestBody(required = false) CommunityPostRequests.State request) {
        return withAuthenticatedPrincipal(memberId -> { service.withdrawLocation(memberId, id, request); return success(); });
    }

    @GetMapping("/posts/{id}")
    @Anonymous
    public AjaxResult detail(@PathVariable Long id) {
        if (!featureGate.canRead(viewer())) return featureUnavailable();
        try { return success(service.detail(viewer(), id)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @GetMapping("/me/posts")
    public AjaxResult myPosts(@RequestParam(required = false) Long cursor,
                              @RequestParam(required = false) Integer limit) {
        return withReadPrincipal(memberId -> success(service.myPosts(memberId, cursor, limit)));
    }

    @GetMapping("/me/bookmarks")
    public AjaxResult myBookmarks(@RequestParam(required = false) Long cursor,
                                  @RequestParam(required = false) Integer limit) {
        return withReadPrincipal(memberId -> success(service.myBookmarks(memberId, cursor, limit)));
    }

    @GetMapping("/drafts")
    public AjaxResult drafts(@RequestParam(required = false) Long cursor,
                             @RequestParam(required = false) Integer limit) {
        return withAuthenticatedPrincipal(memberId -> success(service.drafts(memberId, cursor, limit)));
    }

    @GetMapping("/posts/{id}/revisions")
    public AjaxResult revisions(@PathVariable Long id) {
        return withAuthenticatedPrincipal(memberId -> success(service.revisions(memberId, id)));
    }

    @GetMapping("/reference-options/{type}")
    public AjaxResult referenceOptions(@PathVariable String type,
                                       @RequestParam(required = false) Integer limit) {
        return withReadPrincipal(memberId -> success(service.referenceOptions(memberId, type, limit)));
    }

    @GetMapping("/feeds/{mode}")
    @Anonymous
    public AjaxResult feed(@PathVariable String mode,
                           @RequestParam(required = false) Long cursor,
                           @RequestParam(required = false) Long cursorScore,
                           @RequestParam(required = false) Integer limit,
                           @RequestParam(required = false) String cityCode,
                           @RequestParam(required = false) Long communityId,
                           @RequestParam(required = false) String topicCode,
                           @RequestParam(required = false) String collectionCode,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Long authorId) {
        if (!featureGate.canRead(viewer())) return featureUnavailable();
        CommunityPostFeedQuery query = new CommunityPostFeedQuery();
        query.setMode(mode); query.setCursor(cursor); query.setCursorScore(cursorScore); query.setLimit(limit); query.setCityCode(cityCode);
        query.setCommunityId(communityId); query.setTopicCode(topicCode); query.setCollectionCode(collectionCode);
        query.setKeyword(keyword); query.setAuthorId(authorId);
        try { return success(service.feed(viewer(), query)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/posts/{id}/actions")
    public AjaxResult act(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.Action request) {
        return withWritePrincipal(memberId -> success(service.act(memberId, id, request)));
    }

    @DeleteMapping("/posts/{id}/actions/{type}")
    public AjaxResult unact(@PathVariable Long id, @PathVariable String type,
                            @RequestBody(required = false) CommunityPostRequests.RequestId request) {
        return withWritePrincipal(memberId -> success(service.unact(memberId, id, type,
                request == null ? null : request.getRequestId())));
    }

    @PostMapping("/posts/{id}/comments")
    public AjaxResult comment(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.Comment request) {
        return withWritePrincipal(memberId -> success(service.comment(memberId, id, request)));
    }

    @GetMapping("/posts/{id}/comments")
    @Anonymous
    public AjaxResult comments(@PathVariable Long id, @RequestParam(required = false) Long cursor,
                               @RequestParam(required = false) Integer limit) {
        if (!featureGate.canRead(viewer())) return featureUnavailable();
        try { return success(service.comments(viewer(), id, cursor, limit)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/posts/{postId}/comments/{commentId}/approve")
    public AjaxResult approveComment(@PathVariable Long postId, @PathVariable Long commentId,
                                     @RequestBody(required = false) CommunityPostRequests.State request) {
        return withWritePrincipal(id -> { service.approveComment(id, postId, commentId, request); return success(); });
    }

    @DeleteMapping("/posts/{postId}/comments/{commentId}")
    public AjaxResult deleteComment(@PathVariable Long postId, @PathVariable Long commentId,
                                    @RequestBody(required = false) CommunityPostRequests.State request) {
        return withAuthenticatedPrincipal(id -> { service.deleteComment(id, postId, commentId, request); return success(); });
    }

    @PostMapping("/posts/{id}/shares")
    public AjaxResult share(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.RequestId request) {
        return withWritePrincipal(memberId -> {
            service.recordShare(memberId, id, request == null ? null : request.getRequestId());
            return success();
        });
    }

    @PostMapping("/comments/{id}/actions")
    public AjaxResult likeComment(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.RequestId request) {
        return withWritePrincipal(memberId -> { service.likeComment(memberId, id, request == null ? null : request.getRequestId()); return success(); });
    }

    @DeleteMapping("/comments/{id}/actions/LIKE")
    public AjaxResult unlikeComment(@PathVariable Long id,
                                    @RequestBody(required = false) CommunityPostRequests.RequestId request) {
        return withWritePrincipal(memberId -> { service.unlikeComment(memberId, id,
                request == null ? null : request.getRequestId()); return success(); });
    }

    @PostMapping("/members/{memberId}/mute")
    public AjaxResult mute(@PathVariable Long memberId, @RequestBody(required = false) CommunityPostRequests.RequestId request) {
        return withAuthenticatedPrincipal(id -> { service.mute(id, memberId, request == null ? null : request.getRequestId()); return success(); });
    }

    @DeleteMapping("/members/{memberId}/mute")
    public AjaxResult unmute(@PathVariable Long memberId,
                             @RequestBody(required = false) CommunityPostRequests.RequestId request) {
        return withAuthenticatedPrincipal(id -> { service.unmute(id, memberId,
                request == null ? null : request.getRequestId()); return success(); });
    }

    @GetMapping("/guidelines/active")
    @Anonymous
    public AjaxResult guideline() {
        if (!featureGate.canRead(viewer())) return featureUnavailable();
        return success(service.activeGuideline());
    }

    @PostMapping("/guidelines/ack")
    public AjaxResult acknowledge(@RequestBody(required = false) CommunityPostRequests.GuidelineAck request) {
        return withWritePrincipal(id -> { service.acknowledgeGuideline(id, request); return success(); });
    }

    @PostMapping("/reports")
    public AjaxResult report(@RequestBody(required = false) CommunityReportSubmitRequest request) {
        return withAuthenticatedPrincipal(id -> success(service.report(id, request)));
    }

    @PostMapping("/appeals")
    public AjaxResult appeal(@RequestBody(required = false) CommunityPostRequests.Appeal request) {
        return withAuthenticatedPrincipal(id -> { service.appeal(id, request); return success(); });
    }

    @GetMapping("/me/enforcements")
    public AjaxResult memberEnforcements(@RequestParam(required = false) Long cursor,
                                         @RequestParam(required = false) Integer limit) {
        return withAuthenticatedPrincipal(id -> success(service.memberEnforcements(id, cursor, limit)));
    }

    @GetMapping("/notifications")
    public AjaxResult notifications(@RequestParam(required = false) Long cursor,
                                    @RequestParam(required = false) Integer limit) {
        return withAuthenticatedPrincipal(id -> success(service.notifications(id, cursor, limit)));
    }

    @GetMapping("/notification-preferences")
    public AjaxResult notificationPreferences() {
        return withAuthenticatedPrincipal(id -> success(service.notificationPreferences(id)));
    }

    @PatchMapping("/notification-preferences")
    public AjaxResult updateNotificationPreferences(
            @RequestBody(required = false) CommunityPostRequests.NotificationPreferences request) {
        return withAuthenticatedPrincipal(id -> success(service.updateNotificationPreferences(id, request)));
    }

    @PostMapping("/notifications/{id}/read")
    public AjaxResult readNotification(@PathVariable Long id,
                                       @RequestBody(required = false) CommunityPostRequests.RequestId request) {
        return withAuthenticatedPrincipal(memberId -> { service.readNotification(memberId, id,
                request == null ? null : request.getRequestId()); return success(); });
    }

    private AjaxResult withWritePrincipal(Handler handler) {
        Long memberId = viewer();
        if (memberId == null || memberId <= 0) return new AjaxResult(HttpStatus.UNAUTHORIZED, "请先登录");
        if (!featureGate.canWrite(memberId)) return featureUnavailable();
        try { return handler.run(memberId); } catch (ServiceException e) { return serviceError(e); }
    }

    /**
     * 删除、撤回位置、屏蔽、举报和申诉属于安全/隐私/数据权利操作。
     * 内容写闸事故关闭时这些操作必须继续可用，不能复用发布总闸。
     */
    private AjaxResult withAuthenticatedPrincipal(Handler handler) {
        Long memberId = viewer();
        if (memberId == null || memberId <= 0) return new AjaxResult(HttpStatus.UNAUTHORIZED, "请先登录");
        try { return handler.run(memberId); } catch (ServiceException e) { return serviceError(e); }
    }

    private AjaxResult withReadPrincipal(Handler handler) {
        Long memberId = viewer();
        if (memberId == null || memberId <= 0) return new AjaxResult(HttpStatus.UNAUTHORIZED, "请先登录");
        if (!featureGate.canRead(memberId)) return featureUnavailable();
        try { return handler.run(memberId); } catch (ServiceException e) { return serviceError(e); }
    }

    private AjaxResult featureUnavailable() {
        return new AjaxResult(HttpStatus.NOT_FOUND, "社区广场尚未对当前账号开放");
    }

    private Long viewer() {
        try { Long id = getAppUserId(); return id == null ? 0L : id; }
        catch (Exception e) { return 0L; }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }

    private interface Handler { AjaxResult run(Long memberId); }
}
