package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopPerkTemplate;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.CoopPerkTemplateMapper;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoopLegacyPerkTemplateGuardTest {
    private CoopInviteMapper inviteMapper;
    private CoopPerkTemplateMapper templateMapper;
    private CoopPerkMapper perkMapper;
    private CoopInviteServiceImpl service;

    @BeforeEach
    void setUp() {
        inviteMapper = mock(CoopInviteMapper.class);
        templateMapper = mock(CoopPerkTemplateMapper.class);
        perkMapper = mock(CoopPerkMapper.class);
        service = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(service, "coopInviteMapper", inviteMapper);
        ReflectionTestUtils.setField(service, "coopPerkTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "coopPerkMapper", perkMapper);
    }

    @Test
    void attachRejectsLegacyTemplateBeforeReplacingExistingDeclaration() {
        CoopInvite invite = new CoopInvite();
        invite.setId(31L);
        invite.setToId(201L);
        invite.setToType("merchant");
        CoopPerkTemplate legacy = new CoopPerkTemplate();
        legacy.setId(7L);
        legacy.setMerchantId(201L);
        legacy.setName("旧权益");
        when(inviteMapper.selectCoopInviteById(31L)).thenReturn(invite);
        when(templateMapper.selectById(7L)).thenReturn(legacy);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.attachPerks(31L, 201L, Collections.singletonList(7L)));

        assertEquals("权益模板缺少零售价或可接待份数，请删除后重新创建", error.getMessage());
        verify(perkMapper, never()).deleteByInviteId(31L);
        verify(perkMapper, never()).insertCoopPerk(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void standaloneOfferRejectsLegacyTemplate() {
        CoopPerkTemplate legacy = new CoopPerkTemplate();
        legacy.setId(7L);
        legacy.setMerchantId(201L);
        when(templateMapper.selectById(7L)).thenReturn(legacy);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.materializeStandalonePerkFromTemplate(7L, 201L, 10));

        assertEquals("权益模板缺少零售价或可接待份数，请删除后重新创建", error.getMessage());
        verify(perkMapper, never()).insertCoopPerk(org.mockito.ArgumentMatchers.any());
    }
}
