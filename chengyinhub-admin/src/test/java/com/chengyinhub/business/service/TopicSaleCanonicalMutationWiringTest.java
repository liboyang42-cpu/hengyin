package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TopicSaleCanonicalMutationWiringTest {

    @Test
    void canonicalLiveAuthoritiesAllInvalidateTheApprovedVersion() throws Exception {
        assertWired("OmsTicketServiceImpl.java", 6);
        // W3f(F05)新增 configureWorkPackage 写口时补了一处失效调用(8→9),棘轮未同步;
        // 2026-09-15 生命周期收口时改正,保持「任一写入口删线必须变红」的本意。
        assertWired("CmsTopicChapterServiceImpl.java", 9);
        assertWired("CmsTopicNodeServiceImpl.java", 6);
        // 普通章节供给退出新增失效入口，计入原有精确删除检测。
        assertWired("MerchantOfferServiceImpl.java", 7);
        assertWired("CoopInviteServiceImpl.java", 5);
        assertWired("CmsTopicServiceImpl.java", 4);

        String xml = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/TopicSaleSnapshotMapper.xml")),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertEquals(2, occurrences(xml, "parameter_version = parameter_version + 1"),
                "版本失效必须单调 +1，不能按值对拍或原值覆盖");
        assertTrue(xml.contains("parameter_version = #{expectedVersion}"),
                "版本递增必须带 CAS 预期版本");
    }

    private static void assertWired(String file, int expectedOccurrences) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/" + file)),
                StandardCharsets.UTF_8);
        assertEquals(expectedOccurrences, occurrences(source, "invalidateApprovedCanonical("),
                file + " 任一真实写入口删线都必须精确变红");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}
