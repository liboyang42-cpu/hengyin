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
import com.chengyinhub.business.domain.CmsBetaGameVote;
import com.chengyinhub.business.service.ICmsBetaGameVoteService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 游戏调查投票Controller
 * 
 * @author webxh
 * @date 2025-10-13
 */
@RestController
@RequestMapping("/business/betagamevote")
public class CmsBetaGameVoteController extends BaseController
{
    @Autowired
    private ICmsBetaGameVoteService cmsBetaGameVoteService;

    /**
     * 查询游戏调查投票列表
     */
    @PreAuthorize("@ss.hasPermi('business:betagamevote:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsBetaGameVote cmsBetaGameVote)
    {
        startPage();
        List<CmsBetaGameVote> list = cmsBetaGameVoteService.selectCmsBetaGameVoteList(cmsBetaGameVote);
        return getDataTable(list);
    }

    /**
     * 导出游戏调查投票列表
     */
    @PreAuthorize("@ss.hasPermi('business:betagamevote:export')")
    @Log(title = "游戏调查投票", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsBetaGameVote cmsBetaGameVote)
    {
        List<CmsBetaGameVote> list = cmsBetaGameVoteService.selectCmsBetaGameVoteList(cmsBetaGameVote);
        ExcelUtil<CmsBetaGameVote> util = new ExcelUtil<CmsBetaGameVote>(CmsBetaGameVote.class);
        util.exportExcel(response, list, "游戏调查投票数据");
    }

    /**
     * 获取游戏调查投票详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:betagamevote:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsBetaGameVoteService.selectCmsBetaGameVoteById(id));
    }

    /**
     * 新增游戏调查投票
     */
    @PreAuthorize("@ss.hasPermi('business:betagamevote:add')")
    @Log(title = "游戏调查投票", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsBetaGameVote cmsBetaGameVote)
    {
        return toAjax(cmsBetaGameVoteService.insertCmsBetaGameVote(cmsBetaGameVote));
    }

    /**
     * 修改游戏调查投票
     */
    @PreAuthorize("@ss.hasPermi('business:betagamevote:edit')")
    @Log(title = "游戏调查投票", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsBetaGameVote cmsBetaGameVote)
    {
        return toAjax(cmsBetaGameVoteService.updateCmsBetaGameVote(cmsBetaGameVote));
    }

    /**
     * 删除游戏调查投票
     */
    @PreAuthorize("@ss.hasPermi('business:betagamevote:remove')")
    @Log(title = "游戏调查投票", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(cmsBetaGameVoteService.deleteCmsBetaGameVoteByIds(ids));
    }
}
