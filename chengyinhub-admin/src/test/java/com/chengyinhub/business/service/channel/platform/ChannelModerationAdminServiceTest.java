package com.chengyinhub.business.service.channel.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.mapper.opsconsole.ChannelModerationAdminMapper;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.common.exception.ServiceException;

class ChannelModerationAdminServiceTest
{
    private ChannelModerationAdminMapper mapper;
    private ChannelGovernanceMapper gov;
    private OpsConsoleAudit audit;
    private ChannelModerationAdminService service;

    @BeforeEach
    void setUp()
    {
        mapper = mock(ChannelModerationAdminMapper.class);
        gov = mock(ChannelGovernanceMapper.class);
        audit = mock(OpsConsoleAudit.class);
        service = new ChannelModerationAdminService(mapper, gov, audit);
    }

    private void message(int status)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 40L);
        m.put("channelId", 7L);
        m.put("senderId", 100L);
        m.put("status", status);
        m.put("version", 3);
        when(mapper.lockMessage(40L)).thenReturn(m);
    }

    @Test
    void hideWritesRecallMutationResolvesReportsAndAudits()
    {
        message(0);
        when(mapper.setMessageStatus(40L, 0, 2)).thenReturn(1);
        when(mapper.closeReportsForMessage(40L, "RESOLVED")).thenReturn(2);
        Map<String, Object> out = service.hideMessage(1L, 40L, "广告引流");
        assertEquals(2, out.get("status"));
        assertEquals(2, out.get("reportsResolved"));
        verify(mapper).insertMutation(7L, 40L, 4L, "RECALL");
        verify(audit).record(eq(1L), eq("CHANNEL_MESSAGE_HIDE"), eq("IM_MESSAGE"), eq(40L), any(), any(), eq("广告引流"));
    }

    @Test
    void hideNeedsReasonAndANormalMessage()
    {
        message(1);
        assertThrows(ServiceException.class, () -> service.hideMessage(1L, 40L, " "));
        ServiceException e = assertThrows(ServiceException.class, () -> service.hideMessage(1L, 40L, "x"));
        assertEquals(409, e.getCode());
        verify(mapper, never()).setMessageStatus(anyLong(), anyInt(), anyInt());
    }

    @Test
    void restoreOnlyUndoesPlatformHide()
    {
        message(1);
        assertThrows(ServiceException.class, () -> service.restoreMessage(1L, 40L, "误判"));
        message(2);
        when(mapper.setMessageStatus(40L, 2, 0)).thenReturn(1);
        service.restoreMessage(1L, 40L, "误判");
        verify(mapper).insertMutation(7L, 40L, 4L, "RESTORE");
    }

    @Test
    void muteIsAChannelTimeoutByThePlatformOperator()
    {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("guildKind", 3);
        channel.put("guildId", 55L);
        when(mapper.selectChannel(7L)).thenReturn(channel);
        when(mapper.selectTeamLeader(55L)).thenReturn(1L);
        when(mapper.countTeamMember(55L, 100L)).thenReturn(1);
        when(gov.insertAction(anyMap())).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.mute(1L, 7L, 100L, 30, "SPAM", "刷屏"));
        service.mute(1L, 7L, 100L, 3600, "SPAM", "刷屏");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(gov).insertAction(row.capture());
        assertEquals("TIMEOUT", row.getValue().get("actionType"));
        assertEquals("CHANNEL", row.getValue().get("scopeType"));
        assertEquals(7L, row.getValue().get("scopeId"));
        assertEquals(0L, row.getValue().get("operatorId"));
        assertThrows(ServiceException.class, () -> service.mute(1L, 7L, 100L, 3600, "NOISE", "刷屏"));
    }

    @Test
    void removeBansTheSpaceAndEndsTeamMembershipButNotTheLeader()
    {
        when(mapper.selectTeamLeader(55L)).thenReturn(1L);
        when(mapper.countTeamMember(55L, 100L)).thenReturn(1);
        when(gov.insertAction(anyMap())).thenReturn(1);
        when(gov.endTeamMembership(55L, 100L)).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.remove(9L, 3, 55L, 1L, 86400, "HARASSMENT", "骚扰"));
        Map<String, Object> out = service.remove(9L, 3, 55L, 100L, 86400, "HARASSMENT", "骚扰");
        assertEquals(true, out.get("teamMembershipEnded"));
        assertEquals("BAN", out.get("actionType"));
    }

    @Test
    void removeKeepsClubMembershipAndRefusesTheOwner()
    {
        when(mapper.selectClubOwner(8L)).thenReturn(1L);
        when(mapper.countClubMember(8L, 100L)).thenReturn(1);
        when(gov.insertAction(anyMap())).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.remove(9L, 1, 8L, 1L, 86400, "HARASSMENT", "骚扰"));
        Map<String, Object> out = service.remove(9L, 1, 8L, 100L, 86400, "HARASSMENT", "骚扰");
        assertEquals(true, out.get("clubMembershipKept"));
        verify(gov, never()).endTeamMembership(anyLong(), anyLong());
    }

    @Test
    void messageSearchNeedsAFilter()
    {
        assertThrows(ServiceException.class, () -> service.messages(null, null, " ", null, null));
        verify(mapper, never()).searchMessages(any(), any(), anyString(), any(), any(), anyInt());
    }
}
