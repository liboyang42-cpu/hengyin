package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MemberGrowth;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0.5 契约测试:XP 自动分配(守恒/加权)+ addExp 双轨隔离(只动 member_growth)。
 */
class XpAutoAllocateTest {

    private CmsTopicNode node(long id, Long tplId) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTemplateId(tplId);
        return n;
    }

    @Test
    void autoAllocateSplitsSixtyPercentByWeightAndLeavesRemainder() {
        CmsTopicMapper tm = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setXpBudget(3000);
        when(tm.selectCmsTopicById(9L)).thenReturn(topic);

        CmsTopicNodeMapper nm = mock(CmsTopicNodeMapper.class);
        // 三节点:拍照(vm=2,权3) / 扫码(vm=4,权2) / 到点(无模板,权1) → Σ权=6,池=1800
        when(nm.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(
                node(1L, 11L), node(2L, 12L), node(3L, null)));
        CmsMemberTemplateMapper mtm = mock(CmsMemberTemplateMapper.class);
        CmsMemberTemplate photo = new CmsMemberTemplate(); photo.setValidationMethod(2);
        CmsMemberTemplate scan = new CmsMemberTemplate(); scan.setValidationMethod(4);
        when(mtm.selectCmsMemberTemplateById(11L)).thenReturn(photo);
        when(mtm.selectCmsMemberTemplateById(12L)).thenReturn(scan);

        XpBudgetServiceImpl s = new XpBudgetServiceImpl();
        ReflectionTestUtils.setField(s, "cmsTopicMapper", tm);
        ReflectionTestUtils.setField(s, "cmsTopicNodeMapper", nm);
        ReflectionTestUtils.setField(s, "cmsMemberTemplateMapper", mtm);

        int allocated = s.autoAllocate(9L);

        // 池=1800:拍照 900 / 扫码 600 / 到点 300;Σ=1800 ≤ 预算,余额(通关XP)=1200 ≥ 40%
        assertEquals(1800, allocated);
        ArgumentCaptor<CmsTopicNode> cap = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nm, atLeastOnce()).updateCmsTopicNode(cap.capture());
        List<CmsTopicNode> updates = cap.getAllValues();
        assertEquals(3, updates.size());
        assertEquals(900, updates.get(0).getXpValue().intValue());
        assertEquals(600, updates.get(1).getXpValue().intValue());
        assertEquals(300, updates.get(2).getXpValue().intValue());
        assertTrue(3000 - allocated >= 3000 * 2 / 5, "通关余额应≥40%预算");
    }

    @Test
    void addExpTouchesOnlyMemberGrowth_dualTrackIsolation() {
        MemberGrowthMapper gm = mock(MemberGrowthMapper.class);
        GrowthServiceImpl s = new GrowthServiceImpl();
        ReflectionTestUtils.setField(s, "memberGrowthMapper", gm);
        when(gm.insertEarnedExp(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any())).thenReturn(1);

        int r = s.addExp(7L, 150, "节点探索");

        assertEquals(150, r);
        // 只动 member_growth(建号+累加);双轨红线=绝不触碰积分明细表(本服务根本未注入积分依赖,结构性隔离)
        verify(gm).insertMemberGrowth(any(MemberGrowth.class));
        ArgumentCaptor<MemberGrowth> cap = ArgumentCaptor.forClass(MemberGrowth.class);
        verify(gm).updateMemberGrowthReward(cap.capture());
        assertEquals(150, cap.getValue().getExpValue().intValue());
    }

    @Test
    void addExpRejectsNonPositive() {
        MemberGrowthMapper gm = mock(MemberGrowthMapper.class);
        GrowthServiceImpl s = new GrowthServiceImpl();
        ReflectionTestUtils.setField(s, "memberGrowthMapper", gm);
        assertEquals(0, s.addExp(7L, 0, "x"));
        assertEquals(0, s.addExp(null, 10, "x"));
    }
}
