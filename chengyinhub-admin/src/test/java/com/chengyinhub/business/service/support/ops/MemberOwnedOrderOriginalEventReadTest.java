package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.ICmsRegistrationService;

/**
 * Mockito 替身只活在内存里。这不是 HTTP，也不是 MySQL。
 */
class MemberOwnedOrderOriginalEventReadTest {
    private static final long MEMBER = 7L;
    private static final long ORDER = 501L;

    @Test
    void ownedOrderReturnsTheMapperRowOnlyWhenTheFirstOrderIdMatches() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        when(registrations.selectCmsRegistrationById(ORDER)).thenReturn(registration(ORDER, MEMBER));
        BusinessAuditEvent other = event("req-other", "ORDER", 99L, "PAY", "UNKNOWN");
        BusinessAuditEvent owned = event("c11-owned-order", "ORDER", ORDER, "CREATE", "UNKNOWN");
        when(mapper.selectByTarget("ORDER", ORDER)).thenReturn(List.of(other, owned));
        MemberOwnedOrderOriginalEventRead read = new MemberOwnedOrderOriginalEventRead(registrations, mapper);

        BusinessAuditEvent row = read.readOwnedOrder(MEMBER, "501");

        assertSame(owned, row);
        assertEquals("UNKNOWN", row.getAfterJson());
        InOrder sequence = inOrder(registrations, mapper);
        sequence.verify(registrations).selectCmsRegistrationById(ORDER);
        sequence.verify(mapper).selectByTarget("ORDER", ORDER);
        verify(mapper, times(1)).selectByTarget("ORDER", ORDER);
        verify(mapper, never()).selectByTarget(eq("RUN"), any(Long.class));
        verify(mapper, never()).selectByTarget(eq("ORDER"), eq(99L));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void blankIdAndLoggedOutMemberDoNotReadTheOrderOrEvents() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        MemberOwnedOrderOriginalEventRead read = new MemberOwnedOrderOriginalEventRead(registrations, mapper);

        assertNull(read.readOwnedOrder(null, "501"));
        assertNull(read.readOwnedOrder(0L, "501"));
        assertNull(read.readOwnedOrder(-1L, "501"));
        assertNull(read.readOwnedOrder(MEMBER, null));
        assertNull(read.readOwnedOrder(MEMBER, ""));
        assertNull(read.readOwnedOrder(MEMBER, " "));
        assertNull(read.readOwnedOrder(MEMBER, "0"));
        assertNull(read.readOwnedOrder(MEMBER, "abc"));

