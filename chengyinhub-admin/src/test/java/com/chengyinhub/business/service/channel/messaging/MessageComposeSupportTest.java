package com.chengyinhub.business.service.channel.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.channel.messaging.ImMessagingMapper;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.common.exception.ServiceException;

class MessageComposeSupportTest
{
    private final ImMessagingMapper mapper = mock(ImMessagingMapper.class);
    private final ChannelMessageService channels = mock(ChannelMessageService.class);
    private final MessageComposeSupport support = new MessageComposeSupport(mapper, channels);

    private static Map<String, Object> head(long conv, long sender, int status)
    {
        return Map.of("conversationId", conv, "senderId", sender, "status", status, "checkStatus", 1);
    }

    private static ImMessage message(long id, long conv, long sender)
    {
        ImMessage m = new ImMessage();
        m.setId(id);
        m.setConversationId(conv);
        m.setSenderId(sender);
        return m;
    }

    @Test
    void legacyExtraJsonIsLeftAlone()
    {
        assertSame(null, support.prepareText(1, 9, 4, null).extraJson());
        assertEquals("not json", support.prepareText(1, 9, 4, "not json").extraJson());
        assertEquals("{\"route\":1}", support.prepareText(1, 9, 4, "{\"route\":1}").extraJson());
        assertTrue(support.prepareText(1, 9, 4, "{\"route\":1}").empty());
    }

    @Test
    void groupMentionsKeepOnlyCurrentMembersAndWriteRowsInTheSendTransaction()
    {
        when(mapper.selectActiveMemberIds(9L, List.of(2L, 3L))).thenReturn(List.of(2L));
        when(mapper.selectMessageHead(50L)).thenReturn(head(9, 4, 0));
        MessageComposeSupport.Plan plan = support.prepareText(1, 9, 4,
                "{\"mention_member_ids\":[\"2\",\"3\",\"1\"],\"reply_to_id\":\"50\",\"keep\":\"x\"}");
        JSONObject stored = JSON.parseObject(plan.extraJson());
        assertEquals(List.of("2"), stored.getJSONArray("mention_member_ids").toJavaList(String.class));
        assertEquals("50", stored.getString("reply_to_id"));
        assertEquals("x", stored.getString("keep"));

        support.afterInsert(message(70, 9, 1), plan);
        verify(mapper).insertMention(70, "USER", 2L);
        verify(mapper).insertMention(70, "REPLY", 4L);
        verify(mapper).insertRef(70, 9L, 50, 0);
    }

    @Test
    void roleAndEveryoneMentionsAreChannelOnly()
    {
        ServiceException e = assertThrows(ServiceException.class,
                () -> support.prepareText(1, 9, 4, "{\"mention_everyone\":true}"));
        assertEquals(400, e.getCode());
        assertThrows(ServiceException.class, () -> support.prepareText(1, 9, 1, "{\"mention_role_ids\":[\"3\"]}"));
    }

    @Test
    void channelEveryoneNeedsThePermissionAndRolesMustBeMentionableInTheSameSpace()
    {
        when(channels.mentionScope(1L, 9L)).thenReturn(new ChannelMessageService.MentionScope(2, 77L, false));
        ServiceException noEveryone = assertThrows(ServiceException.class,
                () -> support.prepareText(1, 9, 5, "{\"mention_everyone\":true}"));
        assertEquals(403, noEveryone.getCode());

        when(mapper.selectRoles(List.of(3L))).thenReturn(List.of(Map.of("guildKind", 2, "guildId", 77L, "status", 1, "mentionable", 0)));
        assertEquals(403, assertThrows(ServiceException.class,
                () -> support.prepareText(1, 9, 5, "{\"mention_role_ids\":[\"3\"]}")).getCode());

        when(mapper.selectRoles(List.of(4L))).thenReturn(List.of(Map.of("guildKind", 2, "guildId", 78L, "status", 1, "mentionable", 1)));
        assertEquals(400, assertThrows(ServiceException.class,
                () -> support.prepareText(1, 9, 5, "{\"mention_role_ids\":[\"4\"]}")).getCode());

        when(channels.mentionScope(1L, 9L)).thenReturn(new ChannelMessageService.MentionScope(2, 77L, true));
        MessageComposeSupport.Plan plan = support.prepareText(1, 9, 5, "{\"mention_role_ids\":[\"3\"],\"mention_everyone\":true}");
        support.afterInsert(message(71, 9, 1), plan);
        verify(mapper).insertMention(71, "ROLE", 3L);
        verify(mapper).insertMention(71, "EVERYONE", 0L);
        verify(mapper, never()).insertRef(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void repliesMustStayInTheConversationAndThreadsDoNotNest()
    {
        when(mapper.selectMessageHead(50L)).thenReturn(head(8, 4, 0));
        assertEquals(404, assertThrows(ServiceException.class,
                () -> support.prepareText(1, 9, 4, "{\"reply_to_id\":\"50\"}")).getCode());
        when(mapper.selectMessageHead(51L)).thenReturn(head(9, 4, 1));
        assertEquals(404, assertThrows(ServiceException.class,
                () -> support.prepareText(1, 9, 4, "{\"reply_to_id\":\"51\"}")).getCode());

        when(mapper.selectMessageHead(60L)).thenReturn(head(9, 4, 0));
        when(mapper.selectRef(60L)).thenReturn(Map.of("threadRootId", 40L));
        MessageComposeSupport.Plan plan = support.prepareText(1, 9, 4, "{\"thread_root_id\":\"60\"}");
        assertEquals(40, plan.threadRootId());
        assertEquals("40", JSON.parseObject(plan.extraJson()).getString("thread_root_id"));
        assertFalse(plan.empty());
    }

    @Test
    void malformedFieldsAreRejected()
    {
        assertThrows(ServiceException.class, () -> support.prepareText(1, 9, 4, "{\"reply_to_id\":\"abc\"}"));
        assertThrows(ServiceException.class, () -> support.prepareText(1, 9, 4, "{\"mention_member_ids\":\"2\"}"));
        assertThrows(ServiceException.class, () -> support.prepareText(1, 9, 4, "{\"mention_everyone\":\"yes\"}"));
        verify(mapper, never()).insertMention(anyLong(), anyString(), anyLong());
    }
}
