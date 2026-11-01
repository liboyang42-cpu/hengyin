package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.AuthorWriteReceiptMapper;
import com.chengyinhub.business.service.support.AuthorWriteReceiptService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

class AuthorWriteReceiptServiceTest {
    private JdbcTemplate jdbc;
    private AuthorWriteReceiptService service;
    protected DataSource dataSource() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:author_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return source;
    }
    @BeforeEach void database() throws Exception {
        DataSource source = dataSource();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_author_write_receipt (actor_member_id BIGINT, operation VARCHAR(32), request_id VARCHAR(64), request_hash VARCHAR(64), result_json LONGTEXT, PRIMARY KEY(actor_member_id,operation,request_id))");
        jdbc.execute("CREATE TABLE author_write_effect (id BIGINT PRIMARY KEY)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/AuthorWriteReceiptMapper.xml"));
        AuthorWriteReceiptMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AuthorWriteReceiptMapper.class);
        service = new AuthorWriteReceiptService(mapper, new DataSourceTransactionManager(source));
    }
    private AjaxResult effect(long id) { jdbc.update("INSERT INTO author_write_effect VALUES (?)", id); return AjaxResult.success(id); }
    private int effects() { return jdbc.queryForObject("SELECT COUNT(*) FROM author_write_effect", Integer.class); }

    @Test void exactReplayAndReorderedMapsWriteOnlyOnce() {
        Map<String,Object> a = new LinkedHashMap<>(); a.put("name", "原文"); a.put("id", "11");
        Map<String,Object> b = new LinkedHashMap<>(); b.put("id", "11"); b.put("name", "原文");
        service.execute(9L, "TEMPLATE_DRAFT", "original-1", a, () -> effect(11));
        AjaxResult result = service.execute(9L, "TEMPLATE_DRAFT", "original-1", b, () -> { fail("不得再次执行写入"); return null; });
        assertEquals(11L, ((Number)result.get("data")).longValue()); assertEquals(1, effects());
        assertEquals("FOUND", service.receipt(9L, "TEMPLATE_DRAFT", "original-1").get("state"));
    }
    @Test void changedInputSameKeyIsRejectedAndOwnerCannotReadOthersReceipt() {
        service.execute(9L, "ACTIVITY_PUBLISH", "original-2", Map.of("name", "A"), () -> effect(12));
        assertThrows(ServiceException.class, () -> service.execute(9L, "ACTIVITY_PUBLISH", "original-2", Map.of("name", "B"), () -> effect(13)));
        assertEquals(1, effects()); assertEquals("UNKNOWN", service.receipt(10L, "ACTIVITY_PUBLISH", "original-2").get("state"));
        assertThrows(ServiceException.class, () -> service.receipt(0L, "ACTIVITY_PUBLISH", "original-2"));
    }
    @Test void refusalRollsBackBusinessAndReceiptBeforeMarkingRejected() {
        AjaxResult refusal = service.execute(9L, "TEMPLATE_PUBLISH", "original-3", Map.of(), () -> {
            effect(13); return AjaxResult.error("明确拒绝");
        });
        assertEquals("REJECTED", ((Map<?,?>)refusal.get("writeReceipt")).get("state")); assertEquals(0, effects());
        assertEquals("UNKNOWN", service.receipt(9L, "TEMPLATE_PUBLISH", "original-3").get("state"));
        service.execute(9L, "TEMPLATE_PUBLISH", "original-3", Map.of(), () -> effect(13)); assertEquals(1, effects());
    }
    @Test void exceptionRollsBackBothAndConcurrentReplayExecutesOnce() throws Exception {
        assertThrows(IllegalStateException.class, () -> service.execute(9L, "ACTIVITY_UPDATE", "original-4", Map.of(), () -> {
            effect(14); throw new IllegalStateException("lost write");
        })); assertEquals(0, effects());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.execute(9L, "ACTIVITY_UPDATE", "original-4", Map.of(), () -> {
                calls.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return effect(14);
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> service.execute(9L, "ACTIVITY_UPDATE", "original-4", Map.of(), () -> { calls.incrementAndGet(); return effect(15); }));
            release.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, calls.get()); assertEquals(1, effects());
    }
    @Test void replayDeleteReturnsOriginalCountAndPreservesNewChats() {
        effect(21);
        AjaxResult first = service.execute(9L, "NPC_CHAT_DELETE", "original-5", Map.of(),
                () -> AjaxResult.success(Map.of("deleted", jdbc.update("DELETE FROM author_write_effect"))));
        effect(22);
        AjaxResult replay = service.execute(9L, "NPC_CHAT_DELETE", "original-5", Map.of(),
                () -> AjaxResult.success(Map.of("deleted", jdbc.update("DELETE FROM author_write_effect"))));
        assertEquals(first.get("data"), replay.get("data")); assertEquals(1, effects());
    }
}
