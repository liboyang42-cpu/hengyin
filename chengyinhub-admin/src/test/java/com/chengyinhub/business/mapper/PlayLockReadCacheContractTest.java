package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubLeadCheckin;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.PlayAttemptLog;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MyBatis SESSION-cache policy, including include-expanded SQL. Does not replace MySQL races. */
class PlayLockReadCacheContractTest {
    @ParameterizedTest
    @CsvSource({
        "ClubLeadMapper,selectByActivityForUpdate",
        "ClubLeadMapper,lockClubOwner",
        "TopicRouteSessionMapper,selectAccessibleByIdForUpdate",
        "GameRuntimeSessionMapper,selectByIdForUpdate",
        "GameRuntimeSessionMapper,selectAccessibleByIdForUpdate",
        "PlayAttemptLogMapper,selectByIdempotencyKeyForUpdate"
    })
    void waitedLockMustClearEarlierSessionCacheAndExecuteCurrentSql(String mapper, String query) throws Exception {
        String resource = "mapper/business/" + mapper + ".xml";
        String xml;
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertCurrentRead(statement(resource, xml, mapper, query));
        // Turning off L2 alone is insufficient: this mutant must still fail on a cached null/hit.
        int start = xml.indexOf("<select id=\"" + query + "\"");
        int end = xml.indexOf('>', start);
        String tag = xml.substring(start, end);
        assertTrue(tag.contains("flushCache=\"true\""));
        String mutant = xml.substring(0, start) + tag.replace("flushCache=\"true\"", "flushCache=\"false\"") + xml.substring(end);
        assertThrows(AssertionError.class, () -> assertCurrentRead(statement(resource, mutant, mapper, query)));
    }

    private MappedStatement statement(String resource, String xml, String mapper, String query) {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("ClubLeadState", ClubLeadState.class);
        cfg.getTypeAliasRegistry().registerAlias("ClubLeadCheckin", ClubLeadCheckin.class);
        cfg.getTypeAliasRegistry().registerAlias("PlayAttemptLog", PlayAttemptLog.class);
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), cfg, resource, cfg.getSqlFragments()).parse();
        return cfg.getMappedStatement("com.chengyinhub.business.mapper." + mapper + "." + query);
    }

    private void assertCurrentRead(MappedStatement statement) {
        assertFalse(statement.isUseCache(), "L2 must not supply a prior authorization/receipt");
        assertTrue(statement.isFlushCacheRequired(), "L1 must not supply the miss/hit observed before waiting for the lock");
        String sql = statement.getBoundSql(Map.of("activityId", 1L, "clubId", 2L, "id", 3L,
                "memberId", 7L, "idempotencyKey", "original-action")).getSql().replaceAll("\\s+", " ").trim().toLowerCase();
        assertTrue(sql.endsWith("for update"), "include-expanded SQL must actually lock: " + sql);
    }
}
