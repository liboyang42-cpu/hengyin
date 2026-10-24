package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 命令边界逻辑测试；不证明 MySQL 锁、事务或 HTTP 鉴权。 */
class PlayCommandIntentTest {
    private ApiPlayProgressController controller;
    private GameSessionRuntimeMapper receipts;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession session;
    private final Map<String, GameCommandReceipt> saved = new HashMap<>();
    private final AtomicInteger effects = new AtomicInteger();
    private ICmsRegistrationService registrations;

    @BeforeEach void setup() {
        saved.clear(); effects.set(0);
        controller = spy(new ApiPlayProgressController()); doReturn(7L).when(controller).getAppUserId();
        receipts = mock(GameSessionRuntimeMapper.class); routes = mock(TopicRouteSessionMapper.class);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        registrations = mock(ICmsRegistrationService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playCommandReceiptMapper", receipts);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playCommandRouteMapper", routes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activities);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        CmsTopicNode node = new CmsTopicNode(); node.setId(11L); node.setTopicId(10L);
        when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        CmsActivity activity = new CmsActivity(); activity.setId(20L); activity.setTopicId(10L);
        when(activities.selectCmsActivityById(20L)).thenReturn(activity);
        CmsRegistration ticket = new CmsRegistration(); ticket.setMemberId(7L); ticket.setOwnerType(2L);
        ticket.setOwnerId(20L); ticket.setPaymentStatus(2); ticket.setRegistrationStatus(2);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(ticket));
        session = new TopicRouteSession(); session.setId(99L); session.setVersion(3);
        when(routes.selectCurrentByOwner(20L, 10L, 7L)).thenReturn(session);
        when(routes.selectAccessibleByIdForUpdate(99L, 7L)).thenReturn(session);
        when(receipts.selectReceiptForUpdate(eq(20L), eq(7L), anyString())).thenAnswer(i -> saved.get(i.getArgument(2)));
        when(receipts.selectReceipt(eq(20L), eq(7L), anyString())).thenAnswer(i -> saved.get(i.getArgument(2)));
        when(receipts.insertReceiptClaim(any())).thenAnswer(i -> {
            GameCommandReceipt row = i.getArgument(0);
            if (saved.containsKey(row.getRequestId())) return 0;
            row.setId((long) saved.size() + 1); saved.put(row.getRequestId(), row); return 1;
        });
        when(receipts.completeReceipt(any())).thenReturn(1);
    }

    private AjaxResult command(String id, Integer expected, String answer, Supplier<AjaxResult> execute) {
        return com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "executeBasicIntent", "ANSWER", "20", null, "11",
                id, expected, null, 99L, null, null, Collections.singletonList(answer), execute);
    }

    @Test void replayPrecedesCurrentVersionAndChangedInputOrExpectedConflicts() {
        AjaxResult first = command("same", 3, "A", () -> { effects.incrementAndGet(); return AjaxResult.success("done"); });
        session.setVersion(8);
        assertEquals(first, command("same", 3, "A", () -> fail("must replay")));
        assertEquals(409, command("same", 8, "A", () -> fail("changed expected" )).get("code"));
        assertEquals(409, command("same", 3, "B", () -> fail("changed answer")).get("code"));
        assertEquals(1, effects.get());
    }

    @Test void wrongAnswerIsTerminalOnceAndSameAnswerNewAttemptRunsAgain() {
        Supplier<AjaxResult> wrong = () -> { effects.incrementAndGet(); return AjaxResult.error("答案不正确", Collections.singletonMap("answerWrong", true)); };
        AjaxResult first = command("attempt-1", 3, "wrong", wrong);
        assertEquals(first, command("attempt-1", 3, "wrong", wrong));
        command("attempt-2", 3, "wrong", wrong);
        assertEquals(2, effects.get());
        assertEquals(2, saved.size());
    }

    @Test void staleOrMissingIdentityHasNoEffectsAndNoClaim() {
        assertEquals(409, command("stale", 2, "A", () -> fail("stale write")).get("code"));
        assertNotEquals(200, command(null, 3, "A", () -> fail("missing id")).get("code"));
        assertNotEquals(200, command("missing-version", null, "A", () -> fail("missing version")).get("code"));
        assertTrue(saved.isEmpty()); verify(receipts, never()).insertReceiptClaim(any());
    }

    @Test void receiptReadRequiresActualOwnerAndAbsentReceiptRemainsUnknown() {
        assertEquals(520, controller.basicActionReceipt("20", null, "11", "unknown").get("code"));
        command("done", 3, "A", () -> AjaxResult.success("done"));
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        assertNotEquals(200, controller.basicActionReceipt("20", null, "11", "done").get("code"));
    }

    @Test void ordinaryFreeRegistrationKeepsOriginalScopeButUnpaidExploreAndForeignRowsCannotReadReceipts() {
        CmsRegistration registration = new CmsRegistration(); registration.setMemberId(7L); registration.setOwnerType(2L);
        registration.setOwnerId(20L); registration.setRegistrationStatus(2); registration.setPurchaseKind(1); registration.setPaymentStatus(0);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
        assertEquals(200, command("free", 3, "A", () -> AjaxResult.success("done")).get("code"));
        assertEquals(200, controller.basicActionReceipt("20", null, "11", "free").get("code"));
        registration.setPurchaseKind(3);
        assertNotEquals(200, controller.basicActionReceipt("20", null, "11", "free").get("code"));
        registration.setPaymentStatus(2); registration.setMemberId(8L);
        assertNotEquals(200, controller.basicActionReceipt("20", null, "11", "free").get("code"));
    }

    @Test void concurrentWinnerIsRecheckedAfterBusinessLockBeforeVersionConflict() {
        command("winner", 3, "A", () -> AjaxResult.success("done"));
        GameCommandReceipt committed = saved.values().iterator().next();
        session.setVersion(4);
        when(receipts.selectReceiptForUpdate(eq(20L), eq(7L), anyString())).thenReturn(null, committed);
        assertEquals(200, command("winner", 3, "A", () -> fail("waiter must replay")).get("code"));
    }
}
