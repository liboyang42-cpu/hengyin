package com.chengyinhub.business.service.ai.npc;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.impl.BusinessAuditLogQueryServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class MerchantNpcGenericCreationAndLogTest {
    static final String BIZ=MerchantNpcKnowledgeDraftService.BIZ_TYPE, SOURCE=MerchantNpcKnowledgeDraftService.SOURCE_TYPE;
    @Test void creationRejectsEitherMarkerBeforeReuseRateLimitOrWrite() {
        MerchantNpcGenericAuditBoundaryTest fixture=new MerchantNpcGenericAuditBoundaryTest();
        for(String[] pair:new String[][]{{BIZ,SOURCE},{BIZ,null},{BIZ,"report"},{null,SOURCE},{"OTHER",SOURCE}}) {
            AuditTaskMapper tasks=mock(AuditTaskMapper.class);AuditRecordMapper records=mock(AuditRecordMapper.class);BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
            AuditTaskServiceImpl s=fixture.service(tasks,records,events);
            assertEquals(403,assertThrows(ServiceException.class,()->s.submit(pair[0],10L,20L,pair[1],"SECRET")).getCode());
            // Exercise the shared private creation path with source-only identity as well.
            assertEquals(403,assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(s,"findOrCreateOpenTask",pair[0],10L,20L,pair[1],"NORMAL","SECRET")).getCode());
            verifyNoInteractions(tasks,records,events);
        }
    }
    @Test void npcReportAndSafetyHoldCannotReuseOrInsertGenericTask() {
        MerchantNpcGenericAuditBoundaryTest fixture=new MerchantNpcGenericAuditBoundaryTest();
        AuditTaskMapper tasks=mock(AuditTaskMapper.class);AuditRecordMapper records=mock(AuditRecordMapper.class);BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
        AuditTaskServiceImpl s=fixture.service(tasks,records,events);
        assertEquals(403,assertThrows(ServiceException.class,()->s.reportContent(BIZ,10L,20L,"SECRET")).getCode());
        assertEquals(403,assertThrows(ServiceException.class,()->s.submitSafetyHold(BIZ,10L,20L,"NORMAL","SECRET")).getCode());
        verifyNoInteractions(tasks,records,events);
    }
    @Test void unrelatedGenericCreationStillWritesExactlyOnce() {
        MerchantNpcGenericAuditBoundaryTest fixture=new MerchantNpcGenericAuditBoundaryTest();
        for(String[] pair:new String[][]{{"TOPIC","report"},{"OTHER",null},{null,null}}) {
            AuditTaskMapper tasks=mock(AuditTaskMapper.class);AuditRecordMapper records=mock(AuditRecordMapper.class);BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
            fixture.service(tasks,records,events).submit(pair[0],10L,20L,pair[1],"SAFE");verify(tasks).insertAuditTask(any());verifyNoMoreInteractions(tasks);verifyNoInteractions(records,events);
        }
    }

    static class Logs {
        final MerchantNpcKnowledgeDraftMybatisTest fixture=new MerchantNpcKnowledgeDraftMybatisTest();
        final BusinessAuditLogQueryServiceImpl service=new BusinessAuditLogQueryServiceImpl();
        Logs() throws Exception {fixture.setup();ReflectionTestUtils.setField(service,"businessAuditEventMapper",fixture.events);}
        long task(String biz,String source) {
            AuditTask t=new AuditTask();t.setBizType(biz);t.setSourceType(source);t.setStatus(0);t.setVersion(0);t.setSubmittedAt(new Date());fixture.tasks.insertAuditTask(t);return t.getId();
        }
        BusinessAuditEvent event(String target,long targetId,String domain,long domainId,String action,String text) {
            BusinessAuditEvent e=new BusinessAuditEvent();e.setDomainType(domain);e.setDomainId(domainId);e.setTargetType(target);e.setTargetId(targetId);e.setAction(action);
            e.setOwnerMemberId(20L);e.setActorMemberId(700L);e.setActorType("SYS_USER");e.setActorRoleCode("admin");e.setReason(text);e.setBeforeJson("{\"text\":\""+text+"\"}");e.setAfterJson(e.getBeforeJson());e.setRequestId(UUID.randomUUID().toString());e.setCreateTime(new Date());fixture.events.insertBusinessAuditEvent(e);return e;
        }
        BusinessAuditLogPageVO page(int limit,Long cursor) {BusinessAuditLogQueryDTO q=new BusinessAuditLogQueryDTO();q.setLimit(limit);q.setCursor(cursor);q.setFetchLimit(999999);return service.query(q,true);}
    }
    @Test void logQueryExcludesNpcBeforeLimitAndCursorAccounting() throws Exception {
        Logs l=new Logs();long both=l.task(BIZ,SOURCE),bizOnly=l.task(BIZ,null),sourceOnly=l.task("OTHER",SOURCE);
        BusinessAuditEvent older=l.event("OTHER",both,"MERCHANT",both,"PROFILE_UPDATE","SAFE_OLDER");
        l.event(BIZ,10,"MERCHANT",10,"OTHER", "SECRET_TARGET");
        l.event(null,10,"MERCHANT",10,"NPC_KNOWLEDGE_DRAFT_SAVE","SECRET_ACTION_SAVE");
        BusinessAuditEvent newer=l.event("OTHER",both,"PLATFORM",both,"PROFILE_UPDATE","SAFE_NEWER");
        l.event("OTHER",10,"MERCHANT",10,"NPC_KNOWLEDGE_DRAFT_SUBMIT","SECRET_ACTION_SUBMIT");
        l.event("AUDIT_TASK",both,"MERCHANT",999,"OTHER","SECRET_TASK_TARGET");
        l.event("OTHER",999,"PLATFORM",bizOnly,"AUDIT_DECISION_APPROVE","SECRET_TASK_DOMAIN");
        l.event("AUDIT_TASK",sourceOnly,"PLATFORM",999,"AUDIT_DECISION_REVERTED","SECRET_SOURCE_ONLY");
        BusinessAuditLogPageVO first=l.page(1,null);assertEquals(1,first.getItems().size());assertEquals(newer.getId(),first.getItems().get(0).getId());assertEquals(newer.getId(),first.getNextCursor());
        BusinessAuditLogPageVO second=l.page(1,first.getNextCursor());assertEquals(1,second.getItems().size());assertEquals(older.getId(),second.getItems().get(0).getId());assertNull(second.getNextCursor());
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(l.page(100,null));assertFalse(json.contains("SECRET"));assertTrue(json.contains("SAFE_OLDER"));assertTrue(json.contains("SAFE_NEWER"));
        assertEquals(8,l.fixture.jdbc.queryForObject("select count(*) from business_audit_event",Integer.class));
    }
    @Test void typedIdentityDoesNotHideCollidingUnrelatedOrUnresolvableEvents() throws Exception {
        Logs l=new Logs();long npc=l.task(BIZ,SOURCE);long safe=l.task("OTHER",null);
        List<Long> expected=new ArrayList<>();
        expected.add(l.event("OTHER",npc,"MERCHANT",npc,"AUDIT_DECISION_APPROVE","SAFE_DIFFERENT_DOMAIN").getId());
        expected.add(l.event("OTHER",npc,"PLATFORM",npc,"PROFILE_UPDATE","SAFE_DIFFERENT_ACTION").getId());
        expected.add(l.event(null,npc,null,npc,null,"SAFE_NULL_MARKERS").getId());
        expected.add(l.event("AUDIT_TASK",safe,"PLATFORM",safe,"AUDIT_DECISION_REJECT","SAFE_TASK").getId());
        // There is no NPC marker to resolve this orphan. Do not pretend numeric IDs prove type.
        expected.add(l.event("AUDIT_TASK",99999,"PLATFORM",99999,"AUDIT_DECISION_REJECT","SAFE_UNRESOLVABLE_ORPHAN").getId());
        Collections.reverse(expected);List<Long> actual=new ArrayList<>();for(BusinessAuditEvent e:l.page(100,null).getItems())actual.add(e.getId());assertEquals(expected,actual);
    }
    @Test void exactNpcSearchesReturnNoRowsOrCursorButLedgerReplayIsUnchanged() throws Exception {
        Logs l=new Logs();long task=l.task("OTHER",SOURCE);
        BusinessAuditEvent saved=l.event(BIZ,10,"MERCHANT",10,"NPC_KNOWLEDGE_DRAFT_SAVE","SECRET");
        l.event("AUDIT_TASK",task,"PLATFORM",task,"AUDIT_DECISION_REVERTED","SECRET_REPLAY");
        BusinessAuditLogQueryDTO byType=new BusinessAuditLogQueryDTO();byType.setTargetType(BIZ);byType.setTargetId(10L);
        BusinessAuditLogQueryDTO byAction=new BusinessAuditLogQueryDTO();byAction.setAction("NPC_KNOWLEDGE_DRAFT_SAVE");
        BusinessAuditLogQueryDTO byTask=new BusinessAuditLogQueryDTO();byTask.setTargetType("AUDIT_TASK");byTask.setTargetId(task);
        BusinessAuditLogQueryDTO byDomain=new BusinessAuditLogQueryDTO();byDomain.setDomainType("PLATFORM");byDomain.setDomainId(task);
        for(BusinessAuditLogQueryDTO q:Arrays.asList(byType,byAction,byTask,byDomain,new BusinessAuditLogQueryDTO())) {q.setLimit(1);BusinessAuditLogPageVO result=l.service.query(q,true);assertTrue(result.getItems().isEmpty());assertNull(result.getNextCursor());}
        BusinessAuditEvent internal=l.fixture.events.selectByDomainActionRequestForUpdate("MERCHANT",10L,"NPC_KNOWLEDGE_DRAFT_SAVE",saved.getRequestId());assertNotNull(internal);assertEquals(saved.getId(),internal.getId());
    }
    @Test void independentLogControllerKeepsActualAdminPermissionAndNpcPrivacy() throws Exception {
        Logs logs=new Logs();long task=logs.task(BIZ,SOURCE);
        logs.event(BIZ,10,"MERCHANT",10,"NPC_KNOWLEDGE_DRAFT_SAVE","SECRET_OWNER_DRAFT");
        logs.event("AUDIT_TASK",task,"PLATFORM",task,"AUDIT_DECISION_REVERTED","SECRET_REPLAY");
        logs.event("OTHER",task,"MERCHANT",task,"PROFILE_UPDATE","SAFE_EVENT");
        org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.register(MerchantNpcGenericAuditControllerTest.SecurityConfig.class);
        context.getBeanFactory().registerSingleton("ss",new com.chengyinhub.framework.web.service.PermissionService());
        context.getBeanFactory().registerSingleton("businessAuditLogQueryService",logs.service);
        context.registerBean("logController",com.chengyinhub.web.controller.business.BusinessAuditLogController.class);
        try {
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
            context.refresh();com.chengyinhub.web.controller.business.BusinessAuditLogController controller=context.getBean(com.chengyinhub.web.controller.business.BusinessAuditLogController.class);
            com.chengyinhub.common.core.domain.model.LoginUser login=new com.chengyinhub.common.core.domain.model.LoginUser();
            com.chengyinhub.common.core.domain.entity.SysUser user=new com.chengyinhub.common.core.domain.entity.SysUser();user.setUserId(1L);login.setUser(user);login.setUserId(1L);
            com.chengyinhub.common.core.domain.entity.SysRole role=new com.chengyinhub.common.core.domain.entity.SysRole();role.setRoleKey("admin");user.setRoles(Arrays.asList(role));
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login,null,Collections.emptyList()));
            login.setPermissions(Collections.emptySet());assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.list(new BusinessAuditLogQueryDTO()));
            login.setPermissions(new HashSet<>(Arrays.asList("business:audit:log:list")));role.setRoleKey("reviewer");assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.list(new BusinessAuditLogQueryDTO()));
            role.setRoleKey("admin");user.setUserId(2L);login.setUserId(2L);assertEquals(403,controller.list(new BusinessAuditLogQueryDTO()).get("code"));
            user.setUserId(1L);login.setUserId(1L);com.chengyinhub.common.core.domain.AjaxResult result=controller.list(new BusinessAuditLogQueryDTO());assertEquals(200,result.get("code"));
            String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);assertTrue(json.contains("SAFE_EVENT"));assertFalse(json.contains("SECRET"));
            BusinessAuditLogPageVO page=(BusinessAuditLogPageVO)result.get("data");assertEquals(1,page.getItems().size());assertNull(page.getNextCursor());
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();context.close();}
    }

    @Test void everyExistingDecisionActionUsesTypedDomainBindingOnly() throws Exception {
        Logs l=new Logs();long npc=l.task(null,SOURCE);
        for(String action:new String[]{"AUDIT_DECISION_APPROVE","AUDIT_DECISION_REJECT","AUDIT_DECISION_ESCALATE","AUDIT_DECISION_CANCEL","AUDIT_DECISION_REVIEW","AUDIT_DECISION_REVERTED"})
            l.event("OTHER",999,"PLATFORM",npc,action,"SECRET_DECISION");
        l.event("OTHER",999,"PLATFORM",npc,"AUDIT_DECISION_UNRELATED","SAFE_UNTYPED_ACTION");
        l.event("OTHER",npc,"MERCHANT",npc,"AUDIT_DECISION_APPROVE","SAFE_OTHER_DOMAIN");
        BusinessAuditLogPageVO page=l.page(100,null);assertEquals(2,page.getItems().size());
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(page);assertFalse(json.contains("SECRET"));assertTrue(json.contains("SAFE_UNTYPED_ACTION"));assertTrue(json.contains("SAFE_OTHER_DOMAIN"));
    }

}
