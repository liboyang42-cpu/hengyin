package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.IGroupCodeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import com.chengyinhub.web.service.verify.RegistrationTicketApplication;
import com.chengyinhub.web.service.verify.CityNodeRedemptionApplication;
/**
 * 核销动态码(BE-11 · Layer B 游戏外核销)。
 *
 * <p>玩家侧签发自己报名单的动态核销码(nonce+TTL),供商家/俱乐部扫码后端校验(D-8 收数据方扫)。
 * 本控制器先只负责<b>签发</b>,与在跑的静态 verificationCode 核销(ApiRegistrationController.scanQrCode)并存;
 * 扫码端接入(先试动态码 verify、失败回落静态码)后续切,避免一次性动到资金核销主路径。
 */
@Tag(name = "核销动态码")
@RestController
@RequestMapping("/api/verify")
public class ApiVerifyController extends BaseController {

    @Autowired
    private RegistrationTicketApplication registrationTicketApplication;

    @Autowired
    private IGroupCodeService groupCodeService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private CityNodeRedemptionApplication cityNodeRedemptionApplication;

    @Operation(summary = "签发我的动态核销码(玩家出码,商家/俱乐部扫)")
    @PostMapping("/dyncode/issue")
    public AjaxResult issueDynCode(Long registrationId, Integer seatNo) {
        return registrationTicketApplication.issueDynCode(appUid(), registrationId, seatNo);
    }

    /** Java compatibility only; a multi-seat order still requires an explicit signed seat. */
    public AjaxResult issueDynCode(Long registrationId) { return issueDynCode(registrationId, null); }

    @Operation(summary = "回读原席位核销事实，不再次核销")
    @PostMapping("/seat/receipt")
    public AjaxResult seatReceipt(Long registrationId, Integer seatNo, Long activityId) {
        return registrationTicketApplication.seatReceipt(appUid(), registrationId, seatNo, activityId);
    }

    @Operation(summary = "签发我的据点核销码(玩家出码,商家扫码领券)")
    @PostMapping("/citynode/issue")
    public AjaxResult issueCityNodeCode(Long poiId) {
        return cityNodeRedemptionApplication.issueCityNodeCode(appUid(), poiId);
    }

    @Operation(summary = "商家扫玩家据点核销码,校验后发券(C2 防伪造GPS薅券)")
    @PostMapping("/citynode/redeem")
    public AjaxResult redeemCityNodeCode(String code) {
        return cityNodeRedemptionApplication.redeemCityNodeCode(appUid(), code);
    }

    @Operation(summary = "回读原据点核销结果，不再次发券")
    @PostMapping("/citynode/receipt")
    public AjaxResult cityNodeReceipt(String code) {
        return cityNodeRedemptionApplication.cityNodeReceipt(appUid(), code);
    }

    @Operation(summary = "签发本团的团核销码(D22:主理人/管理员出码,商家扫)")
    @PostMapping("/groupcode/issue")
    public AjaxResult issueGroupCode(Long activityId) {
        return groupCodeService.issue(activityId, appUid());
    }

    /**
     * D22 · T2b:商家扫团码 = 记「该商家接待了该团」。
     *
     * <p>① 是有领队产品,团整体走完全程 ⇒ 每个节点商家接待的是**整个团**,不是零散来客。
     * 所以组织槽有人时,商家不扫玩家票(那是俱乐部干的),改扫领队出示的团码;
     * 他的分账基数 = 「他接待过的那些团各自核销了多少人」,由 settleCoopTopic 结算时现查。
     *
     * <p>本端点**不动钱、不置核销位**,只落一条存在性台账 —— 钱在 settleCoopTopic 出。
     */
    @Operation(summary = "商家扫团核销码,记录接待该团(D22 · T2b)")
    @PostMapping("/groupcode/redeem")
    public AjaxResult redeemGroupCode(String code) {
        long principalMemberId = appUid();
        if (principalMemberId <= 0) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.VERIFY);
        return groupCodeService.redeem(code, access.getOwnerMemberId(),
                principalMemberId, access.getRoleCode());
    }

    /** 取当前登录 App 用户 id;未登录/异常返回 0。 */
    private long appUid() {
        try {
            Long id = getAppUserId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }
}
