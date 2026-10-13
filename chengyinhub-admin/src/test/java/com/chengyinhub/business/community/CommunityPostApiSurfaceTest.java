package com.chengyinhub.business.community;

import com.chengyinhub.web.controller.api.ApiCommunityPostController;
import com.chengyinhub.web.controller.business.CommunityPostModerationController;
import com.chengyinhub.common.annotation.Anonymous;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityPostApiSurfaceTest {
    @Test
    void playerApiExposesCompleteActionSpecificSurface() {
        RequestMapping base = ApiCommunityPostController.class.getAnnotation(RequestMapping.class);
        assertEquals("/api/v1/community", base.value()[0]);
        Set<String> routes = routes(ApiCommunityPostController.class);
        assertTrue(routes.contains("POST /posts"));
        assertTrue(routes.contains("GET /capabilities"));
        assertTrue(routes.contains("POST /media/register"));
        assertTrue(routes.contains("PATCH /posts/{id}"));
        assertTrue(routes.contains("POST /posts/{id}/publish"));
        assertTrue(routes.contains("DELETE /posts/{id}"));
        assertTrue(routes.contains("GET /posts/{id}"));
        assertTrue(routes.contains("GET /drafts"));
        assertTrue(routes.contains("GET /me/posts"));
        assertTrue(routes.contains("GET /me/bookmarks"));
        for (String method : new String[]{"myPosts", "myBookmarks"}) {
            try {
                assertEquals(false, ApiCommunityPostController.class.getDeclaredMethod(method, Long.class, Integer.class)
                        .isAnnotationPresent(Anonymous.class));
            } catch (NoSuchMethodException e) { throw new AssertionError(e); }
        }
        assertTrue(routes.contains("GET /posts/{id}/revisions"));
        assertTrue(routes.contains("GET /feeds/{mode}"));
        assertTrue(routes.contains("POST /posts/{id}/actions"));
        assertTrue(routes.contains("DELETE /posts/{id}/actions/{type}"));
        assertTrue(routes.contains("POST /posts/{id}/comments"));
        assertTrue(routes.contains("GET /posts/{id}/comments"));
        assertTrue(routes.contains("POST /posts/{postId}/comments/{commentId}/approve"));
        assertTrue(routes.contains("DELETE /posts/{postId}/comments/{commentId}"));
        assertTrue(routes.contains("POST /posts/{id}/shares"));
        assertTrue(routes.contains("POST /comments/{id}/actions"));
        assertTrue(routes.contains("DELETE /comments/{id}/actions/LIKE"));
        assertTrue(routes.contains("POST /members/{memberId}/mute"));
        assertTrue(routes.contains("DELETE /members/{memberId}/mute"));
        assertTrue(routes.contains("GET /guidelines/active"));
        assertTrue(routes.contains("POST /guidelines/ack"));
        assertTrue(routes.contains("POST /reports"));
        assertTrue(routes.contains("POST /appeals"));
        assertTrue(routes.contains("GET /me/enforcements"));
        assertTrue(routes.contains("GET /notifications"));
        assertTrue(routes.contains("GET /notification-preferences"));
        assertTrue(routes.contains("PATCH /notification-preferences"));
        assertTrue(routes.contains("POST /notifications/{id}/read"));
        assertAnonymous(ApiCommunityPostController.class, "capabilities");
        assertAnonymous(ApiCommunityPostController.class, "detail", Long.class);
        assertAnonymous(ApiCommunityPostController.class, "feed", String.class, Long.class, Long.class, Integer.class,
                String.class, Long.class, String.class, String.class, String.class, Long.class);
        assertAnonymous(ApiCommunityPostController.class, "comments", Long.class, Long.class, Integer.class);
        assertAnonymous(ApiCommunityPostController.class, "guideline");
    }

    @Test
    void moderatorApiSeparatesPlatformActionsFromPlayerApi() {
        RequestMapping base = CommunityPostModerationController.class.getAnnotation(RequestMapping.class);
        assertEquals("/business/community/posts", base.value()[0]);
        Set<String> routes = routes(CommunityPostModerationController.class);
        assertTrue(routes.contains("POST /{id}/enforcements"));
        assertTrue(routes.contains("POST /{id}/featured"));
        assertTrue(routes.contains("POST /enforcements/{id}/appeals/review"));
        assertTrue(routes.contains("GET /{id}/enforcements"));
        assertTrue(routes.contains("GET /appeals"));
    }

    private Set<String> routes(Class<?> type) {
        Set<String> result = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class))
                result.add("GET " + method.getAnnotation(GetMapping.class).value()[0]);
            if (method.isAnnotationPresent(PostMapping.class))
                result.add("POST " + method.getAnnotation(PostMapping.class).value()[0]);
            if (method.isAnnotationPresent(PatchMapping.class))
                result.add("PATCH " + method.getAnnotation(PatchMapping.class).value()[0]);
            if (method.isAnnotationPresent(DeleteMapping.class))
                result.add("DELETE " + method.getAnnotation(DeleteMapping.class).value()[0]);
        }
        return result;
    }

    private void assertAnonymous(Class<?> type, String method, Class<?>... parameters) {
        try {
            assertTrue(type.getDeclaredMethod(method, parameters).isAnnotationPresent(Anonymous.class),
                    method + " 应允许游客读取公开投影");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
