package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ITopicSaleCanonicalMutationService;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主题节点（路线）Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
@Service
public class CmsTopicNodeServiceImpl implements ICmsTopicNodeService 
{
    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;

    @Autowired
    private com.chengyinhub.business.service.support.TopicContentWriteGuard contentWriteGuard;

    @Autowired(required = false)
    private ITopicSaleCanonicalMutationService canonicalMutationService;

    /**
     * 查询主题节点（路线）
     * 
     * @param id 主题节点（路线）主键
     * @return 主题节点（路线）
     */
    @Override
    public CmsTopicNode selectCmsTopicNodeById(Long id)
    {
        return cmsTopicNodeMapper.selectCmsTopicNodeById(id);
    }

    /**
     * 查询主题节点（路线）列表
     * 
     * @param cmsTopicNode 主题节点（路线）
     * @return 主题节点（路线）
     */
    @Override
    public List<CmsTopicNode> selectCmsTopicNodeList(CmsTopicNode cmsTopicNode)
    {
        return cmsTopicNodeMapper.selectCmsTopicNodeList(cmsTopicNode);
    }

    /**
     * 新增主题节点（路线）
     * 
     * @param cmsTopicNode 主题节点（路线）
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertCmsTopicNode(CmsTopicNode cmsTopicNode)
    {
        contentWriteGuard.lockTopicContent(cmsTopicNode == null ? null : cmsTopicNode.getTopicId());
        cmsTopicNode.setCreateTime(DateUtils.getNowDate());
        if (isBlank(cmsTopicNode.getCheckinCode())) {
            cmsTopicNode.setCheckinCode(newCheckinCode());
        }
        int rows = cmsTopicNodeMapper.insertCmsTopicNode(cmsTopicNode);
        if (rows > 0) {
            contentWriteGuard.assertTopicSizeWithinLimits(cmsTopicNode.getTopicId());
            invalidateApprovedCanonical(cmsTopicNode.getTopicId(), "node-insert");
        }
        return rows;
    }

    /**
     * 修改主题节点（路线）
     * 
     * @param cmsTopicNode 主题节点（路线）
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateCmsTopicNode(CmsTopicNode cmsTopicNode)
    {
        CmsTopicNode existing = cmsTopicNode == null || cmsTopicNode.getId() == null ? null
                : cmsTopicNodeMapper.selectCmsTopicNodeById(cmsTopicNode.getId());
        if (existing == null) return 0;
        Long expectedTopicId = existing.getTopicId();
        Set<Long> parents = new TreeSet<>();
        if (expectedTopicId == null) throw new IllegalStateException("节点缺少父主题，拒绝修改");
        parents.add(expectedTopicId);
        if (cmsTopicNode.getTopicId() != null) parents.add(cmsTopicNode.getTopicId());
        for (Long topicId : parents) contentWriteGuard.lockTopicContent(topicId);
        existing = cmsTopicNodeMapper.selectContentByIdForUpdate(cmsTopicNode.getId());
        if (existing == null) return 0;
        if (!java.util.Objects.equals(expectedTopicId, existing.getTopicId())) {
            throw new IllegalStateException("节点归属已变化，请刷新后重试");
        }
        // 空码在编辑时自愈:迁移只回填生产库，开发/测试库与「回填之后又被清空」的行仍会是空码，
        // 而空码节点在页面上看起来完全正常。⚠️ 只补空码，不换已有码 —— 换码 = 店里已印好的海报全部作废。
        // 传空串同样视同「不改」:mapper 的 <if test="checkinCode != null"> 会把空串真写进去，
        // 表单一个没填的输入框就能把已印好的码抹成空，而抹完页面上看不出任何异常。
        if (isBlank(cmsTopicNode.getCheckinCode())) {
            cmsTopicNode.setCheckinCode(
                    existing != null && isBlank(existing.getCheckinCode()) ? newCheckinCode() : null);
        }
        cmsTopicNode.setUpdateTime(DateUtils.getNowDate());
        int rows = cmsTopicNodeMapper.updateCmsTopicNode(cmsTopicNode);
        if (rows > 0) {
            if (cmsTopicNode.getTopicId() != null && !cmsTopicNode.getTopicId().equals(expectedTopicId)) {
                contentWriteGuard.assertTopicSizeWithinLimits(cmsTopicNode.getTopicId());
            }
            invalidateApprovedCanonical(existing == null ? cmsTopicNode.getTopicId() : existing.getTopicId(),
                    "node-update");
        }
        return rows;
    }

    /**
     * 批量删除主题节点（路线）
     * 
     * @param ids 需要删除的主题节点（路线）主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteCmsTopicNodeByIds(Long[] ids)
    {
        Map<Long, Long> expectedTopics = new TreeMap<>();
        Set<Long> topicIds = new TreeSet<>();
        if (ids != null) {
            for (Long id : ids) {
                CmsTopicNode existing = id == null ? null : cmsTopicNodeMapper.selectCmsTopicNodeById(id);
                if (existing != null) {
                    if (existing.getTopicId() == null) throw new IllegalStateException("节点缺少父主题，拒绝删除");
                    topicIds.add(existing.getTopicId());
                    expectedTopics.put(id, existing.getTopicId());
                }
            }
        }
        if (expectedTopics.isEmpty()) return 0;
        for (Long topicId : topicIds) contentWriteGuard.lockTopicContent(topicId);
        List<Long> lockedIds = new ArrayList<>();
        for (Map.Entry<Long, Long> locator : expectedTopics.entrySet()) {
            CmsTopicNode locked = cmsTopicNodeMapper.selectContentByIdForUpdate(locator.getKey());
            if (locked == null) continue;
            if (!java.util.Objects.equals(locator.getValue(), locked.getTopicId())) {
                throw new IllegalStateException("节点归属已变化，请刷新后重试");
            }
            lockedIds.add(locator.getKey());
        }
        if (lockedIds.isEmpty()) return 0;
        int rows = cmsTopicNodeMapper.deleteCmsTopicNodeByIds(lockedIds.toArray(new Long[0]));
        if (rows > 0) {
            for (Long topicId : topicIds) {
                invalidateApprovedCanonical(topicId, "node-delete-batch");
            }
        }
        return rows;
    }

    /**
     * 删除主题节点（路线）信息
     * 
     * @param id 主题节点（路线）主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteCmsTopicNodeById(Long id)
    {
        CmsTopicNode existing = id == null ? null : cmsTopicNodeMapper.selectCmsTopicNodeById(id);
        if (existing == null) return 0;
        Long expectedTopicId = existing.getTopicId();
        contentWriteGuard.lockTopicContent(expectedTopicId);
        existing = cmsTopicNodeMapper.selectContentByIdForUpdate(id);
        if (existing == null) return 0;
        if (!java.util.Objects.equals(expectedTopicId, existing.getTopicId())) {
            throw new IllegalStateException("节点归属已变化，请刷新后重试");
        }
        int rows = cmsTopicNodeMapper.deleteCmsTopicNodeById(id);
        if (rows > 0 && existing != null) {
            invalidateApprovedCanonical(existing.getTopicId(), "node-delete");
        }
        return rows;
    }

    /**
     * 打卡码 = 店内静态码的二维码内容，玩家扫它进店（{@code /api/play/checkin} 按 checkin_code 反查）。
     *
     * <p>批 5 · T1:此前只有复制主题(CmsTopicServiceImpl:704/3135/3246)与 B1 二期复制
     * (TopicEditionReopenServiceImpl:310)四处生成码，走标准 CRUD 建的节点落 NULL ⇒
     * **该节点永远扫不出来且零报错**。闸收在这里而不是 Controller:商家自助点位
     * (ChapterMerchantNodeServiceImpl.submit)也走同一个 insert。
     *
     * <p>{@code UUID.randomUUID()} 走 SecureRandom(122 bit),不可猜;去横线后 32 位十六进制，
     * 与既有四处生成点逐字同口径。唯一性由 checkin_code 上的唯一索引承重
     * (migration_20260817_tandianri_node_checkin_code_unique.sql)——
     * 撞码此前是 {@code selectCmsTopicNodeList().get(0)} 静默取首行。
     */
    private String newCheckinCode()
    {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private void invalidateApprovedCanonical(Long topicId, String mutation)
    {
        if (canonicalMutationService != null && topicId != null) {
            canonicalMutationService.invalidateApprovedCanonical(topicId, mutation);
        }
    }
}
