package com.chengyinhub.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;

import com.chengyinhub.common.exception.ServiceException;

/**
 * 第十二轮 k1 第 6 项(p3 N1):另存为模板必须复制,不能把在售主题本身改成模板。
 * 旧实现 update cms_topic set is_template=1 where id=原主题 —— 原主题从在售口径消失、无路可回。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicSaveAsTemplateCopyTest {

    @Mock private CmsTopicMapper cmsTopicMapper;
    @Mock private ITopicContentCopyService topicContentCopyService;
    @InjectMocks private CmsTopicServiceImpl service;

    private CmsTopic live(long id, long owner) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setMemberId(owner);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setCheckStatus(1);
        topic.setDelFlag(0);
        return topic;
    }

    @Test
    void copiesAndMarksOnlyTheCopyAsTemplate() {
        when(cmsTopicMapper.selectCmsTopicById(70L)).thenReturn(live(70L, 9L));
        when(topicContentCopyService.copyCmsTopic(eq(70L), any())).thenReturn(170L);
        when(cmsTopicMapper.markTopicTemplateCopy(170L, 9L, 1)).thenReturn(1);

        assertEquals(170L, service.saveAsTemplate(70L, 9L));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> params = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(topicContentCopyService).copyCmsTopic(eq(70L), params.capture());
        assertEquals(2, params.getValue().get("copyType"), "模板不带票、场次、名单与协作者");
        assertEquals(9L, params.getValue().get("memberId"));
        // 原主题一个字段都不改:不置 is_template、不下架
        verify(cmsTopicMapper, never()).updateTopicIsTemplate(anyLong(), anyLong(), anyInt());
        verify(cmsTopicMapper, never()).markTopicTemplateCopy(eq(70L), anyLong(), any());
        verify(cmsTopicMapper, never()).updateCmsTopic(any());
    }

    @Test
    void refusesSomeoneElsesTopicWithoutCopying() {
        when(cmsTopicMapper.selectCmsTopicById(71L)).thenReturn(live(71L, 8L));
        assertThrows(ServiceException.class, () -> service.saveAsTemplate(71L, 9L));
        verify(topicContentCopyService, never()).copyCmsTopic(anyLong(), any());
    }

    @Test
    void failsWholeWhenCopyCannotBeMarked() {
        when(cmsTopicMapper.selectCmsTopicById(72L)).thenReturn(live(72L, 9L));
        when(topicContentCopyService.copyCmsTopic(eq(72L), any())).thenReturn(172L);
        when(cmsTopicMapper.markTopicTemplateCopy(172L, 9L, 1)).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.saveAsTemplate(72L, 9L));
    }
}
