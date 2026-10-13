package com.chengyinhub.web.controller.business;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.CoopComplaint;
import com.chengyinhub.business.service.ICoopComplaintService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;

/**
 * coop 客诉工单后台处理(客服):列表 / 详情 / 先行垫付 / 关闭(扣划+解冻)。
 * advance/close 用 Map 白名单取值(过错方等敏感字段由此处显式设,不整体绑定),handler=当前管理员。
 */
@RestController
@RequestMapping("/business/coopcomplaint")
public class CoopComplaintController extends BaseController {

    @Autowired
    private ICoopComplaintService coopComplaintService;

    @PreAuthorize("@ss.hasPermi('business:coopcomplaint:list')")
    @GetMapping("/list")
    public TableDataInfo list(CoopComplaint q) {
        startPage();
        List<CoopComplaint> list = coopComplaintService.selectList(q);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:coopcomplaint:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(coopComplaintService.getById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:coopcomplaint:handle')")
    @Log(title = "客诉先行垫付", businessType = BusinessType.UPDATE)
    @PostMapping("/advance")
    public AjaxResult advance(@RequestBody Map<String, Object> body) {
        Long id = Convert.toLong(body.get("id"));
        BigDecimal amount = toDecimal(body.get("amount"));
        if (id == null || amount == null) return error("参数不完整");
        try {
            return toAjax(coopComplaintService.advance(id, amount, getUserId()));
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:coopcomplaint:handle')")
    @Log(title = "客诉关闭扣划", businessType = BusinessType.UPDATE)
    @PostMapping("/close")
    public AjaxResult close(@RequestBody Map<String, Object> body) {
        Long id = Convert.toLong(body.get("id"));
        if (id == null) return error("缺少工单");
        String respondentType = Convert.toStr(body.get("respondentType"));
        Long respondentMemberId = Convert.toLong(body.get("respondentMemberId"));
        Integer faultRatio = Convert.toInt(body.get("faultRatio"));
        BigDecimal deduct = toDecimal(body.get("deductAmount"));
        String note = Convert.toStr(body.get("note"));
        try {
            return toAjax(coopComplaintService.close(id, respondentType, respondentMemberId, faultRatio, deduct, note, getUserId()));
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    private BigDecimal toDecimal(Object o) {
        if (o == null) return null;
        try { return new BigDecimal(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }
}
