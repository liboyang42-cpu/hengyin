package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.IPmsProductLikeService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.business.service.IViewProductLikeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分商品
 *
 * @author webxh
 * @date 2025-08-30
 */
@RestController
@Tag(name = "积分商品")
@RequestMapping("/api/product")
public class ApiProductController extends BaseController {
    @Autowired
    private IPmsProductService pmsProductService;

    @Autowired
    private IPmsProductLikeService pmsProductLikeService;

    @Autowired
    private IViewProductLikeService viewProductLikeService;

    @Autowired
    private IPmsSkuService pmsSkuService;

    @Operation(summary = "积分商品列表")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "merchant_id", description = "商户ID"),
            @Parameter(name = "sort_type", description = "排序方式0默认  1时间倒序  2时间升序  3价格倒序  4价格升序"),
    })
    public AjaxResult productList(String merchant_id, String sort_type) {
        PmsProduct pmsProduct = new PmsProduct();
        pmsProduct.setPublishStatus(1);
        pmsProduct.setDelFlag(0);

        Long merchantId = Convert.toLong(merchant_id, 0L);
        Integer sortType = Convert.toInt(sort_type, 0);
        if (merchantId > 0) {
            pmsProduct.setMerchantId(merchantId);
        }
        pmsProduct.setSortType(sortType);

        List<PmsProduct> list;
        // 原有的分页逻辑
        startPage();
        list = pmsProductService.selectPmsProductList(pmsProduct);
        for (PmsProduct product : list) {
            product.setDetailHtml("");
            product.setAlbumPics("");
        }

        TableDataInfo data = getDataTable(list);

        return AjaxResult.success(data);
    }

    @Operation(summary = "积分商品详情")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "id", description = "商品id")
    })
    public AjaxResult producInfo(String id) {
        PmsProduct pmsProduct = new PmsProduct();
        Long productId = Convert.toLong(id, 0L);

        if (productId > 0) {
            pmsProduct.setId(productId);
        } else {
            return error("参数不合规");
        }

        pmsProduct = pmsProductService.selectPmsProductById(productId);
        if (pmsProduct == null) {
            return error("商品不存在");
        }
        PmsSku pmsSku = new PmsSku();
        pmsSku.setProductId(productId);
        List<PmsSku> skuList = pmsSkuService.selectPmsSkuList(pmsSku);
        pmsProduct.setSkuList(skuList);

        return AjaxResult.success(pmsProduct);
    }

    @Operation(summary = "商品收藏")
    @PostMapping("/like")
    @Parameters({
            @Parameter(name = "id", description = "商品ID")
    })
    public AjaxResult productLike(String id) {
        PmsProductLike pmsProductLike = new PmsProductLike();
        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        Long productId = Convert.toLong(id, 0L);
        if (productId < 1) {
            return error("参数不合规");
        }
        pmsProductLike.setProductId(productId);
        pmsProductLike.setMemberId(member_id);

        List<PmsProductLike> list = pmsProductLikeService.selectPmsProductLikeList(pmsProductLike);
        if (list.size() > 0) {
            //取消收藏
            // 提取ID集合
            Long[] ids = list.stream()
                    .map(PmsProductLike::getId)
                    .toArray(Long[]::new);

            // 调用批量删除方法
            int rows = pmsProductLikeService.deletePmsProductLikeByIds(ids);
            if (rows > 0) {
                return success("取消收藏成功");
            } else {
                return error("取消收藏失败");
            }
        } else {
            //新增收藏
            int rows = pmsProductLikeService.insertPmsProductLike(pmsProductLike);
            if (rows > 0) {
                return success("收藏成功");
            } else {
                return error("收藏失败");
            }
        }
    }


    @Operation(summary = "我的收藏商品列表")
    @PostMapping("/mylikelist")
    public AjaxResult myLikeList() {
        ViewProductLike viewProductLike = new ViewProductLike();

        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        viewProductLike.setMemberId(member_id);

        List<ViewProductLike> list;
        // 原有的分页逻辑
        startPage();
        list = viewProductLikeService.selectViewProductLikeList(viewProductLike);

        TableDataInfo data = getDataTable(list);

        return AjaxResult.success(data);
    }


}
