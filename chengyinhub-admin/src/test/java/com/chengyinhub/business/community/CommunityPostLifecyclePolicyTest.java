package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityPostLifecyclePolicyTest {
    private final CommunityPostLifecyclePolicy policy = new CommunityPostLifecyclePolicy();

    @Test
    void publishedEditReturnsToCheckingAndAdvancesVersion() {
        CommunityPost post = post(CommunityPost.LIFECYCLE_PUBLISHED, 4);

        CommunityPostLifecyclePolicy.Decision decision =
                policy.decide(post, CommunityPostLifecyclePolicy.ACTION_EDIT, 4, true);

        assertEquals(CommunityPost.LIFECYCLE_CHECKING, decision.getLifecycle());
        assertEquals(5, decision.getVersion());
        assertFalse(decision.isPubliclyVisible());
    }

    @Test
    void staleWriteCannotOverwriteNewerState() {
        CommunityPost post = post(CommunityPost.LIFECYCLE_PUBLISHED, 5);

        ServiceException error = assertThrows(ServiceException.class,
                () -> policy.decide(post, CommunityPostLifecyclePolicy.ACTION_DELETE, 4, true));

        assertEquals(409, error.getCode());
    }

    @Test
    void nonOwnerCannotEditOrDelete() {
        CommunityPost post = post(CommunityPost.LIFECYCLE_PUBLISHED, 1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> policy.decide(post, CommunityPostLifecyclePolicy.ACTION_EDIT, 1, false));

        assertEquals(403, error.getCode());
    }

    @Test
    void removedPostCannotBeRepublishedByAuthor() {
        CommunityPost post = post(CommunityPost.LIFECYCLE_REMOVED, 2);

        ServiceException error = assertThrows(ServiceException.class,
                () -> policy.decide(post, CommunityPostLifecyclePolicy.ACTION_PUBLISH, 2, true));

        assertEquals(409, error.getCode());
    }

    @Test
    void visibilityCombinesLifecycleAudienceAndRelationshipPolicy() {
        CommunityPost post = post(CommunityPost.LIFECYCLE_PUBLISHED, 1);
        post.setAudience(CommunityPost.AUDIENCE_FOLLOWERS);

        assertTrue(policy.canView(post, 8L, true, false, false, false));
        assertFalse(policy.canView(post, 8L, false, false, false, false));
        assertFalse(policy.canView(post, 8L, true, false, true, false));
        assertFalse(policy.canView(post, 8L, true, false, false, true));
        assertTrue(policy.canView(post, 7L, false, false, true, true));
    }

    @Test
    void authorCanDisableAllComments() {
        CommunityPost post = post(CommunityPost.LIFECYCLE_PUBLISHED, 1);
        post.setCommentPolicy(CommunityPost.COMMENT_OFF);

        assertFalse(policy.canComment(post, 8L, true, true, true));
        assertTrue(policy.canComment(post, 7L, false, false, false));
    }

    @Test
    void checkingEditRequiresOwnerAndCurrentVersionAndRemainsHiddenForReview() {
        CommunityPost post = post(CommunityPost.LIFECYCLE_CHECKING, 4);
        CommunityPostLifecyclePolicy.Decision next = policy.decide(post, "EDIT", 4, true);
        assertEquals("CHECKING", next.getLifecycle());
        assertEquals(5, next.getVersion());
        assertFalse(next.isPubliclyVisible());
        assertEquals(403, assertThrows(ServiceException.class, () -> policy.decide(post, "EDIT", 4, false)).getCode());
        assertEquals(409, assertThrows(ServiceException.class, () -> policy.decide(post, "EDIT", 3, true)).getCode());
        for (String forbidden : new String[] {"REMOVED", "DELETED"}) {
            post.setLifecycle(forbidden);
            assertEquals(409, assertThrows(ServiceException.class, () -> policy.decide(post, "EDIT", 4, true)).getCode());
        }
    }

    private CommunityPost post(String lifecycle, int version) {
        CommunityPost post = new CommunityPost();
        post.setId(11L);
        post.setAuthorId(7L);
        post.setLifecycle(lifecycle);
        post.setAudience(CommunityPost.AUDIENCE_PUBLIC);
        post.setCommentPolicy(CommunityPost.COMMENT_EVERYONE);
        post.setInteractionEnabled(1);
        post.setVersion(version);
        return post;
    }
}
