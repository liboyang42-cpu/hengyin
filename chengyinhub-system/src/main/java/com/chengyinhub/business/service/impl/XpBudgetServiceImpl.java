package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.IXpBudgetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M7 XP 预算校验实现。
 */
@Service
public class XpBudgetServiceImpl implements IXpBudgetService {

    private static final int DEFAULT_BUDGET = 3000;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    @Autowired
    private CmsMemberTemplateMapper cmsMemberTemplateMapper;

    @Override
    public Map<String, Object> computeBudget(Long topicId) {
        Map<String, Object> result = new LinkedHashMap<>();
        CmsTopic topic = topicId == null ? null : cmsTopicMapper.selectCmsTopicById(topicId);
        int budget = (topic != null && topic.getXpBudget() != null) ? topic.getXpBudget() : DEFAULT_BUDGET;

        List<CmsTopicNode> nodes = listTopicNodes(topicId);
        Map<Long, Integer> templateXpCache = new HashMap<>();
        int total = 0;
        List<Map<String, Object>> perNode = new ArrayList<>();
        for (CmsTopicNode n : nodes) {
            int xp = resolveNodeXp(n, templateXpCache);
            total += xp;
            Map<String, Object> pn = new LinkedHashMap<>();
            pn.put("nodeId", n.getId());
            pn.put("name", n.getName());
            pn.put("xp", xp);
            perNode.add(pn);
        }
        boolean over = total > budget;
        result.put("budget", budget);
        result.put("totalXp", total);
        result.put("over", over);
        result.put("overBy", over ? (total - budget) : 0);
        result.put("remain", Math.max(0, budget - total)); // 未分配余额=通关奖励
        result.put("perNode", perNode);
        return result;
    }

    @Override
    public int resolveNodeXp(CmsTopicNode node) {
        return resolveNodeXp(node, new HashMap<>());
    }

    @Override
    public int resolveNodeXp(CmsTopicNode node, Map<Long, Integer> templateXpCache) {
        if (node == null) {
            return 0;
        }
        // 1) 节点自身 xp(纯停靠节点)
        if (node.getXpValue() != null && node.getXpValue() > 0) {
            return node.getXpValue();
        }
        // 2) 游戏节点:模板 xp
        Long tplId = node.getTemplateId();
        if (tplId != null) {
            Integer cached = templateXpCache.get(tplId);
            if (cached != null) {
                return cached;
            }
            CmsMemberTemplate tpl = cmsMemberTemplateMapper.selectCmsMemberTemplateById(tplId);
            int xp = (tpl != null && tpl.getXpValue() != null && tpl.getXpValue() > 0) ? tpl.getXpValue() : 0;
            templateXpCache.put(tplId, xp);
            return xp;
        }
        // 3) 交由全局兜底(此处 0)
        return 0;
    }

    /**
     * M7 XP 自动分配:按验证方式加权拆 budget×0.6 到节点,写 node.xp_value;余额=通关 XP。
     * 权重:拍照(2)=3 · 答题(1/3)=3 · 扫码(4)=2 · 到点/GPS(0/5/null)=1。
     */
    @Override
    public int autoAllocate(Long topicId) {
        CmsTopic topic = topicId == null ? null : cmsTopicMapper.selectCmsTopicById(topicId);
        int budget = (topic != null && topic.getXpBudget() != null && topic.getXpBudget() > 0)
                ? topic.getXpBudget() : DEFAULT_BUDGET;
        List<CmsTopicNode> nodes = listTopicNodes(topicId);
        if (nodes.isEmpty()) {
            return 0;
        }
        // 各节点权重(模板 validationMethod 决定;查过的模板缓存)
        Map<Long, Integer> vmCache = new HashMap<>();
        int[] weights = new int[nodes.size()];
        int totalW = 0;
        for (int i = 0; i < nodes.size(); i++) {
            weights[i] = weightOf(nodes.get(i), vmCache);
            totalW += weights[i];
        }
        if (totalW <= 0) {
            return 0;
        }
        int pool = (int) Math.floor(budget * 0.6);
        int allocated = 0;
        for (int i = 0; i < nodes.size(); i++) {
            int xp = (int) Math.floor((double) pool * weights[i] / totalW);
            if (xp <= 0) xp = 1;
            CmsTopicNode upd = new CmsTopicNode();
            upd.setId(nodes.get(i).getId());
            upd.setXpValue(xp);
            cmsTopicNodeMapper.updateCmsTopicNode(upd);
            allocated += xp;
        }
        return allocated;
    }

    /** 节点权重:模板验证方式 拍照/答题=3 扫码=2 到点·GPS=1 */
    private int weightOf(CmsTopicNode node, Map<Long, Integer> vmCache) {
        Integer vm = null;
        Long tplId = node.getTemplateId();
        if (tplId != null) {
            vm = vmCache.get(tplId);
            if (vm == null) {
                CmsMemberTemplate tpl = cmsMemberTemplateMapper.selectCmsMemberTemplateById(tplId);
                vm = (tpl != null && tpl.getValidationMethod() != null) ? tpl.getValidationMethod() : 0;
                vmCache.put(tplId, vm);
            }
        }
        int v = vm == null ? 0 : vm;
        if (v == 1 || v == 2 || v == 3) return 3; // 答题/拍照
        if (v == 4) return 2;                     // 扫码
        return 1;                                 // 到点/GPS
    }

    @Override
    public List<CmsTopicNode> listTopicNodes(Long topicId) {
        if (topicId == null) {
            return new ArrayList<>();
        }
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        List<CmsTopicNode> list = cmsTopicNodeMapper.selectCmsTopicNodeList(probe);
        return list == null ? new ArrayList<>() : list;
    }
}
