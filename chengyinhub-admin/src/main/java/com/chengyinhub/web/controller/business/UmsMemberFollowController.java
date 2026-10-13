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
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 用户之间关注Controller
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
@RestController
@RequestMapping("/business/follow")
public class UmsMemberFollowController extends BaseController
{
    @Autowired
    private IUmsMemberFollowService umsMemberFollowService;

    /**
     * 查询用户之间关注列表
     */
    @PreAuthorize("@ss.hasPermi('business:follow:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsMemberFollow umsMemberFollow)
    {
        startPage();
        List<UmsMemberFollow> list = umsMemberFollowService.selectUmsMemberFollowList(umsMemberFollow);
        return getDataTable(list);
    }

    /**
     * 导出用户之间关注列表
     */
    @PreAuthorize("@ss.hasPermi('business:follow:export')")
    @Log(title = "用户之间关注", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsMemberFollow umsMemberFollow)
    {
        List<UmsMemberFollow> list = umsMemberFollowService.selectUmsMemberFollowList(umsMemberFollow);
        ExcelUtil<UmsMemberFollow> util = new ExcelUtil<UmsMemberFollow>(UmsMemberFollow.class);
        util.exportExcel(response, list, "用户之间关注数据");
    }

    /**
     * 获取用户之间关注详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:follow:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(umsMemberFollowService.selectUmsMemberFollowById(id));
    }

    /**
     * 新增用户之间关注
     */
    @PreAuthorize("@ss.hasPermi('business:follow:add')")
    @Log(title = "用户之间关注", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsMemberFollow umsMemberFollow)
    {
        // R10-07:后台新增也走同一把 follower 行锁 + 查重,已存在即幂等成功,不造重复行。
        IUmsMemberFollowService.ApplyResult result = umsMemberFollowService.applyFollowState(
                umsMemberFollow.getMemberId(), umsMemberFollow.getFollowMemberId(), Boolean.TRUE);
        if (result == IUmsMemberFollowService.ApplyResult.INVALID_MEMBER
                || result == IUmsMemberFollowService.ApplyResult.FAILED) {
            return error("新增关注关系失败");
        }
        if (result == IUmsMemberFollowService.ApplyResult.SELF_FOLLOW) {
            return error("不能关注自己");
        }
        return success();
    }

    /**
     * 修改用户之间关注
     */
    @PreAuthorize("@ss.hasPermi('business:follow:edit')")
    @Log(title = "用户之间关注", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsMemberFollow umsMemberFollow)
    {
        // R10-07 收尾:edit 也收进 follower 行锁事务(与新增/取消同一把锁),消除 check-then-update 竞态。
        IUmsMemberFollowService.EditResult result =
                umsMemberFollowService.editUmsMemberFollowLocked(umsMemberFollow);
        switch (result.getKind()) {
            case UPDATED:
                return toAjax(1);
            case CONFLICT:
                return error("该关注关系已存在，不能重复");
            case CONCURRENT_CHANGE:
                return error("关注关系已变化，请刷新后重试");
            case NOT_FOUND:
                return error("关注关系不存在");
            case SELF_FOLLOW:
                return error("不能关注自己");
            case INVALID_MEMBER:
                return error("关注对象无效");
            default:
                return error("修改失败");
        }
    }

    /**
     * 删除用户之间关注
     */
    @PreAuthorize("@ss.hasPermi('business:follow:remove')")
    @Log(title = "用户之间关注", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(umsMemberFollowService.deleteUmsMemberFollowByIds(ids));
    }
}
