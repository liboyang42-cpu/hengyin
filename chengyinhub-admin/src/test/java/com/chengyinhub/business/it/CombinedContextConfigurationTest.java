package com.chengyinhub.business.it;

import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.framework.config.properties.DruidProperties;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.HexFormat;
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
import org.springframework.dao.DataAccessResourceFailureException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.chengyinhub.business.service.ai.PhotoCheckIntentService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Property, real datasource and child command assembly only; no web, SQL, Redis or provider proof. */
class CombinedContextConfigurationTest {
    @TempDir
    Path directory;

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

        // Resource assembly doubles only: no Redis connection/client/event-loop is started here.
        var shared = mock(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class);
        var redisConfiguration = new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", 31415);
        redisConfiguration.setDatabase(15);
        when(shared.getStandaloneConfiguration()).thenReturn(redisConfiguration);
        when(shared.isRunning()).thenReturn(true); when(shared.getHostName()).thenReturn("127.0.0.1");
        when(shared.getPort()).thenReturn(31415); when(shared.getDatabase()).thenReturn(15);
        assertSame(shared, CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, null));
        assertSame(shared, CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, ""));
        when(shared.getPassword()).thenReturn("controlled-auth-canary");
        assertSame(shared, CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, "controlled-auth-canary"));
        var wrongAuthentication = assertThrows(AssertionError.class,
                () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, "foreign-auth-canary"));
        assertFalse(wrongAuthentication.getMessage().contains("controlled-auth-canary"));
        assertFalse(wrongAuthentication.getMessage().contains("foreign-auth-canary"));
        when(shared.getPassword()).thenReturn(null);
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(null, 31415, 15, null));
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31416, 15, null));
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 0, null));
        when(shared.getHostName()).thenReturn("localhost");
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, null));
        when(shared.getHostName()).thenReturn("127.0.0.1"); when(shared.getDatabase()).thenReturn(0);
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, null));
        when(shared.getDatabase()).thenReturn(15); when(shared.isRunning()).thenReturn(false);
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, null));
        when(shared.isRunning()).thenReturn(true); when(shared.isClusterAware()).thenReturn(true);
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, null));
        when(shared.isClusterAware()).thenReturn(false); when(shared.isRedisSentinelAware()).thenReturn(true);
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, null));
        when(shared.isRedisSentinelAware()).thenReturn(false);
        when(shared.getSocketConfiguration()).thenReturn(new org.springframework.data.redis.connection.RedisSocketConfiguration("/tmp/controlled-redis.sock"));
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, null));
        when(shared.getSocketConfiguration()).thenReturn(null);
        redisConfiguration.setUsername("foreign-acl-identity");
        assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSharedRedisFactory(shared, 31415, 15, null));
        clearInvocations(shared);
        var borrowedFixture = new CrossDomainStorageFixture(); borrowedFixture.redisFactory = shared;
        assertDoesNotThrow(borrowedFixture::close);
        verifyNoInteractions(shared); // Borrowed application owner is never restarted/stopped/destroyed.
        var owned = mock(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class);
        var standaloneFixture = new CrossDomainStorageFixture(); standaloneFixture.redisFactory = owned;
        standaloneFixture.ownsRedisFactory = true;
        assertDoesNotThrow(standaloneFixture::close);
        verify(owned).destroy(); verifyNoMoreInteractions(owned);

        DataSource metadataOnly = mock(DataSource.class);
        var sessions = assertDoesNotThrow(() -> CrossDomainStorageFixture.fixtureSqlFactory(metadataOnly).getObject());
        assertNotNull(sessions);
        assertEquals(java.util.Set.of("OmsOrderMapper", "MerchantSubscriptionMapper", "OfficialBroadcastMapper", "OfficialBroadcastLogMapper",
                "UmsMemberMapper", "ImConversationMapper", "ImConversationMemberMapper", "ImMessageMapper", "TopicRouteSessionMapper",
                "CmsTopicMapper", "CmsRegistrationMapper", "AiQuotaReservationMapper", "PhotoCheckIntentMapper"),
                sessions.getConfiguration().getMapperRegistry().getMappers().stream().map(Class::getSimpleName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(10L, sessions.getConfiguration().getTypeAliasRegistry().getTypeAliases().values().stream()
                .filter(type -> type.getName().startsWith("com.chengyinhub.business.domain.")).distinct().count());
        verifyNoInteractions(metadataOnly); // Parse every original XML without opening SQL or scanning unrelated domains.
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

        var coordinatorType = com.chengyinhub.quartz.service.MySqlSchedulerCoordinator.class;
        assertEquals(boolean.class, assertDoesNotThrow(() -> coordinatorType.getDeclaredField("running")).getType());
        assertEquals(long.class, assertDoesNotThrow(() -> coordinatorType.getDeclaredField("activatedAt")).getType());
        var realBeanFactoryEntry = assertDoesNotThrow(() -> com.chengyinhub.common.utils.spring.SpringUtils.class
                .getMethod("postProcessBeanFactory", org.springframework.beans.factory.config.ConfigurableListableBeanFactory.class));
        assertEquals(void.class, realBeanFactoryEntry.getReturnType());
        assertTrue(Modifier.isPublic(realBeanFactoryEntry.getModifiers()));
    }

    @Test
    void combinationIdentityFileRetainsFrozenBytesAcrossIntegerNodeWidths() throws Exception {
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
    void combinationIdentityFileRejectsExistingAndPendingFilesBeforeSql() throws Exception {
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

        // Fixed SQL-preparation/entry boundary only; no servlet, provider or command success double.
        JdbcTemplate firstQueries = mock(JdbcTemplate.class);
        String stateSql = "SELECT * FROM club_lead_state WHERE activity_id=?";
        String receiptSql = "SELECT * FROM game_command_receipt WHERE activity_id=? AND actor_member_id=? AND request_id=?";
        Map<String,Object> initialState = new LinkedHashMap<>();
        initialState.put("current_chapter_id", 7L); initialState.put("broadcast_text", null);
        initialState.put("create_time", java.time.LocalDateTime.of(2026, 10, 26, 1, 2, 3, 123456789));
        when(firstQueries.queryForObject(anyString(), eq(Long.class), anyLong())).thenReturn(7L);
        when(firstQueries.queryForList(eq(stateSql), anyLong())).thenReturn(List.of(initialState));
        when(firstQueries.queryForList(eq(receiptSql), anyLong(), eq(1L), anyString())).thenReturn(List.of());
        when(firstQueries.queryForMap(eq(stateSql), anyLong())).thenReturn(initialState);
        var inputs = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        inputs.putObject("a").put("memberId", 1L); inputs.putObject("b").put("memberId", 2L);
        for (int i = 0; i < 24; i++) inputs.putObject(String.format(Locale.ROOT, "CAPACITY_%02d", i))
                .put("activityId", 100L + i).put("commandId", "core-control-" + i)
                .put("expectedChapterId", 7L).put("targetChapterId", 8L);
        var witnessed = new java.util.concurrent.atomic.AtomicInteger();
        try (var core = new CombinedCoreHttpWorkload(firstQueries, inputs, 1)) {
            assertThrows(AssertionError.class, () -> core.run(witnessed::incrementAndGet));
            assertEquals(0, witnessed.get()); verifyNoInteractions(firstQueries);
            core.prepareFirstWave(witnessed::incrementAndGet);
            assertEquals(1, witnessed.get());
            assertEquals(1L, core.entered.getCount(), "SQL preparation/witness must not claim HTTP entry");
            assertEquals(0, core.verifiedResponses.get()); assertTrue(core.waves.isEmpty());
            assertEquals(7L, initialState.get("current_chapter_id")); assertNull(initialState.get("broadcast_text"));
            assertEquals(3, initialState.size(), "Read preparation must not mutate complete JDBC facts");
            assertThrows(AssertionError.class, () -> core.prepareFirstWave(witnessed::incrementAndGet));
            assertEquals(1, witnessed.get(), "An original preparation cannot be replaced");
            for (int i = 0; i < 24; i++) {
                String key = String.format(Locale.ROOT, "CAPACITY_%02d", i);
                long activity = inputs.path(key).path("activityId").asLong();
                String receiptKey = "lead:" + CombinedCoreHttpWorkload.sha(
                        inputs.path(key).path("commandId").asText().getBytes(StandardCharsets.UTF_8)).substring(0, 48);
                verify(firstQueries).queryForObject("SELECT current_chapter_id FROM club_lead_state WHERE activity_id=?", Long.class, activity);
                verify(firstQueries).queryForList(stateSql, activity);
                verify(firstQueries, times(2)).queryForList(receiptSql, activity, 1L, receiptKey);
                verify(firstQueries).queryForMap(stateSql, activity);
            }
            verifyNoMoreInteractions(firstQueries); // Exact original 24 x five read statements, no updates.
        }
    }

    @Test
    void protectedFactDigestPreservesJdbcScalarAndTimeFacts() {
        // Scalar/file-free control, not a driver, database or HTTP execution claim.
        String query = "SELECT * FROM ums_member WHERE id IN (?,?) ORDER BY id";
        Object[] parameters = {9_007_199_254_740_993L, Long.MAX_VALUE};
        JdbcTemplate source = mock(JdbcTemplate.class);
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", Long.MAX_VALUE);
        row.put("unsigned_id", new BigInteger("18446744073709551615"));
        row.put("point", Integer.MAX_VALUE);
        row.put("small", (short) 32767);
        row.put("tiny", (byte) 127);
        row.put("balance", new BigDecimal("12345678901234567890.123456789000"));
        row.put("ratio", 0.1d);
        row.put("fraction", 0.1f);
        row.put("active", true);
        row.put("nickname", "完整事实\u0000中文");
        row.put("binary", new byte[]{0, 1, -1});
        row.put("nullable", null);
        row.put("birthday", java.sql.Date.valueOf("2026-10-26"));
        row.put("clock", new java.sql.Time(1_234_567L));
        row.put("legacy_date", new java.util.Date(1_234_567L));
        row.put("timestamp", java.sql.Timestamp.valueOf("2026-10-26 01:02:03.123456789"));
        row.put("local_date", java.time.LocalDate.of(2026, 10, 26));
        row.put("local_time", java.time.LocalTime.of(1, 2, 3, 123456789));
        row.put("create_time", java.time.LocalDateTime.of(2026, 10, 26, 1, 2, 3, 123456789));
        row.put("instant", java.time.Instant.parse("2026-10-26T01:02:03.123456789Z"));
        List<Map<String,Object>> rows = new ArrayList<>();
        rows.add(row);
        doReturn(rows).when(source).queryForList(eq(query), eq(parameters));
        java.util.function.Supplier<String> digest = () -> ReflectionTestUtils.invokeMethod(
                CrossDomainCombinedLoadMySqlIT.class, "digest", source, query, parameters);
        String original = digest.get();
        assertNotNull(original);
        assertTrue(original.matches("[0-9a-f]{64}"));
        assertEquals(original, digest.get(), "The same complete row has stable bytes");

        Map<String,Object> changes = new LinkedHashMap<>();
        changes.put("id", Long.MAX_VALUE - 1);
        changes.put("unsigned_id", new BigInteger("18446744073709551614"));
        changes.put("point", Integer.MAX_VALUE - 1);
        changes.put("small", (short) 32766);
        changes.put("tiny", (byte) 126);
        changes.put("balance", new BigDecimal("12345678901234567890.123456789001"));
        changes.put("ratio", Math.nextUp(0.1d));
        changes.put("fraction", Math.nextUp(0.1f));
        changes.put("active", false);
        changes.put("nickname", "完整事实\u0000中文变更");
        changes.put("binary", new byte[]{0, 1, -2});
        changes.put("nullable", "present");
        changes.put("birthday", java.sql.Date.valueOf("2026-10-27"));
        changes.put("clock", new java.sql.Time(1_234_568L));
        changes.put("legacy_date", new java.util.Date(1_234_568L));
        changes.put("timestamp", java.sql.Timestamp.valueOf("2026-10-26 01:02:03.123456790"));
        changes.put("local_date", java.time.LocalDate.of(2026, 10, 27));
        changes.put("local_time", java.time.LocalTime.of(1, 2, 3, 123456790));
        changes.put("create_time", java.time.LocalDateTime.of(2026, 10, 26, 1, 2, 3, 123456790));
        changes.put("instant", java.time.Instant.parse("2026-10-26T01:02:03.123456790Z"));
        assertEquals(row.keySet(), changes.keySet(), "Every original field has a change control");
        for (var change : changes.entrySet()) {
            Object before = row.put(change.getKey(), change.getValue());
            assertNotEquals(original, digest.get(), "Must retain full value/precision of " + change.getKey());
            row.put(change.getKey(), before);
            assertEquals(original, digest.get(), change.getKey());
        }
        row.put("balance", new BigDecimal("12345678901234567890.1234567890000"));
        assertNotEquals(original, digest.get(), "Decimal scale is not rounded or converted to double");
        row.put("balance", new BigDecimal("12345678901234567890.123456789000"));
        row.remove("nullable");
        assertNotEquals(original, digest.get(), "SQL NULL must not be omitted");
        row.put("nullable", null);
        assertEquals(original, digest.get(), "Column insertion order does not alter complete facts");
        rows.add(Map.of("id", 1L));
        String orderedRows = digest.get();
        assertNotEquals(original, orderedRows, "No SQL row may be omitted");
        Collections.reverse(rows);
        assertNotEquals(orderedRows, digest.get(), "SQL ORDER BY remains part of the observation");
        verify(source, atLeastOnce()).queryForList(eq(query), eq(parameters));
        verifyNoMoreInteractions(source);

        JdbcTemplate coreSource = mock(JdbcTemplate.class);
        Map<String,Object> coreRow = new LinkedHashMap<>(row);
        List<Map<String,Object>> coreRows = new ArrayList<>(); coreRows.add(coreRow);
        doReturn(coreRows).when(coreSource).queryForList(anyString(), any(Object[].class));
        doReturn(coreRows).when(coreSource).queryForList(anyString());
        var inputs = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        inputs.putObject("a").put("memberId", 1L); inputs.putObject("b").put("memberId", 2L);
        var request = inputs.putObject("CAPACITY_00").put("activityId", 100L).put("commandId", "core-control");
        assertDoesNotThrow(() -> {
            try (var core = new CombinedCoreHttpWorkload(coreSource, inputs, 1)) {
                String coreFact = ReflectionTestUtils.invokeMethod(core, "fact", request);
                String allFacts = ReflectionTestUtils.invokeMethod(core, "allGame");
                assertNotNull(coreFact); assertNotNull(allFacts);
                assertTrue(coreFact.matches("[0-9a-f]{64}")); assertTrue(allFacts.matches("[0-9a-f]{64}"));
                for (var change : changes.entrySet()) {
                    Object before = coreRow.put(change.getKey(), change.getValue());
                    assertNotEquals(coreFact, ReflectionTestUtils.invokeMethod(core, "fact", request), change.getKey());
                    assertNotEquals(allFacts, ReflectionTestUtils.invokeMethod(core, "allGame"), change.getKey());
                    coreRow.put(change.getKey(), before);
                    assertEquals(coreFact, ReflectionTestUtils.invokeMethod(core, "fact", request));
                    assertEquals(allFacts, ReflectionTestUtils.invokeMethod(core, "allGame"));
                }
                coreRows.add(Map.of("id", 1L));
                String orderedCore = ReflectionTestUtils.invokeMethod(core, "fact", request);
                assertNotEquals(coreFact, orderedCore);
                Collections.reverse(coreRows);
                assertNotEquals(orderedCore, ReflectionTestUtils.invokeMethod(core, "fact", request));
                assertEquals(1L, core.entered.getCount()); assertEquals(0, core.verifiedResponses.get());
            }
        });
    }

    @Test
    void protectedFactDigestRetainsQueryAndSerializationFailures() {
        String query = "SELECT * FROM ums_member WHERE id IN (?,?) ORDER BY id";
        Object[] parameters = {9_007_199_254_740_993L, Long.MAX_VALUE};
        JdbcTemplate failingQuery = mock(JdbcTemplate.class);
        var driverFailure = new java.sql.SQLException("controlled SQL failure");
        var queryFailure = new DataAccessResourceFailureException("controlled query failure", driverFailure);
        doThrow(queryFailure).when(failingQuery).queryForList(eq(query), eq(parameters));
        IllegalStateException observedQuery = assertThrows(IllegalStateException.class, () ->
                ReflectionTestUtils.invokeMethod(CrossDomainCombinedLoadMySqlIT.class,
                        "digest", failingQuery, query, parameters));
        assertEquals("CONTROLLED_FACT_OBSERVATION_FAILED", observedQuery.getMessage());
        assertSame(queryFailure, observedQuery.getCause());
        assertSame(driverFailure, observedQuery.getCause().getCause());
        verify(failingQuery).queryForList(eq(query), eq(parameters));
        verifyNoMoreInteractions(failingQuery);

        JdbcTemplate unsupportedRow = mock(JdbcTemplate.class);
        doReturn(List.of(Map.of("unsupported", new Object())))
                .when(unsupportedRow).queryForList(eq(query), eq(parameters));
        IllegalStateException observedSerialization = assertThrows(IllegalStateException.class, () ->
                ReflectionTestUtils.invokeMethod(CrossDomainCombinedLoadMySqlIT.class,
                        "digest", unsupportedRow, query, parameters));
        assertEquals("CONTROLLED_FACT_OBSERVATION_FAILED", observedSerialization.getMessage());
        var serializationFailure = assertInstanceOf(InvalidDefinitionException.class, observedSerialization.getCause());
        assertTrue(serializationFailure.getPath().stream().anyMatch(reference ->
                "unsupported".equals(reference.getFieldName())), "Original failing field path is retained privately");
        verify(unsupportedRow).queryForList(eq(query), eq(parameters));
        verifyNoMoreInteractions(unsupportedRow);

        assertThrows(InvalidDefinitionException.class, () -> CrossDomainCombinedLoadMySqlIT.completeFactGroups(
                Map.of("state", List.of(Map.of("unsupported", new Object())))));
        var completedCore = java.util.concurrent.CompletableFuture.failedFuture(queryFailure);
        var entry = assertDoesNotThrow(() -> CombinedCoreHttpWorkload.entryFailure(completedCore));
        assertEquals("Original core HTTP request did not enter its fixed window", entry.getMessage());
        assertSame(queryFailure, entry.getCause()); assertSame(driverFailure, entry.getCause().getCause());
        var unfinishedCore = new java.util.concurrent.CompletableFuture<Void>();
        var noEntry = assertDoesNotThrow(() -> CombinedCoreHttpWorkload.entryFailure(unfinishedCore));
        assertNull(noEntry.getCause()); assertFalse(unfinishedCore.isDone(), "No extra wait/cancel/retry at entry failure");
    }

    @Test
    void photoNeverReadRetainsConnectionAcrossIndependentTransaction() throws Exception {
        // Real transaction manager, mapper XML and SqlSessionTemplate; JDBC is an explicit double.
        // This proves the binding rule, not that it caused the earlier hosted failure.
        DataSource source = mock(DataSource.class);
        Connection outer = mock(Connection.class), independent = mock(Connection.class);
        when(source.getConnection()).thenReturn(outer, independent);
        var outerCloses = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> { outerCloses.incrementAndGet(); return null; }).when(outer).close();
        when(outer.getAutoCommit()).thenReturn(true);
        when(independent.getAutoCommit()).thenReturn(true);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(outer.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rows);
        when(statement.getConnection()).thenReturn(outer);
        when(rows.getMetaData()).thenReturn(metadata);
        when(outer.getMetaData()).thenReturn(mock(java.sql.DatabaseMetaData.class));
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/PhotoCheckIntentMapper.xml"));
        PhotoCheckIntentMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PhotoCheckIntentMapper.class);
        var manager = new DataSourceTransactionManager(source);
        var withoutBusinessTransaction = new TransactionTemplate(manager);
        withoutBusinessTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NEVER);
        var shortTransaction = new TransactionTemplate(manager);
        shortTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        withoutBusinessTransaction.execute(status -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            assertNull(mapper.selectOwned(1L, 2L, "a".repeat(64)));
            assertSame(outer, ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).getConnection());
            shortTransaction.execute(shortStatus -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertSame(independent, ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).getConnection());
                assertEquals(0, outerCloses.get(), "Suspending an empty synchronization must not be mistaken for connection release");
                return null;
            });
            assertSame(outer, ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).getConnection());
            assertEquals(0, outerCloses.get(), "The original connection remains borrowed until the outer invocation completes");
            return null;
        });
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertFalse(TransactionSynchronizationManager.hasResource(source));
        verify(source, times(2)).getConnection();
        verify(outer).close(); verify(independent).close();
    }

    @Test
    void photoPrepareFailureProjectionKeepsOnlyReviewedCodesAndCause() {
        var fixture = new CrossDomainStorageFixture();
        fixture.tx = mock(TransactionTemplate.class);
        var service = mock(PhotoCheckIntentService.class);
        var command = fixture.command(2);
        var sql = new java.sql.SQLException("private SQL/body/token must not be projected", "40001", 1213);
        var failure = new org.springframework.transaction.CannotCreateTransactionException("private configuration", sql);
        doThrow(failure).when(fixture.tx).execute(any());
        assertSame(failure, assertThrows(org.springframework.transaction.CannotCreateTransactionException.class,
                () -> fixture.preparePhoto(service, command)));
        assertSame(sql, failure.getCause());
        assertEquals(Map.of("phase", "TX_BEGIN", "exceptionTypes", List.of("java.sql.SQLException",
                "org.springframework.transaction.CannotCreateTransactionException"),
                "sql", List.of(Map.of("sqlState", "40001", "vendorCode", "1213"))), fixture.photoPrepareFailure);
        verifyNoInteractions(service);

        doAnswer(call -> ((org.springframework.transaction.support.TransactionCallback<?>) call.getArgument(0))
                .doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)))
                .when(fixture.tx).execute(any());
        var prepareFailure = new DataAccessResourceFailureException("private prepare failure", sql);
        doThrow(prepareFailure).when(service).prepare(command);
        assertSame(prepareFailure, assertThrows(DataAccessResourceFailureException.class,
                () -> fixture.preparePhoto(service, command)));
        assertSame(sql, prepareFailure.getCause());
        assertEquals("PHOTO_PREPARE", fixture.photoPrepareFailure.get("phase"));
        assertEquals(List.of(Map.of("sqlState", "40001", "vendorCode", "1213")), fixture.photoPrepareFailure.get("sql"));
        verify(service).prepare(same(command));
        verifyNoMoreInteractions(service);

        var invalid = new java.sql.SQLException("private again", "secret", 7654321);
        invalid.setNextException(new java.sql.SQLException("private chained", "23000", 1062));
        Map<String,Object> safe = CrossDomainStorageFixture.photoPrepareFailure("untrusted private phase", invalid);
        assertEquals(Map.of("phase", "UNKNOWN", "exceptionTypes", List.of("java.sql.SQLException"),
                "sql", List.of(Map.of("sqlState", "UNKNOWN", "vendorCode", "UNKNOWN"),
                        Map.of("sqlState", "23000", "vendorCode", "1062"))), safe);
        Map<String,Object> unknownType = CrossDomainStorageFixture.photoPrepareFailure("PHOTO_PREPARE", new java.io.IOException("secret"));
        assertEquals(Map.of("phase", "PHOTO_PREPARE", "exceptionTypes", List.of("UNKNOWN_TYPE"), "sql", List.of()), unknownType);

        var faultField = java.util.Arrays.stream(CrossDomainTransportStage.class.getDeclaredFields())
                .filter(field -> field.getName().equals("fault")).findFirst().orElseThrow();
        assertTrue(java.lang.reflect.Modifier.isVolatile(faultField.getModifiers()),
                "The test thread's fault mode must be published to independently dispatched HTTP handlers");
        var originalExecutionFailure = new IllegalStateException("private original photo execution failure");
        var originalProviderFailure = new java.io.IOException("private original handler failure");
        var unfinished = new java.util.concurrent.CompletableFuture<com.chengyinhub.business.service.ai.PhotoCheckIntentView>();
        var failedCall = java.util.concurrent.CompletableFuture
                .<com.chengyinhub.business.service.ai.PhotoCheckIntentView>failedFuture(originalExecutionFailure);
        var missingEntry = assertDoesNotThrow(() -> CrossDomainTransportStage.aiEntryFailure(
                List.of(failedCall, unfinished, unfinished, unfinished), List.of(originalProviderFailure)));
        assertEquals("All four original AI provider handlers must enter the held window", missingEntry.getMessage());
        assertSame(originalExecutionFailure, missingEntry.getCause());
        assertSame(originalProviderFailure, missingEntry.getSuppressed()[0]);
        assertFalse(unfinished.isDone(), "Barrier failure must not await/cancel/retry an unfinished original call");
        var completedWithoutResult = java.util.concurrent.CompletableFuture
                .<com.chengyinhub.business.service.ai.PhotoCheckIntentView>completedFuture(null);
        var noFailureCause = assertDoesNotThrow(() -> CrossDomainTransportStage.aiEntryFailure(
                List.of(completedWithoutResult, unfinished, unfinished, unfinished), List.of()));
        assertNull(noFailureCause.getCause());
        assertEquals(1, noFailureCause.getSuppressed().length);
        assertEquals("Photo completed before the four-handler barrier: state=NULL, providerState=UNAVAILABLE",
                noFailureCause.getSuppressed()[0].getMessage());
        assertFalse(unfinished.isDone());

        var originalMemoryFailure = new OutOfMemoryError("controlled error identity; no heap exhaustion");
        var memoryCall = java.util.concurrent.CompletableFuture
                .<com.chengyinhub.business.service.ai.PhotoCheckIntentView>failedFuture(originalMemoryFailure);
        var memoryEntry = assertDoesNotThrow(() -> CrossDomainTransportStage.aiEntryFailure(
                List.of(memoryCall, unfinished, unfinished, unfinished), List.of()));
        assertSame(originalMemoryFailure, memoryEntry.getCause(), "An Error must never become a fabricated result/retry");
        assertFalse(unfinished.isDone());

        // Source-reference ownership only; no server, physical handler or business success is simulated here.
        var stage = new CrossDomainTransportStage();
        try {
            String image = ReflectionTestUtils.invokeMethod(CrossDomainTransportStage.class, "newFaultImage");
            assertNotNull(image); assertTrue(image.startsWith("data:image/jpeg;base64,"));
            assertEquals("data:image/jpeg;base64,".length() + 16 * 1024 * 1024, image.length());
            assertEquals('A', image.charAt("data:image/jpeg;base64,".length()));
            assertEquals('A', image.charAt(image.length() - 1));
            ReflectionTestUtils.setField(stage, "faultImage", image);
            stage.fault = true;
            assertSame(image, stage.image());
            var done = List.of(completedWithoutResult, completedWithoutResult, completedWithoutResult, completedWithoutResult);
            assertThrows(AssertionError.class, () -> stage.retireImageSource(done));
            for (int i = 0; i < 4; i++) stage.aiEntered.countDown();
            assertThrows(AssertionError.class,
                    () -> stage.retireImageSource(List.of(completedWithoutResult, unfinished, completedWithoutResult, completedWithoutResult)));
            assertSame(image, ReflectionTestUtils.getField(stage, "faultImage"));
            stage.retireImageSource(done);
            assertNull(ReflectionTestUtils.getField(stage, "faultImage"));
            assertTrue(stage.fault); assertEquals(1L, stage.releaseAi.getCount()); assertEquals(4L, stage.aiServerExited.getCount());
            stage.retireImageSource(done); // Idempotent while the same four physical handlers must remain held.
        } finally {
            stage.callers.shutdownNow(); stage.providers.shutdownNow();
            assertTrue(stage.callers.isTerminated()); assertTrue(stage.providers.isTerminated());
        }

        // Finite encoding controls, not a heap measurement or a substitute for four real provider handlers.
        for (String encoded : List.of("", "data:image/jpeg;base64," + "A".repeat(8193), "中文\u0000\"\\",
                "\uD800", "\uDC00", "A".repeat(4095) + "\uD83D\uDE00" + "B".repeat(4097),
                "A".repeat(4095) + "\uD800" + "B", "A".repeat(4096) + "\uDC00", "A".repeat(4095) + "\uD800")) {
            String originalHash = assertDoesNotThrow(() -> HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8))));
            assertEquals(originalHash, ReflectionTestUtils.invokeMethod(
                    com.chengyinhub.business.service.ai.PhotoCheckVisionService.class, "encodedInputHash", encoded));
        }
        var bytePosts = new java.util.concurrent.atomic.AtomicInteger();
        var wire = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        // Stop at the actual new transport entry with a proven local NOT_SENT; never invent provider success.
        try (var transport = mockStatic(com.chengyinhub.common.utils.http.HttpUtils.class, invocation -> {
            assertEquals("sendJsonPostBytes", invocation.getMethod().getName(), "No intermediate JSON String transport");
            bytePosts.incrementAndGet();
            wire.set(assertInstanceOf(byte[].class, invocation.getArgument(1)));
            throw new com.chengyinhub.business.service.ai.AiProviderNotSentException();
        })) {
            String origin = "http://127.0.0.1:1";
            var client = new com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient("controlled-encoding",
                    origin, "fixture-token", "fixture-model", true, 2000,
                    new com.chengyinhub.business.service.ai.AiHttpAdmission(List.of(origin)));
            assertThrows(com.chengyinhub.business.service.ai.AiProviderNotSentException.class,
                    () -> client.chatVision("中文\u0000\"\\", "data:image/jpeg;base64,AA==", "要求\uD83D\uDE00"));
        }
        assertEquals(1, bytePosts.get(), "The new encoding path cannot retry");
        var sent = com.alibaba.fastjson2.JSON.parseObject(wire.get());
        assertEquals(java.util.Set.of("model", "messages"), sent.keySet());
        assertEquals("fixture-model", sent.getString("model"));
        var messages = sent.getJSONArray("messages"); assertEquals(2, messages.size());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("中文\u0000\"\\", messages.getJSONObject(0).getString("content"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        var content = messages.getJSONObject(1).getJSONArray("content"); assertEquals(2, content.size());
        assertEquals("image_url", content.getJSONObject(0).getString("type"));
        assertEquals("data:image/jpeg;base64,AA==", content.getJSONObject(0).getJSONObject("image_url").getString("url"));
        assertEquals("text", content.getJSONObject(1).getString("type"));
        assertEquals("要求\uD83D\uDE00", content.getJSONObject(1).getString("text"));
        assertArrayEquals(sent.toJSONString().getBytes(StandardCharsets.UTF_8), wire.get(),
                "Finite original String codec and direct UTF-8 JSON retain the same complete body");

        // The shared production review.patch is required before these byte-entry controls can pass.
        byte[] body = "{\"value\":\"中文\"}".getBytes(StandardCharsets.UTF_8), retainedBody = body.clone();
        for (int refusal = 0; refusal < 4; refusal++) {
            String target = refusal == 0 ? "ftp://127.0.0.1/" : "http://127.0.0.1:1/";
            byte[] input = refusal == 1 ? null : body;
            String bearer = refusal == 2 ? "fixture\r\nInjected: header" : "fixture-token";
            long deadline = refusal == 3 ? System.nanoTime() - 1 : System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            var releases = new java.util.concurrent.atomic.AtomicInteger();
            try (var lease = new com.chengyinhub.common.utils.http.HttpUtils.AttemptLease(releases::incrementAndGet)) {
                var unsent = assertThrows(com.chengyinhub.common.utils.http.HttpRequestException.class,
                        () -> ReflectionTestUtils.invokeMethod(com.chengyinhub.common.utils.http.HttpUtils.class,
                                "sendJsonPostBytes", target, input, bearer, deadline, lease));
                assertEquals(com.chengyinhub.common.utils.http.HttpRequestException.SendDisposition.NOT_SENT,
                        unsent.getSendDisposition());
                assertFalse(unsent.isRetryable()); assertNull(unsent.getCause());
                assertEquals(1, releases.get());
            }
            assertEquals(1, releases.get(), "Original lease releases once even after caller close");
            assertArrayEquals(retainedBody, body, "Exclusive input bytes must not be mutated");
        }
    }

    @Test
    void precompiledContentSecurityFixtureKeepsOriginalChecksAndRejectsUnprovenOutputs() throws Exception {
        // Self-contained file controls: an existing wrong type is never accepted as a success stub.
        // The actual prepared stub's positive semantics run on the real context refresh, not here.
        String source = "a".repeat(40);
        Path root = copiedPreparedSecurity("positive");
        assertEquals(root.resolve("chengyinhub-admin/target/controlled-content-security/classes"),
                ReflectionTestUtils.invokeMethod(GenericGameplayHttpE2eIT.class,
                        "preparedContentSecurityClasses", root, source));
        try (var application = new GenericApplicationContext()) {
            assertThrows(NoClassDefFoundError.class, () -> ReflectionTestUtils.invokeMethod(GenericGameplayHttpE2eIT.class,
                    "registerPreparedContentSecurityFixture", application, root, source));
            assertEquals(0, application.getBeanFactory().getBeanDefinitionCount(), "Matching hashes do not authorize another class identity");
        }
        for (String mode : List.of("missing-proof", "missing-class", "wrong-source", "source-drift", "class-drift",
                "duplicate-proof", "extra-proof-field", "extra-output", "symlink-output")) {
            Path invalid = copiedPreparedSecurity(mode);
            Path prepared = invalid.resolve("chengyinhub-admin/target/controlled-content-security");
            Path proof = prepared.resolve("proof.properties");
            Path compiled = prepared.resolve("classes/com/chengyinhub/e2e/E2eWxContentSecurityStub.class");
            String expectedSource = source;
            switch (mode) {
                case "missing-proof" -> Files.delete(proof);
                case "missing-class" -> Files.delete(compiled);
                case "wrong-source" -> expectedSource = "0".repeat(40);
                case "source-drift" -> Files.writeString(invalid.resolve("scripts/e2e/E2eWxContentSecurityStub.java.stub"), "drift");
                case "class-drift" -> Files.write(compiled, new byte[]{0, 1, 2});
                case "duplicate-proof" -> Files.writeString(proof, "sourceSha=" + source + "\nsourceSha=" + source + "\nclassSha256=" + "a".repeat(64) + "\n");
                case "extra-proof-field" -> Files.writeString(proof, "private=must-not-be-accepted\n", StandardOpenOption.APPEND);
                case "extra-output" -> Files.writeString(prepared.resolve("extra.class"), "unreviewed");
                case "symlink-output" -> {
                    Path original = invalid.resolve("original.class"); Files.move(compiled, original);
                    Files.createSymbolicLink(compiled, original);
                }
                default -> throw new AssertionError(mode);
            }
            String identity = expectedSource;
            try (var application = new GenericApplicationContext()) {
                assertThrows(AssertionError.class, () -> ReflectionTestUtils.invokeMethod(GenericGameplayHttpE2eIT.class,
                        "registerPreparedContentSecurityFixture", application, invalid, identity), mode);
                assertEquals(0, application.getBeanFactory().getBeanDefinitionCount(), "Refusal must not register a substitute bean");
            }
        }
    }

    @Test
    void providerClasspathCapturePreservesExactBytesAndRejectsUnownedEntries() throws Exception {
        // Local path/metadata controls only; placeholder file is never loaded as an artifact.
        Path root = Files.createDirectory(directory.resolve("classpath-root"));
        Path output = Files.createDirectories(root.resolve("chengyinhub-admin/target/provider-runtime"));
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------"));
        Path main = Files.createDirectories(root.resolve("chengyinhub-admin/target/classes"));
        Path tests = Files.createDirectories(root.resolve("chengyinhub-admin/target/test-classes"));
        Path cache = Files.createDirectory(directory.resolve("classpath-cache"));
        Path artifact = Files.writeString(cache.resolve("controlled.jar"), "not-executed-path-control");
        String separator = java.io.File.pathSeparator;
        String entries = String.join(separator, tests.toString(), artifact.toString(), main.toString(), artifact.toString());
        String cp = entries + separator; // Exact pinned Surefire formatter, including its one terminator.
        byte[] bytes = cp.getBytes(StandardCharsets.UTF_8);
        for (String phase : List.of("ONLINE", "OFFLINE")) {
            var proof = CombinedSurefireRuntimeProbeTest.captureTestClasspath(output, phase, cp, root, cache);
            String file = phase.toLowerCase(Locale.ROOT) + ".classpath";
            assertEquals(Map.of("fileName", file, "sha256", HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), "byteLength", bytes.length, "entryCount", 4), proof);
            assertArrayEquals(bytes, Files.readAllBytes(output.resolve(file)));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(output.resolve(file)));
        }
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> CombinedSurefireRuntimeProbeTest.captureTestClasspath(output, "ONLINE", cp, root, cache));
        Path foreign = Files.writeString(directory.resolve("foreign.jar"), "foreign");
        Path link = cache.resolve("linked.jar"); Files.createSymbolicLink(link, artifact);
        assertThrows(AssertionError.class,
                () -> CombinedSurefireRuntimeProbeTest.captureTestClasspath(output, "ONLINE", null, root, cache));
        for (String invalid : List.of("", separator, entries, separator + cp, cp + separator,
                tests + separator + separator + artifact + separator + main + separator,
                "relative.jar" + separator, "file:/unowned.jar" + separator, cp + "\n" + separator,
                cp + "\r" + separator, cp + "\0" + separator, "x".repeat(1024 * 1024) + separator,
                foreign + separator, link + separator,
                root.resolve("chengyinhub-admin/target/classes/../classes") + separator))
            assertThrows(AssertionError.class,
                    () -> CombinedSurefireRuntimeProbeTest.captureTestClasspath(output, "ONLINE", invalid, root, cache));
        assertThrows(java.nio.file.NoSuchFileException.class, () -> CombinedSurefireRuntimeProbeTest.captureTestClasspath(
                output, "ONLINE", cache.resolve("missing.jar") + separator, root, cache));
        assertThrows(AssertionError.class, () -> CombinedSurefireRuntimeProbeTest.captureTestClasspath(output, "OTHER", cp, root, cache));
        assertArrayEquals(bytes, Files.readAllBytes(output.resolve("online.classpath")));
        assertArrayEquals(bytes, Files.readAllBytes(output.resolve("offline.classpath")));
        try (var files = Files.list(output)) { assertEquals(2L, files.count(), "No invalid capture may create another output"); }

        CrossDomainCombinedLoadMySqlIT.requirePreparedTestClasspath(root, cp);
        assertThrows(AssertionError.class, () -> CrossDomainCombinedLoadMySqlIT.requirePreparedTestClasspath(root, null));
        assertThrows(AssertionError.class, () -> CrossDomainCombinedLoadMySqlIT.requirePreparedTestClasspath(root, entries));
        assertThrows(AssertionError.class, () -> CrossDomainCombinedLoadMySqlIT.requirePreparedTestClasspath(root, cp + separator));
        Files.writeString(output.resolve("offline.classpath"), "different-original-classpath");
        assertThrows(AssertionError.class, () -> CrossDomainCombinedLoadMySqlIT.requirePreparedTestClasspath(root, cp));
        Files.write(output.resolve("offline.classpath"), bytes);
        Files.setPosixFilePermissions(output.resolve("offline.classpath"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(AssertionError.class, () -> CrossDomainCombinedLoadMySqlIT.requirePreparedTestClasspath(root, cp));
        Files.setPosixFilePermissions(output.resolve("offline.classpath"), PosixFilePermissions.fromString("rw-------"));
        Files.delete(output.resolve("offline.classpath"));
        assertThrows(AssertionError.class, () -> CrossDomainCombinedLoadMySqlIT.requirePreparedTestClasspath(root, cp));
        Files.createFile(output.resolve("offline.classpath"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(output.resolve("offline.classpath"), bytes);
        CrossDomainCombinedLoadMySqlIT.requirePreparedTestClasspath(root, cp);
    }

    private Path copiedPreparedSecurity(String label) throws Exception {
        Path repository = P0AcceptanceSchema.repoRoot().toRealPath();
        Path root = Files.createDirectory(directory.resolve("security-" + label));
        Path source = root.resolve("scripts/e2e/E2eWxContentSecurityStub.java.stub");
        Files.createDirectories(source.getParent());
        Files.copy(repository.resolve("scripts/e2e/E2eWxContentSecurityStub.java.stub"), source);
        Path target = root.resolve("chengyinhub-admin/target/controlled-content-security");
        Path classes = target.resolve("classes/com/chengyinhub/e2e"); Files.createDirectories(classes);
        byte[] wrongType;
        try (var input = CombinedContextConfigurationTest.class.getResourceAsStream("CombinedContextConfigurationTest.class")) {
            assertNotNull(input); wrongType = input.readAllBytes();
        }
        Files.write(classes.resolve("E2eWxContentSecurityStub.class"), wrongType);
        String sourceHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        String classHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(wrongType));
        Files.writeString(target.resolve("proof.properties"), "sourceSha=" + "a".repeat(40)
                + "\nsourceSha256=" + sourceHash + "\nclassSha256=" + classHash + "\n");
        for (Path file : List.of(source, classes.resolve("E2eWxContentSecurityStub.class"), target.resolve("proof.properties")))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return root;
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
