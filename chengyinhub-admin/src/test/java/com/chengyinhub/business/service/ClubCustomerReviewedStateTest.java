package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import com.chengyinhub.business.service.impl.ClubCrmAppServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * K3 稿画了四步履约轨迹,而「已评价」这一档此前在代码里根本不存在:
 * statusCode 只产出四种、railStep 最大取 2,前端 RAIL 的第四格永远灰着 —— 一个装饰性的死节点。
 *
 * <p>这里钉三件:①有可见评价就判「已评价」;②它排在「已核销」之后而不是取代它
 * (核销计数与「已核销」筛选都不能因此掉人);③没有评价时口径一个字不变。
 */
class ClubCustomerReviewedStateTest {

    private static String statusCode(ClubCustomerVO.RawTopicRow raw) {
        return (String) ReflectionTestUtils.invokeMethod(
                ClubCrmAppServiceImpl.class, "topicStatusCode", raw);
    }

    private static String statusText(String code) {
        return (String) ReflectionTestUtils.invokeMethod(
                ClubCrmAppServiceImpl.class, "topicStatusText", code);
    }

    private ClubCustomerVO.RawTopicRow verifiedRow() {
        ClubCustomerVO.RawTopicRow raw = new ClubCustomerVO.RawTopicRow();
        raw.setPaymentStatus(2);
        raw.setVerificationStatus(1);
        raw.setVerificationTime(new Date());
        return raw;
    }

    @Test
    void aVisibleReviewMovesTheRowToReviewed() {
        ClubCustomerVO.RawTopicRow raw = verifiedRow();
        raw.setReviewTime(new Date());

        assertEquals("REVIEWED", statusCode(raw), "★评价事实在 merchant_review 上,不是新造的状态位");
        assertEquals("已评价", statusText("REVIEWED"));
    }

    @Test
    void withoutAReviewNothingChanges() {
        assertEquals("VERIFIED", statusCode(verifiedRow()), "没评价就还是已核销,口径一个字不变");
    }

    @Test
    void refundTrumpsReview() {
        ClubCustomerVO.RawTopicRow refunded = verifiedRow();
        refunded.setPaymentStatus(4);
        refunded.setReviewTime(new Date());
        assertEquals("REFUNDED", statusCode(refunded), "退了款就是退了款,评价过也一样");
    }

    @Test
    void reviewedStillCountsAsVerifiedForCountsAndFilter() {
        boolean counted = (Boolean) ReflectionTestUtils.invokeMethod(
                ClubCrmAppServiceImpl.class, "isVerifiedOrLater", "REVIEWED");
        assertTrue(counted, "★核销数不能因为客户评了价就掉一个");
        boolean kept = (Boolean) ReflectionTestUtils.invokeMethod(
                ClubCrmAppServiceImpl.class, "matchesTopicFilter", "VERIFIED", "REVIEWED");
        assertTrue(kept, "★选「已核销」时,评过价的人不能被筛没了");
    }

    /**
     * ★列表算出「已评价」而详情算不出来,是两套判据 —— 而 K3 稿真正画那条四步轨迹的
     * 恰恰是详情页。2026-09-11 第一版就漏在这:probe 只喂了付款与核销两项。
     */
    @Test
    void checkinDetailFeedsTheSameFieldsAsTheList() throws Exception {
        String service = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubCrmAppServiceImpl.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        int probeAt = service.indexOf("ClubCustomerVO.RawTopicRow probe = new ClubCustomerVO.RawTopicRow();");
        org.junit.jupiter.api.Assertions.assertTrue(probeAt > 0, "找不到详情页的 probe");
        String probeBlock = service.substring(probeAt, service.indexOf("String statusCode = topicStatusCode(probe);", probeAt));
        org.junit.jupiter.api.Assertions.assertTrue(probeBlock.contains("probe.setReviewTime(raw.getReviewTime());"),
                "★probe 少喂 reviewTime,railStep=3 就是死分支,第四格永远亮不起来");

        String mapper = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/ClubCrmMapper.xml")),
                java.nio.charset.StandardCharsets.UTF_8);
        int detailAt = mapper.indexOf("<select id=\"selectCheckinDetail\"");
        org.junit.jupiter.api.Assertions.assertTrue(detailAt > 0);
        String detailSql = mapper.substring(detailAt, mapper.indexOf("</select>", detailAt));
        org.junit.jupiter.api.Assertions.assertTrue(detailSql.contains("as review_time"),
                "详情 SQL 不查 review_time,喂进 probe 的永远是 null");
    }
}
