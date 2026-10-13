package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.dto.ChapterServiceCapacityDTO;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 章节 CRUD 权限必须经过 Spring Method Security 代理，不能只靠注解文本断言。 */
class CmsTopicChapterAuthorizationTest
{
    private AnnotationConfigApplicationContext context;
    private ICmsTopicChapterService chapterService;

    @BeforeEach
    void setUp()
    {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        chapterService = context.getBean(ICmsTopicChapterService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void chapterQueryPermissionIsRequiredBeforeServiceRead()
    {
        loginWith("business:topic:query");
        CmsTopicChapterController controller = context.getBean(CmsTopicChapterController.class);

        assertThrows(AccessDeniedException.class, () -> controller.getInfo(8L));

        verify(chapterService, never()).selectCmsTopicChapterById(8L);
    }

    @Test
    void chapterQueryPermissionTraversesSecurityProxyAndReadsDetail()
    {
        CmsTopicChapter chapter = chapter(8L, 17L);
        when(chapterService.selectCmsTopicChapterById(8L)).thenReturn(chapter);
        loginWith("business:topicchapter:query");
        CmsTopicChapterController controller = context.getBean(CmsTopicChapterController.class);

        assertEquals(200, controller.getInfo(8L).get("code"));

        verify(chapterService).selectCmsTopicChapterById(8L);
    }

    @Test
    void mutationPermissionsAreIndependentlyEnforcedBySecurityProxy()
    {
        CmsTopicChapter chapter = chapter(8L, 17L);
        CmsTopicChapterController controller = context.getBean(CmsTopicChapterController.class);

        loginWith("business:topicchapter:query");
        assertThrows(AccessDeniedException.class, () -> controller.add(chapter));
        assertThrows(AccessDeniedException.class, () -> controller.edit(chapter));
        assertThrows(AccessDeniedException.class, () -> controller.remove(new Long[]{8L}));

        verify(chapterService, never()).insertCmsTopicChapter(any());
        verify(chapterService, never()).updateCmsTopicChapter(any());
        verify(chapterService, never()).deleteCmsTopicChapterByIds(any());
    }

    @Test
    void capacityEndpointsReuseChapterQueryAndEditPermissions()
    {
        CmsTopicChapter chapter = chapter(8L, 17L);
        when(chapterService.selectCmsTopicChapterById(8L)).thenReturn(chapter);
        CmsTopicChapterController controller = context.getBean(CmsTopicChapterController.class);
        ChapterServiceCapacityDTO capacity = new ChapterServiceCapacityDTO();

        loginWith("business:topicchapter:edit");
        assertThrows(AccessDeniedException.class, () -> controller.getServiceCapacity(8L));

        loginWith("business:topicchapter:query");
        assertThrows(AccessDeniedException.class, () -> controller.configureServiceCapacity(8L, capacity));
        verify(chapterService, never()).configureServiceCapacity(8L, capacity);
    }

    @Test
    void capacityEndpointsRejectOutOfScopeChapterBeforeReadOrWrite()
    {
        CmsTopicChapter chapter = chapter(9L, 99L);
        when(chapterService.selectCmsTopicChapterById(9L)).thenReturn(chapter);
        CmsTopicChapterController controller = context.getBean(CmsTopicChapterController.class);
        ChapterServiceCapacityDTO capacity = new ChapterServiceCapacityDTO();

        loginWith("business:topicchapter:query");
        assertEquals(500, controller.getServiceCapacity(9L).get("code"));
        verify(chapterService, never()).getServiceCapacity(9L);

        loginWith("business:topicchapter:edit");
        assertEquals(500, controller.configureServiceCapacity(9L, capacity).get("code"));
        verify(chapterService, never()).configureServiceCapacity(9L, capacity);
    }

    private static CmsTopicChapter chapter(Long id, Long topicId)
    {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(id);
        chapter.setTopicId(topicId);
        return chapter;
    }

    private static void loginWith(String permission)
    {
        SysUser user = new SysUser();
        user.setUserName("chapter-security-test");
        user.setPassword("ignored");
        LoginUser loginUser = new LoginUser(99L, 1L, user,
                new HashSet<>(Collections.singleton(permission)));
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        SecurityContextHolder.setContext(securityContext);
    }

    @Configuration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig
    {
        @Bean("ss")
        PermissionService permissionService()
        {
            return new PermissionService();
        }

        @Bean
        ICmsTopicChapterService chapterService()
        {
            return mock(ICmsTopicChapterService.class);
        }

        @Bean
        ITopicOperationsScopeAuthorizer scopeAuthorizer()
        {
            ITopicOperationsScopeAuthorizer authorizer = mock(ITopicOperationsScopeAuthorizer.class);
            when(authorizer.canViewTopic(17L)).thenReturn(true);
            return authorizer;
        }

        @Bean
        CmsTopicChapterController chapterController(ICmsTopicChapterService chapterService,
                                                     ITopicOperationsScopeAuthorizer scopeAuthorizer)
        {
            CmsTopicChapterController controller = new CmsTopicChapterController();
            ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);
            ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
            return controller;
        }
    }
}
