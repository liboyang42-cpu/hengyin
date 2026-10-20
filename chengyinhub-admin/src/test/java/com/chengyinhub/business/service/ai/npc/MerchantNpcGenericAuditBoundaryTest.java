package com.chengyinhub.business.service.ai.npc;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.Page;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Generic console cannot provide the missing dedicated NPC object authority. */
public class MerchantNpcGenericAuditBoundaryTest {
    static final String BIZ = MerchantNpcKnowledgeDraftService.BIZ_TYPE;
    static final String SOURCE = MerchantNpcKnowledgeDraftService.SOURCE_TYPE;
    AuditTask task(String biz, String source, int status) {
        AuditTask t=new AuditTask(); t.setId(91L); t.setBizType(biz); t.setSourceType(source);
        t.setStatus(status); t.setVersion(2); t.setSubmitterId(921L); t.setSnapshotJson("SECRET_DRAFT_OWNER");
        return t;
    }
    AuditTaskServiceImpl service(AuditTaskMapper tasks, AuditRecordMapper records, BusinessAuditEventMapper events) {
        AuditTaskServiceImpl s=new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(s,"auditTaskMapper",tasks);
        ReflectionTestUtils.setField(s,"auditRecordMapper",records);
        ReflectionTestUtils.setField(s,"businessAuditEventMapper",events); return s;
    }
    List<AuditTask> restricted() {
        List<AuditTask> out=new ArrayList<>();
        for(int status: new int[]{0,1,2,3}) for(String[] pair:new String[][]{{BIZ,SOURCE},{BIZ,null},{BIZ,"report"},{null,SOURCE},{"TOPIC",SOURCE}})
            out.add(task(pair[0],pair[1],status));
        return out;
    }
    @Test void genericReadsDoNotRevealNpcOrQueryRecords() {
        for(AuditTask t:restricted()) {
            AuditTaskMapper tasks=mock(AuditTaskMapper.class); AuditRecordMapper records=mock(AuditRecordMapper.class);
            BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
            when(tasks.selectAuditTaskById(91L)).thenReturn(t);
            AuditTaskServiceImpl s=service(tasks,records,events);
            assertNull(s.selectAuditTaskById(91L)); assertTrue(s.listAuditRecords(91L).isEmpty());
            ServiceException denied=assertThrows(ServiceException.class,()->s.detail(91L,1L,true));
            assertEquals(404,denied.getCode()); assertFalse(denied.getMessage().contains("SECRET"));
            verifyNoInteractions(records,events);
        }
    }
    @Test void genericDecisionRejectsBeforeEveryReplayOrWrite() {
        for(AuditTask t:restricted()) for(int decision:new int[]{1,2,3}) {
            AuditTaskMapper tasks=mock(AuditTaskMapper.class); AuditRecordMapper records=mock(AuditRecordMapper.class);
            BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
            when(tasks.selectAuditTaskById(91L)).thenReturn(t);
            AuditTaskServiceImpl s=service(tasks,records,events);
            AuditReviewDTO request=new AuditReviewDTO();request.setStatus(decision);request.setExpectedVersion(2);
            request.setReasonText("reason");request.setRequestId("existing-receipt");
            assertEquals(403,assertThrows(ServiceException.class,()->s.review(91L,1L,request)).getCode());
            verify(tasks).selectAuditTaskById(91L);verifyNoMoreInteractions(tasks);verifyNoInteractions(records,events);
        }
    }
    @Test void genericRevertRejectsBeforeExistingReceiptLookup() {
        for(AuditTask t:restricted()) {
            AuditTaskMapper tasks=mock(AuditTaskMapper.class); AuditRecordMapper records=mock(AuditRecordMapper.class);
            BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
            when(tasks.selectAuditTaskById(91L)).thenReturn(t);
            AuditRevertDTO request=new AuditRevertDTO();request.setExpectedVersion(2);request.setReason("reason");request.setIdempotencyKey("existing-receipt");
            assertEquals(403,assertThrows(ServiceException.class,()->service(tasks,records,events).revert(91L,1L,true,request)).getCode());
            verify(tasks).selectAuditTaskById(91L);verifyNoMoreInteractions(tasks);verifyNoInteractions(records,events);
        }
    }
    @Test void unrelatedTypesRemainReadableIncludingNullAndUnknown() {
        for(String[] pair:new String[][]{{"TOPIC","report"},{"OTHER",null},{null,null}}) {
            AuditTask t=task(pair[0],pair[1],0); AuditTaskMapper tasks=mock(AuditTaskMapper.class);AuditRecordMapper records=mock(AuditRecordMapper.class);
            when(tasks.selectAuditTaskById(91L)).thenReturn(t); when(records.selectAuditRecordList(any())).thenReturn(Collections.emptyList());
            AuditTaskServiceImpl s=service(tasks,records,mock(BusinessAuditEventMapper.class));assertSame(t,s.selectAuditTaskById(91L));assertTrue(s.listAuditRecords(91L).isEmpty());verify(records).selectAuditRecordList(any());
        }
    }
    @Test void realMapperExcludesNpcBeforePageCountAndFilteredSearch() throws Exception {
        MerchantNpcKnowledgeDraftMybatisTest f=new MerchantNpcKnowledgeDraftMybatisTest();f.setup();
        for(AuditTask t:restricted()) {t.setId(null);t.setSubmittedAt(new Date());f.tasks.insertAuditTask(t);}
        for(String[] pair:new String[][]{{"TOPIC","report"},{"OTHER",null},{null,null}}) {AuditTask t=task(pair[0],pair[1],0);t.setId(null);t.setSubmittedAt(new Date());f.tasks.insertAuditTask(t);}
        List<AuditTask> all=f.tasks.selectAuditTaskList(new AuditTask());assertEquals(3,all.size());
        AuditTask filter=new AuditTask();filter.setBizType(BIZ);assertTrue(f.tasks.selectAuditTaskList(filter).isEmpty());
        org.apache.ibatis.session.Configuration cfg=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("paged",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),f.jdbc.getDataSource()));
        cfg.getTypeAliasRegistry().registerAlias("AuditTask",AuditTask.class);
        com.github.pagehelper.PageInterceptor paging=new com.github.pagehelper.PageInterceptor();
        java.util.Properties properties=new java.util.Properties();properties.setProperty("helperDialect","mysql");paging.setProperties(properties);cfg.addInterceptor(paging);
        String resource="mapper/business/AuditTaskMapper.xml";
        try(java.io.InputStream in=getClass().getClassLoader().getResourceAsStream(resource)) {new org.apache.ibatis.builder.xml.XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();}
        AuditTaskMapper paged=new org.mybatis.spring.SqlSessionTemplate(new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg)).getMapper(AuditTaskMapper.class);
        try {PageHelper.startPage(1,2);List<AuditTask> first=paged.selectAuditTaskList(new AuditTask());assertEquals(2,first.size());assertEquals(3,((Page<?>)first).getTotal());}
        finally {PageHelper.clearPage();}
        try {PageHelper.startPage(2,2);List<AuditTask> second=paged.selectAuditTaskList(new AuditTask());assertEquals(1,second.size());assertEquals(3,((Page<?>)second).getTotal());}
        finally {PageHelper.clearPage();}
        try {PageHelper.startPage(1,2);List<AuditTask> denied=paged.selectAuditTaskList(filter);assertTrue(denied.isEmpty());assertEquals(0,((Page<?>)denied).getTotal());}
        finally {PageHelper.clearPage();}
    }
    @Test void revertRechecksFreshTaskBeforeReturningReceiptOrContinuing() {
        for(boolean receipt:new boolean[]{false,true}) {
            AuditTaskMapper tasks=mock(AuditTaskMapper.class);AuditRecordMapper records=mock(AuditRecordMapper.class);BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
            when(tasks.selectAuditTaskById(91L)).thenReturn(task("OTHER",null,1),task("OTHER",SOURCE,1));
            if(receipt) {BusinessAuditEvent e=new BusinessAuditEvent();e.setActorMemberId(1L);e.setReason("reason");e.setBeforeJson("{\"version\":2}");e.setAfterJson("{\"status\":0,\"version\":3}");when(events.selectByDomainActionRequestForUpdate(anyString(),anyLong(),anyString(),anyString())).thenReturn(e);}
            AuditRevertDTO request=new AuditRevertDTO();request.setExpectedVersion(2);request.setReason("reason");request.setIdempotencyKey("receipt");
            assertEquals(403,assertThrows(ServiceException.class,()->service(tasks,records,events).revert(91L,1L,true,request)).getCode());
            verify(tasks,times(2)).selectAuditTaskById(91L);verifyNoMoreInteractions(tasks);verifyNoInteractions(records);
        }
    }
    @Test void nonNpcReviewAndRevertReplayRemainSupported() {
        AuditTaskMapper tasks=mock(AuditTaskMapper.class);AuditRecordMapper records=mock(AuditRecordMapper.class);BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
        when(tasks.selectAuditTaskById(91L)).thenReturn(task("OTHER",null,0));
        BusinessAuditEvent e=new BusinessAuditEvent();e.setActorMemberId(1L);e.setReason("reason");e.setBeforeJson("{\"version\":2}");e.setAfterJson("{\"status\":1,\"version\":3}");
        when(events.selectByDomainActionRequestForUpdate(anyString(),anyLong(),anyString(),anyString())).thenReturn(e);
        AuditTaskServiceImpl s=service(tasks,records,events);AuditReviewDTO review=new AuditReviewDTO();review.setExpectedVersion(2);review.setStatus(1);review.setReasonText("reason");review.setRequestId("receipt");assertEquals(1,s.review(91L,1L,review));
        AuditRevertDTO revert=new AuditRevertDTO();revert.setExpectedVersion(2);revert.setReason("reason");revert.setIdempotencyKey("receipt");assertEquals(3,s.revert(91L,1L,false,revert).getVersion());verifyNoInteractions(records);
    }

}
