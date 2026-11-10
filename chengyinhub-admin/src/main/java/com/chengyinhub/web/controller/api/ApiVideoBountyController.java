package com.chengyinhub.web.controller.api;

import java.util.List;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.VideoBountyAccrual;
import com.chengyinhub.business.domain.dto.VideoBountyAppealRequest;
import com.chengyinhub.business.domain.dto.VideoBountySubmissionView;
import com.chengyinhub.business.domain.dto.VideoBountySubmitRequest;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.service.IVideoBountyService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 视频内容悬赏(作者侧)。
 *
 * <p>作者能做四件事:投稿、看自己的投稿、对被拒申诉、取消自己未通过的投稿。
 * <b>审核与证据录入不在这个入口</b> —— 那是运营权限,走 {@code /business/videobounty}。
 *
 * <p><b>不暴露钱</b>:这里没有任何"提现/余额/收益"接口;应计只读,且明确是"仅记录"。
 *
 * @author chengyinhub
 */
@Tag(name = "视频内容悬赏(作者)")
@RestController
@RequestMapping("/api/video-bounty")
public class ApiVideoBountyController extends BaseController
{
    private final IVideoBountyService bountyService;

    public ApiVideoBountyController(IVideoBountyService bountyService) {
        this.bountyService = bountyService;
    }

    /**
     * 可投稿的悬赏活动(作者侧活动选择)。只列进行中的活动,只回 campaignKey/title/description/windowDays;
     * 费率、封顶、预算、归属与兑付标记一个都不下发。空列表 = 暂无进行中的悬赏。
     */
    @Operation(summary = "进行中的悬赏活动(作者选择用)")
    @GetMapping("/campaign/list")
    public AjaxResult listOpenCampaigns() {
        requireAppMemberId();
        return success(bountyService.listOpenCampaigns());
    }

    @Operation(summary = "投稿:同一视频不可重复投稿,跨平台搬运需同一原视频分组")
    @PostMapping("/submission")
    public AjaxResult submit(@RequestBody(required = false) VideoBountySubmitRequest request) {
        return success(bountyService.submit(requireAppMemberId(), request));
    }

    @Operation(summary = "我的投稿列表")
    @GetMapping("/submission/mine")
    public AjaxResult listMine(@RequestParam(value = "campaignKey", required = false) String campaignKey) {
        return success(bountyService.listMine(requireAppMemberId(), campaignKey));
    }

    @Operation(summary = "对被拒投稿提出申诉")
    @PostMapping("/submission/{id}/appeal")
    public AjaxResult appeal(@PathVariable("id") Long id,
                             @RequestBody(required = false) VideoBountyAppealRequest request) {
        String note = request == null ? null : request.getNote();
        return success(bountyService.appeal(requireAppMemberId(), id, note));
    }

    @Operation(summary = "取消自己的投稿(已通过的需运营撤销)")
    @PostMapping("/submission/{id}/cancel")
    public AjaxResult cancel(@PathVariable("id") Long id) {
        return success(bountyService.cancel(requireAppMemberId(), id));
    }

    /**
     * 我的应计记录。<b>只读、不是可提现余额</b>:当前外部平台证据与兑付通道未启用,
     * 所有记录都是"仅记录"状态;客户端不得显示成"可提现"。
     */
    @Operation(summary = "我的应计记录(只读,不是余额)")
    @GetMapping("/accrual/mine")
    public AjaxResult listMyAccruals() {
        List<VideoBountyAccrual> accruals = bountyService.listMyAccruals(requireAppMemberId());
        return success(accruals);
    }

    /**
     * 我这条投稿带来的成交归因(只读)。
     *
     * <p>聚合主体与目标都由服务端从投稿行读取,请求里没有 sharer/owner/target 参数 ——
     * 客户端换不了。未绑定时 {@code status=UNBOUND}、数字为 null,<b>不是</b>"确认 0 成交"。
     * 只回计数与合计,不返回任何买家 id/订单 id。
     */
    @Operation(summary = "我的投稿成交归因(只读;未绑定显示 UNBOUND)")
    @GetMapping("/submission/{id}/attribution")
    public AjaxResult submissionAttribution(@PathVariable("id") Long id) {
        return success(bountyService.submissionAttribution(requireAppMemberId(), id));
    }

    /**
     * 我作为活动 owner 按目标主题读这条 campaign 的成交归因。
     *
     * <p>只有 {@code video_bounty_campaign.owner_member_id == 当前会员} 才允许;
     * {@code topicId} 必须是该活动下真实已通过投稿的作品,否则按越界拒绝。
     */
    @Operation(summary = "活动 owner 按主题读成交归因")
    @GetMapping("/campaign/{id}/attribution")
    public AjaxResult campaignAttribution(@PathVariable("id") Long id,
                                          @RequestParam("topicId") long topicId) {
        return success(bountyService.ownerCampaignAttribution(requireAppMemberId(), id, topicId));
    }

    /** 理由码随响应返回,客户端按码分支,不解析人工文案。 */
    @ExceptionHandler(BountyTipException.class)
    public AjaxResult handleRejection(BountyTipException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    /**
     * 取真实登录身份。未登录抛 401,原样传播;
     * 绝不把"没登录"降级成 0L 伪身份。
     */
    private long requireAppMemberId() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        return memberId;
    }
}
