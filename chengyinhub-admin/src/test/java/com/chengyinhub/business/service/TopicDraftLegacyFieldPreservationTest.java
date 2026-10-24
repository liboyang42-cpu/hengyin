package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real owner-update path, capturing the SQL patch; this is not a database readback test. */
class TopicDraftLegacyFieldPreservationTest {
    private static final Long TOPIC = 700L;
    private static final Long MEMBER = 42L;
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topicMapper;
    private CmsTopic existing;

    @BeforeEach
    void setUp() throws Exception {
        service = new CmsTopicServiceImpl();
        for (Field field : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            if (field.get(service) == null
                    && field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                ReflectionTestUtils.setField(service, field.getName(), mock(field.getType()));
            }
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(service, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        topicMapper = (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper");
        CmsTopicChapterMapper chapterMapper =
                (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        CmsTopicNodeMapper nodeMapper =
                (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
        OmsTicketMapper ticketMapper =
                (OmsTicketMapper) ReflectionTestUtils.getField(service, "omsTicketMapper");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.emptyList());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.emptyList());
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.emptyList());
        existing = new CmsTopic();
        existing.setId(TOPIC);
        existing.setMemberId(MEMBER);
        existing.setName("已有草稿");
        existing.setStatus(0);
        existing.setLifecycle(0);
        existing.setDelFlag(0);
        existing.setProductType(ProductType.ORIENTEERING);
        existing.setPublishMode("simple");
        existing.setTeamMode(2);
        existing.setTeamMaxMembers(3);
        existing.setSelfPlay(1);
        existing.setSelfPlayPrice(new BigDecimal("8.00"));
        existing.setSelfPlayQuota(9);
        existing.setXpBudget(6200);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(existing);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(1);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
    }

    private TopicUpdateDTO legacyEdit() {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("只改名称");
        dto.setChapters(new ArrayList<ChapterDTO>());
        dto.setTickets(new ArrayList<ActivityTicketRequest>());
        return dto;
    }

    private CmsTopic savedPatch(TopicUpdateDTO dto) {
        assertEquals(TOPIC, com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(dto, MEMBER, "creator"));
        ArgumentCaptor<CmsTopic> patch = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(patch.capture());
        verify(topicMapper, never()).insertCmsTopic(any(CmsTopic.class));
        return patch.getValue();
    }

    @Test
    void omittedFieldsKeepExistingAuthorConfiguration() {
        CmsTopic patch = savedPatch(legacyEdit());
        assertEquals("simple", patch.getPublishMode());
        assertEquals(Integer.valueOf(2), patch.getTeamMode());
        assertEquals(Integer.valueOf(3), patch.getTeamMaxMembers());
        assertEquals(Integer.valueOf(1), patch.getSelfPlay());
        assertEquals(new BigDecimal("8.00"), patch.getSelfPlayPrice());
        assertEquals(Integer.valueOf(9), patch.getSelfPlayQuota());
        assertEquals(Integer.valueOf(6200), patch.getXpBudget());
    }

    @Test
    void freeExploreWithoutModeInLegacyPayloadKeepsItsProductType() {
        existing.setProductType(ProductType.FREE_EXPLORE);
        existing.setSelfPlay(0);
        CmsTopic patch = savedPatch(legacyEdit());
        assertEquals(Integer.valueOf(ProductType.FREE_EXPLORE), patch.getProductType());
    }

    @Test
    void explicitConfigurationChangesStillApply() {
        TopicUpdateDTO dto = legacyEdit();
        dto.setPublishMode("pro");
        dto.setTeamMode(0);
        dto.setTeamMaxMembers(4);
        dto.setSelfPlay(0);
        dto.setXpBudget(1200);
        CmsTopic patch = savedPatch(dto);
        assertEquals("pro", patch.getPublishMode());
        assertEquals(Integer.valueOf(0), patch.getTeamMode());
        assertEquals(Integer.valueOf(4), patch.getTeamMaxMembers());
        assertEquals(Integer.valueOf(0), patch.getSelfPlay());
        assertEquals(Integer.valueOf(1200), patch.getXpBudget());
    }

    @Test
    void oldTicketModeStillSelectsProductTypeWhenExplicitTypeIsAbsent() {
        ActivityTicketRequest ticket = new ActivityTicketRequest();
        ticket.setName("尚未定时的票");
        ticket.setMode(ProductType.FREE_EXPLORE);
        ticket.setPrice(BigDecimal.ZERO);
        ticket.setTotalStock(10);
        TopicUpdateDTO dto = legacyEdit();
        dto.setTickets(new ArrayList<ActivityTicketRequest>(Collections.singletonList(ticket)));
        CmsTopic patch = savedPatch(dto);
        assertEquals(Integer.valueOf(ProductType.FREE_EXPLORE), patch.getProductType());
    }

    @Test
    void explicitInvalidTeamValueIsStillRejectedBeforeWriting() {
        TopicUpdateDTO dto = legacyEdit();
        dto.setTeamMode(7);
        assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(service).updateTopicByOwner(dto, MEMBER, "creator"));
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }
}
