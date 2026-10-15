package com.chengyinhub.web.controller.business;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IMmsMerchantService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
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
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 商家Controller
 *
 * @author ruoyi
 * @date 2025-08-22
 */
@RestController
@RequestMapping("/business/merchant")
public class MmsMerchantController extends BaseController {
    @Autowired
    private IMmsMerchantService mmsMerchantService;

    /**
     * 查询商家列表
     */
    @PreAuthorize("@ss.hasPermi('business:merchant:list')")
    @GetMapping("/list")
    public TableDataInfo list(MmsMerchant mmsMerchant) {
        startPage();
        List<MmsMerchant> list = mmsMerchantService.selectMmsMerchantList(mmsMerchant);
        return getDataTable(list);
    }

    /**
     * 导出商家列表
     */
    @PreAuthorize("@ss.hasPermi('business:merchant:export')")
    @Log(title = "商家", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, MmsMerchant mmsMerchant) {
        List<MmsMerchant> list = mmsMerchantService.selectMmsMerchantList(mmsMerchant);
        ExcelUtil<MmsMerchant> util = new ExcelUtil<MmsMerchant>(MmsMerchant.class);
        util.exportExcel(response, list, "商家数据");
    }

    /**
     * 获取商家详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:merchant:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(mmsMerchantService.selectMmsMerchantById(id));
    }

    /**
     * 新增商家
     */
    @PreAuthorize("@ss.hasPermi('business:merchant:add')")
    @Log(title = "商家", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody MmsMerchant mmsMerchant) {
        return toAjax(mmsMerchantService.insertMmsMerchant(mmsMerchant));
    }

    /**
     * 修改商家
     */
    @PreAuthorize("@ss.hasPermi('business:merchant:edit')")
    @Log(title = "商家", businessType = BusinessType.UPDATE)
    @PutMapping
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult edit(@RequestBody MmsMerchant mmsMerchant) {
        int rows = mmsMerchantService.updateMmsMerchant(mmsMerchant);
        if (rows <= 0) {
            throw new ServiceException("商家更新失败");
        }
        return toAjax(rows);
    }

    /**
     * 平台停用/启用商家账号(R9-35)。
     * <p>停用必须带 disableReason，写入独立列 disable_reason；启用清空该列。
     * 审核通过/驳回走编辑接口只写 reson，两者不复用。
     */
    @PreAuthorize("@ss.hasPermi('business:merchant:edit')")
    @Log(title = "商家账号状态", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody MmsMerchant mmsMerchant) {
        if (mmsMerchant == null || mmsMerchant.getId() == null) {
            return error("商家参数无效");
        }
        return toAjax(mmsMerchantService.updatePlatformAccountStatus(
                mmsMerchant.getId(), mmsMerchant.getAccountStatus(),
                mmsMerchant.getDisableReason(), getUsername()));
    }

    /**
     * 删除商家
     */
    @PreAuthorize("@ss.hasPermi('business:merchant:remove')")
    @Log(title = "商家", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(mmsMerchantService.deleteMmsMerchantByIds(ids));
    }
}
