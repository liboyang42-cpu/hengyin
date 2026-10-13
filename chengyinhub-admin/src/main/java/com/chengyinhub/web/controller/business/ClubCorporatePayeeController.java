package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.ClubCorporatePayeeProfile;
import com.chengyinhub.business.service.IClubCorporatePayeeService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * A4 俱乐部对公收款人：写资料 / 财务审核 / 受控解密三条路各自鉴权，互不代替。
 *
 * <p>写资料与查看默认只回掩码；完整明文只有 business:clubpayee:payout 一条路能拿到。
 *
 * <p>★契约是「财务<b>一次</b>审核」，<b>不设 maker/checker</b>：service 侧刻意删掉了
 * {@code createdBy != verifier} 那条比较，理由写在
 * {@code ClubCorporatePayeeServiceImpl.verify}（后台单超管账号下加了它，所有 profile 永不过审）。
 * 「俱乐部 owner 不得自审」由 <b>app 侧零 payee 入口</b>保证
 * （{@code ClubCorporatePayeeExternalSelfVerifyClosedTest} 扫描式钉住），不靠那条比较。
 * ⚠️ 别再把这里写成「有第二双眼睛」—— 裁决表 §7.1 E3：宁可明确没有，不要假装有。
 * ⚠️ 与批次的「确认人 ≠ 付款登记人」不是一回事，那条是明令保留的，见
 * {@code TopicEditionFinalizationController}。
 */
@RestController
@RequestMapping("/business/club-payee")
public class ClubCorporatePayeeController extends BaseController {

    private final IClubCorporatePayeeService payeeService;

    public ClubCorporatePayeeController(IClubCorporatePayeeService payeeService) {
        this.payeeService = payeeService;
    }

    @PreAuthorize("@ss.hasPermi('business:club:edit')")
    @Log(title = "俱乐部对公收款人资料", businessType = BusinessType.UPDATE)
    @PutMapping("/{clubId}")
    public AjaxResult saveProfile(@PathVariable Long clubId, @RequestBody ClubCorporatePayeeProfile body) {
        payeeService.createVersion(clubId, body, getUserId());
        return success(payeeService.maskedView(clubId));
    }

    @PreAuthorize("@ss.hasPermi('business:club:query')")
    @GetMapping("/{clubId}")
    public AjaxResult view(@PathVariable Long clubId) {
        return success(payeeService.maskedView(clubId));
    }

    @PreAuthorize("@ss.hasPermi('business:clubpayee:verify')")
    @Log(title = "俱乐部对公收款人审核", businessType = BusinessType.UPDATE)
    @PostMapping("/{clubId}/verify/{profileId}")
    public AjaxResult verify(@PathVariable Long clubId, @PathVariable Long profileId,
                             @RequestBody Map<String, Object> body) {
        // 显式解析：JSON 传字符串 "true" 时 Boolean.TRUE.equals 会静默变 false，
        // 而审核是一次性不可逆动作 —— 静默判不过 = 该版本永久 REJECTED。
        Object rawPass = body == null ? null : body.get("pass");
        if (!(rawPass instanceof Boolean)) {
            throw new IllegalArgumentException("PAYEE_VERIFY_PASS_MUST_BE_BOOLEAN:" + rawPass);
        }
        boolean pass = (Boolean) rawPass;
        String licenseHash = text(body.get("licenseHash"));
        payeeService.verify(clubId, profileId, getUserId(), pass, licenseHash);
        return success(payeeService.maskedView(clubId));
    }

    @PreAuthorize("@ss.hasPermi('business:clubpayee:payout')")
    @Log(title = "俱乐部对公收款人受控解密", businessType = BusinessType.OTHER)
    @GetMapping("/{clubId}/payout-detail")
    public AjaxResult payoutDetail(@PathVariable Long clubId) {
        return success(payeeService.payoutView(clubId));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
