package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 锁价只把已接受且尚未缴费的合作推进到应缴态,不把支付提前到接受邀约。 */
class PricingDepositDueTest {

    private CmsTopicMapper cmsTopicMapper;
    private CoopOrderMapper coopOrderMapper;
    private OmsTicketMapper ticketMapper;
    private PricingServiceImpl service;

    @BeforeEach
    void setUp() {
        cmsTopicMapper = mock(CmsTopicMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        service = new PricingServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(service, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(service, "coopPerkMapper", mock(CoopPerkMapper.class));
        ReflectionTestUtils.setField(service, "offerMapper", mock(MerchantChapterOfferMapper.class));
        ReflectionTestUtils.setField(service, "settlementRuleService", mock(ISettlementRuleService.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
    }

    @Test
    void priceConfirmationStopsAtPricingForExplicitCanonicalSubmission() {
        CmsTopic pricing = new CmsTopic();
        pricing.setId(702L); pricing.setMemberId(900001L); pricing.setLifecycle(2);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(702L)).thenReturn(pricing);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(702L)).thenReturn(Collections.emptyList());

        service.confirmPrice(702L, null, new BigDecimal("100"), null, null, null);

        verify(cmsTopicMapper).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void confirmPriceRefusesSuccessWhenGuidedTicketInventoryChanged() {
        CmsTopic pricing = new CmsTopic();
        pricing.setId(701L); pricing.setMemberId(900001L); pricing.setLifecycle(2);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(701L)).thenReturn(pricing);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(701L)).thenReturn(Collections.emptyList());
        com.chengyinhub.business.domain.OmsTicket guided = new com.chengyinhub.business.domain.OmsTicket();
        guided.setId(901L);
        when(ticketMapper.selectOmsTicketList(any(com.chengyinhub.business.domain.OmsTicket.class)))
                .thenReturn(Collections.singletonList(guided));
        when(ticketMapper.updateOmsTicket(any(com.chengyinhub.business.domain.OmsTicket.class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(701L, new BigDecimal("100"), null, 10, null, null));

        verify(ticketMapper).updateOmsTicket(any(com.chengyinhub.business.domain.OmsTicket.class));
    }

    /** P-01:编辑器发的城市定向票不带 sub_type;锁价沿用作者的票,不再插一张无时间的「有人带」票。 */
    @Test
    void cityConfirmAdoptsAuthoredTicketsInsteadOfInsertingATimelessGuidedOne() {
        CmsTopic pricing = new CmsTopic();
        pricing.setId(703L); pricing.setMemberId(900001L); pricing.setLifecycle(2);
        pricing.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(703L)).thenReturn(pricing);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(703L)).thenReturn(Collections.emptyList());
        com.chengyinhub.business.domain.OmsTicket authored = new com.chengyinhub.business.domain.OmsTicket();
        authored.setId(902L); authored.setName("领队场"); authored.setPrice(new BigDecimal("69")); authored.setDelFlag(0);
        when(ticketMapper.selectOmsTicketList(any(com.chengyinhub.business.domain.OmsTicket.class))).thenAnswer(inv -> {
            com.chengyinhub.business.domain.OmsTicket q = inv.getArgument(0);
            return "guided".equals(q.getSubType()) ? Collections.emptyList() : Collections.singletonList(authored);
        });

        service.confirmPrice(703L, new BigDecimal("69"), null, null, null, null);

        org.mockito.Mockito.verify(ticketMapper, org.mockito.Mockito.never())
                .insertOmsTicket(any(com.chengyinhub.business.domain.OmsTicket.class));
    }

    /** 自由探索等非城市主题照旧:没有 guided 票就插一张(原口径不动)。 */
    @Test
    void nonCityConfirmStillInsertsGuidedTicket() {
        CmsTopic pricing = new CmsTopic();
        pricing.setId(704L); pricing.setMemberId(900001L); pricing.setLifecycle(2);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(704L)).thenReturn(pricing);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(704L)).thenReturn(Collections.emptyList());
        when(ticketMapper.selectOmsTicketList(any(com.chengyinhub.business.domain.OmsTicket.class)))
                .thenReturn(Collections.emptyList());

        service.confirmPrice(704L, new BigDecimal("69"), null, null, null, null);

        verify(ticketMapper).insertOmsTicket(any(com.chengyinhub.business.domain.OmsTicket.class));
    }
}
