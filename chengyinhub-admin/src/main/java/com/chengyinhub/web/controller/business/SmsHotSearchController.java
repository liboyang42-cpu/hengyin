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
import com.chengyinhub.business.domain.SmsHotSearch;
import com.chengyinhub.business.service.ISmsHotSearchService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 热门搜索词Controller
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@RestController
@RequestMapping("/business/search")
public class SmsHotSearchController extends BaseController
{
    @Autowired
    private ISmsHotSearchService smsHotSearchService;

    /**
     * 查询热门搜索词列表
     */
    @PreAuthorize("@ss.hasPermi('business:search:list')")
    @GetMapping("/list")
    public TableDataInfo list(SmsHotSearch smsHotSearch)
    {
        startPage();
        List<SmsHotSearch> list = smsHotSearchService.selectSmsHotSearchList(smsHotSearch);
        return getDataTable(list);
    }

    /**
     * 导出热门搜索词列表
     */
    @PreAuthorize("@ss.hasPermi('business:search:export')")
    @Log(title = "热门搜索词", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SmsHotSearch smsHotSearch)
    {
        List<SmsHotSearch> list = smsHotSearchService.selectSmsHotSearchList(smsHotSearch);
        ExcelUtil<SmsHotSearch> util = new ExcelUtil<SmsHotSearch>(SmsHotSearch.class);
        util.exportExcel(response, list, "热门搜索词数据");
    }

    /**
     * 获取热门搜索词详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:search:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(smsHotSearchService.selectSmsHotSearchById(id));
    }

    /**
     * 新增热门搜索词
     */
    @PreAuthorize("@ss.hasPermi('business:search:add')")
    @Log(title = "热门搜索词", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SmsHotSearch smsHotSearch)
    {
        return toAjax(smsHotSearchService.insertSmsHotSearch(smsHotSearch));
    }

    /**
     * 修改热门搜索词
     */
    @PreAuthorize("@ss.hasPermi('business:search:edit')")
    @Log(title = "热门搜索词", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SmsHotSearch smsHotSearch)
    {
        return toAjax(smsHotSearchService.updateSmsHotSearch(smsHotSearch));
    }

    /**
     * 删除热门搜索词
     */
    @PreAuthorize("@ss.hasPermi('business:search:remove')")
    @Log(title = "热门搜索词", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(smsHotSearchService.deleteSmsHotSearchByIds(ids));
    }
}
