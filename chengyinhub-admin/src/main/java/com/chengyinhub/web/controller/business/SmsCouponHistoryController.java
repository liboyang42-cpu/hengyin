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
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.dto.CouponReverseRequest;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 优惠券领取使用记录Controller
 * 
 * @author webxh
 * @date 2025-09-01
 */
@RestController
@RequestMapping("/business/couponhistory")
public class SmsCouponHistoryController extends BaseController
{
    @Autowired
    private ISmsCouponHistoryService smsCouponHistoryService;

    /**
     * 查询优惠券领取使用记录列表
     */
    @PreAuthorize("@ss.hasPermi('business:couponhistory:list')")
    @GetMapping("/list")
    public TableDataInfo list(SmsCouponHistory smsCouponHistory)
    {
        startPage();
        List<SmsCouponHistory> list = smsCouponHistoryService.selectSmsCouponHistoryList(smsCouponHistory);
        return getDataTable(list);
    }

    /**
     * 导出优惠券领取使用记录列表
     */
    @PreAuthorize("@ss.hasPermi('business:couponhistory:export')")
    @Log(title = "优惠券领取使用记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SmsCouponHistory smsCouponHistory)
    {
        List<SmsCouponHistory> list = smsCouponHistoryService.selectSmsCouponHistoryList(smsCouponHistory);
        ExcelUtil<SmsCouponHistory> util = new ExcelUtil<SmsCouponHistory>(SmsCouponHistory.class);
        util.exportExcel(response, list, "优惠券领取使用记录数据");
    }

    /**
     * 获取优惠券领取使用记录详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:couponhistory:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(smsCouponHistoryService.selectSmsCouponHistoryById(id));
    }

    /**
     * 撤销券核销(平台后台唯一入口;商家端不给)。
     *
     * <p><b>为什么只在后台</b>:商家自助撤销核销的券 = 新的刷单面(核销→撤销→再核销可刷量),
     * 需产品拍板;后台撤销至少让「店员扫错券」这类现场事故有救,不必人工改库。
     *
     * <p><b>留痕</b>:@Log 记平台账号与时间(sys_oper_log,不含请求参数);
     * 撤销事实与操作者另落 business_audit_event,由
     * {@code SmsCouponHistoryServiceImpl.reverseRedemption} 与撤销同事务写入。
     */
    @PreAuthorize("@ss.hasPermi('business:couponhistory:reverseRedeem')")
    @Log(title = "撤销券核销", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/reverse")
    public AjaxResult reverse(@PathVariable("id") Long id, @RequestBody CouponReverseRequest request)
    {
        if (id == null || id <= 0)
        {
            return error("核销记录不能为空");
        }
        String reason = request == null ? null : request.getReason();
        if (reason == null || reason.trim().isEmpty())
        {
            // 无原因不留痕 —— 撤销是资金/权益动作,「想撤就撤」查无可查。
            return error("撤销原因不能为空");
        }
        reason = reason.trim();
        if (reason.length() > 255)
        {
            return error("撤销原因不能超过 255 字");
        }
        boolean reversed = smsCouponHistoryService.reverseRedemption(id, reason, SecurityUtils.getUserId());
        if (!reversed)
        {
            // 条件置位拿 0 行 = 券当前不是已核销态。幂等不报成功:运维需要知道这次点击没有产生新动作。
            return error("该券当前不是已核销状态,无需撤销");
        }
        return success("已撤销");
    }

    /**
     * 某领取记录的不可变审计流水(核销 + 撤销,含操作者与原因)。
     * 权限沿用 query(看得到流水的人才看得到它的处置记录),不另立读取权限点。
     */
    @PreAuthorize("@ss.hasPermi('business:couponhistory:query')")
    @GetMapping(value = "/{id}/audit-trail")
    public AjaxResult auditTrail(@PathVariable("id") Long id)
    {
        return success(smsCouponHistoryService.listAuditTrail(id));
    }

    /**
     * 新增优惠券领取使用记录
     */
    @PreAuthorize("@ss.hasPermi('business:couponhistory:add')")
    @Log(title = "优惠券领取使用记录", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SmsCouponHistory smsCouponHistory)
    {
        return toAjax(smsCouponHistoryService.insertSmsCouponHistory(smsCouponHistory));
    }

    /**
     * 修改优惠券领取使用记录
     */
    @PreAuthorize("@ss.hasPermi('business:couponhistory:edit')")
    @Log(title = "优惠券领取使用记录", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SmsCouponHistory smsCouponHistory)
    {
        return toAjax(smsCouponHistoryService.updateSmsCouponHistory(smsCouponHistory));
    }

    /**
     * 删除优惠券领取使用记录
     */
    @PreAuthorize("@ss.hasPermi('business:couponhistory:remove')")
    @Log(title = "优惠券领取使用记录", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(smsCouponHistoryService.deleteSmsCouponHistoryByIds(ids));
    }
}
