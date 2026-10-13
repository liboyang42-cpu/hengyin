package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayPredictResult;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayPredictService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;

/**
 * 竞猜结算(商家侧)。
 *
 * <p>玩家的下注走 {@code /api/play/advanced/action},不在这里。这里只有两件事:
 * 商家看待办、商家给答案。
 *
 * <p>★ 归属闸:只有**这个点位的承接商家**能结算它。竞猜结算会发券(商家自己的券),
 * 谁都能结等于谁都能花别人的钱。
 */
@RestController
@Tag(name = "竞猜结算")
@RequestMapping("/api/merchant/predict")
public class ApiPredictSettleController extends BaseController {

    @Autowired
    private IPlayPredictService playPredictService;

    @Autowired
    private ICmsTopicNodeService nodeService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    /** 与兜底任务 PredictVoidExpiredTask 同一个值:超过它没结算的轮次会被作废 */
    private static final int GRACE_HOURS = IPlayPredictService.ANSWER_GRACE_HOURS;

    @Operation(summary = "给出竞猜答案并结算这一轮")
    @PostMapping("/settle")
    public AjaxResult settle(String nodeId, String playDay, String settledOption) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) return error("请先登录");

        Long node = Convert.toLong(nodeId, 0L);
        if (node <= 0) return error("缺少点位");
        if (StringUtils.isBlank(settledOption)) return error("请选出正确答案");

        Date day;
        try {
            day = Date.valueOf(StringUtils.isBlank(playDay)
                    ? LocalDate.now().toString() : playDay.trim());
        } catch (IllegalArgumentException e) {
            return error("日期格式应为 YYYY-MM-DD");
        }

        Long ownerMemberId;
        try {
            ownerMemberId = merchantAccessService
                    .require(principalMemberId, MerchantPermission.PROJECT_MANAGE)
                    .getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }

        String denied = assertOwnsNode(node, ownerMemberId);
        if (denied != null) return error(denied);

        try {
            Map<String, Object> summary =
                    playPredictService.settle(node, day, settledOption.trim(), ownerMemberId);
            return success(summary);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            logger.error("竞猜结算失败 node={} day={}", node, day, e);
            return error("结算失败,请稍后重试");
        }
    }

    @Operation(summary = "我的竞猜待办(还没给答案的轮次)")
    @PostMapping("/pending")
    public AjaxResult pending(String nodeId) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        Long node = Convert.toLong(nodeId, 0L);
        if (node <= 0) return error("缺少点位");

        Long ownerMemberId;
        try {
            ownerMemberId = merchantAccessService
                    .require(principalMemberId, MerchantPermission.PROJECT_MANAGE)
                    .getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }

        String denied = assertOwnsNode(node, ownerMemberId);
        if (denied != null) return error(denied);

        java.util.List<PlayPredictResult> rounds =
                playPredictService.pendingRounds(java.util.Collections.singletonList(node));
        return success(rounds);
    }

    /**
     * 铃铛里那一条:我名下**所有**点位上还等着我给答案的轮次。
     *
     * <p>与 {@code /pending} 的差别是范围和内容:那个要传 nodeId、只回轮次本身,
     * 这个按承接关系把我的点位全扫一遍,并且把题面、选项、多少人押了、还剩几天一起给 ——
     * 商家是在消息列表里被提醒的,他手上没有 nodeId,也不该为了给个答案先去翻点位。
     */
    @Operation(summary = "竞猜待答收件箱(我名下全部点位)")
    @PostMapping("/inbox")
    public AjaxResult inbox() {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) return error("请先登录");

        Long ownerMemberId;
        try {
            ownerMemberId = merchantAccessService
                    .require(principalMemberId, MerchantPermission.PROJECT_MANAGE)
                    .getOwnerMemberId();
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        try {
            return success(playPredictService.pendingInbox(ownerMemberId, GRACE_HOURS));
        } catch (Exception e) {
            logger.error("竞猜待办读取失败 owner={}", ownerMemberId, e);
            return error("待办读取失败,请稍后重试");
        }
    }

    /**
     * 这个点位是不是这个商家承接的。
     *
     * <p>「点位不存在」与「不是你的」共用一句话 —— 分开说就等于给出一个点位 id 探测器。
     */
    private String assertOwnsNode(Long nodeId, Long ownerMemberId) {
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getMerchantMemberId() == null
                || !node.getMerchantMemberId().equals(ownerMemberId)) {
            return "点位不存在或不归你承接";
        }
        return null;
    }
}