        verifyNoInteractions(registrations);
        verifyNoInteractions(mapper);
    }

    @Test
    void missingForeignAndMismatchedRegistrationDoNotBecomeThisOrder() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        when(registrations.selectCmsRegistrationById(ORDER)).thenReturn(
                null, registration(ORDER, 8L), registration(88L, MEMBER));
        MemberOwnedOrderOriginalEventRead read = new MemberOwnedOrderOriginalEventRead(registrations, mapper);

        assertNull(read.readOwnedOrder(MEMBER, "501"));
        assertNull(read.readOwnedOrder(MEMBER, "501"));
        assertNull(read.readOwnedOrder(MEMBER, "501"));

        verify(registrations, times(3)).selectCmsRegistrationById(ORDER);
        verifyNoMoreInteractions(registrations);
        verifyNoInteractions(mapper);
    }

    @Test
    void differentMissingBlankAndRunRowsDoNotBecomeThisOrder() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        when(registrations.selectCmsRegistrationById(ORDER)).thenReturn(registration(ORDER, MEMBER));
        BusinessAuditEvent different = event("req-other", "ORDER", 99L, "PAY", "UNKNOWN");
        BusinessAuditEvent blankRequest = event("  ", "ORDER", ORDER, "CREATE", "UNKNOWN");
        BusinessAuditEvent runShaped = event("req-run", "RUN", 77L, "START", "UNKNOWN");
        when(mapper.selectByTarget("ORDER", ORDER))
                .thenReturn(List.of(different))
                .thenReturn(List.of())
                .thenReturn(null)
                .thenReturn(List.of(blankRequest))
                .thenReturn(List.of(runShaped));
        MemberOwnedOrderOriginalEventRead read = new MemberOwnedOrderOriginalEventRead(registrations, mapper);

        assertNull(read.readOwnedOrder(MEMBER, "501"));
        assertNull(read.readOwnedOrder(MEMBER, "501"));
        assertNull(read.readOwnedOrder(MEMBER, "501"));
        assertNull(read.readOwnedOrder(MEMBER, "501"));
        assertNull(read.readOwnedOrder(MEMBER, "501"));

        verify(mapper, times(5)).selectByTarget("ORDER", ORDER);
        verify(mapper, never()).selectByTarget(eq("RUN"), any(Long.class));
        verify(mapper, never()).selectByTarget(eq("ORDER"), eq(99L));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void controllerAdditionStaysUnappliedAndDoesNotAddAnAdminPermission() throws Exception {
        String controller = readRepo(
                "src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java");
        String auditController = readRepo(
                "src/main/java/com/chengyinhub/web/controller/business/BusinessAuditLogController.java",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/BusinessAuditLogController.java");
        String mapper = readRepo(
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/BusinessAuditEventMapper.java",
                "chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/BusinessAuditEventMapper.java");
        String mapperXml = readRepo(
                "../chengyinhub-system/src/main/resources/mapper/business/BusinessAuditEventMapper.xml",
                "chengyinhub-system/src/main/resources/mapper/business/BusinessAuditEventMapper.xml");
        String service = readRepo(
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ops/MemberOwnedOrderOriginalEventRead.java",
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ops/MemberOwnedOrderOriginalEventRead.java");
        String hunk = readRepo(
                "../docs/issue5-hunks/C11-user-event-read.hunk",
                "docs/issue5-hunks/C11-user-event-read.hunk");

        assertFalse(controller.contains("ownedOrderOriginalEvent"));
        assertFalse(controller.contains("MemberOwnedOrderOriginalEventRead"));
        assertFalse(auditController.contains("MemberOwnedOrderOriginalEventRead"));
        assertFalse(auditController.contains("readOwnedOrder"));
        assertFalse(mapper.contains("MemberOwnedOrderOriginalEventRead"));
        assertFalse(mapperXml.contains("MemberOwnedOrderOriginalEventRead"));
        assertTrue(mapper.contains("selectByTarget"));
        assertTrue(service.contains("OwnershipUtils.notOwner"));
        assertTrue(service.contains("selectCmsRegistrationById"));
        assertTrue(service.contains("MapperOriginalEventSource"));
        assertTrue(service.contains("locateByOrderId"));
        assertFalse(service.contains("selectByTarget(\"RUN\""));
        assertFalse(service.contains("@Service"));
        assertFalse(service.contains("@RestController"));
        assertFalse(service.contains("business:audit:log:list"));
        assertFalse(service.contains("hasRole"));
        assertTrue(hunk.contains("DO NOT APPLY"));
        assertTrue(hunk.contains("public AjaxResult ownedOrderOriginalEvent(String id)"));
        assertTrue(hunk.contains("@PostMapping(\"/original-event\")"));
        assertTrue(hunk.contains("readOwnedOrder"));
        assertTrue(hunk.contains("getAppUserId"));
        assertTrue(hunk.contains("POST /api/registration/info"));
        assertFalse(hunk.contains("@RequestMapping(\"/business/audit-log\")"));
        assertFalse(hunk.contains("business:audit:log:list"));
        assertFalse(hunk.contains("hasRole"));
        assertFalse(hunk.contains("@PreAuthorize"));
    }

    private static String readRepo(String... candidates) throws Exception {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new java.io.FileNotFoundException(String.join(" | ", candidates));
    }

    private static CmsRegistration registration(long id, long memberId) {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(id);
        registration.setMemberId(memberId);
        return registration;
    }

    private static BusinessAuditEvent event(String requestId, String targetType, Long targetId,
                                            String action, String afterJson) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setRequestId(requestId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setAction(action);
        event.setAfterJson(afterJson);
        event.setCreateTime(new Date(1L));
        return event;
    }
}
