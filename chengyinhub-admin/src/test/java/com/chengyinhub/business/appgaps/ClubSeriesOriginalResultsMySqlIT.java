package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.ClubSeriesOriginalResultMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 系列原始结果真库 IT：迁移幂等、快照只插一次（唯一键）、读回原样、事务回滚不留快照。 */
@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubSeriesOriginalResultsMySqlIT {
    static final long CLUB = 990401L, TOPIC = 990402L, ACTOR = 990403L;
    AppGapsMySql my; ClubSeriesOriginalResults results;

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql("migration_20261113_appgaps_series_original_result.sql");
        results = new ClubSeriesOriginalResults(my.mapper(ClubSeriesOriginalResultMapper.class, "mapper/business/appgaps/ClubSeriesOriginalResultMapper.xml"));
    }
    @BeforeEach @AfterAll void clean() { my.db.update("delete from club_event_series_original_result where club_id=?", CLUB); }

    @SuppressWarnings("unchecked")
    @Test void recordOnceReadBackVerbatimAndRejectDuplicate() {
        ClubSeriesOriginalResultsFixture f = new ClubSeriesOriginalResultsFixture(CLUB, TOPIC, ACTOR, 990404L, "it-series-1");
        results.record(f.series, f.occurrences, f.dates, f.request);
        Map<String, Object> d = (Map<String, Object>) results.read(ACTOR, f.fields()).get("data");
        assertEquals("APPLIED", d.get("state"));
        assertEquals(d.get("receiptHash"), my.db.queryForObject("select receipt_hash from club_event_series_original_result where series_id=990404", String.class));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> results.record(f.series, f.occurrences, f.dates, f.request),
                "同一 (club, 发起人, requestId) 只冻结一次");
    }

    @Test void rolledBackCreateLeavesNoSnapshot() {
        ClubSeriesOriginalResultsFixture f = new ClubSeriesOriginalResultsFixture(CLUB, TOPIC, ACTOR, 990405L, "it-series-2");
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(my.tx).executeWithoutResult(s -> {
            results.record(f.series, f.occurrences, f.dates, f.request);
            throw new IllegalStateException("场次关联失败");
        }));
        assertEquals(0, my.db.queryForObject("select count(*) from club_event_series_original_result where club_id=?", Integer.class, CLUB));
    }
}
