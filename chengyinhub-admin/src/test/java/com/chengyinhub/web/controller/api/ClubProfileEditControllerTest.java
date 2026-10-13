package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClubProfileEditControllerTest {
    private ApiClubController controller;
    private IClubService service;
    private IClubMemberService members;
    private Club existing;
    private MockMvc mvc;
    private AtomicReference<Club> command;

    @BeforeEach void setup() {
        controller = spy(new ApiClubController()); doReturn(1L).when(controller).getAppUserId();
        service = mock(IClubService.class); members = mock(IClubMemberService.class);
        ReflectionTestUtils.setField(controller, "clubService", service);
        ReflectionTestUtils.setField(controller, "clubMemberService", members);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        existing = new Club(); existing.setId(9L); existing.setMemberId(1L); existing.setStatus(1L);
        existing.setMemberCount(8); existing.setLevel(3); existing.setExperience("Must remain unchanged");
        when(service.selectClubById(9L)).thenReturn(existing);
        when(service.selectClubByMemberId(1L)).thenReturn(existing);
        when(members.canGovernClub(existing, 1L)).thenReturn(true);
        command = new AtomicReference<>();
        when(service.readOwnedClubProfileReceipt(any(), eq(1L), nullable(Integer.class), nullable(String.class))).thenReturn(null);
        when(service.saveOwnedClubProfile(any(), eq(1L), nullable(Integer.class), nullable(String.class)))
                .thenAnswer(call -> {
                    Club c = call.getArgument(0); command.set(c);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("editRequestId", c.getEditRequestId()); result.put("profileVersion", 2L);
                    result.put("joinPolicySupported", false); result.put("joinPolicy", 0);
                    return result;
                });
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    private Club body() { Club c = new Club(); c.setId(9L); c.setName("Edited"); c.setExpectedVersion(1L); c.setEditRequestId("club_request_owner"); return c; }

    @Test void ownerWritesWhitelistOnlyAndReturnsAuthoritativeResult() {
        Club body = body(); body.setMemberId(999L); body.setStatus(2L); body.setMemberCount(500); body.setLevel(5);
        AjaxResult result = controller.updateMine(body);
        assertEquals(200, result.get("code"));
        Club c = command.get(); assertNotSame(existing, c);
        assertNull(c.getMemberId()); assertNull(c.getStatus()); assertNull(c.getMemberCount()); assertNull(c.getLevel());
        assertNull(c.getExperience()); assertEquals(1L, c.getExpectedVersion());
        assertEquals(2L, ((Map<?, ?>) result.get("data")).get("profileVersion"));
    }
    @Test void staleVersionIsBusiness409AndUnknownServiceFailureIsNotDefinitiveRejection() {
        when(service.saveOwnedClubProfile(any(), anyLong(), nullable(Integer.class), anyString()))
                .thenThrow(new ConcurrentModificationException("Conflict"));
        AjaxResult result = controller.updateMine(body()); assertEquals(409, result.get("code"));
        doThrow(new RuntimeException("Connection failed")).when(service)
                .saveOwnedClubProfile(any(), anyLong(), nullable(Integer.class), anyString());
        assertThrows(RuntimeException.class, () -> controller.updateMine(body()));
    }
    @Test void unchangedOwnerAndAdminChecksRejectUnauthorizedOperationalWrites() {
        existing.setMemberId(2L);
        Club c = body(); c.setOperationConfigUpdated(true);
        AjaxResult error = controller.updateMine(c); assertEquals(500, error.get("code"));
        assertEquals(true, ((Map<?, ?>) error.get("data")).get("saveRejected"));
        c.setOperationConfigUpdated(false); c.setJoinPolicy(1);
        assertEquals(500, controller.updateMine(c).get("code"));
        verify(service, never()).saveOwnedClubProfile(any(), anyLong(), nullable(Integer.class), anyString());
    }
    @Test void oldCallerWithoutIdOrVersionStillUsesExistingOwnerPath() {
        Club c = new Club(); c.setName("Legacy"); assertEquals(200, controller.updateMine(c).get("code"));
        assertEquals(9L, command.get().getId()); assertNull(command.get().getExpectedVersion());
    }
    @Test void originalReceiptReadsBeforeModerationAndDoesNotIssueASecondSave() {
        Map<String, Object> receipt = new HashMap<>(); receipt.put("profileVersion", 2L);
        when(service.readOwnedClubProfileReceipt(any(), eq(1L), nullable(Integer.class), anyString())).thenReturn(receipt);
        IWxContentSecurityService moderation = mock(IWxContentSecurityService.class);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", moderation);
        when(moderation.checkText(anyString(), nullable(String.class))).thenThrow(new RuntimeException("offline"));
        assertEquals(receipt, controller.updateMine(body()).get("data"));
        verify(service, never()).saveOwnedClubProfile(any(), anyLong(), nullable(Integer.class), anyString());
        verify(moderation, never()).checkText(anyString(), nullable(String.class));
    }
    @Test void omittedDiscountPreservesValueButExplicitNullStillReachesOldClearContract() throws Exception {
        String base = "{\"id\":9,\"name\":\"Edited\",\"operationConfigUpdated\":true,\"prioritySignupEnabled\":1,\"memberReservedQuota\":2";
        mvc.perform(post("/api/club/update-mine").contentType(MediaType.APPLICATION_JSON).content(base + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        assertFalse(command.get().getMemberDiscountPriceProvided());
        mvc.perform(post("/api/club/update-mine").contentType(MediaType.APPLICATION_JSON).content(base + ",\"memberDiscountPrice\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        assertTrue(command.get().getMemberDiscountPriceProvided()); assertNull(command.get().getMemberDiscountPrice());
    }
}
