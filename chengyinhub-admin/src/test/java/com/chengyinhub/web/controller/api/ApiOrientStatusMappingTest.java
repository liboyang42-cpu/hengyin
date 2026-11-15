package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.IOrientTaskService;
import com.chengyinhub.business.service.IOrientTeamBoardService;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.business.service.support.OrientScoringException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证城市定向控制器将 OrientRejection / OrientScoringException 映射为语义化 HTTP 状态码并保留 errorCode。 */
class ApiOrientStatusMappingTest {

    @Test
    void orientRejectionErrorCodeMappingCoversAllClasses() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, OrientRejection.httpStatusForErrorCode(OrientRejection.DISABLED));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, OrientRejection.httpStatusForErrorCode(OrientScoringException.DISABLED));
        assertEquals(HttpStatus.UNAUTHORIZED, OrientRejection.httpStatusForErrorCode(OrientRejection.LOGIN_REQUIRED));
        assertEquals(HttpStatus.FORBIDDEN, OrientRejection.httpStatusForErrorCode(OrientRejection.NOT_OWNER));
        assertEquals(HttpStatus.NOT_FOUND, OrientRejection.httpStatusForErrorCode(OrientRejection.NOT_FOUND));
        assertEquals(HttpStatus.NOT_FOUND, OrientRejection.httpStatusForErrorCode(OrientScoringException.SESSION_NOT_FOUND));
        assertEquals(HttpStatus.BAD_REQUEST, OrientRejection.httpStatusForErrorCode(OrientRejection.BAD_REQUEST));
        assertEquals(HttpStatus.BAD_REQUEST, OrientRejection.httpStatusForErrorCode(OrientScoringException.INVALID_ARGUMENT));
        assertEquals(HttpStatus.CONFLICT, OrientRejection.httpStatusForErrorCode(OrientScoringException.WINDOW_CLOSED));
    }

    @Test
    void orientControllerMapsRejectionAndKeepsErrorCode() {
        IOrientTeamBoardService boardService = mock(IOrientTeamBoardService.class);
        when(boardService.ranking(9001L, 1, 99L))
                .thenThrow(new OrientRejection(OrientRejection.NOT_FOUND, "场次不存在"));
        TestableOrientController controller = new TestableOrientController();
        ReflectionTestUtils.setField(controller, "teamBoardService", boardService);

        AjaxResult res = controller.teamRanking(1, 99L);
        assertEquals(HttpStatus.NOT_FOUND, res.get("code"));
        assertEquals(OrientRejection.NOT_FOUND, res.get("errorCode"));
    }

    @Test
    void taskReceiptControllerMapsRejectionAndKeepsErrorCode() {
        IOrientTaskService taskService = mock(IOrientTaskService.class);
        when(taskService.receipt(any(), any(), any(), any(), any(), any()))
                .thenThrow(new OrientRejection(OrientRejection.BAD_REQUEST, "缺少幂等键"));
        TestableOrientTaskReceiptController controller = new TestableOrientTaskReceiptController();
        ReflectionTestUtils.setField(controller, "taskService", taskService);

        AjaxResult res = controller.receipt(1L, 2L, "p1", 3L, null);
        assertEquals(HttpStatus.BAD_REQUEST, res.get("code"));
        assertEquals(OrientRejection.BAD_REQUEST, res.get("errorCode"));
    }

    @Test
    void scoringControllerMapsScoringExceptionsAndDisabledWithoutBreakingUnauthContract() {
        TestableOrientScoringController disabledController = new TestableOrientScoringController(9001L);
        AjaxResult disabled = disabledController.breakdown(10L);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, disabled.get("code"));
        assertEquals(OrientScoringException.DISABLED, disabled.get("errorCode"));

        IOrientScoringService scoringService = mock(IOrientScoringService.class);
        when(scoringService.breakdown(9001L, 10L))
                .thenThrow(new OrientScoringException(OrientScoringException.SESSION_NOT_FOUND, "对局不存在"));
        ReflectionTestUtils.setField(disabledController, "orientScoringService", scoringService);

        AjaxResult notFound = disabledController.breakdown(10L);
        assertEquals(HttpStatus.NOT_FOUND, notFound.get("code"));
        assertEquals(OrientScoringException.SESSION_NOT_FOUND, notFound.get("errorCode"));

        TestableOrientScoringController anonController = new TestableOrientScoringController(null);
        assertEquals(HttpStatus.ERROR, anonController.breakdown(10L).get("code"));
    }

    private static class TestableOrientController extends ApiOrientController {
        @Override
        public Long getAppUserId() {
            return 9001L;
        }
    }

    private static class TestableOrientTaskReceiptController extends ApiOrientTaskReceiptController {
        @Override
        public Long getAppUserId() {
            return 9001L;
        }
    }

    private static class TestableOrientScoringController extends ApiOrientScoringController {
        private final Long memberId;

        TestableOrientScoringController(Long memberId) {
            this.memberId = memberId;
        }

        @Override
        public Long getAppUserId() {
            if (memberId == null) {
                throw new IllegalStateException("unauthenticated");
            }
            return memberId;
        }
    }
}
