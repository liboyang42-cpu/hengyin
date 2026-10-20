package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 链路验收内容安全桩:只桩两次出站调用,入口闸与回调路径全部继承真实实现。 */
class E2eWxContentSecurityStubContractTest {
    @TempDir static Path scratch;
    private static Class<?> stubClass;

    @BeforeAll
    static void compileActualFixture() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("scripts/e2e/E2eWxContentSecurityStub.java.stub"))) {
            root = root.getParent();
        }
        assertNotNull(root, "repository fixture must be present");
        Path source = scratch.resolve("E2eWxContentSecurityStub.java");
        Files.copy(root.resolve("scripts/e2e/E2eWxContentSecurityStub.java.stub"), source);
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "run on a JDK, not a JRE");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", classpath, "-d", scratch.toString(), source.toString()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {scratch.toUri().toURL()},
                E2eWxContentSecurityStubContractTest.class.getClassLoader())) {
            stubClass = loader.loadClass("com.chengyinhub.e2e.E2eWxContentSecurityStub");
        }
    }

    private IWxContentSecurityService stub(boolean enabled) throws Exception {
        IWxContentSecurityService stub = (IWxContentSecurityService) stubClass.getDeclaredConstructor().newInstance();
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isSecCheckEnabled()).thenReturn(enabled);
        ReflectionTestUtils.setField(stub, "wxConfig", config);
        return stub;
    }

    @Test
    void extendsTheRealServiceAndOverridesOnlyTheTwoOutboundCalls() {
        assertEquals(WxContentSecurityServiceImpl.class, stubClass.getSuperclass());
        Set<String> overridden = Arrays.stream(stubClass.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(Arrays.asList("checkMediaAsync", "checkText")), overridden);
    }

    @Test
    void textPassesUnlessMarkedAndKeepsTheRealEntryGates() throws Exception {
        IWxContentSecurityService stub = stub(true);
        assertNull(stub.checkText("正常的帖文正文", "openid-e2e"));
        assertNull(stub.checkText("", "openid-e2e"));
        assertEquals("内容包含违规信息，请修改后重试", stub.checkText("含 E2E-SEC-REJECT 的正文", "openid-e2e"));
        assertEquals(IWxContentSecurityService.MSG_UNAVAILABLE, stub.checkText("正文", null));
        assertEquals(IWxContentSecurityService.MSG_UNAVAILABLE, stub.checkText("正文", ""));
        assertEquals(IWxContentSecurityService.MSG_UNAVAILABLE, stub.checkText("正文", " \t\n"));
        assertEquals(IWxContentSecurityService.MSG_UNAVAILABLE, stub(false).checkText("正文", "openid-e2e"));
    }

    @Test
    void mediaGetsAFreshTraceOnlyWhenEnabled() throws Exception {
        IWxContentSecurityService stub = stub(true);
        String first = stub.checkMediaAsync("e2e/fixture.png", "openid-e2e");
        String second = stub.checkMediaAsync("e2e/fixture.png", "openid-e2e");
        assertTrue(first.startsWith("e2e-sec-"));
        assertNotEquals(first, second);
        assertNull(stub.checkMediaAsync("", "openid-e2e"));
        assertNull(stub(false).checkMediaAsync("e2e/fixture.png", "openid-e2e"));
    }
}
