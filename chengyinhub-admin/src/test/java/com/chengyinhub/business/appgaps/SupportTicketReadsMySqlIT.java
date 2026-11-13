package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.SupportTicketReadMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportTicketReadsMySqlIT {
    static final long MEMBER = 990401L, OTHER = 990402L;
    AppGapsMySql my; SupportTicketReads reads;

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql("migration_20261113_appgaps_support_ticket.sql");
        reads = new SupportTicketReads(my.mapper(SupportTicketReadMapper.class, "mapper/business/appgaps/SupportTicketReadMapper.xml"));
    }
    @BeforeEach @AfterAll void clean() {
        my.db.update("delete m from support_ticket_message m join support_ticket t on t.id=m.ticket_id where t.member_id in (?,?)", MEMBER, OTHER);
        my.db.update("delete e from support_ticket_event e join support_ticket t on t.id=e.ticket_id where t.member_id in (?,?)", MEMBER, OTHER);
        my.db.update("delete from support_ticket where member_id in (?,?)", MEMBER, OTHER);
    }
    long ticket(long member, String no) {
        my.db.update("insert into support_ticket(ticket_no,member_id,requester_role,category,subject,status,create_time,update_time,related_source,related_domain_type,related_resource_id)"
                + " values(?,?,'PLAYER','ORDER','退款咨询','OPEN',now(),now(),'cms_registration','REGISTRATION',77)", no, member);
        return my.db.queryForObject("select id from support_ticket where ticket_no=?", Long.class, no);
    }

    @SuppressWarnings("unchecked")
    @Test void ownTicketsMessagesWithoutInternalNotesAndTimeline() {
        long id = ticket(MEMBER, "CS-APPGAPS-1"); ticket(OTHER, "CS-APPGAPS-2");
        my.db.update("insert into support_ticket_message(ticket_id,sender_type,content,attachments_json,is_internal,create_time) values(?,'MEMBER','你好','[3,3,-1]',0,now())", id);
        my.db.update("insert into support_ticket_message(ticket_id,sender_type,content,is_internal,create_time) values(?,'AGENT','内部：疑似刷单',1,now())", id);
        my.db.update("insert into support_ticket_event(ticket_id,event_type,actor_type,result_state,request_id,created_at) values(?,'CREATED','MEMBER','APPLIED','r-1',now())", id);
        Map<String, Object> list = reads.list(MEMBER, Map.of("cursor", 0));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.get("list");
        assertEquals(List.of("CS-APPGAPS-1"), rows.stream().map(r -> r.get("ticket_no")).toList());
        assertEquals("REGISTRATION", ((Map<String, Object>) rows.get(0).get("related_reference")).get("domain_type"));
        assertEquals(String.valueOf(MEMBER), ((Map<String, Object>) rows.get(0).get("related_reference")).get("owner_member_id"));
        Map<String, Object> detail = reads.detail(MEMBER, Map.of("ticket_no", "CS-APPGAPS-1", "cursor", 0, "event_cursor", 0));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) detail.get("messages");
        assertEquals(1, messages.size()); assertEquals(List.of(3L), messages.get(0).get("attachments"));
        assertFalse(messages.get(0).containsKey("is_internal"));
        List<Map<String, Object>> events = (List<Map<String, Object>>) ((Map<String, Object>) detail.get("timeline")).get("events");
        assertEquals("CREATED", events.get(0).get("event_type")); assertEquals("r-1", events.get(0).get("request_id"));
        assertEquals(404, assertThrows(ServiceException.class, () -> reads.detail(MEMBER, Map.of("ticket_no", "CS-APPGAPS-2", "cursor", 0))).getCode());
    }
}
