package com.chengyinhub.web.controller.business;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
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
import com.chengyinhub.business.domain.CmsBetaGame;
import com.chengyinhub.business.service.ICmsBetaGameService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 游戏Controller
 * 
 * @author webxh
 * @date 2025-10-13
 */
@RestController
@RequestMapping("/business/betagame")
public class CmsBetaGameController extends BaseController
{
    @Autowired
    private ICmsBetaGameService cmsBetaGameService;

    /**
     * 查询游戏列表
     */
    @PreAuthorize("@ss.hasPermi('business:betagame:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsBetaGame cmsBetaGame)
    {
        cmsBetaGame.setDelFlag(0L);
        startPage();
        List<CmsBetaGame> list = cmsBetaGameService.selectCmsBetaGameList(cmsBetaGame);
        return getDataTable(list);
    }

    /**
     * 导出游戏列表
     */
    @PreAuthorize("@ss.hasPermi('business:betagame:export')")
    @Log(title = "游戏", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsBetaGame cmsBetaGame)
    {
        cmsBetaGame.setDelFlag(0L);
        List<CmsBetaGame> list = cmsBetaGameService.selectCmsBetaGameList(cmsBetaGame);
        ExcelUtil<CmsBetaGame> util = new ExcelUtil<CmsBetaGame>(CmsBetaGame.class);
        util.exportExcel(response, list, "游戏数据");
    }

    /**
     * 获取游戏详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:betagame:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsBetaGameService.selectCmsBetaGameById(id));
    }

    /**
     * 新增游戏
     */
    @PreAuthorize("@ss.hasPermi('business:betagame:add')")
    @Log(title = "游戏", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsBetaGame cmsBetaGame)
    {
        return toAjax(cmsBetaGameService.insertCmsBetaGame(cmsBetaGame));
    }

    /**
     * 修改游戏
     */
    @PreAuthorize("@ss.hasPermi('business:betagame:edit')")
    @Log(title = "游戏", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsBetaGame cmsBetaGame)
    {
        return toAjax(cmsBetaGameService.updateCmsBetaGame(cmsBetaGame));
    }

    /**
     * 删除活动
     */
    @PreAuthorize("@ss.hasPermi('business:betagame:remove')")
    @Log(title = "游戏", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(cmsBetaGameService.deleteCmsBetaGameByIds(ids));
    }



    /**
     * 批量修改游戏调查删除状态
     */
    @PreAuthorize("@ss.hasPermi('business:betagame:edit')")
    @Log(title = "游戏", businessType = BusinessType.UPDATE)
    @PostMapping("/update/delstatus")
    @Parameters({
            @Parameter(name = "ids", description = "活动id数组")
    })
    public AjaxResult updateCmsBetaGameDelStatusByIds(@RequestBody Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return error("请选择要删除的活动");
        }
        return toAjax(cmsBetaGameService.updateCmsBetaGameDelStatusByIds(ids));
    }
}
