package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.PmsGift;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.UmsCrowdfunding;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.service.IPmsGiftService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsCrowdfundingService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 众筹礼品
 *
 * @author webxh
 * @date 2025-08-28
 */
@RestController
@Tag(name = "众筹礼品")
@RequestMapping("/api/gift")
public class ApiCrowdfundingController extends BaseController {

    // 添加日志记录器
    private static final Logger log = LoggerFactory.getLogger(ApiCrowdfundingController.class);

    @Autowired
    private IPmsGiftService pmsGiftService;

    @Autowired
    private IUmsCreativeSquareService umsCreativeSquareService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IUmsCrowdfundingService umsCrowdfundingService;

    @Autowired
    private IUmsMemberPointsDetailService umsMemberPointsDetailService;

    @Operation(summary = "众筹礼品赠送")
    @PostMapping("/give")
    @Parameters({
            @Parameter(name = "active_id", description = "活动id"),
            @Parameter(name = "gift_id", description = "礼物ID"),
            @Parameter(name = "contents", description = "内容")
    })
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult giftGive(String active_id, String gift_id, String contents) {
        Long member_id = getAppUserId();
        try {
            // 参数校验
            if (member_id == null || member_id <= 0) {
                return error("请先登录");
            }

            Long activeIdLong = Convert.toLong(active_id, 0L);
            Long giftIdLong = Convert.toLong(gift_id, 0L);
            if (activeIdLong <= 0 || giftIdLong <= 0) {
                return error("参数错误");
            }

            PmsGift pmsGift = pmsGiftService.selectPmsGiftById(giftIdLong);
            if (pmsGift == null) {
                return error("该礼物已下架");
            }

            if (!Integer.valueOf(1).equals(pmsGift.getStatus()) || !Integer.valueOf(0).equals(pmsGift.getDelFlag())) {
                return error("该礼物已下架");
            }

            Long point = pmsGift.getPoint();
            if (point == null || point <= 0 || point > Integer.MAX_VALUE) {
                return error("礼物积分配置无效");
            }

            UmsCreativeSquare creativeSquare = umsCreativeSquareService.selectUmsCreativeSquareById(activeIdLong);
            if (!isGiftableCrowdfundingTarget(creativeSquare)) {
                return error("该众筹活动不可赠送");
            }

            // 查询用户信息
            UmsMember umsMember = umsMemberService.selectUmsMemberById(member_id);
            if (umsMember == null) {
                return error("用户不存在");
            }

            Long userPoint = umsMember.getPoint();
            if (userPoint < point) {
                return error("用户积分不足");
            }

            // 扣除用户积分
            // [P1-资损] 原子扣减(point >= ? 才扣),并发赠送不会把积分扣穿;不足时服务抛异常触发回滚
            umsMemberService.deductPoints(umsMember.getId(), point.intValue());

            // 插入礼物赠送记录
            UmsCrowdfunding umsCrowdfunding = new UmsCrowdfunding();
            umsCrowdfunding.setMemberId(member_id);
            umsCrowdfunding.setGiftId(pmsGift.getId());
            umsCrowdfunding.setActiveId(activeIdLong);
            umsCrowdfunding.setPoint(point);
            umsCrowdfunding.setContents(contents);
            umsCrowdfunding.setDelFlag(0);

            int rows2 = umsCrowdfundingService.insertUmsCrowdfunding(umsCrowdfunding);
            if (rows2 <= 0) {
                throw new ServiceException("赠送失败"); // [P0-资损] 抛异常回滚,避免扣分成功但赠送记录未落库
            }

            // 插入用户积分变动明细
            UmsMemberPointsDetail umsMemberPointsDetail = new UmsMemberPointsDetail();
            umsMemberPointsDetail.setMemberId(member_id);
            umsMemberPointsDetail.setCurrentPoints(userPoint);
            umsMemberPointsDetail.setChangePoints(point);
            umsMemberPointsDetail.setAfterPoints(userPoint - point);
            umsMemberPointsDetail.setEventType(2L);
            umsMemberPointsDetail.setEventId(umsCrowdfunding.getId());
            umsMemberPointsDetail.setChangeReason("众筹赠送");
            umsMemberPointsDetail.setChangeType(2L);
            umsMemberPointsDetail.setDelFlag(0);

            int rows3 = umsMemberPointsDetailService.insertUmsMemberPointsDetail(umsMemberPointsDetail);
            if (rows3 <= 0) {
                throw new ServiceException("赠送失败"); // [P0-资损] 抛异常回滚
            }

            return success("赠送成功");
        } catch (ServiceException se) {
            throw se; // 业务异常上抛:触发 @Transactional 回滚 + 全局返回清晰消息
        } catch (Exception e) {
            // [P0-资损] 记录后必须上抛,否则扣积分成功但后续失败仍提交 → 资损/数据不一致
            log.error("礼品赠送失败: active_id={}, gift_id={}, member_id={}", active_id, gift_id, member_id, e);
            throw new ServiceException("系统繁忙，请稍后重试");
        }
    }

    private boolean isGiftableCrowdfundingTarget(UmsCreativeSquare creativeSquare) {
        return creativeSquare != null
                && Integer.valueOf(0).equals(creativeSquare.getDelFlag())
                && Integer.valueOf(1).equals(creativeSquare.getStatus())
                && Integer.valueOf(1).equals(creativeSquare.getIsCrowdfunding());
    }
}
