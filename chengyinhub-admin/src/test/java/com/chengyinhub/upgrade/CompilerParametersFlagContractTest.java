package com.chengyinhub.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring 6.1+ 不再从调试信息推断参数名：未写 name 的 @PathVariable/@RequestParam 只能依赖 javac -parameters。
 * 项目不继承 spring-boot-starter-parent，-parameters 只来自根 pom 的 maven-compiler-plugin 配置；
 * 若该配置被合并覆盖，编译仍会通过，但这些接口在运行时 400/500。此测试直接检查编译产物（F-L1-04）。
 */
class CompilerParametersFlagContractTest {

    @Test
    void unnamedRequestBindingsHaveCompiledParameterNames() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        int unnamed = 0;
        List<String> missing = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.chengyinhub")) {
            Class<?> type = Class.forName(definition.getBeanClassName(), false, getClass().getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (!requiresCompiledName(parameter)) continue;
                    unnamed++;
                    if (!parameter.isNamePresent()) missing.add(type.getName() + "#" + method.getName());
                }
            }
        }
        assertTrue(unnamed > 0, "expected at least one unnamed @PathVariable/@RequestParam to guard");
        assertEquals(List.of(), missing, "javac -parameters missing; keep <parameters>true</parameters> in root pom");
    }

    private static boolean requiresCompiledName(Parameter parameter) {
        PathVariable path = parameter.getAnnotation(PathVariable.class);
        if (path != null) return !StringUtils.hasText(path.value()) && !StringUtils.hasText(path.name());
        RequestParam param = parameter.getAnnotation(RequestParam.class);
        return param != null && !StringUtils.hasText(param.value()) && !StringUtils.hasText(param.name());
    }
}
