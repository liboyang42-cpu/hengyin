package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditTaskMapperCasContractTest {
    @Test
    void reviewAndRevertBothNameExpectedStatusAndVersionInWhereClause() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "AuditTaskMapper.xml")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        String review = update(xml, "updateAuditTaskReviewCas");
        String revert = update(xml, "revertAuditTaskDecisionCas");
        assertTrue(review.contains("where id = #{id}")
                && review.contains("and status = #{expectedStatus}")
                && review.contains("and version = #{version}"));
        assertTrue(revert.contains("where id = #{id}")
                && revert.contains("and status = #{expectedStatus}")
                && revert.contains("and version = #{expectedVersion}"));
    }

    private String update(String xml, String id) {
        int start = xml.indexOf("<update id=\"" + id + "\">");
        int end = xml.indexOf("</update>", start);
        return start < 0 || end < 0 ? "" : xml.substring(start, end);
    }
}
