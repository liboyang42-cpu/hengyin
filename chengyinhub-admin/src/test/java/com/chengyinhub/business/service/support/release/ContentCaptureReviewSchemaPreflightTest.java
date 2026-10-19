package com.chengyinhub.business.service.support.release;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
/** Fail-closed local wiring checks; no H2 substitution for MySQL metadata certification. */
class ContentCaptureReviewSchemaPreflightTest extends ContentCaptureReviewMapperTest {
    @Test void guardExpressionPreservesQuotedIdentifierBoundaries() {
        assertTrue(ContentCaptureReviewSchemaPreflight.supportedGuardExpression("(case when (`status` in (0,3)) then 1 else NULL end)"));
        assertTrue(ContentCaptureReviewSchemaPreflight.supportedGuardExpression("CASE WHEN status IN (0, 3) THEN 1 ELSE NULL END"));
        for(String unsafe:new String[]{"case when `sta tus` in (0,3) then 1 else null end","case when `sta(tus)` in (0,3) then 1 else null end","case when `sta``tus` in (0,3) then 1 else null end","case when `Status` in (0,3) then 1 else null end","case when `sta\ntus` in (0,3) then 1 else null end","case when 'status' in (0,3) then 1 else null end","case when status in (0) then 1 else null end",null})
            assertFalse(ContentCaptureReviewSchemaPreflight.supportedGuardExpression(unsafe));
    }
    @Test void triggerNormalizationKeepsLiteralBytes() {
        String expected="SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Candidate review bindings cannot be updated'";
        String canonical=ReflectionTestUtils.invokeMethod(ContentCaptureReviewSchemaPreflight.class,"normalize",expected);
        assertEquals(canonical,ReflectionTestUtils.invokeMethod(ContentCaptureReviewSchemaPreflight.class,"normalize","signal   SQLSTATE '45000' SET MESSAGE_TEXT = 'Candidate review bindings cannot be updated'"));
        assertNotEquals(canonical,ReflectionTestUtils.invokeMethod(ContentCaptureReviewSchemaPreflight.class,"normalize",expected.replace("Candidate review","candidate  review")));
    }
    @Test void missingPreflightCannotEnableCandidateWrites(){ReflectionTestUtils.setField(service,"schemaPreflight",null);assertThrows(IllegalStateException.class,()->submit("no-schema-guard"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));}
    @Test void unboundOrNonMysqlPreflightFailsClosed(){ContentCaptureReviewSchemaPreflight guard=new ContentCaptureReviewSchemaPreflight(source);assertThrows(IllegalStateException.class,guard::verifyCurrentTransaction);assertThrows(IllegalStateException.class,()->run(()->{guard.verifyCurrentTransaction();return null;}));}
}
