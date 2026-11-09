package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IMmsMerchantService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** P-39:探店图鉴写本章这家店的节点名,不写商家账号名。 */
class ExploreCollectibleShopNameTest {

    private ExploreCollectibleServiceImpl service(ICmsTopicNodeService nodes, MemberCollectibleMapper mapper) {
        ExploreCollectibleServiceImpl s = new ExploreCollectibleServiceImpl();
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setName("第一章");
        when(chapters.selectCmsTopicChapterById(42L)).thenReturn(chapter);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant merchant = new MmsMerchant(); merchant.setName("探店日样板咖啡");
        when(merchants.selectMmsMerchantByMemberId(9002L)).thenReturn(merchant);
        when(mapper.insertMemberCollectible(any())).thenReturn(1);
        ReflectionTestUtils.setField(s, "memberCollectibleMapper", mapper);
        ReflectionTestUtils.setField(s, "topicChapterService", chapters);
        ReflectionTestUtils.setField(s, "merchantService", merchants);
        ReflectionTestUtils.setField(s, "topicNodeService", nodes);
        return s;
    }

    private String recorded(MemberCollectibleMapper mapper) {
        ArgumentCaptor<MemberCollectible> row = ArgumentCaptor.forClass(MemberCollectible.class);
        verify(mapper).insertMemberCollectible(row.capture());
        return row.getValue().getContent();
    }

    @Test void stampUsesTheShopNodeName() {
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        CmsTopicNode node = new CmsTopicNode(); node.setChapterId(42L); node.setMerchantMemberId(9002L); node.setName("梧桐咖啡 · 武康路店");
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        MemberCollectibleMapper mapper = mock(MemberCollectibleMapper.class);
        service(nodes, mapper).record(9004L, 990014L, 42L, 9002L);
        assertEquals("第一章 · 梧桐咖啡 · 武康路店", recorded(mapper));
    }

    @Test void fallsBackToMerchantNameWithoutNode() {
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        MemberCollectibleMapper mapper = mock(MemberCollectibleMapper.class);
        service(nodes, mapper).record(9004L, 990014L, 42L, 9002L);
        assertEquals("第一章 · 探店日样板咖啡", recorded(mapper));
    }
}
