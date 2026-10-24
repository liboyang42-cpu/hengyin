package com.chengyinhub.web.controller.business;

import java.util.List;
import java.util.Set;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RedemptionReverseRequest;
import com.chengyinhub.business.domain.dto.ParentOrderRedemptionReverseRequest;
import com.chengyinhub.business.domain.dto.RegistrationVerificationReverseRequest;
import com.chengyinhub.business.security.AdminPiiProtector;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 活动/主题报名Controller
 * 
 * @author webxh
 * @date 2025-09-04
 */
@RestController
@RequestMapping("/business/registration")
public class CmsRegistrationController extends BaseController
{
    /**
     * 「看全量 PII」权限。无此权限者,姓名/手机/邮箱/地址一律脱敏后才出后端。
     * 超管持 *:*:*,天然匹配本权限,看到的仍是全量。
     */
    private static final String PERM_VIEW_PII = "business:registration:pii";

    @Autowired
    private ICmsRegistrationService cmsRegistrationService;

    @Autowired
    private IPerkLedgerService perkLedgerService;

    /**
     * 直接读登录态权限集判定,而不是调 PermissionService.hasPermi —— 后者会顺手
     * PermissionContextHolder.setContext(权限串),把 @PreAuthorize 刚设进去的 context 覆盖掉;
     * DataScopeAspect 在注解没写 permission 时正是回退读这个 context 做数据范围过滤。
     * 今天业务 service 上没有 @DataScope(只有 3 个 sys* 有)所以够不着,但这里本就不需要那个副作用,
     * 从源头不产生比事后 save/restore 更简单。判定语义与框架 hasPermissions 一致(超管 *:*:* 匹配一切),
     * 由 canViewPii与框架hasPermi语义一致 用例交叉钉住,防止两边漂移。
     */
    private boolean canViewPii()
    {
        Set<String> permissions = SecurityUtils.getLoginUser().getPermissions();
        return permissions != null
                && (permissions.contains(Constants.ALL_PERMISSION) || permissions.contains(PERM_VIEW_PII));
    }

