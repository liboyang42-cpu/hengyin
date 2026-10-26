package com.chengyinhub.business.it;

import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.framework.config.properties.DruidProperties;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
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
