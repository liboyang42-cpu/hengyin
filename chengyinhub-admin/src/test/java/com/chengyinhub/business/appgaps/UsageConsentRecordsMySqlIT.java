package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.UsageConsentMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsageConsentRecordsMySqlIT {
    static final long MEMBER = 990301L, OTHER = 990302L;
    AppGapsMySql my; UsageConsentRecords records;

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql();
        records = new UsageConsentRecords(my.mapper(UsageConsentMapper.class, "mapper/business/appgaps/UsageConsentMapper.xml"));
    }
    @BeforeEach @AfterAll void clean() {
        my.db.update("delete from consent_record where member_id in (?,?)", MEMBER, OTHER);
        my.db.update("delete from agreement_doc where version like 'appgaps-%'");
    }
    void consent(long member, String docType, String version, String scene, String event, String rid) {
        my.db.update("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(?,?,?,?,?,now(),?,now())",
                member, docType, version, scene, event, rid);
    }

    @SuppressWarnings("unchecked")
    @Test void onlyOwnAppLaunchRecordsWithExactDocument() {
        consent(MEMBER, "user_service_agreement", "appgaps-1", "app_launch", "AGREE", "appgaps-r1");
        consent(MEMBER, "user_service_agreement", "appgaps-1", "roam_location", "AGREE", "appgaps-r2");
        consent(OTHER, "user_service_agreement", "appgaps-1", "app_launch", "AGREE", "appgaps-r3");
        consent(MEMBER, "user_service_agreement", "appgaps-2", "app_launch", "REVOKE", "appgaps-r4");
        my.db.update("insert into agreement_doc(doc_type,version,title,content,status,publish_time) values('user_service_agreement','appgaps-1','隐私政策','正文',1,now())");
        Map<String, Object> page = records.history(MEMBER, null);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertEquals(List.of("appgaps-r4", "appgaps-r1"), items.stream().map(i -> i.get("requestId")).toList());
        assertNull(page.get("nextBeforeId"));
        Map<String, Object> original = records.original(MEMBER, "appgaps-r1");
        assertEquals("SOURCE_AVAILABLE", ((Map<String, Object>) original.get("document")).get("availability"));
        assertEquals(items.get(0).get("id"), original.get("latestRecordId"));
        assertEquals("MISSING", ((Map<String, Object>) records.original(MEMBER, "appgaps-r4").get("document")).get("availability"));
        assertThrows(Exception.class, () -> records.original(MEMBER, "appgaps-r3"), "他人留痕不可读");
    }
}
