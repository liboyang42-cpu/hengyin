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
import com.chengyinhub.business.domain.TmsBrowseRecord;
import com.chengyinhub.business.service.ITmsBrowseRecordService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 主题/活动浏览记录Controller
 * 
 * @author webxh
 * @date 2025-10-20
 */
@RestController
@RequestMapping("/business/browserecord")
public class TmsBrowseRecordController extends BaseController
{
    @Autowired
    private ITmsBrowseRecordService tmsBrowseRecordService;

    /**
     * 查询主题/活动浏览记录列表
     */
    @PreAuthorize("@ss.hasPermi('business:browserecord:list')")
    @GetMapping("/list")
    public TableDataInfo list(TmsBrowseRecord tmsBrowseRecord)
    {
        startPage();
        List<TmsBrowseRecord> list = tmsBrowseRecordService.selectTmsBrowseRecordList(tmsBrowseRecord);
        return getDataTable(list);
    }

    /**
     * 导出主题/活动浏览记录列表
     */
    @PreAuthorize("@ss.hasPermi('business:browserecord:export')")
    @Log(title = "主题/活动浏览记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, TmsBrowseRecord tmsBrowseRecord)
    {
        List<TmsBrowseRecord> list = tmsBrowseRecordService.selectTmsBrowseRecordList(tmsBrowseRecord);
        ExcelUtil<TmsBrowseRecord> util = new ExcelUtil<TmsBrowseRecord>(TmsBrowseRecord.class);
        util.exportExcel(response, list, "主题、活动浏览记录数据");
    }

    /**
     * 获取主题/活动浏览记录详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:browserecord:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(tmsBrowseRecordService.selectTmsBrowseRecordById(id));
    }

    /**
     * 新增主题/活动浏览记录
     */
    @PreAuthorize("@ss.hasPermi('business:browserecord:add')")
    @Log(title = "主题/活动浏览记录", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody TmsBrowseRecord tmsBrowseRecord)
    {
        return toAjax(tmsBrowseRecordService.insertTmsBrowseRecord(tmsBrowseRecord));
    }

    /**
     * 修改主题/活动浏览记录
     */
    @PreAuthorize("@ss.hasPermi('business:browserecord:edit')")
    @Log(title = "主题/活动浏览记录", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody TmsBrowseRecord tmsBrowseRecord)
    {
        return toAjax(tmsBrowseRecordService.updateTmsBrowseRecord(tmsBrowseRecord));
    }

    /**
     * 删除主题/活动浏览记录
     */
    @PreAuthorize("@ss.hasPermi('business:browserecord:remove')")
    @Log(title = "主题/活动浏览记录", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(tmsBrowseRecordService.deleteTmsBrowseRecordByIds(ids));
    }
}
