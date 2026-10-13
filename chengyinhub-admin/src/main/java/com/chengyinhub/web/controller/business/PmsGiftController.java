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
import com.chengyinhub.business.domain.PmsGift;
import com.chengyinhub.business.service.IPmsGiftService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.utils.SecurityUtils;

/**
 * 众筹礼物Controller
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
@RestController
@RequestMapping("/business/gift")
public class PmsGiftController extends BaseController
{
    @Autowired
    private IPmsGiftService pmsGiftService;

    /**
     * 查询众筹礼物列表
     */
    @PreAuthorize("@ss.hasPermi('business:gift:list')")
    @GetMapping("/list")
    public TableDataInfo list(PmsGift pmsGift)
    {
        startPage();
        List<PmsGift> list = pmsGiftService.selectPmsGiftList(pmsGift);
        return getDataTable(list);
    }

    /**
     * 导出众筹礼物列表
     */
    @PreAuthorize("@ss.hasPermi('business:gift:export')")
    @Log(title = "众筹礼物", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, PmsGift pmsGift)
    {
        List<PmsGift> list = pmsGiftService.selectPmsGiftList(pmsGift);
        ExcelUtil<PmsGift> util = new ExcelUtil<PmsGift>(PmsGift.class);
        util.exportExcel(response, list, "众筹礼物数据");
    }

    /**
     * 获取众筹礼物详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:gift:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(pmsGiftService.selectPmsGiftById(id));
    }

    /**
     * 新增众筹礼物
     */
    @PreAuthorize("@ss.hasPermi('business:gift:add')")
    @Log(title = "礼物目录定价", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody PmsGift pmsGift)
    {
        pmsGift.setCreateBy(SecurityUtils.getUsername());
        return toAjax(pmsGiftService.insertPmsGift(pmsGift));
    }

    /**
     * 修改众筹礼物
     */
    @PreAuthorize("@ss.hasPermi('business:gift:edit')")
    @Log(title = "礼物目录定价", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody PmsGift pmsGift)
    {
        pmsGift.setUpdateBy(SecurityUtils.getUsername());
        return toAjax(pmsGiftService.updatePmsGift(pmsGift));
    }

    /**
     * 删除众筹礼物
     */
    @PreAuthorize("@ss.hasPermi('business:gift:remove')")
    @Log(title = "众筹礼物", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(pmsGiftService.deletePmsGiftByIds(ids));
    }
}
