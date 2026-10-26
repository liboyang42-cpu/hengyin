package com.chengyinhub.business.it;

import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.framework.config.properties.DruidProperties;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.List;
import java.nio.file.Path;
import com.chengyinhub.quartz.util.CrossDomainSchedulerStage;
import com.chengyinhub.quartz.util.SchedulerFenceProcessProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Property, real datasource and child command assembly only; no web, SQL, Redis or provider proof. */
class CombinedContextConfigurationTest {
    @Test
    void boundedPoolOverridesBothIncompatibleInheritedFloorsWithoutChangingOtherOwners() {
        try (var application = inheritedDruidContext()) {
            var environment = application.getEnvironment();
            assertEquals(5, environment.getProperty("spring.datasource.druid.initialSize", Integer.class));
            assertEquals(10, environment.getProperty("spring.datasource.druid.minIdle", Integer.class));
            CrossDomainCombinedLoadMySqlIT.Initializer.configureBoundedPool(application);
            assertEquals(0, environment.getProperty("spring.datasource.druid.initialSize", Integer.class));
            assertEquals(0, environment.getProperty("spring.datasource.druid.minIdle", Integer.class));
            assertEquals(3, environment.getProperty("spring.datasource.druid.maxActive", Integer.class));
            assertEquals(5000, environment.getProperty("spring.datasource.druid.maxWait", Integer.class));
            assertEquals(5000, environment.getProperty("spring.datasource.druid.socketTimeout", Integer.class));
            assertEquals("original-owned-jdbc", environment.getProperty("spring.datasource.druid.master.url"));
            assertEquals("original-actor-secret", environment.getProperty("token.secret"));
            assertEquals("true", environment.getProperty("original.fixture.guard"));
        }
    }

    @Test
    void originalDruidPropertiesAssemblesAValidUninitializedThreeConnectionPool() {
        try (var application = inheritedDruidContext(); var pool = new DruidDataSource()) {
            CrossDomainCombinedLoadMySqlIT.Initializer.configureBoundedPool(application);
            var environment = application.getEnvironment();
            var properties = new DruidProperties();
            for (String field : new String[]{"initialSize", "minIdle", "maxActive", "maxWait", "socketTimeout"})
                ReflectionTestUtils.setField(properties, field,
                        environment.getRequiredProperty("spring.datasource.druid." + field, Integer.class));
            ReflectionTestUtils.setField(properties, "connectTimeout", 30000);
            ReflectionTestUtils.setField(properties, "timeBetweenEvictionRunsMillis", 60000);
            ReflectionTestUtils.setField(properties, "minEvictableIdleTimeMillis", 300000);
            ReflectionTestUtils.setField(properties, "maxEvictableIdleTimeMillis", 900000);
            ReflectionTestUtils.setField(properties, "validationQuery", "SELECT 1 FROM DUAL");
            ReflectionTestUtils.setField(properties, "testWhileIdle", true);
            ReflectionTestUtils.setField(properties, "testOnBorrow", false);
            ReflectionTestUtils.setField(properties, "testOnReturn", false);
            assertSame(pool, properties.dataSource(pool));
            assertEquals(0, pool.getInitialSize()); assertEquals(0, pool.getMinIdle());
            assertEquals(3, pool.getMaxActive()); assertEquals(5000, pool.getMaxWait());
            assertEquals(5000, pool.getSocketTimeout());
            assertTrue(pool.getInitialSize() <= pool.getMaxActive());
            assertTrue(pool.getMinIdle() <= pool.getMaxActive());
            assertFalse(pool.isInited(), "Assembly control must not open a database connection");
            assertEquals(0, pool.getActiveCount()); assertEquals(0, pool.getPoolingCount());
        }
    }

