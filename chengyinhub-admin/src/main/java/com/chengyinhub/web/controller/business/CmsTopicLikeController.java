package com.chengyinhub.web.controller.business;

import java.util.List;
import javax.servlet.http.HttpServletResponse;
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
import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 主题点赞Controller
 * 
 * @author webxh
 * @date 2025-09-13
 */
@RestController
@RequestMapping("/business/topiclike")
public class CmsTopicLikeController extends BaseController
{
    @Autowired
    private ICmsTopicLikeService cmsTopicLikeService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    /**
     * 查询主题点赞列表
     */
    @PreAuthorize("@ss.hasPermi('business:topiclike:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsTopicLike cmsTopicLike)
    {
        if (!canViewTopic(cmsTopicLike == null ? null : cmsTopicLike.getDataId())) {
            return getDataTable(java.util.Collections.<CmsTopicLike>emptyList());
        }
        startPage();
        List<CmsTopicLike> list = cmsTopicLikeService.selectCmsTopicLikeList(cmsTopicLike);
        return getDataTable(list);
    }

    /**
     * 导出主题点赞列表
     */
    @PreAuthorize("@ss.hasPermi('business:topiclike:export')")
    @Log(title = "主题点赞", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsTopicLike cmsTopicLike)
    {
        List<CmsTopicLike> list = canViewTopic(cmsTopicLike == null ? null : cmsTopicLike.getDataId())
                ? cmsTopicLikeService.selectCmsTopicLikeList(cmsTopicLike)
                : java.util.Collections.<CmsTopicLike>emptyList();
        ExcelUtil<CmsTopicLike> util = new ExcelUtil<CmsTopicLike>(CmsTopicLike.class);
        util.exportExcel(response, list, "主题点赞数据");
    }

    /**
     * 获取主题点赞详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:topiclike:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        CmsTopicLike like = cmsTopicLikeService.selectCmsTopicLikeById(id);
        if (!canViewLike(like)) {
            return error("点赞记录不存在或当前账号无查看范围");
        }
        return success(like);
    }

    /**
     * 新增主题点赞
     */
    @PreAuthorize("@ss.hasPermi('business:topiclike:add')")
    @Log(title = "主题点赞", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsTopicLike cmsTopicLike)
    {
        if (!canViewTopic(cmsTopicLike == null ? null : cmsTopicLike.getDataId())) {
            return error("主题不存在或当前账号无操作范围");
        }
        return toAjax(cmsTopicLikeService.insertCmsTopicLike(cmsTopicLike));
    }

    /**
     * 修改主题点赞
     */
    @PreAuthorize("@ss.hasPermi('business:topiclike:edit')")
    @Log(title = "主题点赞", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsTopicLike cmsTopicLike)
    {
        CmsTopicLike existing = cmsTopicLike == null || cmsTopicLike.getId() == null
                ? null : cmsTopicLikeService.selectCmsTopicLikeById(cmsTopicLike.getId());
        if (!canViewLike(existing)) {
            return error("点赞记录不存在或当前账号无操作范围");
        }
        cmsTopicLike.setDataId(existing.getDataId());
        return toAjax(cmsTopicLikeService.updateCmsTopicLike(cmsTopicLike));
    }

    /**
     * 删除主题点赞
     */
    @PreAuthorize("@ss.hasPermi('business:topiclike:remove')")
    @Log(title = "主题点赞", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        if (ids == null || ids.length == 0) {
            return error("点赞记录ID不能为空");
        }
        for (Long id : ids) {
            CmsTopicLike like = id == null ? null : cmsTopicLikeService.selectCmsTopicLikeById(id);
            if (!canViewLike(like)) {
                return error("点赞记录不存在或当前账号无操作范围");
            }
        }
        return toAjax(cmsTopicLikeService.deleteCmsTopicLikeByIds(ids));
    }


    private boolean canViewLike(CmsTopicLike like) {
        return like != null && canViewTopic(like.getDataId());
    }

    private boolean canViewTopic(Long topicId) {
        return topicId != null && topicOperationsScopeAuthorizer.canViewTopic(topicId);
    }
}
