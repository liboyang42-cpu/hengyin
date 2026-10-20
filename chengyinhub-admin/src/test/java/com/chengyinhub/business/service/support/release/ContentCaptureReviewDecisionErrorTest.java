package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.ContentCaptureReviewMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.business.AuditWorkbenchController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real candidate validation and audit controller; H2 fixture is local and makes no MySQL claim. */
class ContentCaptureReviewDecisionErrorTest extends ContentCaptureReviewServicePathTest {
    private AuditWorkbenchController controller(IAuditTaskService target) {
        AuditWorkbenchController controller=new AuditWorkbenchController() {
            @Override public Long getUserId() { return 90L; }
        };
        ReflectionTestUtils.setField(controller,"auditTaskService",target);
        return controller;
    }
    private AjaxResult decide(long task,int version) {
        AuditReviewDTO dto=request(version);dto.setTaskId(task);
        return run(()->controller(audit).review(dto));
    }
    private void assertConflict(AjaxResult result) {
        assertEquals(409,result.get("code"));assertEquals("AUDIT_SNAPSHOT_STALE",result.get("errorCode"));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM audit_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
        verifyNoInteractions(publisher);
    }
    @Test void staleExpectedVersionIsTypedConflictThroughActualServiceAndController() {
        long id=submit("stale-version");assertConflict(decide(id,1));
    }
    @Test void changedOwnerIsTypedConflictWithoutApprovalOrLegacyPublication() {
        long id=submit("changed-owner");jdbc.update("UPDATE product_role SET entity_id=8");assertConflict(decide(id,0));
    }
    @Test void corruptedStoredCanonicalJsonIsTypedConflictNotValidation400() {
        long id=submit("bad-json");jdbc.update("UPDATE content_capture_snapshot SET canonical_json='{bad'");assertConflict(decide(id,0));
    }
    @Test void changedHashIsTypedConflictBeforeAnyReviewWrites() {
        long id=submit("bad-hash");jdbc.update("UPDATE content_capture_snapshot SET content_hash=?",new String(new char[64]).replace('\0','a'));
        assertConflict(decide(id,0));
    }
    @Test void disabledIntegrationRemainsUnavailableRatherThanConflict() {
        long id=submit("disabled");ReflectionTestUtils.setField(service,"enabled",false);
        ContentCaptureReviewService.UnavailableException failure=assertThrows(ContentCaptureReviewService.UnavailableException.class,()->decide(id,0));
        assertEquals("Candidate review is unavailable",failure.getMessage());
    }
    @Test void schemaFailureIsNotConvertedToCandidateConflict() {
        long id=submit("schema");IllegalStateException failure=new IllegalStateException("schema unavailable");
        doThrow(failure).when((ContentCaptureReviewSchemaPreflight)ReflectionTestUtils.getField(service,"schemaPreflight")).verifyCurrentTransaction();
        assertSame(failure,assertThrows(ContentCaptureReviewService.UnavailableException.class,()->decide(id,0)).getCause());
    }
    @Test void databaseReadFailureIsNotConvertedToCandidateConflict() {
        long id=submit("database");ContentCaptureReviewMapper mapper=mock(ContentCaptureReviewMapper.class);
        IllegalStateException failure=new IllegalStateException("database unavailable");when(mapper.lockTopic(10L)).thenThrow(failure);
        ReflectionTestUtils.setField(service,"mapper",mapper);
        assertSame(failure,assertThrows(IllegalStateException.class,()->decide(id,0)));
    }
    @Test void unrelatedRuntimeAndServiceFailuresKeepLegacyControllerClassification() {
        IAuditTaskService target=mock(IAuditTaskService.class);AuditReviewDTO dto=request(0);dto.setTaskId(99L);
        IllegalStateException unexpected=new IllegalStateException("unrelated unavailable");
        when(target.review(eq(99L),eq(90L),any())).thenThrow(unexpected);
        assertSame(unexpected,assertThrows(IllegalStateException.class,()->controller(target).review(dto)));
        reset(target);when(target.review(eq(99L),eq(90L),any())).thenThrow(new ServiceException("legacy failure",500));
        AjaxResult result=controller(target).review(dto);assertEquals(500,result.get("code"));assertEquals("AUDIT_REVIEW_FAILED",result.get("errorCode"));
        reset(target);when(target.review(eq(99L),eq(90L),any())).thenThrow(new IllegalArgumentException("invalid legacy request"));
        result=controller(target).review(dto);assertEquals(400,result.get("code"));assertEquals("AUDIT_REVIEW_INVALID",result.get("errorCode"));
    }
}
