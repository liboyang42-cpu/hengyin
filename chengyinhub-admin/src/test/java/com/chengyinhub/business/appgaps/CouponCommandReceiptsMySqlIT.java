package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.AppOriginalRequestMapper;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 优惠券命令回执真库 IT：真 sms_coupon 插入/停发 CAS 与真台账同事务；未知异常连同券一起回滚、不留回执。 */
@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponCommandReceiptsMySqlIT {
    static final long OWNER = 990301L, ACTOR = 990302L, MERCHANT = 990303L;
    AppGapsMySql my; SmsCouponMapper coupons; CouponCommandReceipts receipts;

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql("migration_20261113_appgaps_original_request.sql");
        coupons = my.mapper(SmsCouponMapper.class, "mapper/business/SmsCouponMapper.xml");
        receipts = new CouponCommandReceipts(new AppOriginalRequestLedger(
                my.mapper(AppOriginalRequestMapper.class, "mapper/business/appgaps/AppOriginalRequestMapper.xml"), my.tx));
    }
    @BeforeEach @AfterAll void clean() {
        my.db.update("delete from sms_coupon where member_id=?", OWNER);
        my.db.update("delete from app_original_request where actor_member_id=?", ACTOR);
    }

    SmsCoupon coupon(String name) {
        SmsCoupon c = new SmsCoupon();
        c.setMemberId(OWNER); c.setScopeType(1); c.setMerchantId(MERCHANT); c.setStatus(1); c.setDelFlag(0); c.setName(name);
        c.setCouponType(1); c.setPlatform(0); c.setStartTime(new Date(1767196800000L)); c.setEndTime(new Date(1767283199000L));
        c.setPublishCount(10L); c.setUseCount(0L); c.setReceiveCount(0L);
        return c;
    }

    @SuppressWarnings("unchecked")
    @Test void publishThenStopThenRepeatStopProduceTerminalReceipts() {
        String publishId = UUID.randomUUID().toString();
        String hash = CouponCommandReceipts.publishHash(MERCHANT, OWNER, "真库券", null, 1, 10, 1767196800000L, 1767283199000L);
        AtomicInteger runs = new AtomicInteger();
        java.util.function.Supplier<CouponCommandReceipts.Outcome> publish = () -> {
            runs.incrementAndGet();
            SmsCoupon c = coupon("真库券");
            assertEquals(1, coupons.insertSmsCoupon(c));
            return new CouponCommandReceipts.Outcome(true, "PUBLISHED", c.getId(), c.getStatus(), "发布成功");
        };
        AjaxResult first = receipts.execute(ACTOR, publishId, "PUBLISH", MERCHANT, OWNER, hash, publish);
        receipts.execute(ACTOR, publishId, "PUBLISH", MERCHANT, OWNER, hash, publish);
        assertEquals(1, runs.get());
        assertEquals(1, my.db.queryForObject("select count(*) from sms_coupon where member_id=?", Integer.class, OWNER));
        long id = (Long) ((Map<String, Object>) first.get("data")).get("id");
        AjaxResult read = receipts.read(ACTOR, MERCHANT, publishId);
        assertEquals(id, ((Map<String, Object>) read.get("data")).get("id"));
        assertEquals("SUCCEEDED", ((Map<String, Object>) ((Map<String, Object>) read.get("data")).get("commandReceipt")).get("outcome"));

        String stopId = UUID.randomUUID().toString(), stopHash = CouponCommandReceipts.stopHash(MERCHANT, OWNER, id);
        AjaxResult stopped = receipts.execute(ACTOR, stopId, "STOP", MERCHANT, OWNER, stopHash, () ->
                coupons.stopIssuingCoupon(id, OWNER) == 1 ? new CouponCommandReceipts.Outcome(true, "STOPPED", id, 4, "已停发")
                        : CouponCommandReceipts.Outcome.rejected("STOP_CAS_NOT_APPLIED", id, coupons.selectSmsCouponById(id).getStatus(), "停发失败"));
        assertEquals(200, stopped.get("code"));
        assertEquals(4, my.db.queryForObject("select status from sms_coupon where id=?", Integer.class, id));

        String againId = UUID.randomUUID().toString();
        AjaxResult again = receipts.execute(ACTOR, againId, "STOP", MERCHANT, OWNER, stopHash, () ->
                coupons.stopIssuingCoupon(id, OWNER) == 1 ? new CouponCommandReceipts.Outcome(true, "STOPPED", id, 4, "已停发")
                        : CouponCommandReceipts.Outcome.rejected("STOP_CAS_NOT_APPLIED", id, coupons.selectSmsCouponById(id).getStatus(), "停发失败"));
        assertEquals(500, again.get("code"));
        Map<String, Object> r = (Map<String, Object>) ((Map<String, Object>) receipts.read(ACTOR, MERCHANT, againId).get("data")).get("commandReceipt");
        assertEquals("STOP_CAS_NOT_APPLIED", r.get("reasonCode")); assertEquals(4, r.get("couponStatusAtExecution"));
    }

    @Test void unknownFailureRollsBackCouponAndLeavesNoReceipt() {
        String rid = UUID.randomUUID().toString();
        String hash = CouponCommandReceipts.publishHash(MERCHANT, OWNER, "回滚券", null, 1, 10, 1767196800000L, 1767283199000L);
        assertThrows(IllegalStateException.class, () -> receipts.execute(ACTOR, rid, "PUBLISH", MERCHANT, OWNER, hash, () -> {
            coupons.insertSmsCoupon(coupon("回滚券"));
            throw new IllegalStateException("网络中断");
        }));
        assertEquals(0, my.db.queryForObject("select count(*) from sms_coupon where member_id=?", Integer.class, OWNER));
        assertTrue(((Map<?, ?>) receipts.read(ACTOR, MERCHANT, rid).get("data")).isEmpty(), "无回执 → App 视为未知");
    }
}
