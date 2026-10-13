package com.chengyinhub.web.controller.api;


import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
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
 * 余额
 *
 * @author webxh
 * @date 2025-10-21
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/api/balance")
public class ApiBalanceController extends BaseController {

    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;

    @PostMapping("/list")
    @Operation(summary = "我的余额列表")
    @Parameters({
            @Parameter(name = "change_type", description = "变化类型1：收入  2、支出")
    })
    public AjaxResult myBalanceList(String change_type) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id == 0L) {
            return error("请登录");
        }

        UmsMemberBalanceDetail umsMemberBalanceDetail = new UmsMemberBalanceDetail();
        Long changeType = Convert.toLong(change_type, 0L);
        if (changeType > 0) {
            umsMemberBalanceDetail.setChangeType(changeType);
        }
        umsMemberBalanceDetail.setMemberId(member_id);
        umsMemberBalanceDetail.setDelFlag(0L);

        List<UmsMemberBalanceDetail> balanceDetailList = umsMemberBalanceDetailService.selectUmsMemberBalanceDetailList(umsMemberBalanceDetail);

        return success(balanceDetailList);
    }

}