    @Test
    void schedulerChildHeapIsFixedOnlyForOriginalCombinedScope() {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String main=SchedulerFenceProcessProbe.class.getName();
        assertEquals(List.of(java,"-Xmx64m","-cp",cp,main),
                CrossDomainSchedulerStage.childArguments("CrossDomainCombinedLoadMySqlIT"));
        for(String scope:new String[]{null,"","CrossDomainFaultIsolationMySqlIT",
                " CrossDomainCombinedLoadMySqlIT","CrossDomainCombinedLoadMySqlIT -Xmx1024m",
                "crossdomaincombinedloadmysqlit"})
            assertEquals(List.of(java,"-cp",cp,main),CrossDomainSchedulerStage.childArguments(scope));
    }

    @Test
    void combinationIdentityFileRetainsFrozenBytesAcrossIntegerNodeWidths(@TempDir Path directory) throws Exception {
        // File/serialization control only: the SQL, member and login boundaries are explicit doubles.
        // IDs include small longs and values beyond both int and JavaScript's exact integer range.
        for (long base : new long[]{1L, 9_007_199_254_740_993L}) {
            Path owned = Files.createDirectory(directory.resolve("ids-" + base));
            Path file = owned.resolve("identities.json");
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ConfigurableApplicationContext context = combinationSeedContext(jdbc, base);
            var readback = GameplayProcessRecoveryChild.seedCombination(context, jdbc, file);

            assertTrue(Files.isRegularFile(file));
            assertFalse(Files.isSymbolicLink(file));
            assertFalse(Files.exists(owned.resolve("identities.json.new")));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            ObjectNode frozen = GameplayProcessRecoveryChild.JSON.createObjectNode();
            for (int actor = 0; actor < 2; actor++) {
                String key = actor == 0 ? "a" : "b";
                long memberId = base + actor + 1;
                frozen.putObject(key).put("memberId", memberId).put("token", "controlled-token-" + memberId);
                assertTrue(frozen.path(key).path("memberId").isLong());
                assertEquals(memberId, readback.path(key).path("memberId").longValue());
                assertEquals(base == 1L, readback.path(key).path("memberId").isInt());
                assertEquals(base != 1L, readback.path(key).path("memberId").isLong());
                assertEquals("controlled-token-" + memberId, readback.path(key).path("token").textValue());
            }
            var commandIds = new HashSet<String>();
            int capacityItems = 0;
            for (int index = 0; index < 75; index++) {
                String phase = index < 3 ? List.of("BEFORE_TX", "COMMITTED_NO_REPLY", "ACKED").get(index)
                        : String.format(Locale.ROOT, "CAPACITY_%02d", index - 3);
                if (index >= 3) capacityItems++;
                var row = readback.path(phase);
                assertTrue(row.isObject(), phase);
                assertEquals(5, row.size(), phase);
                String commandId = row.path("commandId").textValue();
                assertNotNull(commandId, phase);
                assertTrue(commandId.matches("process-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), phase);
                assertTrue(commandIds.add(commandId), "Each original workload has its own command identity");
                // Key order: club, then topic/first/second/third/activity for each original workload.
                long firstChapter = base + 102 + index * 5L;
                frozen.putObject(phase).put("activityId", firstChapter + 3).put("commandId", commandId)
                        .put("expectedChapterId", firstChapter).put("targetChapterId", firstChapter + 1)
                        .put("thirdChapterId", firstChapter + 2);
                assertEquals(firstChapter + 3, row.path("activityId").longValue(), phase);
                assertEquals(firstChapter, row.path("expectedChapterId").longValue(), phase);
                assertEquals(firstChapter + 1, row.path("targetChapterId").longValue(), phase);
                assertEquals(firstChapter + 2, row.path("thirdChapterId").longValue(), phase);
                assertEquals(base == 1L, row.path("activityId").isInt(), phase);
                assertEquals(base != 1L, row.path("activityId").isLong(), phase);
            }
            assertEquals(72, capacityItems);
            assertEquals(75, commandIds.size());
            assertEquals(77, readback.size(), "Two actors, three original phases and exactly 72 capacity items");
            byte[] frozenBytes = GameplayProcessRecoveryChild.JSON.writeValueAsBytes(frozen);
            byte[] actualBytes = Files.readAllBytes(file);
            assertArrayEquals(frozenBytes, actualBytes, "Original long-valued identity serialization is retained exactly");
            assertArrayEquals(actualBytes, GameplayProcessRecoveryChild.JSON.writeValueAsBytes(readback));
            assertEquals(readback, GameplayProcessRecoveryChild.JSON.readTree(actualBytes));
            verify(jdbc).queryForObject("SELECT COUNT(*) FROM club_lead_state", Integer.class);
            verify(jdbc).queryForObject("SELECT COUNT(*) FROM game_command_receipt", Integer.class);
            verify(jdbc, times(376)).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
            verify(jdbc, times(75)).update(
                    eq("INSERT INTO club_lead_state(activity_id,leader_member_id,current_chapter_id,status,create_time) VALUES(?,?,?,2,NOW())"),
                    any(Object[].class));
            verifyNoMoreInteractions(jdbc);
            verify(context.getBean(UmsMemberMapper.class), times(2)).insertUmsMember(any(UmsMember.class));
            verify(context.getBean(AppLoginService.class), times(2))
                    .loginByMember(any(UmsMember.class), eq("phone"), anyString(), isNull());
        }
    }

    @Test
    void combinationIdentityFileRejectsExistingAndPendingFilesBeforeSql(@TempDir Path directory) throws Exception {
        for (boolean pending : new boolean[]{false, true}) {
            Path owned = Files.createDirectory(directory.resolve(pending ? "pending" : "existing"));
            Path file = owned.resolve("identities.json");
            Path pendingFile = owned.resolve("identities.json.new");
            Path sentinel = pending ? pendingFile : file;
            byte[] original = "original-owned-identity-sentinel".getBytes(StandardCharsets.UTF_8);
            Files.write(sentinel, original, StandardOpenOption.CREATE_NEW);
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
            assertThrows(IllegalStateException.class,
                    () -> GameplayProcessRecoveryChild.seedCombination(context, jdbc, file));
            assertArrayEquals(original, Files.readAllBytes(sentinel));
            assertFalse(Files.exists(pending ? file : pendingFile), "Refusal must not create the other file");
            verifyNoInteractions(jdbc, context);
        }
    }

    private static ConfigurableApplicationContext combinationSeedContext(JdbcTemplate jdbc, long base) {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        AppLoginService login = mock(AppLoginService.class);
        when(context.getBean(UmsMemberMapper.class)).thenReturn(members);
        when(context.getBean(AppLoginService.class)).thenReturn(login);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM club_lead_state", Integer.class)).thenReturn(0);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM game_command_receipt", Integer.class)).thenReturn(0);
        AtomicLong memberIds = new AtomicLong(base + 1);
        when(members.insertUmsMember(any(UmsMember.class))).thenAnswer(call -> {
            UmsMember member = call.getArgument(0);
            member.setId(memberIds.getAndIncrement());
            return 1;
        });
        when(login.loginByMember(any(UmsMember.class), eq("phone"), anyString(), isNull())).thenAnswer(call -> {
            UmsMember member = call.getArgument(0);
            assertEquals(member.getPhone(), call.getArgument(2));
            return "controlled-token-" + member.getId();
        });
        AtomicLong generatedIds = new AtomicLong(base + 100);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(call -> {
            KeyHolder holder = call.getArgument(1);
            holder.getKeyList().add(Map.of("id", generatedIds.getAndIncrement()));
            return 1;
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        return context;
    }

    private static GenericApplicationContext inheritedDruidContext() {
        var application = new GenericApplicationContext();
        application.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original-druid", Map.of(
                "spring.datasource.druid.initialSize", "5", "spring.datasource.druid.minIdle", "10",
                "spring.datasource.druid.maxActive", "20", "spring.datasource.druid.maxWait", "5000",
                "spring.datasource.druid.socketTimeout", "60000", "spring.datasource.druid.master.url", "original-owned-jdbc",
                "token.secret", "original-actor-secret", "original.fixture.guard", "true")));
        return application;
    }
}
