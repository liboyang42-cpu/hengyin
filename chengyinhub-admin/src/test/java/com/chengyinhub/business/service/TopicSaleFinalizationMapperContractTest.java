package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL 合同：CAS 成功不能只改 lifecycle 而漏写首次实际开售时刻。 */
class TopicSaleFinalizationMapperContractTest {

    @Test
    void casWritesLifecycleAndSellingStartedAtInOneStatement() throws Exception {
        String xml = WiringSource.read("chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml")
                .replaceAll("\\s+", "");
        int start = xml.indexOf("<updateid=\"finalizeForSale\">");
        int end = xml.indexOf("</update>", start);
        String statement = xml.substring(start, end);

        assertTrue(statement.contains("setlifecycle=3,selling_started_at=#{sellingStartedAt}"),
                "finalize CAS 必须和 sellingStartedAt 同条 SQL，不能先开售再补时刻");
        assertTrue(statement.contains("andlifecycle=2andselling_started_atisnull"),
                "CAS 必须同时守住 PRICING 前态与首次写入，重试不得覆盖既有时刻");
    }
}
