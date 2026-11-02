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

    /** P-34(第三轮):确认的终价写进作者的城市票;已是终价的票不重写。 */
    @Test
    void cityConfirmWritesTheConfirmedPriceOntoAuthoredTickets() {
        CmsTopic pricing = new CmsTopic();
        pricing.setId(705L); pricing.setMemberId(900001L); pricing.setLifecycle(2);
        pricing.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(705L)).thenReturn(pricing);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(705L)).thenReturn(Collections.emptyList());
        com.chengyinhub.business.domain.OmsTicket old = new com.chengyinhub.business.domain.OmsTicket();
        old.setId(905L); old.setName("领队场"); old.setPrice(new BigDecimal("69")); old.setDelFlag(0);
        com.chengyinhub.business.domain.OmsTicket same = new com.chengyinhub.business.domain.OmsTicket();
        same.setId(906L); same.setName("晚场"); same.setPrice(new BigDecimal("79")); same.setDelFlag(0);
        when(ticketMapper.selectOmsTicketList(any(com.chengyinhub.business.domain.OmsTicket.class))).thenAnswer(inv -> {
            com.chengyinhub.business.domain.OmsTicket q = inv.getArgument(0);
            return "guided".equals(q.getSubType()) ? Collections.emptyList() : java.util.Arrays.asList(old, same);
        });
        java.util.List<com.chengyinhub.business.domain.OmsTicket> writes = new java.util.ArrayList<>();
        when(ticketMapper.updateOmsTicket(any(com.chengyinhub.business.domain.OmsTicket.class))).thenAnswer(inv -> {
            writes.add(inv.getArgument(0));
            return 1;
        });

        service.confirmPrice(705L, new BigDecimal("79"), null, null, null, null);

        org.junit.jupiter.api.Assertions.assertEquals(1, writes.size());
        org.junit.jupiter.api.Assertions.assertEquals(905L, writes.get(0).getId());
        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal("79").compareTo(writes.get(0).getPrice()));
        org.junit.jupiter.api.Assertions.assertNull(writes.get(0).getTotalInventory(), "库存不动");
        org.junit.jupiter.api.Assertions.assertNull(writes.get(0).getRemainingInventory(), "库存不动");
        verify(ticketMapper, org.mockito.Mockito.never()).insertOmsTicket(any(com.chengyinhub.business.domain.OmsTicket.class));
    }

    /** P-34(第三轮):作者的票并发变化(写 0 行)时不报确认成功,整笔回滚。 */
    @Test
    void cityConfirmRefusesWhenAnAuthoredTicketChangedUnderneath() {
        CmsTopic pricing = new CmsTopic();
        pricing.setId(706L); pricing.setMemberId(900001L); pricing.setLifecycle(2);
        pricing.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(706L)).thenReturn(pricing);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(706L)).thenReturn(Collections.emptyList());
        com.chengyinhub.business.domain.OmsTicket authored = new com.chengyinhub.business.domain.OmsTicket();
        authored.setId(907L); authored.setName("领队场"); authored.setPrice(new BigDecimal("69")); authored.setDelFlag(0);
        when(ticketMapper.selectOmsTicketList(any(com.chengyinhub.business.domain.OmsTicket.class))).thenAnswer(inv -> {
            com.chengyinhub.business.domain.OmsTicket q = inv.getArgument(0);
            return "guided".equals(q.getSubType()) ? Collections.emptyList() : Collections.singletonList(authored);
        });
        when(ticketMapper.updateOmsTicket(any(com.chengyinhub.business.domain.OmsTicket.class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(706L, new BigDecimal("79"), null, null, null, null));
    }

    /** P-34(第三轮):开售后的预览标 priced=true,定价页据此不再说「合作阵容锁定后可定价」;定价阶段为 false。 */
    @Test
    void previewMarksPricedOnceTheThemeIsPastPricing() {
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(707L)).thenReturn(Collections.emptyList());
        when(ticketMapper.selectOmsTicketList(any(com.chengyinhub.business.domain.OmsTicket.class))).thenReturn(Collections.emptyList());
        CmsTopic selling = new CmsTopic();
        selling.setId(707L); selling.setLifecycle(3);
        when(cmsTopicMapper.selectCmsTopicLifecycleById(707L)).thenReturn(selling);
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, service.previewTopic(707L, null, null, "guided").getPriced());
        selling.setLifecycle(2);
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, service.previewTopic(707L, null, null, "guided").getPriced());
        selling.setLifecycle(1);
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, service.previewTopic(707L, null, null, "guided").getPriced());
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
