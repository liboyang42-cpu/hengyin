package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.service.impl.ExploreCollectibleServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExploreCollectibleServiceTest {
    @Test
    void recordsOneChapterStampUsingExistingCollectibleLedger() {
        MemberCollectibleMapper mapper = mock(MemberCollectibleMapper.class);
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        ExploreCollectibleServiceImpl service = new ExploreCollectibleServiceImpl();
        ReflectionTestUtils.setField(service, "memberCollectibleMapper", mapper);
        ReflectionTestUtils.setField(service, "topicChapterService", chapters);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        ReflectionTestUtils.setField(service, "merchantService", merchants);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(31L);
        chapter.setName("咖啡章节");
        when(chapters.selectCmsTopicChapterById(31L)).thenReturn(chapter);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setName("白鲸咖啡");
        when(merchants.selectMmsMerchantByMemberId(19L)).thenReturn(merchant);
        when(mapper.countOne(9L, MemberCollectible.TYPE_EXPLORE_STAMP, 31L)).thenReturn(0);
        when(mapper.insertMemberCollectible(any())).thenReturn(1);

        service.record(9L, 7L, 31L, 19L);

        verify(mapper).insertMemberCollectible(argThat(c ->
                c.getMemberId().equals(9L) && c.getTopicId().equals(7L)
                        && c.getRefId().equals(31L)
                        && c.getCollType() == MemberCollectible.TYPE_EXPLORE_STAMP
                        && "咖啡章节 · 白鲸咖啡".equals(c.getContent())));
        assertEquals(5, MemberCollectible.TYPE_EXPLORE_STAMP);
    }
}
