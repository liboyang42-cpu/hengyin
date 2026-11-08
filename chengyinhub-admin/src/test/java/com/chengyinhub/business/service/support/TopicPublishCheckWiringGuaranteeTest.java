package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产注入守卫:发布预检的必需检查不得因为「有单测爱手工 new」而在生产里被静默省掉。
 *
 * <p>做法是把「谁是生产装配点」写成可执行断言,而不是靠注释约定:
 * <ol>
 *   <li>{@link TopicPublishCheckService} 只能有一个 {@code @Autowired} 构造,且必须带上
 *       {@link TopicPublicationContentGuard} 与章节 mapper —— 否则预检退回改造前的覆盖面;</li>
 *   <li>发布闸里被预检依赖的字段一律是 {@code @Autowired}(required),不得出现
 *       {@code @Autowired(required=false)} 这种「缺失就跳过检查」的装配。</li>
 * </ol>
 *
 * <p>手工 new 的旧构造仍保留,但它不是 Spring 的装配点,因此不会让生产少跑检查。
 */
class TopicPublishCheckWiringGuaranteeTest {

    /** 预检在收集器里实际用到的发布闸依赖。少一个,就会静默少一类检查。 */
    private static final List<String> REQUIRED_GUARD_FIELDS = Arrays.asList(
            "contentWriteGuard", "cmsTopicChapterMapper", "cmsTopicNodeMapper", "cmsMemberTemplateMapper",
            "themeStateKeyService", "topicStateKeyRefService", "omsTicketMapper", "topicRouteRuntimeService",
            "routeOutcomeReader", "npcProfileMapper");

    @Test
    void previewOwnerHasExactlyOneAutowiredConstructorAndItCarriesThePublicationGuard() {
        List<Constructor<?>> autowired = new ArrayList<Constructor<?>>();
        for (Constructor<?> candidate : TopicPublishCheckService.class.getDeclaredConstructors()) {
            if (candidate.isAnnotationPresent(Autowired.class)) autowired.add(candidate);
        }
        assertEquals(1, autowired.size(), "生产装配点必须唯一:" + autowired);
        List<Class<?>> parameters = Arrays.asList(autowired.get(0).getParameterTypes());
        assertTrue(parameters.contains(TopicPublicationContentGuard.class),
                "生产构造必须注入发布闸,否则预检只剩改造前的覆盖面:" + parameters);
        assertTrue(parameters.contains(CmsTopicChapterMapper.class),
                "生产构造必须注入章节 mapper,否则问题无法定位到章节:" + parameters);
    }

    @Test
    void legacyTestConstructorIsNotAnAutowiredProductionSeam() {
        for (Constructor<?> candidate : TopicPublishCheckService.class.getDeclaredConstructors()) {
            if (candidate.getParameterCount() != 5) continue;
            assertNull(candidate.getAnnotation(Autowired.class),
                    "5 参构造是测试接缝,不能被 Spring 选中");
        }
    }

    @Test
    void guardDependenciesAreRequiredNotOptional() {
        for (String name : REQUIRED_GUARD_FIELDS) {
            Field field = findField(name);
            assertNotNull(field, "发布闸缺少预期依赖字段:" + name);
            Autowired annotation = field.getAnnotation(Autowired.class);
            assertNotNull(annotation, "预检依赖必须由 Spring 注入:" + name);
            assertTrue(annotation.required(), "预检依赖不得声明 required=false:" + name);
        }
    }

    private static Field findField(String name) {
        for (Field field : TopicPublicationContentGuard.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getName().equals(name)) return field;
        }
        return null;
    }
}
