package com.chengyinhub.web.controller.business;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import java.util.Set;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.security.AdminPiiProtector;
import com.chengyinhub.business.service.IUmsMemberService;
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
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 用户Controller
 * 
 * @author ruoyi
 * @date 2025-08-13
 */
@RestController
@RequestMapping("/business/member")
public class UmsMemberController extends BaseController
{
    /**
     * 「看全量 PII」权限。无此权限者,手机号/姓名一律脱敏后才出后端;密码哈希则对谁都不返回。
     * 超管持 *:*:*,天然匹配本权限,看到的仍是全量。
     */
    private static final String PERM_VIEW_PII = "business:member:pii";

    @Autowired
    private IUmsMemberService umsMemberService;

    /** 不调 PermissionService.hasPermi:它会污染 DataScope 依赖的 PermissionContextHolder。 */
    private boolean canViewPii()
    {
        Set<String> permissions = SecurityUtils.getLoginUser().getPermissions();
        return permissions != null
                && (permissions.contains(Constants.ALL_PERMISSION) || permissions.contains(PERM_VIEW_PII));
    }

    /**
     * 查询用户列表
     */
    @PreAuthorize("@ss.hasPermi('business:member:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsMember umsMember)
    {
        boolean full = canViewPii();
        if (!full)
        {
            // 先清检索条件:按 PII 检索本身就是 PII 能力,否则可枚举反推
            AdminPiiProtector.stripPii(umsMember);
        }
        startPage();
        umsMember.setDelFlag(0);
        List<UmsMember> list = umsMemberService.selectUmsMemberList(umsMember);
        // 密码哈希对谁都不返回,故不受 full 影响;PII 才看权限
        list.forEach(m -> m.setPassword(null));
        if (!full)
        {
            list.forEach(AdminPiiProtector::maskInPlace);
        }
        return getDataTable(list);
    }

    /**
     * 导出用户列表
     */
    @PreAuthorize("@ss.hasPermi('business:member:export')")
    @Log(title = "用户", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsMember umsMember)
    {
        boolean full = canViewPii();
        if (!full)
        {
            AdminPiiProtector.stripPii(umsMember);
        }
        List<UmsMember> list = umsMemberService.selectUmsMemberList(umsMember);
        if (!full)
        {
            // 导出是真正的批量外泄口:phone/name 都带 @Excel,不脱敏就是一份全员通讯录
            list.forEach(AdminPiiProtector::maskInPlace);
        }
        ExcelUtil<UmsMember> util = new ExcelUtil<UmsMember>(UmsMember.class);
        util.exportExcel(response, list, "用户数据");
    }

    /**
     * 获取用户详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:member:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        UmsMember member = umsMemberService.selectUmsMemberById(id);
        if (member != null)
        {
            // 密码哈希不返回给前端(小程序侧 ApiUmsMemberController 早已这么做,后台这条一直漏着)。
            // ★ 必须 null 不能空串:见 AdminPiiProtector.maskInPlace(UmsMember) 的说明——
            //   编辑表单会把它整体写回,空串会把密码清掉。
            member.setPassword(null);
            if (!canViewPii())
            {
                AdminPiiProtector.maskInPlace(member);
            }
        }
        return success(member);
    }

    /**
     * 新增用户
     */
    @PreAuthorize("@ss.hasPermi('business:member:add')")
    @Log(title = "用户", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsMember umsMember)
    {
        umsMember.setBalance(null);
        return toAjax(umsMemberService.insertUmsMember(umsMember));
    }

    /**
     * 修改用户
     */
    @PreAuthorize("@ss.hasPermi('business:member:edit')")
    @Log(title = "用户", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsMember umsMember)
    {
        if (!canViewPii())
        {
            // 看到的是脱敏值,提交回来的也只能是脱敏值。置 null 让 update 的动态 <if> 跳过这几列,
            // 否则 "138****1234" 会被当真值写回,真实手机号当场没了。
            AdminPiiProtector.stripPii(umsMember);
        }
        // 2026-09-16 提现闸:余额只能由结算/退款/提现等带流水的资金路径增减。通用编辑写进来的余额
        // 没有流水、不经结算闸,当场就是可提现的钱 —— 后台表单本就不发这个字段,这里一律丢弃。
        umsMember.setBalance(null);
        return toAjax(umsMemberService.updateUmsMember(umsMember));
    }

    /**
     * 删除用户
     */
    @PreAuthorize("@ss.hasPermi('business:member:remove')")
    @Log(title = "用户", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        for (int i = 0; i < ids.length; i++) {
            UmsMember umsMember = new UmsMember();
            umsMember.setDelFlag(2);
            umsMember.setId(ids[i]);
            umsMemberService.updateUmsMember(umsMember);
        }
        return toAjax(1);
    }
}
