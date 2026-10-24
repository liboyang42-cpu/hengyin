package com.chengyinhub.business.mapper;

import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WechatVerifiedPaymentFactMapperXmlTest {
    @Test void everyFactMapperStatementParsesAndNeitherIdentityCanBeUpdatedOrDeleted() throws Exception {
        Configuration config=new Configuration();String resource="mapper/business/WechatVerifiedPaymentFactMapper.xml";
        try(InputStream in=Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse();
        }
        String prefix=WechatVerifiedPaymentFactMapper.class.getName()+".";
        for(String name:new String[]{"insertIgnore","selectMatching","selectById","insertRejection","insertInboxLink","selectInboxLink"})
            assertTrue(config.hasStatement(prefix+name),name);
        String read=config.getMappedStatement(prefix+"selectMatching").getBoundSql(
                Map.of("kind","PAYMENT","mchid","merchant","providerId","TX1","eventNo","REG1")).getSql();
        assertTrue(read.contains("provider_id=") && read.contains("merchant_event_no="));
        assertFalse(read.toLowerCase().contains("for update"),"Immutable replay must not upgrade duplicate INSERT shared locks");
        String link=config.getMappedStatement(prefix+"selectInboxLink").getBoundSql(Map.of("notifyHash","a".repeat(64))).getSql();
        assertTrue(link.contains("FOR SHARE"));assertTrue(link.contains("LOWER(") && link.contains("UPPER("));
        assertFalse(config.getMappedStatement(prefix+"selectInboxLink").isUseCache());
        assertTrue(config.getMappedStatement(prefix+"selectInboxLink").isFlushCacheRequired());
        assertEquals(6,java.util.Arrays.stream(WechatVerifiedPaymentFactMapper.class.getDeclaredMethods()).count());
    }
}
