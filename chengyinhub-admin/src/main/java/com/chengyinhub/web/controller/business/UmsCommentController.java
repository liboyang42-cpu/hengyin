package com.chengyinhub.web.controller.business;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 用户评论Controller
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@RestController
@RequestMapping("/business/comment")
public class UmsCommentController extends BaseController
{
    @Autowired
    private IUmsCommentService umsCommentService;

    @Autowired
    private IAuditTaskService auditTaskService;

    /**
     * 查询用户评论列表
     */
    @PreAuthorize("@ss.hasPermi('business:comment:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsComment umsComment)
    {
        startPage();
        umsComment.setDelFlag(0);
        List<UmsComment> list = umsCommentService.selectUmsCommentList(umsComment);
        return getDataTable(list);
    }

    /**
     * 导出用户评论列表
     */
    @PreAuthorize("@ss.hasPermi('business:comment:export')")
    @Log(title = "用户评论", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsComment umsComment)
    {
        List<UmsComment> list = umsCommentService.selectUmsCommentList(umsComment);
        ExcelUtil<UmsComment> util = new ExcelUtil<UmsComment>(UmsComment.class);
        util.exportExcel(response, list, "用户评论数据");
    }

    /**
     * 获取用户评论详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:comment:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(umsCommentService.selectUmsCommentById(id));
    }

    /**
     * 新增用户评论
     */
    @PreAuthorize("@ss.hasPermi('business:comment:add')")
    @Log(title = "用户评论", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsComment umsComment)
    {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "后台不创建用户评论，请由用户端发布并进入统一审核流程");
    }

    /**
     * 修改用户评论
     */
    @PreAuthorize("@ss.hasPermi('business:comment:edit')")
    @Log(title = "用户评论", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsComment umsComment)
    {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "评论内容与上下架状态不支持后台直改，请提交统一审核");
    }

    /** 把指定评论送入统一审核台；已有未结任务时复用，不重复刷单。 */
    @PreAuthorize("@ss.hasPermi('business:comment:edit')")
    @Log(title = "用户评论-提交统一审核", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/review")
    public AjaxResult submitReview(@PathVariable Long id)
    {
        UmsComment comment = umsCommentService.selectUmsCommentById(id);
        if (comment == null || Integer.valueOf(2).equals(comment.getDelFlag())) {
            return error("评论不存在或已删除");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bizType", AuditTask.BIZ_COMMENT);
        snapshot.put("bizId", comment.getId());
        snapshot.put("memberId", comment.getMemberId());
        snapshot.put("ownerType", comment.getOwnerType());
        snapshot.put("ownerId", comment.getOwnerId());
        snapshot.put("rating", comment.getRating());
        snapshot.put("contents", comment.getContents());
        snapshot.put("imgArr", comment.getImgArr());
        Long taskId = auditTaskService.submitSafetyHold(AuditTask.BIZ_COMMENT, comment.getId(),
                comment.getMemberId(), "NORMAL", toSnapshotJson(snapshot));
        return success(taskId);
    }

    private String toSnapshotJson(Map<String, Object> snapshot)
    {
        try {
            return new ObjectMapper().writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{\"bizType\":\"COMMENT\",\"bizId\":" + snapshot.get("bizId") + "}";
        }
    }

    /**
     * 删除用户评论
     */
    @PreAuthorize("@ss.hasPermi('business:comment:remove')")
    @Log(title = "用户评论", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "评论不支持业务页软删，请提交统一审核决定是否下架");
    }
}
