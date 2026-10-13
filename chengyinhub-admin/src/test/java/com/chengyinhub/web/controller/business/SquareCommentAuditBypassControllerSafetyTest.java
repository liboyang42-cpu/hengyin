package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-3:SQUARE / COMMENT 的上下架与软删除只能由统一审核台决定。 */
class SquareCommentAuditBypassControllerSafetyTest {

    @Test
    void squareGenericEditCannotChangeModerationState() {
        IUmsCreativeSquareService service = mock(IUmsCreativeSquareService.class);
        UmsCreativeSquareController controller = new UmsCreativeSquareController();
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", service);
        UmsCreativeSquare request = new UmsCreativeSquare();
        request.setId(7L);
        request.setStatus(2);
        request.setCheckStatus(2);

        AjaxResult result = controller.edit(request);

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).updateUmsCreativeSquare(any(UmsCreativeSquare.class));
    }

    @Test
    void squareGenericDeleteCannotBypassAudit() {
        IUmsCreativeSquareService service = mock(IUmsCreativeSquareService.class);
        UmsCreativeSquareController controller = new UmsCreativeSquareController();
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", service);

        AjaxResult result = controller.remove(new Long[] { 7L });

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).updateUmsCreativeSquare(any(UmsCreativeSquare.class));
    }

    @Test
    void squareGenericAddCannotForgeApprovedContent() {
        IUmsCreativeSquareService service = mock(IUmsCreativeSquareService.class);
        UmsCreativeSquareController controller = new UmsCreativeSquareController();
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", service);

        AjaxResult result = controller.add(new UmsCreativeSquare());

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).insertUmsCreativeSquare(any(UmsCreativeSquare.class));
    }

    @Test
    void squareSubmitReviewUsesExistingContentSnapshot() {
        IUmsCreativeSquareService service = mock(IUmsCreativeSquareService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        UmsCreativeSquareController controller = new UmsCreativeSquareController();
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", service);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditService);
        UmsCreativeSquare square = new UmsCreativeSquare();
        square.setId(7L);
        square.setMemberId(99L);
        square.setContents("待调证帖文");
        when(service.selectUmsCreativeSquareById(7L)).thenReturn(square);
        when(auditService.submitSafetyHold(eq(AuditTask.BIZ_SQUARE), eq(7L), eq(99L),
                eq("NORMAL"), any(String.class))).thenReturn(55L);

        AjaxResult result = controller.submitReview(7L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(55L, result.get(AjaxResult.DATA_TAG));
        verify(auditService).submitSafetyHold(eq(AuditTask.BIZ_SQUARE), eq(7L), eq(99L),
                eq("NORMAL"), argThat(json -> json.contains("待调证帖文")));
    }

    @Test
    void commentGenericEditCannotChangeModerationState() {
        IUmsCommentService service = mock(IUmsCommentService.class);
        UmsCommentController controller = new UmsCommentController();
        ReflectionTestUtils.setField(controller, "umsCommentService", service);
        UmsComment request = new UmsComment();
        request.setId(9L);
        request.setStatus(2);

        AjaxResult result = controller.edit(request);

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).updateUmsComment(any(UmsComment.class));
    }

    @Test
    void commentGenericDeleteCannotBypassAudit() {
        IUmsCommentService service = mock(IUmsCommentService.class);
        UmsCommentController controller = new UmsCommentController();
        ReflectionTestUtils.setField(controller, "umsCommentService", service);

        AjaxResult result = controller.remove(new Long[] { 9L });

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).updateUmsComment(any(UmsComment.class));
    }

    @Test
    void commentGenericAddCannotForgeVisibleContent() {
        IUmsCommentService service = mock(IUmsCommentService.class);
        UmsCommentController controller = new UmsCommentController();
        ReflectionTestUtils.setField(controller, "umsCommentService", service);

        AjaxResult result = controller.add(new UmsComment());

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).insertUmsComment(any(UmsComment.class));
    }

    @Test
    void commentSubmitReviewUsesExistingContentSnapshot() {
        IUmsCommentService service = mock(IUmsCommentService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        UmsCommentController controller = new UmsCommentController();
        ReflectionTestUtils.setField(controller, "umsCommentService", service);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditService);
        UmsComment comment = new UmsComment();
        comment.setId(9L);
        comment.setMemberId(88L);
        comment.setContents("待调证评论");
        when(service.selectUmsCommentById(9L)).thenReturn(comment);
        when(auditService.submitSafetyHold(eq(AuditTask.BIZ_COMMENT), eq(9L), eq(88L),
                eq("NORMAL"), any(String.class))).thenReturn(66L);

        AjaxResult result = controller.submitReview(9L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(66L, result.get(AjaxResult.DATA_TAG));
        verify(auditService).submitSafetyHold(eq(AuditTask.BIZ_COMMENT), eq(9L), eq(88L),
                eq("NORMAL"), argThat(json -> json.contains("待调证评论")));
    }
}
