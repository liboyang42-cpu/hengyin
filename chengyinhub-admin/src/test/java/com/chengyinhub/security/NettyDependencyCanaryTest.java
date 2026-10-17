package com.chengyinhub.security;

import io.lettuce.core.resource.DefaultClientResources;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Version;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NettyDependencyCanaryTest {
    @Test
    @SuppressWarnings("unchecked")
    void lettuceDefaultResourcesCanAllocateAndReleaseEventLoop() throws Exception {
        DefaultClientResources resources = DefaultClientResources.builder()
                .ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        try {
            Class<? extends EventLoopGroup> type = (Class<? extends EventLoopGroup>)
                    Class.forName("io.netty.channel.MultiThreadIoEventLoopGroup");
            EventLoopGroup group = resources.eventLoopGroupProvider().allocate(type);
            assertEquals("lettuce-netty-canary", group.submit(() -> "lettuce-netty-canary")
                    .get(5, TimeUnit.SECONDS));
            resources.eventLoopGroupProvider().release(group, 0, 5, TimeUnit.SECONDS)
                    .get(10, TimeUnit.SECONDS);
            assertTrue(group.isTerminated());
        } finally {
            resources.shutdown(0, 5, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void nettyModulesUseBootManagedVersion() throws Exception {
        var versions = Version.identify();
        assertTrue(versions.containsKey("netty-transport"));
        versions.forEach((artifact, version) -> assertEquals("4.2.17.Final",
                version.artifactVersion(), artifact));
        assertEquals(1, Collections.list(getClass().getClassLoader().getResources(
                "io/netty/channel/MultiThreadIoEventLoopGroup.class")).size());
    }
}
