package com.chengyinhub.web.controller.business;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.domain.dto.ProductPublishStatusDTO;
import com.chengyinhub.business.service.IPmsSkuService;
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
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 积分商城商品Controller
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
@RestController
@RequestMapping("/business/product")
public class PmsProductController extends BaseController
{
    @Autowired
    private IPmsProductService pmsProductService;

    @Autowired
    private IPmsSkuService pmsSkuService;

    /**
     * 查询积分商城商品列表
     */
    @PreAuthorize("@ss.hasPermi('business:product:list')")
    @GetMapping("/list")
    public TableDataInfo list(PmsProduct pmsProduct)
    {
        startPage();
        List<PmsProduct> list = pmsProductService.selectPmsProductList(pmsProduct);
        return getDataTable(list);
    }

    /**
     * 导出积分商城商品列表
     */
    @PreAuthorize("@ss.hasPermi('business:product:export')")
    @Log(title = "积分商城商品", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, PmsProduct pmsProduct)
    {
        List<PmsProduct> list = pmsProductService.selectPmsProductList(pmsProduct);
        ExcelUtil<PmsProduct> util = new ExcelUtil<PmsProduct>(PmsProduct.class);
        util.exportExcel(response, list, "积分商城商品数据");
    }

    /**
     * 获取积分商城商品详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:product:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        PmsSku pmsSku = new PmsSku();
        pmsSku.setProductId(id);
        List<PmsSku> skuList =  pmsSkuService.selectPmsSkuList(pmsSku);

        PmsProduct pmsProduct = new PmsProduct();
        pmsProduct = pmsProductService.selectPmsProductById(id);
        pmsProduct.setSkuList(skuList);
        return success(pmsProduct);
    }

    /**
     * 新增积分商城商品
     */
    @PreAuthorize("@ss.hasPermi('business:product:add')")
    @Log(title = "积分商城商品", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody PmsProduct pmsProduct)
    {
        // 新建商品先进入审核中；上/下架由专用目录接口处理，客户端不能伪造建档人或在建档时直上架。
        pmsProduct.setPublishStatus(0);
        pmsProduct.setDelFlag(0);
        pmsProduct.setCreateBy(getUsername());
        pmsProduct.setUpdateBy(getUsername());
        return toAjax(pmsProductService.insertPmsProduct(pmsProduct));
    }

    /**
     * 修改积分商城商品
     */
    @PreAuthorize("@ss.hasPermi('business:product:edit')")
    @Log(title = "积分商城商品", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody PmsProduct pmsProduct)
    {
        // 通用编辑承载资料和 SKU；状态/删除标记必须走目录专用动作，服务端覆盖操作人。
        pmsProduct.setPublishStatus(null);
        pmsProduct.setDelFlag(null);
        pmsProduct.setCreateBy(null);
        pmsProduct.setCreateTime(null);
        pmsProduct.setUpdateBy(getUsername());
        return toAjax(pmsProductService.updatePmsProduct(pmsProduct));
    }

    /**
     * 商品目录上下架。请求白名单不含 SKU、价格、库存或删除标记，防止状态动作夹带商品资料写入。
     * 商品目录沿用既有 business:product:edit 平台全目录权限；当前数据没有可验证的后台账号—商家归属映射，
     * 不能在页面迁移中擅自收紧为创建者范围而阻断已有协作账号。
     */
    @PreAuthorize("@ss.hasPermi('business:product:edit')")
    @Log(title = "积分商城商品上下架", businessType = BusinessType.UPDATE, isSaveRequestData = true)
    @PutMapping("/publish-status")
    public AjaxResult updatePublishStatus(@RequestBody ProductPublishStatusDTO request)
    {
        return toAjax(pmsProductService.updateProductPublishStatus(request, getUsername()));
    }

    /**
     * 删除积分商城商品
     */
    @PreAuthorize("@ss.hasPermi('business:product:remove')")
    @Log(title = "积分商城商品", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        // 商品仍被小程序列表、详情、购物车及订单按 ID 读取，不能在业务页抹掉历史事实。
        throw new ServiceException("积分商城商品不支持物理删除，请使用下架", HttpStatus.FORBIDDEN);
    }
}
