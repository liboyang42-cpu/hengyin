package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.AppOriginalRequestMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 原始请求信封在真库台账上的提交/重放/拒绝/读回。 */
@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppOriginalEnvelopeMySqlIT {
    static final long ACTOR = 990201L;
    AppGapsMySql my; AppOriginalEnvelope envelopes;

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql("migration_20261113_appgaps_original_request.sql");
        envelopes = new AppOriginalEnvelope(new AppOriginalRequestLedger(
                my.mapper(AppOriginalRequestMapper.class, "mapper/business/appgaps/AppOriginalRequestMapper.xml"), my.tx));
    }
    @BeforeEach @AfterAll void clean() { my.db.update("delete from app_original_request where actor_member_id=?", ACTOR); }

    @Test void activitySubmitIsOnceAndReadable() {
        Map<String, Object> body = AppOriginalEnvelopeTest.submit(AppOriginalEnvelope.ACTIVITY, "{\"name\":\"夜行\"}", 16);
        var original = AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.ACTIVITY, body);
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> first = envelopes.submit(ACTOR, original, o -> { calls.incrementAndGet(); return AjaxResult.success("活动发布成功", 501L); });
        Map<String, Object> again = envelopes.submit(ACTOR, original, o -> { calls.incrementAndGet(); return AjaxResult.success("活动发布成功", 999L); });
        assertEquals(1, calls.get());
        assertEquals(Map.of("activityId", 501), again.get("originalResult"));
        assertEquals("COMMITTED", first.get("state"));
        Map<String, Object> read = envelopes.read(ACTOR, AppOriginalEnvelope.ACTIVITY,
                Map.of("protocolVersion", AppOriginalEnvelope.ACTIVITY, "requestId", AppOriginalEnvelopeTest.RID, "payloadSHA256", body.get("payloadSHA256")));
        assertEquals("COMMITTED", read.get("state"));
    }

    @Test void templateRejectionIsTerminal() {
        Map<String, Object> body = AppOriginalEnvelopeTest.submit(AppOriginalEnvelope.TEMPLATE, "{\"fields\":{\"title\":\"重名\"},\"originalPath\":\"api/template/publish\"}", 64);
        var original = AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.TEMPLATE, body);
        Map<String, Object> r = envelopes.submit(ACTOR, original, o -> AjaxResult.error("模板名称 重名 已存在"));
        assertEquals("REJECTED", r.get("state"));
        assertEquals("REJECTED", my.db.queryForObject("select state from app_original_request where actor_member_id=? and protocol=?", String.class, ACTOR, AppOriginalEnvelope.TEMPLATE));
    }
}
