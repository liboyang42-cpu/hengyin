package com.chengyinhub.web.controller.api;


import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.exception.FundActionPreflightException;
import com.chengyinhub.business.service.IBankWithdrawalCommandService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提现
 *
 * @author webxh
 * @date 2025-10-21
 */
@RestController
@Tag(name = "提现")
@RequestMapping("/api/withdrawal")
public class ApiWithdrawalController extends BaseController {

    @Autowired
    private IBankWithdrawalCommandService bankWithdrawalCommandService;
    @Autowired
    private IUmsMemberWithdrawalService umsMemberWithdrawalService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IRoleService roleService;

    @PostMapping("/list")
    @Operation(summary = "我的提现列表")
    public AjaxResult myPointsList() {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id == 0L) {
            return error("请登录");
        }

        UmsMemberWithdrawal umsMemberWithdrawal = new UmsMemberWithdrawal();
        umsMemberWithdrawal.setMemberId(member_id);
        umsMemberWithdrawal.setDelFlag(0L);

        List<UmsMemberWithdrawal> withdrawalList = umsMemberWithdrawalService.selectUmsMemberWithdrawalList(umsMemberWithdrawal);

        return success(withdrawalList);
    }

    @Operation(summary = "提现申请")
    @Log(title = "提现申请", businessType = BusinessType.INSERT)
    @PostMapping("/create")
    public AjaxResult request(@Validated @RequestBody WithdrawalDTO request) {

        // 获取当前登录用户ID
        Long member_id = 0L;
        String username = "";
        try {
            member_id = getAppUserId();
            username = getAppUserName();
        } catch (Exception e) {
            return error("请先登录");
        }
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        try {
            // P3 劝退位:后端格挡提现(此前 withdrawable 仅前端下发,后端不拦→属实钱漏洞)
            UmsMember m = umsMemberService.selectUmsMemberById(member_id);
            if (m != null && Boolean.FALSE.equals(roleService.getPermission(m).getWithdrawable())) {
                return error("当前身份不支持提现");
            }
            Long id = bankWithdrawalCommandService.submit(request, member_id, username);
            return AjaxResult.success("提现申请成功，请等待审核", id);

        } catch (FundActionPreflightException e) {
            return AjaxResult.error(409, e.getMessage()).put("businessCode", e.getBusinessCode());
        } catch (Exception e) {
            logger.error("提现申请失败 memberId={}", member_id, e);
            return AjaxResult.error("提现申请失败，请稍后重试");
        }
    }

    @Operation(summary = "提现删除")
    @PostMapping("/delete")
    @Parameters({
            @Parameter(name = "id", description = "提现id")
    })
    public AjaxResult withdrawalDelete(String id) {
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }

        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {

        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }

        // owner + status=0 + del_flag=0 必须在同一条 SQL 里 CAS，避免查后改 TOCTOU。
        int rows = umsMemberWithdrawalService.withdrawPendingByMember(cid, memberId);
        if (rows > 0) {
            return success("撤回成功");
        } else {
            return error("仅本人待审核的提现申请可撤回");
        }
    }


}
