package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessAuditEventAppendOnlyContractTest {
    @Test
    void mapperExposesNoUpdateOrDeleteMethods() {
        assertTrue(Arrays.stream(BusinessAuditEventMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .noneMatch(name -> name.startsWith("update") || name.startsWith("delete")),
                "append-only mapper 不得暴露 UPDATE/DELETE");
    }
}
