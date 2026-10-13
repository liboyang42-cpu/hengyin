package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

class RoamQuotaCurrentReadContractTest {
    @Test void quotaPreservesFilterAndUsesCurrentReadAfterMerchantLock() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("../chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml")), StandardCharsets.UTF_8);
        int start = xml.indexOf("<select id=\"countActiveNodesByMerchantForUpdate\"");
        assertTrue(start >= 0);
        String statement = xml.substring(start, xml.indexOf("</select>", start)).replaceAll("\\s+", " ");
        assertTrue(statement.contains("type = 2 and merchant_id = #{merchantId} and status = 1"));
        assertTrue(statement.contains("for update")); assertTrue(statement.contains("useCache=\"false\""));
        String source = new String(Files.readAllBytes(Paths.get("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RoamPoiApplicationServiceImpl.java")), StandardCharsets.UTF_8);
        assertTrue(source.contains("countActiveNodesByMerchantForUpdate(merchant.getId())"));
        assertFalse(source.contains("countActiveNodesByMerchant(merchant.getId())"));
    }
}
