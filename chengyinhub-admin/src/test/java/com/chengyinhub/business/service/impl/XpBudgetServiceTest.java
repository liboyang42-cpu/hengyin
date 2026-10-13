package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M7 XP 预算校验测试:节点探索值解析(节点→模板→0)+ 预算总计/超额。
 */
class XpBudgetServiceTest {

    private CmsTopicNode node(Long id, Integer xp, Long templateId) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setName("节点" + id);
        n.setXpValue(xp);
        n.setTemplateId(templateId);
        return n;
    }

    private XpBudgetServiceImpl service(CmsTopicMapper tm, CmsTopicNodeMapper nm, CmsMemberTemplateMapper mtm) {
        XpBudgetServiceImpl s = new XpBudgetServiceImpl();
        ReflectionTestUtils.setField(s, "cmsTopicMapper", tm);
        ReflectionTestUtils.setField(s, "cmsTopicNodeMapper", nm);
        ReflectionTestUtils.setField(s, "cmsMemberTemplateMapper", mtm);
        return s;
    }

    @Test
    void resolveNodeXpPrefersNodeThenTemplateThenZero() {
        CmsMemberTemplateMapper mtm = mock(CmsMemberTemplateMapper.class);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setXpValue(200);
        when(mtm.selectCmsMemberTemplateById(5L)).thenReturn(tpl);
        XpBudgetServiceImpl s = service(mock(CmsTopicMapper.class), mock(CmsTopicNodeMapper.class), mtm);

        assertEquals(100, s.resolveNodeXp(node(1L, 100, 5L), new HashMap<>())); // 节点 xp 优先
        assertEquals(200, s.resolveNodeXp(node(2L, null, 5L), new HashMap<>())); // 退模板 xp
        assertEquals(0, s.resolveNodeXp(node(3L, null, null), new HashMap<>())); // 无 → 0(全局兜底)
    }

    @Test
    void computeBudgetUnderBudget() {
        CmsTopicMapper tm = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setXpBudget(3000);
        when(tm.selectCmsTopicById(9L)).thenReturn(topic);
        CmsTopicNodeMapper nm = mock(CmsTopicNodeMapper.class);
        when(nm.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(node(1L, 100, null), node(2L, 200, null)));
        XpBudgetServiceImpl s = service(tm, nm, mock(CmsMemberTemplateMapper.class));

        Map<String, Object> r = s.computeBudget(9L);
        assertEquals(3000, r.get("budget"));
        assertEquals(300, r.get("totalXp"));
        assertFalse((Boolean) r.get("over"));
        assertEquals(2700, r.get("remain")); // 未分配=通关奖励
    }

    @Test
    void computeBudgetOverBudgetReturnsOverBy() {
        CmsTopicMapper tm = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setXpBudget(250);
        when(tm.selectCmsTopicById(9L)).thenReturn(topic);
        CmsTopicNodeMapper nm = mock(CmsTopicNodeMapper.class);
        when(nm.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(node(1L, 100, null), node(2L, 200, null)));
        XpBudgetServiceImpl s = service(tm, nm, mock(CmsMemberTemplateMapper.class));

        Map<String, Object> r = s.computeBudget(9L);
        assertTrue((Boolean) r.get("over"));
        assertEquals(50, r.get("overBy"));
        assertEquals(0, r.get("remain"));
    }

    @Test
    void computeBudgetDefaultsTo3000WhenTopicBudgetNull() {
        CmsTopicMapper tm = mock(CmsTopicMapper.class);
        when(tm.selectCmsTopicById(anyLong())).thenReturn(new CmsTopic()); // xpBudget null
        CmsTopicNodeMapper nm = mock(CmsTopicNodeMapper.class);
        when(nm.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(node(1L, 50, null)));
        XpBudgetServiceImpl s = service(tm, nm, mock(CmsMemberTemplateMapper.class));

        Map<String, Object> r = s.computeBudget(9L);
        assertEquals(3000, r.get("budget"));
        assertEquals(50, r.get("totalXp"));
    }
}
