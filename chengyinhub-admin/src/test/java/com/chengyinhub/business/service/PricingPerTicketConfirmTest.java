package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.PricingResult;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第四轮(业主:定价页逐张确认):城市定向作者发了几张不同价的票时,
 * 预览逐张列出,确认时每张写自己的终价,每张都按地板价校验,库存不动。
 */
class PricingPerTicketConfirmTest {

    private static final long TOPIC = 811L;

    private CmsTopicMapper cmsTopicMapper;
    private OmsTicketMapper ticketMapper;
    private PricingServiceImpl service;
    private OmsTicket day;
    private OmsTicket night;
    private final List<OmsTicket> writes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cmsTopicMapper = mock(CmsTopicMapper.class);
        CoopOrderMapper coopOrderMapper = mock(CoopOrderMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        service = new PricingServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(service, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "coopPerkMapper", mock(CoopPerkMapper.class));
        ReflectionTestUtils.setField(service, "offerMapper", mock(MerchantChapterOfferMapper.class));
        ReflectionTestUtils.setField(service, "settlementRuleService", PricingTestRules.ruleService());

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC); topic.setMemberId(900001L); topic.setLifecycle(2);
        topic.setProductType(ProductType.ORIENTEERING);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(cmsTopicMapper.selectCmsTopicLifecycleById(TOPIC)).thenReturn(topic);
        when(cmsTopicMapper.selectProductTypeById(TOPIC)).thenReturn(ProductType.ORIENTEERING);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Collections.emptyList());

        day = ticket(931L, "日场", "69");
        night = ticket(932L, "夜场", "99");
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenAnswer(inv -> {
            OmsTicket q = inv.getArgument(0);
            return "guided".equals(q.getSubType()) ? Collections.emptyList() : Arrays.asList(day, night);
        });
        when(ticketMapper.updateOmsTicket(any(OmsTicket.class))).thenAnswer(inv -> {
            writes.add(inv.getArgument(0));
            return 1;
        });
    }

    private static OmsTicket ticket(long id, String name, String price) {
        OmsTicket t = new OmsTicket();
        t.setId(id); t.setName(name); t.setPrice(new BigDecimal(price)); t.setDelFlag(0);
        t.setTotalInventory(20L); t.setRemainingInventory(20L);
        return t;
    }

    private static Map<Long, BigDecimal> prices(long a, String pa, long b, String pb) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        m.put(a, new BigDecimal(pa));
        m.put(b, new BigDecimal(pb));
        return m;
    }

    @Test
    void previewListsEachAuthoredTicketWithItsOwnPrice() {
        PricingResult r = service.previewTopic(TOPIC, null, null, "guided");
        assertEquals(2, r.getTickets().size());
        assertEquals(931L, r.getTickets().get(0).getTicketId());
        assertEquals("日场", r.getTickets().get(0).getName());
        assertEquals(0, new BigDecimal("69").compareTo(r.getTickets().get(0).getPrice()));
        assertEquals(0, new BigDecimal("99").compareTo(r.getTickets().get(1).getPrice()));
        assertTrue(service.previewTopic(TOPIC, null, null, "self").getTickets().isEmpty(), "无人带档不列作者票");
    }

    @Test
    void previewListsNothingForNonCityThemes() {
        when(cmsTopicMapper.selectProductTypeById(TOPIC)).thenReturn(ProductType.FREE_EXPLORE);
        assertTrue(service.previewTopic(TOPIC, null, null, "guided").getTickets().isEmpty());
    }

    @Test
    void confirmWritesEachTicketsOwnPriceAndLeavesInventoryAlone() {
        service.confirmPrice(TOPIC, null, null, null, null, null, prices(931L, "79", 932L, "109"));

        assertEquals(2, writes.size());
        assertEquals(931L, writes.get(0).getId());
        assertEquals(0, new BigDecimal("79").compareTo(writes.get(0).getPrice()));
        assertEquals(932L, writes.get(1).getId());
        assertEquals(0, new BigDecimal("109").compareTo(writes.get(1).getPrice()));
        for (OmsTicket w : writes) {
            assertNull(w.getTotalInventory(), "库存不动");
            assertNull(w.getRemainingInventory(), "库存不动");
        }
        verify(ticketMapper, never()).insertOmsTicket(any(OmsTicket.class));
    }

    @Test
    void confirmSkipsATicketWhosePriceIsUnchanged() {
        service.confirmPrice(TOPIC, null, null, null, null, null, prices(931L, "69", 932L, "109"));
        assertEquals(1, writes.size());
        assertEquals(932L, writes.get(0).getId());
    }

    @Test
    void everyTicketIsCheckedAgainstTheFloor() {
        // 带队成本 100 / 1 人 ⇒ 地板 > 100;日场 79 低于地板,整笔拒
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.confirmPrice(
                TOPIC, null, null, null, new BigDecimal("100"), 1, prices(931L, "79", 932L, "300")));
        assertTrue(e.getMessage().startsWith("有人带票价不能低于底价"), e.getMessage());
        assertTrue(writes.isEmpty());
    }

    @Test
    void aTicketOfAnotherThemeOrAMissingTicketIsRefused() {
        assertThrows(IllegalStateException.class, () -> service.confirmPrice(
                TOPIC, null, null, null, null, null, prices(931L, "79", 999L, "109")));
        Map<Long, BigDecimal> onlyOne = new LinkedHashMap<>();
        onlyOne.put(931L, new BigDecimal("79"));
        assertThrows(IllegalStateException.class, () -> service.confirmPrice(
                TOPIC, null, null, null, null, null, onlyOne));
        assertTrue(writes.isEmpty());
    }

    @Test
    void withoutTicketPricesTheSinglePriceStillAppliesToAll() {
        service.confirmPrice(TOPIC, new BigDecimal("89"), null, null, null, null);
        assertEquals(2, writes.size());
        for (OmsTicket w : writes) assertEquals(0, new BigDecimal("89").compareTo(w.getPrice()));
    }
}
