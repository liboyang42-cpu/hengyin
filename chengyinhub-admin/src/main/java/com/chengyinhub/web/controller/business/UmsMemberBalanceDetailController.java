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
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 用户余额明细Controller
 * 
 * @author webxh
 * @date 2025-10-20
 */
@RestController
@RequestMapping("/business/memberbalancedetail")
public class UmsMemberBalanceDetailController extends BaseController
{
    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;

    /**
     * 查询用户余额明细列表
     */
    @PreAuthorize("@ss.hasPermi('business:memberbalancedetail:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsMemberBalanceDetail umsMemberBalanceDetail)
    {
        umsMemberBalanceDetail.setDelFlag(0L);
        startPage();
        List<UmsMemberBalanceDetail> list = umsMemberBalanceDetailService.selectUmsMemberBalanceDetailList(umsMemberBalanceDetail);
        return getDataTable(list);
    }

    /**
     * 导出用户余额明细列表
     */
    @PreAuthorize("@ss.hasPermi('business:memberbalancedetail:export')")
    @Log(title = "用户余额明细", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsMemberBalanceDetail umsMemberBalanceDetail)
    {
        umsMemberBalanceDetail.setDelFlag(0L);
        List<UmsMemberBalanceDetail> list = umsMemberBalanceDetailService.selectUmsMemberBalanceDetailList(umsMemberBalanceDetail);
        ExcelUtil<UmsMemberBalanceDetail> util = new ExcelUtil<UmsMemberBalanceDetail>(UmsMemberBalanceDetail.class);
        util.exportExcel(response, list, "用户余额明细数据");
    }

    /**
     * 获取用户余额明细详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:memberbalancedetail:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(umsMemberBalanceDetailService.selectUmsMemberBalanceDetailById(id));
    }

    /**
     * 新增用户余额明细
     */
    @PreAuthorize("@ss.hasPermi('business:memberbalancedetail:add')")
    @Log(title = "用户余额明细", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsMemberBalanceDetail umsMemberBalanceDetail)
    {
        // A3-1(P0):余额明细是财务台账(currentBalance/afterBalance/changeBalance),只能由真实交易系统写入。
        // 后台裸新增台账行 = 凭空造余额事实(资损面),一律禁止;余额调整应走带审计的专门流程。
        return AjaxResult.error(HttpStatus.FORBIDDEN, "余额明细为财务台账,不支持后台新增");
    }

    /**
     * 修改用户余额明细
     */
    @PreAuthorize("@ss.hasPermi('business:memberbalancedetail:edit')")
    @Log(title = "用户余额明细", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsMemberBalanceDetail umsMemberBalanceDetail)
    {
        // A3-1(P0):台账为财务真相,后台裸改余额金额 = 篡改财务事实,一律禁止。
        return AjaxResult.error(HttpStatus.FORBIDDEN, "余额明细为财务台账,不支持后台修改");
    }

    /**
     * 删除用户余额明细
     */
    @PreAuthorize("@ss.hasPermi('business:memberbalancedetail:remove')")
    @Log(title = "用户余额明细", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        // A3-1(P0):删台账行 = 抹掉资金变动证据,一律禁止(台账应只增不删)。
        return AjaxResult.error(HttpStatus.FORBIDDEN, "余额明细为财务台账,不支持删除");
    }
}
