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
            // 被下架(REMOVED)的帖子作者仍可删除:删除自己的内容是数据权利,不因被处置而丧失(AUDIT-G3-1)。
            requireOneOf(current, CommunityPost.LIFECYCLE_DRAFT, CommunityPost.LIFECYCLE_CHECKING,
                    CommunityPost.LIFECYCLE_PUBLISHED, CommunityPost.LIFECYCLE_LIMITED,
                    CommunityPost.LIFECYCLE_HIDDEN, CommunityPost.LIFECYCLE_REMOVED);
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

    /**
     * 纯「访问资格」判定：只看作者本人、lifecycle、audience（公开/关注者/社群成员）以及拉黑/屏蔽作者。
     *
     * <p><b>不含</b>个人负反馈（不感兴趣 / 少看该作者 / 少看该话题）：那是信息流个性化过滤，不是访问控制。
     * SQL 片段 {@code CommunityPostMapper.xml#visibilityPolicy} = 本方法的访问规则 + 上述负反馈过滤，
     * 因此两者<b>刻意不等价</b>；凡经 Mapper 查询的读取（信息流、收藏、详情等）以 SQL 为准。
     * 当前生产代码未调用本方法（仅单测）；新调用方若需要与列表一致的结果，应走 Mapper 查询而不是本方法。</p>
     */
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
