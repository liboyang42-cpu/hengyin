package com.chengyinhub.web.controller.api;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钱包/提现(T5 真实出金)。可用余额提现到微信零钱。
 */
@RestController
@Tag(name = "钱包提现")
@RequestMapping("/api/wallet")
public class ApiWalletController extends BaseController {

    @Autowired
    private ICashWithdrawService cashWithdrawService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private com.chengyinhub.business.service.impl.MemberFundsStageService memberFundsStageService;

    /**
     * 通道A 快速提现(微信商家转账到零钱)总开关。
     * 默认 false:未开通「商家转账到零钱」或类目未通过前保持关闭——前端据此隐藏入口,后端拒绝发起。
     * 开通并配好证书后,在外部配置 application.yml 置 withdrawal.fast-withdraw.enabled=true 开启。
     */
    @org.springframework.beans.factory.annotation.Value("${withdrawal.fast-withdraw.enabled:false}")
    private boolean fastWithdrawEnabled;

    @Operation(summary = "可用余额 + 最低提现额")
    @PostMapping("/balance")
    public AjaxResult balance(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        Map<String, Object> d = new HashMap<>();
        d.put("balance", m != null && m.getBalance() != null ? m.getBalance() : BigDecimal.ZERO);
        d.put("minAmount", ICashWithdrawService.MIN_AMOUNT);
        d.put("boundWechat", m != null && m.getWxOpenId() != null && !m.getWxOpenId().isEmpty());
        d.put("fastWithdrawEnabled", fastWithdrawEnabled); // 前端据此显隐快速提现入口
        return AjaxResult.success(d);
    }

    @Operation(summary = "余额三段:待结算 / 客诉期中(X月X日可提现)/ 可提现,涉诉金额单列")
    @PostMapping("/stages")
    public AjaxResult stages(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        try {
            return AjaxResult.success(memberFundsStageService.stages(memberId, new java.util.Date()));
        } catch (com.chengyinhub.common.exception.ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "提现到微信零钱")
    @PostMapping("/withdraw")
    public AjaxResult withdraw(@RequestBody Map<String, Object> body) {
        if (!fastWithdrawEnabled) return error("快速提现暂未开放,请使用其他提现方式或联系客服");
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.get("amount") == null) return error("请输入提现金额");
        BigDecimal amount;
        try { amount = new BigDecimal(String.valueOf(body.get("amount")).trim()); }
        catch (Exception e) { return error("提现金额不合法"); }
        try {
            return AjaxResult.success("提现申请已受理", cashWithdrawService.withdraw(memberId, amount));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "我的提现记录")
    @PostMapping("/withdraws")
    public AjaxResult withdraws(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        return AjaxResult.success(cashWithdrawService.myWithdraws(memberId));
    }
}
