package com.chengyinhub.business.service.channel.platform;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelInteractionMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** GET /api/im/chain/info 不带 chain_id：取该会话最新接龙；会话没有接龙 → 404 CHAIN_NOT_FOUND。 */
class ChannelChainLatestTest
{
    @Test void missingChainIdReadsTheLatestChainOfTheConversation()
    {
        ChannelInteractionMapper mapper = mock(ChannelInteractionMapper.class);
        ChannelMessageService messages = mock(ChannelMessageService.class);
        ChannelInteractionService service = new ChannelInteractionService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "messages", messages);
        when(messages.authorize(anyLong(), any(), anyLong(), anyBoolean())).thenReturn(null);
        when(mapper.selectLatestChainId(100L)).thenReturn(null);
        ChannelRejection none = assertThrows(ChannelRejection.class, () -> service.chainInfo(7, 100, null, 0, 50));
        assertEquals("CHAIN_NOT_FOUND", none.getErrorCode());
        when(mapper.selectLatestChainId(100L)).thenReturn(42L);
        when(mapper.selectChain(42L, 100L)).thenReturn(null);
        assertEquals("CHAIN_NOT_FOUND", assertThrows(ChannelRejection.class, () -> service.chainInfo(7, 100, null, 0, 50)).getErrorCode());
        verify(mapper).selectChain(42L, 100L);
        verify(mapper, never()).selectChain(eq(0L), anyLong());
    }
}