    /**
     * 查询活动/主题报名列表
     */
    @PreAuthorize("@ss.hasPermi('business:registration:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsRegistration cmsRegistration)
    {
        boolean full = canViewPii();
        if (!full)
        {
            // 先清检索条件:按 PII 检索本身就是 PII 能力。否则看到 138****1234 的人
            // 枚举中间四位就能反推出真号,脱敏形同虚设。
            AdminPiiProtector.stripPii(cmsRegistration);
        }
        startPage();
        List<CmsRegistration> list = cmsRegistrationService.selectCmsRegistrationList(cmsRegistration);
        if (!full)
        {
            list.forEach(AdminPiiProtector::maskInPlace);
        }
        return getDataTable(list);
    }

    /**
     * 导出活动/主题报名列表
     */
    @PreAuthorize("@ss.hasPermi('business:registration:export')")
    @Log(title = "活动/主题报名", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsRegistration cmsRegistration)
    {
        boolean full = canViewPii();
        if (!full)
        {
            AdminPiiProtector.stripPii(cmsRegistration);
        }
        List<CmsRegistration> list = cmsRegistrationService.selectCmsRegistrationList(cmsRegistration);
        if (!full)
        {
            // 导出是真正的批量外泄口,不脱敏的话前面几道都白做
            list.forEach(AdminPiiProtector::maskInPlace);
        }
        ExcelUtil<CmsRegistration> util = new ExcelUtil<CmsRegistration>(CmsRegistration.class);
        util.exportExcel(response, list, "活动、主题报名数据");
    }

    /**
     * 获取活动/主题报名详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:registration:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        CmsRegistration registration = cmsRegistrationService.selectCmsRegistrationById(id);
        if (!canViewPii())
        {
            AdminPiiProtector.maskInPlace(registration);
        }
        return success(registration);
    }

    /**
     * 某票的全部核销流水(含已撤销)—— 撤销入口的读回列表。
     *
     * <p>为什么需要它:红单撤销后必须能**看见**「何时、因何被撤」,否则撤销动作本身没有观测面。
     * 权限沿用 query(看得到票的人才看得到它的核销流水),不另立读取权限点。
     */
    @PreAuthorize("@ss.hasPermi('business:registration:query')")
    @GetMapping(value = "/{id}/redemptions")
    public AjaxResult redemptions(@PathVariable("id") Long id)
    {
        return success(perkLedgerService.listRedemptions(id));
    }

    /**
     * 撤销核销(平台后台唯一入口;商家端不给)。
     *
     * <p><b>为什么只在后台</b>:商家自助撤销 = 新的刷单/骗权益面(核销→撤销→再核销可刷通关与计酬),
     * 需产品拍板后才谈;后台撤销至少让「店员扫错票/券」这类现场事故有救,不必人工改库。
     *
     * <p><b>留痕</b>:@Log 记平台账号与时间(sys_oper_log,不含请求参数);
     * 撤销事实与操作者另落 redemption + business_audit_event,由
     * {@code PerkLedgerServiceImpl.reverseRedemption} 与撤销同事务写入。
     */
    @PreAuthorize("@ss.hasPermi('business:registration:reverseRedeem')")
    @Log(title = "撤销核销", businessType = BusinessType.UPDATE)
    @PostMapping("/redemption/reverse")
    public AjaxResult reverseRedemption(@RequestBody RedemptionReverseRequest request)
    {
        if (request == null || request.getRedemptionId() == null || request.getRedemptionId() <= 0)
        {
            return error("核销流水不能为空");
        }
        String reason = request.getReason();
        if (reason == null || reason.trim().isEmpty())
        {
            // 无原因不留痕 —— 撤销是资金/权益动作,「想撤就撤」查无可查。
            return error("撤销原因不能为空");
        }
        reason = reason.trim();
        if (reason.length() > 255)
        {
            return error("撤销原因不能超过 255 字");
        }
        boolean reversed = perkLedgerService.reverseRedemption(
                request.getRedemptionId(), reason, SecurityUtils.getUserId(),
                Boolean.TRUE.equals(request.getClawbackRewards()));
        if (!reversed)
        {
            // 条件置位拿 0 行 = 已被撤销过。幂等不报成功:运维需要知道这次点击没有产生新动作。
            return error("该核销已被撤销,无需重复撤销");
        }
        return success("已撤销");
    }

    @PreAuthorize("@ss.hasPermi('business:registration:reverseRedeem')")
    @Log(title = "父订单撤销核销", businessType = BusinessType.UPDATE)
    @PostMapping("/redemption/reverse-parent")
    public AjaxResult reverseParentOrderRedemptions(@RequestBody ParentOrderRedemptionReverseRequest request) {
        if (request == null) return error("撤销参数不能为空");
        List<Long> ids = perkLedgerService.reverseParentOrderRedemptions(request.getParentOrderNo(),
                request.getReason(), SecurityUtils.getUserId(), Boolean.TRUE.equals(request.getClawbackRewards()));
        return success(ids);
    }

    /** 撤销活动票整单核销(无按章流水的票);与撤销按章核销同一权限点。 */
    @PreAuthorize("@ss.hasPermi('business:registration:reverseRedeem')")
    @Log(title = "撤销整单核销", businessType = BusinessType.UPDATE)
    @PostMapping("/verification/reverse")
    public AjaxResult reverseWholeOrderVerification(@RequestBody RegistrationVerificationReverseRequest request) {
        if (request == null || request.getRegistrationId() == null || request.getRegistrationId() <= 0) {
            return error("订单不能为空");
        }
        try {
            cmsRegistrationService.reverseWholeOrderVerification(request.getRegistrationId(),
                    request.getReason(), SecurityUtils.getUserId(), Boolean.TRUE.equals(request.getClawbackRewards()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
        return success("已撤销");
    }

    /**
     * 新增活动/主题报名
     */
    @PreAuthorize("@ss.hasPermi('business:registration:add')")
    @Log(title = "活动/主题报名", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsRegistration cmsRegistration)
    {
        return AjaxResult.error(403, "通用新增报名已关闭，请通过正式下单流程创建报名")
                .put("errorCode", "REGISTRATION_GENERIC_CREATE_DISABLED");
    }

    /**
     * 修改活动/主题报名
     */
    @PreAuthorize("@ss.hasPermi('business:registration:edit')")
    @Log(title = "活动/主题报名", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsRegistration cmsRegistration)
    {
        if (cmsRegistration == null || cmsRegistration.getId() == null) {
            return error("报名ID不能为空");
        }
        // 只接受普通展示/联系信息；原身份、资金、库存和权益依据不从通用请求复制。
        cmsRegistration = editableRegistrationFields(cmsRegistration);
        if (!canViewPii())
        {
            // 这些人看到的是脱敏值,提交回来的也只能是脱敏值。置 null 让 update 的动态 <if> 跳过这几列,
            // 否则 "138****1234" 会被当真值加密写回,真实手机号当场没了。
            AdminPiiProtector.stripPii(cmsRegistration);
        }
        return toAjax(cmsRegistrationService.updateCmsRegistration(cmsRegistration));
    }

    /**
     * 后台通用编辑的明确白名单。新增领域字段默认不可写；内部支付/汇总 Mapper 能力不受影响。
     */
    private static CmsRegistration editableRegistrationFields(CmsRegistration request)
    {
        CmsRegistration editable = new CmsRegistration();
        editable.setId(request.getId());
        editable.setActivityTitle(request.getActivityTitle());
        editable.setTicketName(request.getTicketName());
        editable.setRealName(request.getRealName());
        editable.setPhone(request.getPhone());
        editable.setEmail(request.getEmail());
        editable.setAddress(request.getAddress());
        editable.setAddressId(request.getAddressId());
        return editable;
    }

    /**
     * 删除活动/主题报名
     */
    @PreAuthorize("@ss.hasPermi('business:registration:remove')")
    @Log(title = "活动/主题报名", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        // A3-1(P0):报名承载支付事实(金额/交易号/核销),删除即抹掉交易记录。
        // 一律禁止后台裸删;确需取消报名应走退款/取消流程(留痕、可对账)。
        return AjaxResult.error(HttpStatus.FORBIDDEN, "报名含支付事实,不支持删除;如需取消请走退款/取消流程");
    }
}
