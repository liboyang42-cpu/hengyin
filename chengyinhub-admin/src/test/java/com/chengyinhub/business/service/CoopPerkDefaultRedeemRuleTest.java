package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.CoopPerkTemplate;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.CoopPerkTemplateMapper;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M-01:商家「常备权益」表单没有核销规则字段。旧代码把空规则一路带进 coop_perk,
 * 开售快照要求 redeemRule 非空 ⇒ 一家店的权益卡住整个主题开售。
 * 现在空规则落为「到店出示核销码，店员扫码核销」，商家写过的规则原样保留。
 */
class CoopPerkDefaultRedeemRuleTest {
    private static final Long MERCHANT = 201L;
    private CoopPerkMapper perkMapper;
    private CoopPerkTemplateMapper templateMapper;
    private CoopInviteServiceImpl service;

    @BeforeEach
    void setUp() {
        perkMapper = mock(CoopPerkMapper.class);
        templateMapper = mock(CoopPerkTemplateMapper.class);
        service = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(service, "coopPerkMapper", perkMapper);
        ReflectionTestUtils.setField(service, "coopPerkTemplateMapper", templateMapper);
    }

    @Test
    void templateSavedFromTheMerchantFormGetsTheDefaultRule() {
        CoopPerkTemplate t = template(null);
        service.savePerkTemplate(t);
        ArgumentCaptor<CoopPerkTemplate> saved = ArgumentCaptor.forClass(CoopPerkTemplate.class);
        verify(templateMapper).insertCoopPerkTemplate(saved.capture());
        assertEquals(CoopPerk.DEFAULT_REDEEM_RULE, saved.getValue().getRedeemRule());
    }

    @Test
    void legacyTemplateWithoutRuleMaterializesWithDefaultRule() {
        when(templateMapper.selectById(71L)).thenReturn(template("  "));
        service.materializeStandalonePerkFromTemplate(71L, MERCHANT, 20);
        ArgumentCaptor<CoopPerk> perk = ArgumentCaptor.forClass(CoopPerk.class);
        verify(perkMapper).insertCoopPerk(perk.capture());
        assertEquals(CoopPerk.DEFAULT_REDEEM_RULE, perk.getValue().getRedeemRule());
    }

    @Test
    void merchantWrittenRuleIsKept() {
        when(templateMapper.selectById(71L)).thenReturn(template("凭明信片换一杯手冲"));
        service.materializeStandalonePerkFromTemplate(71L, MERCHANT, 20);
        ArgumentCaptor<CoopPerk> perk = ArgumentCaptor.forClass(CoopPerk.class);
        verify(perkMapper).insertCoopPerk(perk.capture());
        assertEquals("凭明信片换一杯手冲", perk.getValue().getRedeemRule());
    }

    private static CoopPerkTemplate template(String rule) {
        CoopPerkTemplate t = new CoopPerkTemplate();
        t.setId(71L);
        t.setMerchantId(MERCHANT);
        t.setPerkType(0);
        t.setName("手冲一杯 + 日期章明信片");
        t.setRetailValue(new BigDecimal("38"));
        t.setUnitCost(new BigDecimal("12"));
        t.setQuota(30);
        t.setRedeemRule(rule);
        return t;
    }
}
