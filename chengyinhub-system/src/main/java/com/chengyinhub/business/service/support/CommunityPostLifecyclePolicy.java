package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Component;

/** 帖文状态、受众和互动资格的单一决策入口。 */
@Component
public class CommunityPostLifecyclePolicy {
    public static final String ACTION_EDIT = "EDIT";
    public static final String ACTION_PUBLISH = "PUBLISH";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_LIMIT = "LIMIT";
    public static final String ACTION_HIDE = "HIDE";
    public static final String ACTION_REMOVE = "REMOVE";
    public static final String ACTION_RESTORE = "RESTORE";

    public Decision decide(CommunityPost post, String action, int expectedVersion, boolean owner) {
        if (post == null || post.getLifecycle() == null) throw error("post not found", 404);
        int actual = post.getVersion() == null ? 0 : post.getVersion();
        if (actual != expectedVersion) throw error("帖文已被修改，请刷新后重试", 409);
        if ((ACTION_EDIT.equals(action) || ACTION_PUBLISH.equals(action) || ACTION_DELETE.equals(action)) && !owner) {
            throw error("无权操作该帖文", 403);
        }
        String current = post.getLifecycle();
        String target;
        if (ACTION_EDIT.equals(action)) {
            requireOneOf(current, CommunityPost.LIFECYCLE_DRAFT, CommunityPost.LIFECYCLE_CHECKING,
                    CommunityPost.LIFECYCLE_PUBLISHED, CommunityPost.LIFECYCLE_LIMITED, CommunityPost.LIFECYCLE_HIDDEN);
            target = CommunityPost.LIFECYCLE_DRAFT.equals(current)
                    ? CommunityPost.LIFECYCLE_DRAFT : CommunityPost.LIFECYCLE_CHECKING;
        } else if (ACTION_PUBLISH.equals(action)) {
            requireOneOf(current, CommunityPost.LIFECYCLE_DRAFT);
            target = CommunityPost.LIFECYCLE_CHECKING;
        } else if (ACTION_DELETE.equals(action)) {
            requireOneOf(current, CommunityPost.LIFECYCLE_DRAFT, CommunityPost.LIFECYCLE_CHECKING,
                    CommunityPost.LIFECYCLE_PUBLISHED, CommunityPost.LIFECYCLE_LIMITED,
                    CommunityPost.LIFECYCLE_HIDDEN);
            target = CommunityPost.LIFECYCLE_DELETED;
        } else if (ACTION_APPROVE.equals(action)) {
            requireOneOf(current, CommunityPost.LIFECYCLE_CHECKING, CommunityPost.LIFECYCLE_HIDDEN);
            target = CommunityPost.LIFECYCLE_PUBLISHED;
        } else if (ACTION_LIMIT.equals(action)) {
            requireOneOf(current, CommunityPost.LIFECYCLE_PUBLISHED);
            target = CommunityPost.LIFECYCLE_LIMITED;
        } else if (ACTION_HIDE.equals(action)) {
            requireOneOf(current, CommunityPost.LIFECYCLE_PUBLISHED, CommunityPost.LIFECYCLE_LIMITED);
            target = CommunityPost.LIFECYCLE_HIDDEN;
        } else if (ACTION_REMOVE.equals(action)) {
            requireOneOf(current, CommunityPost.LIFECYCLE_CHECKING, CommunityPost.LIFECYCLE_PUBLISHED,
                    CommunityPost.LIFECYCLE_LIMITED, CommunityPost.LIFECYCLE_HIDDEN);
            target = CommunityPost.LIFECYCLE_REMOVED;
        } else if (ACTION_RESTORE.equals(action)) {
            requireOneOf(current, CommunityPost.LIFECYCLE_LIMITED, CommunityPost.LIFECYCLE_HIDDEN,
                    CommunityPost.LIFECYCLE_REMOVED);
            target = CommunityPost.LIFECYCLE_PUBLISHED;
        } else {
            throw error("未知帖文动作", 400);
        }
        return new Decision(target, actual + 1, CommunityPost.LIFECYCLE_PUBLISHED.equals(target));
    }

    public boolean canView(CommunityPost post, Long viewerId, boolean followsAuthor,
                           boolean communityMember, boolean blocked, boolean muted) {
        if (post == null || post.getAuthorId() == null) return false;
        boolean owner = post.getAuthorId().equals(viewerId);
        if (owner) return !CommunityPost.LIFECYCLE_DELETED.equals(post.getLifecycle());
        if (!CommunityPost.LIFECYCLE_PUBLISHED.equals(post.getLifecycle()) || blocked || muted) return false;
        if (CommunityPost.AUDIENCE_PUBLIC.equals(post.getAudience())) return true;
        if (CommunityPost.AUDIENCE_FOLLOWERS.equals(post.getAudience())) return followsAuthor;
        if (CommunityPost.AUDIENCE_COMMUNITY.equals(post.getAudience())) return communityMember;
        return false;
    }

    public boolean canComment(CommunityPost post, Long viewerId, boolean followsAuthor,
                              boolean communityMember, boolean mentioned) {
        if (post == null || !Integer.valueOf(1).equals(post.getInteractionEnabled())) return false;
        if (post.getAuthorId() != null && post.getAuthorId().equals(viewerId)) return true;
        if (CommunityPost.COMMENT_EVERYONE.equals(post.getCommentPolicy())) return true;
        if (CommunityPost.COMMENT_FOLLOWERS.equals(post.getCommentPolicy())) return followsAuthor;
        if (CommunityPost.COMMENT_MEMBERS.equals(post.getCommentPolicy())) return communityMember;
        if (CommunityPost.COMMENT_MENTIONED.equals(post.getCommentPolicy())) return mentioned;
        return false;
    }

    private void requireOneOf(String current, String... allowed) {
        for (String value : allowed) if (value.equals(current)) return;
        throw error("当前状态不允许该操作", 409);
    }

    private ServiceException error(String message, int code) {
        return new ServiceException(message, code);
    }

    public static final class Decision {
        private final String lifecycle;
        private final int version;
        private final boolean publiclyVisible;

        public Decision(String lifecycle, int version, boolean publiclyVisible) {
            this.lifecycle = lifecycle;
            this.version = version;
            this.publiclyVisible = publiclyVisible;
        }

        public String getLifecycle() { return lifecycle; }
        public int getVersion() { return version; }
        public boolean isPubliclyVisible() { return publiclyVisible; }
    }
}
