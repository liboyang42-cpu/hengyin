package com.chengyinhub.upgrade;

import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.matcher.LegacyAntRequestMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises real MVC handler metadata, without Boot auto-configuration or external services. */
class PermitAllUrlPropertiesPathPatternTest {

    private AnnotationConfigWebApplicationContext context;
    private PermitAllUrlProperties properties;

    @BeforeEach
    void createRealPathPatternHandlerMappings() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.refresh();
        properties = new PermitAllUrlProperties();
        properties.setApplicationContext(context);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void collectsMethodAndClassAnnotationsWithParsedPathPatterns() {
        RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
        assertFalse(mapping.getHandlerMethods().isEmpty());
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            assertNotNull(info.getPathPatternsCondition(), "This regression must exercise parsed PathPatterns");
            assertNull(info.getPatternsCondition(), "The old getter is null under parsed PathPatterns");
        }

        assertDoesNotThrow(properties::afterPropertiesSet);
        assertEquals(Set.of(
                "/api/upgrade/annotated/articles/*",
                "/api/upgrade/annotated/article-alias/*",
                "/api/upgrade/annotated/owners/*/articles/*",
                "/api/upgrade/annotated/slugs/*",
                "/api/upgrade/open/categories/*",
                "/api/upgrade/open/labels/*"
        ), new HashSet<>(properties.getUrls()));
        assertEquals(6, properties.getUrls().size(), "Each mapped alias must be collected exactly once here");
    }

    @Test
    void variableNormalizationKeepsSingleSegmentAndPrivateSiblingBoundaries() {
        properties.afterPropertiesSet();

        assertTrue(isAnonymous("/api/upgrade/annotated/articles/42"));
        assertTrue(isAnonymous("/api/upgrade/annotated/article-alias/42"));
        assertTrue(isAnonymous("/api/upgrade/annotated/owners/7/articles/42"));
        assertTrue(isAnonymous("/api/upgrade/annotated/slugs/hello-world"));
        assertTrue(isAnonymous("/api/upgrade/open/categories/featured"));
        assertTrue(isAnonymous("/api/upgrade/open/labels/9"));

        assertFalse(isAnonymous("/api/upgrade/annotated/private/42"));
        assertFalse(isAnonymous("/api/upgrade/private/42"));
        assertFalse(isAnonymous("/api/upgrade/annotated/articles/42/edit"));
        assertFalse(isAnonymous("/api/upgrade/annotated/owners/7/nested/articles/42"));
        assertFalse(isAnonymous("/api/upgrade/open/categories/featured/private"));
        assertFalse(isAnonymous("/api/upgrade/open/management"));
        assertFalse(isAnonymous("/api/upgrade/open/Categories/featured"));
        assertFalse(properties.getUrls().contains("/**"));
        assertFalse(properties.getUrls().contains("/api/upgrade/open/**"));
    }

    @Test
    void selectsMvcMappingWhenActuatorAddsAnotherMappingOfTheSameType() throws Exception {
        RequestMappingHandlerMapping managementMapping = new RequestMappingHandlerMapping();
        managementMapping.registerMapping(RequestMappingInfo.paths("/actuator/private-trap").build(),
                new MethodAnonymousController(), MethodAnonymousController.class.getDeclaredMethod("article"));
        context.getBeanFactory().registerSingleton("controllerEndpointHandlerMapping", managementMapping);
        assertThrows(NoUniqueBeanDefinitionException.class,
                () -> context.getBean(RequestMappingHandlerMapping.class));

        assertDoesNotThrow(properties::afterPropertiesSet);
        assertEquals(6, properties.getUrls().size());
        assertTrue(isAnonymous("/api/upgrade/annotated/articles/42"));
        assertFalse(isAnonymous("/actuator/private-trap"));
        assertFalse(isAnonymous("/api/upgrade/private/42"));
    }

    @Test
    void missingMvcMappingFailsInsteadOfFallingBackToManagementMapping() {
        try (GenericApplicationContext onlyManagement = new GenericApplicationContext()) {
            onlyManagement.getBeanFactory().registerSingleton("controllerEndpointHandlerMapping",
                    new RequestMappingHandlerMapping());
            onlyManagement.refresh();
            properties.setApplicationContext(onlyManagement);
            assertThrows(NoSuchBeanDefinitionException.class, properties::afterPropertiesSet);
            assertTrue(properties.getUrls().isEmpty());
        }
    }

    private boolean isAnonymous(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return properties.getUrls().stream()
                .anyMatch(pattern -> new LegacyAntRequestMatcher(pattern, null).matches(request));
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfiguration {
        @Bean
        MethodAnonymousController methodAnonymousController() {
            return new MethodAnonymousController();
        }

        @Bean
        ClassAnonymousController classAnonymousController() {
            return new ClassAnonymousController();
        }

        @Bean
        PrivateController privateController() {
            return new PrivateController();
        }
    }

    @RestController
    @RequestMapping("/api/upgrade/annotated")
    static class MethodAnonymousController {
        @Anonymous
        @GetMapping({"/articles/{articleId}", "/article-alias/{articleId}"})
        String article() {
            return "public";
        }

        @Anonymous
        @PostMapping("/owners/{ownerId}/articles/{articleId}")
        String ownerArticle() {
            return "public";
        }

        @Anonymous
        @GetMapping("/slugs/{slug:[a-z-]+}")
        String slug() {
            return "public";
        }

        @GetMapping("/private/{id}")
        String privateSibling() {
            return "private";
        }
    }

    @Anonymous
    @RestController
    @RequestMapping("/api/upgrade/open")
    static class ClassAnonymousController {
        @GetMapping("/categories/{category}")
        String category() {
            return "public";
        }

        @PostMapping("/labels/{labelId}")
        String label() {
            return "public";
        }
    }

    @RestController
    static class PrivateController {
        @GetMapping("/api/upgrade/private/{id}")
        String privateRoute() {
            return "private";
        }
    }
}
