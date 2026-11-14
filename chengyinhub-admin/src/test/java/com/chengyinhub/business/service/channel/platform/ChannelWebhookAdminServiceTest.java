package com.chengyinhub.business.service.channel.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;

/** /business/im-webhook/deliveries (+ redrive): the DLQ view and the redrive under the member retry rules. */
class ChannelWebhookAdminServiceTest {
    JdbcTemplate jdbc;
    ChannelWebhookMapper hooks;
    ChannelWebhookAdminService service;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:hookadm_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "", true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE im_webhook_subscription (id BIGINT PRIMARY KEY, owner_type VARCHAR(8), owner_id BIGINT, channel_id BIGINT,"
                + " name VARCHAR(64), url VARCHAR(512), url_host VARCHAR(255), status TINYINT, deleted TINYINT)");
        jdbc.execute("CREATE TABLE im_webhook_delivery (id BIGINT PRIMARY KEY, subscription_id BIGINT, event_id VARCHAR(64), event_type VARCHAR(64),"
                + " state VARCHAR(16), attempt INT, reason VARCHAR(255), response_snippet VARCHAR(512), http_status INT, latency_ms BIGINT,"
                + " next_retry_at TIMESTAMP, payload_json TEXT, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO im_webhook_subscription VALUES (7, 'CLUB', 70, 700, 'ops', 'https://hooks.example.com/x?token=secret', 'hooks.example.com', 1, 0)");
        jdbc.update("INSERT INTO im_webhook_subscription VALUES (8, 'CLUB', 80, 800, 'paused', 'https://p.example.com/', 'p.example.com', 0, 0)");
        delivery(1, 7, "SENT", 1);
        delivery(2, 7, "DEAD", 5);
        delivery(3, 7, "FAILED", 2);
        delivery(4, 7, "FAILED", 5);
        delivery(5, 8, "DEAD", 5);
        delivery(6, 7, "DEAD", 5);
        hooks = mock(ChannelWebhookMapper.class);
        service = new ChannelWebhookAdminService(jdbc, hooks);
    }

    private void delivery(long id, long sub, String state, int attempt) {
        jdbc.update("INSERT INTO im_webhook_delivery(id, subscription_id, event_id, event_type, state, attempt, reason, payload_json)"
                + " VALUES (?,?,?,?,?,?,?,?)", id, sub, "evt-" + id, "message.created", state, attempt, "HTTP_500", "{\"a\":1}");
    }

    private Map<String, Object> row(long id) {
        // queryForMap gives a case-insensitive map (H2 upper-cases labels), as MyBatis gives the aliased keys on MySQL.
        return jdbc.queryForMap("SELECT id, subscription_id AS subscriptionId, event_id AS eventId, event_type AS eventType, state,"
                + " attempt, reason, response_snippet AS responseSnippet, http_status AS httpStatus, latency_ms AS latencyMs,"
                + " next_retry_at AS nextRetryAt, payload_json AS payloadJson, create_time AS createTime FROM im_webhook_delivery WHERE id = ?", id);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }

    @Test
    void defaultIsTheDeadLetterQueueNewestFirstWithoutUrlOrPayload() {
        Map<String, Object> page = service.deliveries(null, null, null, null);
        assertEquals(3L, page.get("total"));
        List<Map<String, Object>> list = rows(page);
        assertEquals(List.of("6", "5", "2"), list.stream().map(r -> r.get("id")).toList());
        Map<String, Object> first = list.get(0);
        assertEquals("hooks.example.com", first.get("url_host"));
        assertFalse(first.containsKey("url"));
        assertFalse(first.containsKey("payload_json"));
        assertEquals(true, first.get("retryable"));
        assertEquals(false, list.get(1).get("retryable"), "paused subscription cannot be redriven");
        assertEquals(3L, ((Map<?, ?>) page.get("counts")).get("DEAD"));
        assertEquals(5, page.get("maxRetryAttempt"));
    }

    @Test
    void filtersByStateAndSubscription() {
        assertEquals(6L, service.deliveries("ALL", null, null, null).get("total"));
        assertEquals(2L, service.deliveries("FAILED", 7L, null, null).get("total"));
        assertEquals(1L, service.deliveries("DEAD", 8L, null, null).get("total"));
        assertEquals(400, assertThrows(ChannelRejection.class, () -> service.deliveries("BOGUS", null, null, null)).getStatus());
    }

    @Test
    void redriveOfDeadResetsAttemptThroughTheSameMapperCas() {
        when(hooks.lockDelivery(7L, 2L)).thenReturn(row(2));
        when(hooks.requeueDelivery(2L, "DEAD", 5, true)).thenReturn(1);
        Map<String, Object> after = row(2);
        after.put("state", "PENDING");
        after.put("attempt", 0);
        after.put("reason", "RETRY_REQUESTED");
        when(hooks.selectDelivery(2L)).thenReturn(after);
        Map<String, Object> view = service.redrive(42L, 2L);
        verify(hooks).requeueDelivery(2L, "DEAD", 5, true);
        assertEquals("PENDING", view.get("state"));
        assertEquals("DEAD", view.get("previous_state"));
        assertEquals(42L, view.get("redriven_by"));
    }

    @Test
    void redriveOfFailedUnderTheLimitKeepsAttempt() {
        when(hooks.lockDelivery(7L, 3L)).thenReturn(row(3));
        when(hooks.requeueDelivery(3L, "FAILED", 2, false)).thenReturn(1);
        when(hooks.selectDelivery(3L)).thenReturn(row(3));
        service.redrive(42L, 3L);
        verify(hooks).requeueDelivery(3L, "FAILED", 2, false);
    }

    @Test
    void refusesWhatTheMemberRetryRefuses() {
        when(hooks.lockDelivery(7L, 1L)).thenReturn(row(1));
        when(hooks.lockDelivery(7L, 4L)).thenReturn(row(4));
        assertEquals("DELIVERY_NOT_RETRYABLE", assertThrows(ChannelRejection.class, () -> service.redrive(42L, 1L)).getErrorCode());
        assertEquals("DELIVERY_NOT_RETRYABLE", assertThrows(ChannelRejection.class, () -> service.redrive(42L, 4L)).getErrorCode());
        assertEquals("WEBHOOK_PAUSED", assertThrows(ChannelRejection.class, () -> service.redrive(42L, 5L)).getErrorCode());
        assertEquals(404, assertThrows(ChannelRejection.class, () -> service.redrive(42L, 99L)).getStatus());
        assertEquals(401, assertThrows(ChannelRejection.class, () -> service.redrive(0L, 2L)).getStatus());
        verify(hooks, never()).requeueDelivery(anyLong(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void lostCasIsAConflict() {
        when(hooks.lockDelivery(7L, 6L)).thenReturn(row(6));
        when(hooks.requeueDelivery(6L, "DEAD", 5, true)).thenReturn(0);
        assertEquals(409, assertThrows(ChannelRejection.class, () -> service.redrive(42L, 6L)).getStatus());
    }

    @Test
    void retryableMirrorsTheMemberRule() {
        assertTrue(ChannelWebhookAdminService.retryable("DEAD", 9));
        assertTrue(ChannelWebhookAdminService.retryable("UNKNOWN", 4));
        assertFalse(ChannelWebhookAdminService.retryable("UNKNOWN", 5));
        assertFalse(ChannelWebhookAdminService.retryable("PENDING", 0));
        assertFalse(ChannelWebhookAdminService.retryable("SENT", 1));
    }
}
