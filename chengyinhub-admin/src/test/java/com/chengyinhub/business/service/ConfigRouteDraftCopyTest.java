package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConfigRouteDraftCopyTest {
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topics;
    private CmsTopicNodeMapper nodes;
    private ITemplatePublishService templates;
    private final String source = "{\"schemaVersion\":1,\"capacity\":6,\"configSource\":{\"schemaVersion\":1,\"route\":{\"id\":7,\"updatedAt\":\"v1\",\"nodes\":[{\"id\":10,\"playTemplateId\":77}]}}}";

    @BeforeEach void setup() throws Exception {
        service = new CmsTopicServiceImpl();
        for (Field f : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (!f.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) continue;
            f.setAccessible(true); if (f.get(service) == null) f.set(service, mock(f.getType()));
        }
        topics = (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper");
        nodes = (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
        templates = (ITemplatePublishService) ReflectionTestUtils.getField(service, "configTemplatePublishService");
        when(templates.copyPublicTemplateToDraft(77L,42L,"creator")).thenReturn(500L);
        when(topics.insertCmsTopic(any())).thenAnswer(i -> { ((CmsTopic)i.getArgument(0)).setId(900L); return 1; });
        when(topics.updateCmsTopic(any())).thenReturn(1);
        CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        when(chapters.insertCmsTopicChapter(any())).thenAnswer(i -> { ((CmsTopicChapter)i.getArgument(0)).setId(1000L); return 1; });
        AtomicLong ids = new AtomicLong(2000);
        when(nodes.insertCmsTopicNode(any())).thenAnswer(i -> { CmsTopicNode n=i.getArgument(0); assertNull(n.getId()); n.setId(ids.incrementAndGet()); return 1; });
        CmsMemberTemplateMapper memberTemplates=(CmsMemberTemplateMapper)ReflectionTestUtils.getField(service,"cmsMemberTemplateMapper");
        CmsMemberTemplate own = new CmsMemberTemplate(); own.setId(500L); own.setMemberId(42L); own.setDelFlag(0); own.setDraftStatus(0); own.setStatus(0L); own.setCheckStatus(0); own.setPublishStatus(0L);
        when(memberTemplates.selectCmsMemberTemplateById(500L)).thenReturn(own);
    }
    private TopicCreateDTO draft() {
        TopicCreateDTO dto=new TopicCreateDTO(); dto.setName("新路线"); dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("pro"); dto.setSaveDraft(1); dto.setThemeRuntimeConfig(source);
        dto.setTickets(Collections.emptyList()); dto.setCollaboratorIds(Collections.emptyList());
        ChapterDTO chapter=new ChapterDTO(); chapter.setName("路线");
        List<NodeDTO> list=new ArrayList<>();
        for(int i=0;i<2;i++){NodeDTO n=new NodeDTO(); n.setName("站"+i); n.setNodeTime(0); n.setSourceLibraryTemplateId(77L); n.setClientNodeKey("new"+i); list.add(n);}
        chapter.setNodes(list); dto.setChapters(Collections.singletonList(chapter)); return dto;
    }
    @Test void twoStationsShareOneNewPrivateCopyAndNeverReusePublicId() {
        assertEquals(900L,service.createTopicV2(draft(),42L,"creator"));
        verify(templates,times(1)).copyPublicTemplateToDraft(77L,42L,"creator");
        ArgumentCaptor<CmsTopicNode> captured=ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodes,times(2)).insertCmsTopicNode(captured.capture());
        assertNotEquals(captured.getAllValues().get(0).getId(),captured.getAllValues().get(1).getId());
        captured.getAllValues().forEach(n->assertEquals(500L,n.getTemplateId()));
        ArgumentCaptor<CmsTopic> config=ArgumentCaptor.forClass(CmsTopic.class); verify(topics,atLeastOnce()).updateCmsTopic(config.capture());
        assertTrue(config.getAllValues().stream().anyMatch(t->t.getThemeRuntimeConfigJson()!=null && t.getThemeRuntimeConfigJson().contains("\"77\":500")));
    }
    @Test void unavailableCopyFailsBeforeThemeInsertionAndNewSourceCannotDirectPublish() {
        when(templates.copyPublicTemplateToDraft(anyLong(),anyLong(),anyString())).thenThrow(new IllegalArgumentException("不可复制"));
        assertThrows(IllegalArgumentException.class,()->service.createTopicV2(draft(),42L,"creator"));
        verify(topics,never()).insertCmsTopic(any());
        TopicCreateDTO publish=draft(); publish.setSaveDraft(0);
        assertThrows(IllegalArgumentException.class,()->service.createTopicV2(publish,42L,"creator"));
    }
    @Test void oldDraftPublishedAndSoldContentCannotAdoptUpdatedSource() {
        CmsTopic topic=new CmsTopic(); topic.setId(900L); topic.setMemberId(42L); topic.setDelFlag(0);
        when(topics.selectCmsTopicById(900L)).thenReturn(topic); when(topics.selectCmsTopicByIdForUpdate(900L)).thenReturn(topic);
        for(int state: Arrays.asList(0,1,2)) {
            topic.setStatus(state); TopicUpdateDTO update=new TopicUpdateDTO(); update.setId(900L); update.setThemeRuntimeConfig(source);
            assertThrows(IllegalArgumentException.class,()->service.updateTopicByOwnerV2(update,42L,"creator"));
        }
        verify(templates,never()).copyPublicTemplateToDraft(anyLong(),anyLong(),anyString());
        verify(topics,never()).updateCmsTopic(any());
    }
}
