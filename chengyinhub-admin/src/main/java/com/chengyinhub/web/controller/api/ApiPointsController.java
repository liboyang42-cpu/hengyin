package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
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
 * 积分
 *
 * @author webxh
 * @date 2025-10-21
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/api/points")
public class ApiPointsController extends BaseController {

    @Autowired
    private IUmsMemberPointsDetailService umsMemberPointsDetailService;

    @Autowired
    private ICmsPointsSettingService cmsPointsSettingService;


    @PostMapping("/result_list")
    @Operation(summary = "我的积分列表")
    public AjaxResult resultList() {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }

        if (member_id == 0L) {
            return error("请登录");
        }

        CmsPointsSetting cmsPointsSetting = new CmsPointsSetting();
        cmsPointsSetting.setType(1L);
        List<CmsPointsSetting> pointsDetailList = cmsPointsSettingService.selectCmsPointsSettingList(cmsPointsSetting);

        for (int i = 0; i < pointsDetailList.size(); i++) {
            UmsMemberPointsDetail umsMemberPointsDetail = new UmsMemberPointsDetail();
            umsMemberPointsDetail.setMemberId(member_id);
            umsMemberPointsDetail.setEventType(pointsDetailList.get(i).getEventType());
            int pointsNum =  umsMemberPointsDetailService.selectUmsMemberPointsDetailCount(umsMemberPointsDetail);
            pointsDetailList.get(i).setPointsNum(pointsNum);
        }

        return success(pointsDetailList);
    }

    @PostMapping("/list")
    @Operation(summary = "我的积分列表")
    @Parameters({
            @Parameter(name = "change_type", description = "变化类型1：收入  2、支出")
    })
    public AjaxResult myPointsList(String change_type) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id == 0L) {
            return error("请登录");
        }

        UmsMemberPointsDetail umsMemberPointsDetail = new UmsMemberPointsDetail();
//        Long changeType = Convert.toLong(change_type, 0L);
//        if (changeType > 0) {
//            umsMemberPointsDetail.setChangeType(changeType);
//        }
        umsMemberPointsDetail.setMemberId(member_id);
        umsMemberPointsDetail.setDelFlag(0);

        List<UmsMemberPointsDetail> pointsDetailList = umsMemberPointsDetailService.selectUmsMemberPointsDetailList(umsMemberPointsDetail);

        return success(pointsDetailList);
    }


}
