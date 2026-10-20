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
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.domain.CmsInfomation;
import com.chengyinhub.business.domain.dto.CmsInfomationWriteDTO;
import com.chengyinhub.business.service.ICmsInfomationService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.StringUtils;

/**
 * 玩法说明Controller
 * 
 * @author ruoyi
 * @date 2025-11-24
 */
@RestController
@RequestMapping("/business/infomation")
public class CmsInfomationController extends BaseController
{
    @Autowired
    private ICmsInfomationService cmsInfomationService;

    /**
     * 查询玩法说明列表
     */
    @PreAuthorize("@ss.hasPermi('business:infomation:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsInfomation cmsInfomation)
    {
        startPage();
        List<CmsInfomation> list = cmsInfomationService.selectCmsInfomationList(cmsInfomation);
        return getDataTable(list);
    }

    /**
     * 导出玩法说明列表
     */
    @PreAuthorize("@ss.hasPermi('business:infomation:export')")
    @Log(title = "玩法说明", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsInfomation cmsInfomation)
    {
        List<CmsInfomation> list = cmsInfomationService.selectCmsInfomationList(cmsInfomation);
        ExcelUtil<CmsInfomation> util = new ExcelUtil<CmsInfomation>(CmsInfomation.class);
        util.exportExcel(response, list, "玩法说明数据");
    }

    /**
     * 获取玩法说明详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:infomation:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsInfomationService.selectCmsInfomationById(id));
    }

    /**
     * 新增玩法说明
     */
    @PreAuthorize("@ss.hasPermi('business:infomation:add')")
    @Log(title = "玩法说明", businessType = BusinessType.INSERT, isSaveRequestData = true)
    @PostMapping
    public AjaxResult add(@RequestBody CmsInfomationWriteDTO request)
    {
        validateAuditReason(request);
        CmsInfomation cmsInfomation = toCmsInfomation(request);
        cmsInfomation.setId(null);
        cmsInfomation.setCreateBy(SecurityUtils.getUsername());
        cmsInfomation.setCreateTime(null);
        cmsInfomation.setUpdateBy(null);
        cmsInfomation.setUpdateTime(null);
        return toAjax(cmsInfomationService.insertCmsInfomation(cmsInfomation, request.getAuditReason(),
                SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    /**
     * 修改玩法说明
     */
    @PreAuthorize("@ss.hasPermi('business:infomation:edit')")
    @Log(title = "玩法说明", businessType = BusinessType.UPDATE, isSaveRequestData = true)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsInfomationWriteDTO request)
    {
        validateAuditReason(request);
        if (request.getId() == null)
        {
            throw new ServiceException("缺少玩法说明 ID", HttpStatus.BAD_REQUEST);
        }
        CmsInfomation cmsInfomation = toCmsInfomation(request);
        cmsInfomation.setCreateBy(null);
        cmsInfomation.setCreateTime(null);
        cmsInfomation.setUpdateBy(SecurityUtils.getUsername());
        cmsInfomation.setUpdateTime(null);
        return toAjax(cmsInfomationService.updateCmsInfomation(cmsInfomation, request.getAuditReason(),
                SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    /**
     * 删除玩法说明
     */
    @PreAuthorize("@ss.hasPermi('business:infomation:remove')")
    @Log(title = "玩法说明", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        throw new ServiceException("玩法说明仍被小程序展示链路消费，当前不支持物理删除", HttpStatus.FORBIDDEN);
    }

    private void validateAuditReason(CmsInfomationWriteDTO request)
    {
        if (request == null || StringUtils.isBlank(request.getAuditReason()))
        {
            throw new ServiceException("请填写变更说明", HttpStatus.BAD_REQUEST);
        }
        if (request.getAuditReason().trim().length() > 500)
        {
            throw new ServiceException("变更说明不能超过 500 个字符", HttpStatus.BAD_REQUEST);
        }
        request.setAuditReason(request.getAuditReason().trim());
    }

    private CmsInfomation toCmsInfomation(CmsInfomationWriteDTO request)
    {
        CmsInfomation cmsInfomation = new CmsInfomation();
        cmsInfomation.setId(request.getId());
        cmsInfomation.setTitle(request.getTitle());
        cmsInfomation.setSubtitle(request.getSubtitle());
        cmsInfomation.setSortId(request.getSortId());
        cmsInfomation.setContents(request.getContents());
        return cmsInfomation;
    }
}
