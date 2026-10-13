package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 资金 SQL 口径静态负控；真实 SQL 绑定另由点名 MySQL IT 与 H2 seam 覆盖。 */
class TopicEditionFundsMapperContractTest {
    private String sql() throws Exception {
        return new String(Files.readAllBytes(Paths.get("../chengyinhub-system/src/main/resources/mapper/business/TopicEditionFundsMapper.xml")),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    @Test
    void firstThreeEditionsKeepUOutEqualToPendingAndReleaseClosedNoShow() throws Exception {
        String source = sql();
        assertEquals(2, occurrences(source, "r.refund_deadline &gt; #{asOf}"),
                "u_out 与 u_pending 必须共用退款窗/未结投诉口径，首三期 transfer_out=0");
        assertEquals(2, occurrences(source, "cc.status &lt; 3 and cc.del_flag = 0"));
        assertFalse(source.contains("as u_out, coalesce(sum(case when r.purchase_kind = #{purchaseKind} and r.refund_deadline"),
                "u_out 不得退回永久挂住所有未核销 entitlement 的旧方向");
    }

    @Test
    void repairOnlyClassifiesPaidActivityShellRowsWithTicketsUsingNamedConstants() throws Exception {
        String source = sql();
        int start = source.indexOf("<update id=\"classifyUnknownExplorePurchases\">");
        int end = source.indexOf("</update>", start);
        String repair = source.substring(start, end);
        assertTrue(repair.contains("join cms_activity a on r.owner_type = 2"));
        assertTrue(repair.contains("r.ticket_id is not null"));
        assertTrue(repair.contains("t.product_type = #{productType}"));
        assertFalse(repair.contains("r.owner_type in (1, 2)"));
        assertFalse(repair.contains("t.product_type = 2"));
    }

    @Test
    void finalAttributionUsesPaidTicketsAndTheSameSettledRefundAmount() throws Exception {
        String source = sql();
        int start = source.indexOf("<select id=\"selectFinalAttributionFacts\"");
        int end = source.indexOf("</select>", start);
        String attribution = source.substring(start, end);
        assertTrue(attribution.contains("r.payment_status = 2"), "未支付票不得进入 FINAL 归因");
        assertTrue(attribution.contains("r.purchase_kind = #{purchaseKind}"));
        assertTrue(attribution.contains("payout_status in (1, 3, 4)"));
        assertTrue(attribution.contains("greatest(r.wechat_payment_amount - coalesce(rr.refund_amount, 0), 0)"),
                "退款后的净确认金额才是逐票 E2 基数");
        assertTrue(attribution.contains("r.source_frozen_at is null"), "未冻结票必须让 FINAL fail closed");
    }

    private int occurrences(String source, String needle) {
        int count = 0;
        for (int at = 0; (at = source.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }
}
