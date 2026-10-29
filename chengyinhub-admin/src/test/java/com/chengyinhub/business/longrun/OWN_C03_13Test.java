package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Both real internal rebuild paths, injected mapper return/key failures; no transaction proof. */
class OWN_C03_13Test {
    @Test void chapterZeroRowsCannotBeUsedEvenIfIdIsPresent() {
        for (boolean compiled : new boolean[]{false, true}) {
            Fixture f = new Fixture(compiled);
            f.chapterResult(0, 801L);
            assertThrows(IllegalStateException.class, f::save);
            verify(f.nodes, never()).insertCmsTopicNode(any());
        }
    }
    @Test void missingChapterIdentityStopsBeforeNodes() {
        for (boolean compiled : new boolean[]{false, true}) {
            Fixture f = new Fixture(compiled);
            f.chapterResult(1, null);
            assertThrows(IllegalStateException.class, f::save);
            verify(f.nodes, never()).insertCmsTopicNode(any());
        }
    }
    @Test void nodeZeroRowsCannotReachMaterializedBlocks() {
        for (boolean compiled : new boolean[]{false, true}) {
            Fixture f = new Fixture(compiled);
            f.nodeResult(0, 901L);
            assertThrows(IllegalStateException.class, f::save);
            verify(f.chapters, never()).updateCmsTopicChapter(any());
        }
    }
    @Test void missingNodeIdentityCannotBeSilentlySkipped() {
        for (boolean compiled : new boolean[]{false, true}) {
            Fixture f = new Fixture(compiled);
            f.nodeResult(1, null);
            assertThrows(IllegalStateException.class, f::save);
            verify(f.chapters, never()).updateCmsTopicChapter(any());
        }
    }
    @Test void positiveGeneratedIdentitiesKeepBothPathsWorking() {
        for (boolean compiled : new boolean[]{false, true}) {
            Fixture f = new Fixture(compiled);
            assertNotNull(f.save());
            verify(f.chapters).insertCmsTopicChapter(any(CmsTopicChapter.class));
            verify(f.nodes).insertCmsTopicNode(any(CmsTopicNode.class));
            if (compiled) verify(f.chapters).updateCmsTopicChapter(any(CmsTopicChapter.class));
        }
    }

    private static class Fixture {
        final CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        final com.chengyinhub.business.mapper.CmsTopicMapper topics =
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final ChapterDTO chapter = new ChapterDTO();
        final boolean compiled;
        Fixture(boolean compiled) {
            this.compiled = compiled;
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
            when(topics.lockTopicContentBudget(700L)).thenReturn(700L);
            ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapters);
            ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
            NodeDTO node = new NodeDTO();
            node.setId(11L);
            node.setName("节点");
            node.setNodeTime(1);
            chapter.setId(1L);
            chapter.setName("章节");
            chapter.setNodes(Collections.singletonList(node));
            if (compiled) {
                chapter.setSchemaVersion(1);
                ChapterBlockDTO block = new ChapterBlockDTO();
                block.setType("node");
                block.setNodeIndex(0);
                chapter.setBlocks(Collections.singletonList(block));
            }
            chapterResult(1, 801L);
            nodeResult(1, 901L);
            when(chapters.updateCmsTopicChapter(any(CmsTopicChapter.class))).thenReturn(1);
        }
        void chapterResult(int rows, Long id) {
            doAnswer(call -> { ((CmsTopicChapter) call.getArgument(0)).setId(id); return rows; })
                    .when(chapters).insertCmsTopicChapter(any(CmsTopicChapter.class));
        }
        void nodeResult(int rows, Long id) {
            doAnswer(call -> { ((CmsTopicNode) call.getArgument(0)).setId(id); return rows; })
                    .when(nodes).insertCmsTopicNode(any(CmsTopicNode.class));
        }
        Object save() {
            if (compiled) return ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.graph(service), "compileChapterFlow",
                    Collections.singletonList(chapter), 700L, "creator", 42L, false, false);
            return ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.graph(service), "saveChaptersAndNodes",
                    Collections.singletonList(chapter), 700L, "creator", 42L, false);
        }
    }
}
