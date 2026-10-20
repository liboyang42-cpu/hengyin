package com.chengyinhub.business.service.support.release;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual existing review/revert service and SQL task/record/CAS; unrelated business-event and IM ports mocked. */
class ContentCaptureReviewServicePathTest extends ContentCaptureReviewMapperTest {
    AuditTaskServiceImpl audit;BusinessAuditEventMapper events;ObjectProvider<ICmsTopicService> publisher;
    @BeforeEach void wireAudit() {
        audit=new AuditTaskServiceImpl();events=mock(BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(audit,"auditTaskMapper",session.getMapper(AuditTaskMapper.class));
        ReflectionTestUtils.setField(audit,"auditRecordMapper",session.getMapper(AuditRecordMapper.class));
        ReflectionTestUtils.setField(audit,"contentCaptureReviewService",service);
        ReflectionTestUtils.setField(audit,"businessAuditEventMapper",events);
        ObjectProvider<IImService> im=mock(ObjectProvider.class);when(im.getObject()).thenReturn(mock(IImService.class));
        ReflectionTestUtils.setField(audit,"imServiceProvider",im);
        publisher=mock(ObjectProvider.class);ReflectionTestUtils.setField(audit,"cmsTopicServiceProvider",publisher);
        Map<String,BusinessAuditEvent> stored=new HashMap<>();
        when(events.insertBusinessAuditEvent(any())).thenAnswer(call->{BusinessAuditEvent event=call.getArgument(0);stored.put(event.getAction()+":"+event.getRequestId(),event);return 1;});
        when(events.selectByDomainActionRequestForUpdate(anyString(),anyLong(),anyString(),anyString())).thenAnswer(call->stored.get(call.getArgument(2)+":"+call.getArgument(3)));
    }
    @Test void actualReviewReplayRevertAndRevertReplayKeepCandidateIncomplete() {
        long task=submit("actual");AuditReviewDTO request=request(0);request.setRequestId("review-1");
        assertEquals(1,run(()->audit.review(task,90L,request)));
        assertEquals(1,run(()->audit.review(task,90L,request)));
        assertTrue(run(()->service.inspect(task)).candidateReviewed());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
        AuditRevertDTO revert=new AuditRevertDTO();revert.setExpectedVersion(1);revert.setIdempotencyKey("revert-1");revert.setReason("recheck");
        assertEquals(2,run(()->audit.revert(task,90L,false,revert)).getVersion());
        assertEquals(2,run(()->audit.revert(task,90L,false,revert)).getVersion());
        assertFalse(run(()->service.inspect(task)).candidateReviewed());
        assertFalse(run(()->service.inspect(task)).publicationEligible());
        verifyNoInteractions(publisher);
        jdbc.update("UPDATE product_role SET entity_id=8");
        assertThrows(IllegalStateException.class,()->run(()->audit.revert(task,90L,false,revert)));
    }
    @Test void actualReviewRejectsStaleVersionOrOwnerAndDoesNotPublish() {
        long task=submit("invalid");
        assertThrows(IllegalStateException.class,()->run(()->audit.review(task,90L,request(1))));
        jdbc.update("UPDATE product_role SET entity_id=8");
        assertThrows(IllegalStateException.class,()->run(()->audit.review(task,90L,request(0))));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM audit_task WHERE id=?",Integer.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));verifyNoInteractions(publisher);
    }
    @Test void auditEventFailureRollsBackReviewCasAndDecisionRecord() {
        long task=submit("rollback-decision");doReturn(0).when(events).insertBusinessAuditEvent(any());
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->run(()->audit.review(task,90L,request(0))));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM audit_task WHERE id=?",Integer.class,task));
        assertEquals(0,jdbc.queryForObject("SELECT version FROM audit_task WHERE id=?",Integer.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
    }
    @Test void revertingOldApprovalWhileAnotherCandidateIsPendingFailsAtomically() {
        long first=submit("first");run(()->audit.review(first,90L,request(0)));
        snapshot.setId(null);snapshot.setRequestId("second-capture");
        run(()->session.getMapper(ContentCaptureSnapshotMapper.class).insertFrozen(snapshot));
        long second=submit("second");
        AuditRevertDTO revert=new AuditRevertDTO();revert.setExpectedVersion(1);revert.setIdempotencyKey("blocked-revert");revert.setReason("recheck");
        assertThrows(org.springframework.dao.DataAccessException.class,()->run(()->audit.revert(first,90L,false,revert)));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM audit_task WHERE id=?",Integer.class,first));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM audit_task WHERE id=?",Integer.class,second));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE action='REVERT'",Integer.class));
    }
    @Test void genericSubmitCannotCreateUnboundCandidateReviewTask() {
        assertThrows(IllegalArgumentException.class,()->audit.submit(ContentCaptureReviewService.BIZ_TYPE,10L,7L,"forged","{}"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
    }
}
