package com.chengyinhub.web.controller.business;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.impl.ChapterMerchantNodeServiceImpl;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 主题节点（路线）Controller
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
@RestController
@RequestMapping("/business/topicnode")
public class CmsTopicNodeController extends BaseController
{
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    /**
     * 查询主题节点（路线）列表
     */
    @PreAuthorize("@ss.hasPermi('business:topicnode:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsTopicNode cmsTopicNode)
    {
        if (!canViewTopic(cmsTopicNode == null ? null : cmsTopicNode.getTopicId())) {
            return getDataTable(java.util.Collections.<CmsTopicNode>emptyList());
        }
        startPage();
        List<CmsTopicNode> list = cmsTopicNodeService.selectCmsTopicNodeList(cmsTopicNode);
        return getDataTable(list);
    }

    /**
     * 导出主题节点（路线）列表
     */
    @PreAuthorize("@ss.hasPermi('business:topicnode:export')")
    @Log(title = "主题节点（路线）", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsTopicNode cmsTopicNode)
    {
        List<CmsTopicNode> list = canViewTopic(cmsTopicNode == null ? null : cmsTopicNode.getTopicId())
                ? cmsTopicNodeService.selectCmsTopicNodeList(cmsTopicNode)
                : java.util.Collections.<CmsTopicNode>emptyList();
        ExcelUtil<CmsTopicNode> util = new ExcelUtil<CmsTopicNode>(CmsTopicNode.class);
        util.exportExcel(response, list, "主题节点（路线）数据");
    }

    /**
     * 获取主题节点（路线）详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:topicnode:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(id);
        if (!canViewNode(node)) {
            return error("节点不存在或当前账号无查看范围");
        }
        return success(node);
    }

    /**
     * 新增主题节点（路线）
     */
    @PreAuthorize("@ss.hasPermi('business:topicnode:add')")
    @Log(title = "主题节点（路线）", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsTopicNode cmsTopicNode)
    {
        if (cmsTopicNode == null || cmsTopicNode.getChapterId() == null) {
            return error("章节不能为空");
        }
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(cmsTopicNode.getChapterId());
        if (chapter == null || chapter.getTopicId() == null
                || !chapter.getTopicId().equals(cmsTopicNode.getTopicId())
                || !canViewTopic(chapter.getTopicId())) {
            return error("章节不存在或当前账号无操作范围");
        }
        cmsTopicNode.setTopicId(chapter.getTopicId());
        applyHostAuthorship(cmsTopicNode, null);
        return toAjax(cmsTopicNodeService.insertCmsTopicNode(cmsTopicNode));
    }

    /**
     * 修改主题节点（路线）
     */
    @PreAuthorize("@ss.hasPermi('business:topicnode:edit')")
    @Log(title = "主题节点（路线）", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsTopicNode cmsTopicNode)
    {
        CmsTopicNode existing = cmsTopicNode == null || cmsTopicNode.getId() == null
                ? null : cmsTopicNodeService.selectCmsTopicNodeById(cmsTopicNode.getId());
        if (!canViewNode(existing)) {
            return error("节点不存在或当前账号无操作范围");
        }
        cmsTopicNode.setTopicId(existing.getTopicId());
        cmsTopicNode.setChapterId(existing.getChapterId());
        applyHostAuthorship(cmsTopicNode, existing);
        return toAjax(cmsTopicNodeService.updateCmsTopicNode(cmsTopicNode));
    }

    /**
     * 删除主题节点（路线）
     */
    @PreAuthorize("@ss.hasPermi('business:topicnode:remove')")
    @Log(title = "主题节点（路线）", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        if (ids == null || ids.length == 0) {
            return error("节点ID不能为空");
        }
        for (Long id : ids) {
            CmsTopicNode node = id == null ? null : cmsTopicNodeService.selectCmsTopicNodeById(id);
            if (!canViewNode(node)) {
                return error("节点不存在或当前账号无操作范围");
            }
        }
        return toAjax(cmsTopicNodeService.deleteCmsTopicNodeByIds(ids));
    }


    /**
     * 批 5:平台代商家建/改探店日点位时的审核态收口。
     *
     * <p>新建或首次绑定仍是平台已认可；已绑定 A 的节点换绑到 B 时，A 的审核结论
     * 不能跟着商家归属一起继承，必须退回待审并显式清空旧审核留痕。
     */
    private void applyHostAuthorship(CmsTopicNode node, CmsTopicNode existing) {
        Long requestedMerchantId = node.getMerchantMemberId();
        Long existingMerchantId = existing == null ? null : existing.getMerchantMemberId();
        Long merchantMemberId = requestedMerchantId != null ? requestedMerchantId : existingMerchantId;
        boolean firstBinding = existing == null || existingMerchantId == null;
        boolean merchantChanged = existingMerchantId != null && requestedMerchantId != null
                && !existingMerchantId.equals(requestedMerchantId);

        node.setClearNodeAuditTrace(false);
        if (merchantMemberId == null) {
            node.setNodeAuditStatus(null);
            node.setNodeAuditReason(null);
            node.setNodeAuditMemberId(null);
            node.setNodeAuditTime(null);
            return;
        }
        if (merchantChanged) {
            node.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
            node.setNodeAuditReason(null);
            node.setNodeAuditMemberId(null);
            node.setNodeAuditTime(null);
            node.setClearNodeAuditTrace(true);
            return;
        }
        if (!firstBinding) {
            node.setNodeAuditStatus(null);
            node.setNodeAuditReason(null);
            node.setNodeAuditMemberId(null);
            node.setNodeAuditTime(null);
            return;
        }
        node.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED);
        node.setNodeAuditReason("");
        node.setNodeAuditMemberId(0L);
        node.setNodeAuditTime(new java.util.Date());
    }

    private boolean canViewNode(CmsTopicNode node) {
        return node != null && canViewTopic(node.getTopicId());
    }

    private boolean canViewTopic(Long topicId) {
        return topicId != null && topicOperationsScopeAuthorizer.canViewTopic(topicId);
    }
}
