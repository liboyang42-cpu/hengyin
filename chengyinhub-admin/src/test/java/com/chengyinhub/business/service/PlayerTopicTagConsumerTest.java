package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayerTopicTag;
import com.chengyinhub.business.mapper.PlayerTopicTagMapper;
import com.chengyinhub.business.service.impl.PlayerTopicTagServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 消费方真入口：只把节点声明且玩家已确认的标签交给下一站。 */
class PlayerTopicTagConsumerTest {

    @Test
    void unconfirmedRevokedAndUndeclaredTagsNeverReachConsumer() {
        PlayerTopicTagMapper mapper = mock(PlayerTopicTagMapper.class);
        PlayerTopicTagServiceImpl service = new PlayerTopicTagServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        when(mapper.selectConfirmedByCodes(eq(7L), eq(9L), anyList())).thenReturn(Arrays.asList(
                tag("space_constraints", 0),
                tag("space_constraints", 1),
                tag("space_constraints", 2),
                tag("social_mode", 1)));

        List<PlayerTopicTag> visible = service.confirmedForConsumer(7L, 9L,
                new HashSet<String>(Collections.singletonList("space_constraints")));

        assertEquals(1, visible.size());
        assertEquals("space_constraints", visible.get(0).getTagCode());
        assertEquals(Integer.valueOf(1), visible.get(0).getStatus());
    }

    @Test
    void nodeWithoutConsumptionDeclarationDoesNotQueryAnyPlayerTag() {
        PlayerTopicTagMapper mapper = mock(PlayerTopicTagMapper.class);
        PlayerTopicTagServiceImpl service = new PlayerTopicTagServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertEquals(0, service.confirmedForConsumer(7L, 9L, Collections.<String>emptySet()).size());
        verify(mapper, never()).selectConfirmedByCodes(eq(7L), eq(9L), anyList());
    }

    @Test
    void confirmAndRevokeConvergeWhenThePreviousResponseWasLost() {
        PlayerTopicTagMapper mapper = mock(PlayerTopicTagMapper.class);
        PlayerTopicTagServiceImpl service = new PlayerTopicTagServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        PlayerTopicTag confirmed = tag("space_constraints", 1);
        PlayerTopicTag revoked = tag("space_constraints", 2);
        when(mapper.selectOwnedById(11L, 7L)).thenReturn(confirmed);
        when(mapper.selectOwnedById(12L, 7L)).thenReturn(revoked);

        assertEquals(Integer.valueOf(1), service.confirm(7L, 11L).getStatus());
        assertEquals(Integer.valueOf(2), service.revoke(7L, 12L).getStatus());
        verify(mapper, never()).confirmOwned(11L, 7L);
        verify(mapper, never()).revokeOwned(12L, 7L);
    }

    @Test
    void correctionOnlyAcceptsADeclaredResultBeforeConfirmation() {
        PlayerTopicTagMapper mapper = mock(PlayerTopicTagMapper.class);
        PlayerTopicTagServiceImpl service = new PlayerTopicTagServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        when(mapper.updatePendingValueOwned(11L, 7L, "open")).thenReturn(1);
        when(mapper.selectOwnedById(11L, 7L)).thenReturn(tag("space_constraints", 0));

        service.correctPending(7L, 11L, "open", new HashSet<String>(Arrays.asList("compact", "open")));
        assertEquals("标签档位不在本站结果范围", assertThrows(IllegalArgumentException.class,
                () -> service.correctPending(7L, 11L, "invented", Collections.singleton("compact"))).getMessage());
    }

    private PlayerTopicTag tag(String code, int status) {
        PlayerTopicTag tag = new PlayerTopicTag();
        tag.setTagCode(code);
        tag.setStatus(status);
        tag.setTagValue("compact");
        return tag;
    }
}
