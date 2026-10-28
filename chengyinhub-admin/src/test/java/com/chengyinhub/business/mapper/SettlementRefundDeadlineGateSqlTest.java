package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-09-16 用户硬规则「收款方的钱,等所有玩家都确定退不了才可提现」的无库契约。
 * 结算即入余额(可提现),所以两条结算选单都必须带「仍有可退票就不结」的条件。
 * 行为本身由 TandianriBatch0FundsMybatisIntegrationTest 真库验证;这里只防普通 CI 里被悄悄删掉。
 */
class SettlementRefundDeadlineGateSqlTest {

    @Test
    void activitySettlementSelectionSkipsActivitiesWithRefundableTickets() throws Exception {
        String sql = select("mapper/business/CmsActivityMapper.xml", "selectActivitiesForSettlement");
        assertTrue(sql.contains("not exists ( select 1 from cms_registration r where r.owner_type = 2 and r.owner_id = activity.id"), sql);
        assertTrue(sql.contains("(r.verification_status is null or r.verification_status <> 1)"), sql);
        assertTrue(sql.contains("coalesce(r.refund_deadline, date_sub(activity.start_date, interval 24 hour)) > now()"), sql);
    }

    /** 2026-09-16 晚拍板第 1 条:客诉期 = 活动结束后 7 天,满 7 天才结算入余额(真库见 ComplaintPeriodWithdrawalGateMybatisIntegrationTest)。 */
    @Test
    void activitySettlementSelectionWaitsForTheSevenDayComplaintPeriod() throws Exception {
        String sql = select("mapper/business/CmsActivityMapper.xml", "selectActivitiesForSettlement");
        assertTrue(sql.contains("activity.end_date <= date_sub(now(), interval 7 day)"), sql);
    }

    @Test
    void topicSettlementSelectionSkipsTopicsWithRefundableDirectTickets() throws Exception {
        String sql = select("mapper/business/CmsTopicMapper.xml", "selectTopicsForSettlement");
        assertTrue(sql.contains("not exists ( select 1 from cms_registration r left join oms_ticket t on t.id = r.ticket_id where r.owner_type = 1 and r.owner_id = cms_topic.id"), sql);
        assertTrue(sql.contains("(r.verification_status is null or r.verification_status <> 1)"), sql);
        assertTrue(sql.contains("coalesce(r.refund_deadline, case when t.mode = 2 then t.end_time else t.start_time end) > now()"), sql);
    }


    @Test
    void topicSettlementClaimRechecksAllScanEligibilityAtItsAtomicWrite() throws Exception {
        String claim = select("mapper/business/CmsTopicMapper.xml", "claimTopicForSettlement");
        for (String predicate : new String[] {"del_flag = 0", "status = 1", "is_settlement = 0",
                "end_date <= date_sub(now(), interval 12 hour)",
                "(product_type is null or product_type <> 2)", "not exists (",
                "r.payment_status = 2", "r.registration_status = 2",
                "coalesce(r.refund_deadline, case when t.mode = 2 then t.end_time else t.start_time end) > now()"}) {
            assertTrue(claim.contains(predicate), claim);
        }
    }

    private static String select(String resource, String id) throws Exception {
        String xml;
        try (InputStream in = SettlementRefundDeadlineGateSqlTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            xml = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("<(?:select|update) id=\"" + id + "\".*?</(?:select|update)>", Pattern.DOTALL).matcher(xml);
        assertTrue(m.find(), "找不到 " + id);
        String statement = m.group();
        String include = "<include refid=\"topicSettlementEligibility\"/>";
        if (statement.contains(include)) {
            Matcher predicate = Pattern.compile("<sql id=\"topicSettlementEligibility\">(.*?)</sql>",
                    Pattern.DOTALL).matcher(xml);
            assertTrue(predicate.find(), "找不到共享结算资格");
            statement = statement.replace(include, predicate.group(1));
        }
        return statement.replaceAll("\\s+", " ").toLowerCase();
    }
}
