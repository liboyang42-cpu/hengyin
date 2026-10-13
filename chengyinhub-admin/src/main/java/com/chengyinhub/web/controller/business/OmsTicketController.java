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
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 主题/活动票务Controller
 * 
 * @author webxh
 * @date 2025-09-02
 */
@RestController
@RequestMapping("/business/ticket")
public class OmsTicketController extends BaseController
{
    @Autowired
    private IOmsTicketService omsTicketService;

    /**
     * 查询主题/活动票务列表
     */
    @PreAuthorize("@ss.hasPermi('business:ticket:list')")
    @GetMapping("/list")
    public TableDataInfo list(OmsTicket omsTicket)
    {
        startPage();
        List<OmsTicket> list = omsTicketService.selectOmsTicketList(omsTicket);
        return getDataTable(list);
    }

    /**
     * 导出主题/活动票务列表
     */
    @PreAuthorize("@ss.hasPermi('business:ticket:export')")
    @Log(title = "主题/活动票务", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, OmsTicket omsTicket)
    {
        List<OmsTicket> list = omsTicketService.selectOmsTicketList(omsTicket);
        ExcelUtil<OmsTicket> util = new ExcelUtil<OmsTicket>(OmsTicket.class);
        util.exportExcel(response, list, "主题、活动票务数据");
    }

    /**
     * 获取主题/活动票务详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:ticket:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(omsTicketService.selectOmsTicketById(id));
    }

    /**
     * 新增主题/活动票务
     */
    @PreAuthorize("@ss.hasPermi('business:ticket:add')")
    @Log(title = "主题/活动票务", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody OmsTicket omsTicket)
    {
        return toAjax(omsTicketService.insertOmsTicket(omsTicket));
    }

    /**
     * 修改主题/活动票务
     */
    @PreAuthorize("@ss.hasPermi('business:ticket:edit')")
    @Log(title = "主题/活动票务", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody OmsTicket omsTicket)
    {
        // A3-2(P0):票的归属(owner/member)与在售库存计数器不得经通用 PUT 被裸改。
        // 归属改写 = 手填 ownerId 把票挪到别的主题/活动绕开发布审核;remainingInventory 是订单流原子扣减
        // (deductInventory)维护的运行时真相,后台手改 = 超卖/清库存。
        // ⚠️ B4:剥离统一放在 OmsTicketServiceImpl(「校验 ticket 归属与声明一致」之后) —— controller
        // 提前置 null 会让 Service 看不到声明,无法拒绝跨活动/主题的 ticket id;写库前剥离这道护栏不降级。
        return toAjax(omsTicketService.updateOmsTicket(omsTicket));
    }

    /**
     * 删除主题/活动票务
     */
    @PreAuthorize("@ss.hasPermi('business:ticket:remove')")
    @Log(title = "主题/活动票务", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        // A3-2(P0):票被订单(oms_order)引用,物理删除在售/已售票=孤儿订单+抹掉交易凭据。
        // 一律禁止裸删;下架请调整上下架时间(del_flag/时间窗)。
        return AjaxResult.error(HttpStatus.FORBIDDEN, "票务不支持物理删除,请用下架(调整上下架时间)");
    }
}
