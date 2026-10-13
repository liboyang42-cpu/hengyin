package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantManagedTopicMapperContractTest {

    @Test
    void managedReadRequiresApprovedApplicationAndCurrentActiveOffer() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "CmsTopicMapper.xml")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").toLowerCase();
        int start = xml.indexOf("<select id=\"selectmerchantmanagedcmstopicbyid\"");
        int end = xml.indexOf("</select>", start);
        String query = xml.substring(start, end);

        assertTrue(query.contains("application.merchant_id = #{merchantid}"));
        assertTrue(query.contains("application.status = 1"));
        assertTrue(query.contains("application.del_flag = 0"));
        assertTrue(query.contains("offer.merchant_id = application.merchant_id"));
        assertTrue(query.contains("offer.status = 1"));
        assertTrue(query.contains("offer.valid_end is null or offer.valid_end &gt; now()"));
        // 上面已断言 application.del_flag = 0,所以裸 "del_flag = 0" 子串恒真 ——
        // 主题自身那道闸删掉也照绿。必须连 where 一起断言才守得住。
        assertTrue(query.contains("where id = #{id} and del_flag = 0"),
                "已删除主题不能借履约入口恢复可见");
    }
}
