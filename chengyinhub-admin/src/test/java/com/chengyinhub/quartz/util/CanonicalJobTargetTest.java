package com.chengyinhub.quartz.util;

import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.spring.SpringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalJobTargetTest {
    public static class Target {
        public void run() { }
        public void text(String value) { }
        public void number(Integer value) { }
        public void number(Long value) { }
    }
    @Test void aliasWhitespaceAndParsedValuesShareIdentityButTypesAndActualStringBytesDoNot() {
        Object old = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("task", new Target()); factory.registerAlias("task", "alias");
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", factory);
        try {
            assertEquals(CanonicalJobTarget.canonical("task.run"), CanonicalJobTarget.canonical(" alias . run() "));
            assertEquals(CanonicalJobTarget.canonical("task.number(01)"), CanonicalJobTarget.canonical("alias.number(1)"));
            assertNotEquals(CanonicalJobTarget.canonical("task.number(1)"), CanonicalJobTarget.canonical("task.number(1L)"));
            assertEquals(CanonicalJobTarget.canonical("task.text('a,b')"), CanonicalJobTarget.canonical("alias.text(\"a,b\")"));
            assertNotEquals(CanonicalJobTarget.canonical("task.text('a b')"), CanonicalJobTarget.canonical("task.text('ab')"));
            assertThrows(ServiceException.class, () -> CanonicalJobTarget.canonical("task.missing()"));
            assertThrows(ServiceException.class, () -> CanonicalJobTarget.canonical("missing.run()"));
        } finally { ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", old); }
    }
}
