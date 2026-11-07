package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.TopicContentCopyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * p3 N6(第十二轮 k9):「应用」主题模板(copyType 3)要把模板的主题票种带进草稿,
 * 分类同样保留;移交俱乐部 / 另存为模板(copyType 2)仍不带票。
 */
class TopicTemplateUseTicketCopyTest {

    private static final Long TEMPLATE = 990015L;
    private static final Long DRAFT = 990016L;
    private static final Long MEMBER = 9003L;

    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final OmsTicketMapper tickets = mock(OmsTicketMapper.class);
    private final TopicContentCopyServiceImpl copy = new TopicContentCopyServiceImpl();

    @BeforeEach
    void setUp() {
        CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        CmsCollaboratorsMapper collaborators = mock(CmsCollaboratorsMapper.class);
        when(collaborators.selectCmsCollaboratorsList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(copy, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(copy, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(copy, "cmsCollaboratorsMapper", collaborators);
        ReflectionTestUtils.setField(copy, "omsTicketMapper", tickets);
        ReflectionTestUtils.setField(copy, "productRoleService", mock(IProductRoleService.class));

        CmsTopic template = new CmsTopic();
        template.setId(TEMPLATE);
        template.setMemberId(9009L);
        template.setName("人民广场 · 喷泉报时");
        template.setCategoryIds("3,7");
        template.setDelFlag(0);
        when(topics.selectCmsTopicById(TEMPLATE)).thenReturn(template);
        doAnswer(inv -> { ((CmsTopic) inv.getArgument(0)).setId(DRAFT); return 1; })
                .when(topics).insertCmsTopic(any(CmsTopic.class));

        OmsTicket ticket = new OmsTicket();
        ticket.setId(81L);
        ticket.setOwnerType(1L);
        ticket.setOwnerId(TEMPLATE);
        ticket.setMemberId(9009L);
        ticket.setName("领队场");
        ticket.setPrice(new BigDecimal("39.00"));
        ticket.setTotalInventory(20L);
        ticket.setRemainingInventory(17L);
        ticket.setMode(2);
        ticket.setTeamSize(4);
        ticket.setStartTime(new Date(1_800_000_000_000L));
        ticket.setRefundRule("集合前24小时");
        when(tickets.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Arrays.asList(ticket));
        when(tickets.insertOmsTicket(any(OmsTicket.class))).thenReturn(1);
    }

    @Test
    void useTemplateCopiesTopicTicketTypesAndCategoryIntoDraft() {
        Map<String, Object> params = new HashMap<>();
        params.put("copyType", 3);
        params.put("memberId", MEMBER);
        params.put("saveDraft", true);
        when(topics.updateTopicDraftState(DRAFT, 1)).thenReturn(1);

        assertEquals(DRAFT, copy.copyCmsTopic(TEMPLATE, params));
        verify(topics).updateTopicDraftState(DRAFT, 1);

        ArgumentCaptor<CmsTopic> inserted = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topics).insertCmsTopic(inserted.capture());
        assertEquals("3,7", inserted.getValue().getCategoryIds());

        ArgumentCaptor<OmsTicket> query = ArgumentCaptor.forClass(OmsTicket.class);
        verify(tickets).selectOmsTicketList(query.capture());
        assertEquals(Long.valueOf(1L), query.getValue().getOwnerType());
        assertEquals(TEMPLATE, query.getValue().getOwnerId());
        assertEquals(Integer.valueOf(0), query.getValue().getDelFlag());

        ArgumentCaptor<OmsTicket> written = ArgumentCaptor.forClass(OmsTicket.class);
        verify(tickets, times(1)).insertOmsTicket(written.capture());
        OmsTicket t = written.getValue();
        assertNull(t.getId());
        assertEquals(Long.valueOf(1L), t.getOwnerType());
        assertEquals(DRAFT, t.getOwnerId());
        assertEquals(MEMBER, t.getMemberId());
        assertEquals("领队场", t.getName());
        assertEquals(new BigDecimal("39.00"), t.getPrice());
        assertEquals(Long.valueOf(20L), t.getTotalInventory());
        assertEquals(Long.valueOf(20L), t.getRemainingInventory(), "库存回满,不带来源售出");
        assertEquals(Integer.valueOf(2), t.getMode());
        assertEquals(Integer.valueOf(4), t.getTeamSize());
        assertEquals("集合前24小时", t.getRefundRule());
        assertNull(t.getSourceTicketId());
        assertEquals(Integer.valueOf(0), t.getDelFlag());
    }

    @Test
    void contentOnlyCopyStillLeavesTicketsBehind() {
        Map<String, Object> params = new HashMap<>();
        params.put("copyType", 2);
        params.put("memberId", MEMBER);

        copy.copyCmsTopic(TEMPLATE, params);

        verify(tickets, never()).selectOmsTicketList(any());
        verify(tickets, never()).insertOmsTicket(any());
        verify(topics, never()).updateTopicDraftState(any(), any());
    }

    @Test
    void explicitFalseDoesNotMarkGenericFullCopyAsDraft() {
        assertEquals(DRAFT, copy.copyCmsTopic(TEMPLATE,
                Map.of("copyType", 3, "memberId", MEMBER, "saveDraft", false)));

        verify(tickets).insertOmsTicket(any());
        verify(topics, never()).updateTopicDraftState(any(), any());
    }

    @Test
    void draftWriteMustAffectExactlyOneRowBeforeReturning() {
        for (int rows : new int[]{0, 2}) {
            when(topics.updateTopicDraftState(DRAFT, 1)).thenReturn(rows);
            assertThrows(IllegalStateException.class, () -> copy.copyCmsTopic(TEMPLATE,
                    Map.of("copyType", 3, "memberId", MEMBER, "saveDraft", true)));
        }

        verify(tickets, times(2)).insertOmsTicket(any());
        verify(topics, times(2)).updateTopicDraftState(DRAFT, 1);
    }

}
