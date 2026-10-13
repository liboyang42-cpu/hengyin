package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.CoopPerkTemplate;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.CoopPerkTemplateMapper;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.common.utils.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CU-M-79：过期权益模板不得被物化成可承接核销的真实权益。
 *
 * <p>病：valid_end 的闸只写在「开放招商」(materializeStandalonePerkFromTemplate)那一条路上，
 * 批量申报 attachPerks 只调 {@code ensureUsablePerkTemplate}，而该方法当时只判零售价与份数
 * ⇒ 过期模板走批量申报照样落成 coop_perk，进承接容量与核销池。闸现在收进共享入口。</p>
 *
 * <p>边界按「日」判：validEnd 存的是当日 00:00，拿瞬时比会把「今天到期」的模板当天就判成过期。</p>
 */
class CoopPerkTemplateExpiryGateTest {
    private static final Long INVITE = 42L;
    private static final Long MERCHANT_MEMBER = 201L;
    private static final Long TEMPLATE = 71L;

    private CoopPerkMapper perkMapper;
    private CoopPerkTemplateMapper templateMapper;
    private CoopInviteServiceImpl service;

    @BeforeEach
    void setUp() {
        perkMapper = mock(CoopPerkMapper.class);
        templateMapper = mock(CoopPerkTemplateMapper.class);
        CoopInviteMapper inviteMapper = mock(CoopInviteMapper.class);
        service = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(service, "coopPerkMapper", perkMapper);
        ReflectionTestUtils.setField(service, "coopPerkTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "coopInviteMapper", inviteMapper);

        CoopInvite invite = new CoopInvite();
        invite.setId(INVITE);
        invite.setStatus(1);
        invite.setInviteType(0);
        invite.setFromId(11L);
        invite.setFromType("talent");
        invite.setToId(MERCHANT_MEMBER);
        invite.setToType("merchant");
        when(inviteMapper.selectCoopInviteById(INVITE)).thenReturn(invite);
    }

    @Test
    void attachPerksRejectsExpiredTemplateInsteadOfMaterializingIt() {
        when(templateMapper.selectById(TEMPLATE)).thenReturn(template(daysFromToday(-1)));

        assertThrows(IllegalStateException.class, () ->
                service.attachPerks(INVITE, MERCHANT_MEMBER, Collections.singletonList(TEMPLATE)));

        verify(perkMapper, never()).insertCoopPerk(any(CoopPerk.class));
        verify(perkMapper, never()).deleteByInviteId(anyLong());
    }

    // CU-M-79:与下游 offer 的 validEnd.after(now) 同口径 —— 今天到期(当日 00:00)已不在售,不得物化
    @Test
    void attachPerksRejectsTemplateThatExpiresToday() {
        when(templateMapper.selectById(TEMPLATE)).thenReturn(template(daysFromToday(0)));

        assertThrows(IllegalStateException.class, () ->
                service.attachPerks(INVITE, MERCHANT_MEMBER, Collections.singletonList(TEMPLATE)));

        verify(perkMapper, never()).insertCoopPerk(any(CoopPerk.class));
    }

    @Test
    void attachPerksStillMaterializesTemplateThatExpiresTomorrow() {
        when(templateMapper.selectById(TEMPLATE)).thenReturn(template(daysFromToday(1)));

        assertEquals(1, service.attachPerks(
                INVITE, MERCHANT_MEMBER, Collections.singletonList(TEMPLATE)));

        verify(perkMapper).insertCoopPerk(any(CoopPerk.class));
    }

    @Test
    void attachPerksStillMaterializesTemplateWithoutExpiry() {
        when(templateMapper.selectById(TEMPLATE)).thenReturn(template(null));

        assertEquals(1, service.attachPerks(
                INVITE, MERCHANT_MEMBER, Collections.singletonList(TEMPLATE)));

        verify(perkMapper).insertCoopPerk(any(CoopPerk.class));
    }

    private static CoopPerkTemplate template(Date validEnd) {
        CoopPerkTemplate template = new CoopPerkTemplate();
        template.setId(TEMPLATE);
        template.setMerchantId(MERCHANT_MEMBER);
        template.setPerkType(0);
        template.setName("咖啡兑换券");
        template.setRetailValue(new BigDecimal("88.00"));
        template.setQuota(20);
        template.setValidEnd(validEnd);
        return template;
    }

    private static Date daysFromToday(int days) {
        Calendar day = Calendar.getInstance();
        day.add(Calendar.DAY_OF_MONTH, days);
        return DateUtils.convertToStartOfDay(day.getTime());
    }
}
