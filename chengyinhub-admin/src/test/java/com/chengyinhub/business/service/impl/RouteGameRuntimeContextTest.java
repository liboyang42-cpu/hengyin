package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class RouteGameRuntimeContextTest {
    @Test
    void routeFirstStartsWithCircularReferencesDisabled() throws Exception {
        start(TopicRouteRuntimeServiceImpl.class, GameSessionRuntimeServiceImpl.class);
    }

    @Test
    void gameFirstStartsWithCircularReferencesDisabled() throws Exception {
        start(GameSessionRuntimeServiceImpl.class, TopicRouteRuntimeServiceImpl.class);
    }

    private void start(Class<?> first, Class<?> second) throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            Set<Class<?>> dependencies = new LinkedHashSet<>();
            for (Class<?> service : new Class<?>[] {first, second}) {
                for (Field field : service.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Autowired.class)) dependencies.add(field.getType());
                }
                for (Constructor<?> constructor : service.getDeclaredConstructors()) {
                    if (constructor.isAnnotationPresent(Autowired.class)) {
                        java.util.Collections.addAll(dependencies, constructor.getParameterTypes());
                    }
                }
            }
            dependencies.remove(IGameSessionRuntimeService.class);
            dependencies.remove(ITopicRouteRuntimeService.class);
            dependencies.remove(ObjectProvider.class);
            for (Class<?> dependency : dependencies) registerDependency(context, dependency);
            context.register(first, second);
            context.refresh();

            ITopicRouteRuntimeService route = context.getBean(ITopicRouteRuntimeService.class);
            IGameSessionRuntimeService game = context.getBean(IGameSessionRuntimeService.class);
            assertInstanceOf(TopicRouteRuntimeServiceImpl.class, route);
            assertInstanceOf(GameSessionRuntimeServiceImpl.class, game);
            assertSame(game, ReflectionTestUtils.getField(route, "gameSessionRuntimeService"));
            Advised reverse = assertInstanceOf(Advised.class,
                    ReflectionTestUtils.getField(game, "topicRouteRuntimeService"));
            assertSame(route, reverse.getTargetSource().getTarget());
        }
    }

    private static <T> void registerDependency(AnnotationConfigApplicationContext context, Class<T> type) {
        context.getBeanFactory().registerSingleton(type.getName(), mock(type));
    }
}
