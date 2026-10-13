package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.PmsGift;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IPmsGiftService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.text.Convert;
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
 * 众筹礼品
 *
 * @author webxh
 * @date 2025-08-28
 */
@RestController
@Tag(name = "众筹礼品")
@RequestMapping("/api/gift")
public class ApiGiftController extends BaseController {
    @Autowired
    private IPmsGiftService pmsGiftService;


    @Operation(summary = "众筹礼品列表")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "isAll", description = "1取所有数据，0分页取数据"),
    })
    public AjaxResult giftList(String isAll) {
        List<PmsGift> list;
        if (Convert.toInt(isAll, 0) == 1) {
            // 如果不分页，直接查询所有列表
            list = pmsGiftService.selectAvailablePmsGiftList();
            return AjaxResult.success(list);
        } else {
            // 原有的分页逻辑
            startPage();
            list = pmsGiftService.selectAvailablePmsGiftList();
            TableDataInfo data = getDataTable(list);
            return AjaxResult.success(data);
        }
    }


}
