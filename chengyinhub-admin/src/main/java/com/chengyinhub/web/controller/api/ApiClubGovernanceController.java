package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.dto.ClubGovernanceAppealRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceBanRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceListRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceOwnerTransferRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceReportRequest;
import com.chengyinhub.business.domain.dto.ClubGovernanceUnbanRequest;
import com.chengyinhub.business.domain.vo.ClubGovernanceBanVO;
import com.chengyinhub.business.domain.vo.ClubGovernanceCaseVO;
import com.chengyinhub.business.domain.vo.ClubGovernanceOwnerTransferVO;
import com.chengyinhub.business.service.ClubGovernanceService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@RestController
@Tag(name = "俱乐部成员治理")
@RequestMapping("/api/club/governance")
public class ApiClubGovernanceController extends BaseController {
    @Autowired private ClubGovernanceService clubGovernanceService;

    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) ClubGovernanceListRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少俱乐部参数");
        try {
            List<ClubGovernanceBanVO> result = new ArrayList<>();
            for (ClubGovernanceBan row : clubGovernanceService.list(principal, request.getClubId())) {
                result.add(ClubGovernanceBanVO.from(row));
            }
            return success(result);
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/ban")
    public AjaxResult ban(@RequestBody(required = false) ClubGovernanceBanRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少封禁参数");
        try {
            ClubGovernanceBan row = clubGovernanceService.ban(principal, request.getClubId(),
                    request.getTargetMemberId(), request.getReason(), banExpiry(request),
                    request.getRequestId());
            return success(ClubGovernanceBanVO.from(row));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/unban")
    public AjaxResult unban(@RequestBody(required = false) ClubGovernanceUnbanRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少解封参数");
        try {
            clubGovernanceService.unban(principal, request.getClubId(), request.getBanId(),
                    request.getVersion(), request.getReason(), request.getRequestId());
            return success("已解除封禁");
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/owner/transfer")
    public AjaxResult transferOwner(@RequestBody(required = false) ClubGovernanceOwnerTransferRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少转让参数");
        try {
            return success(ClubGovernanceOwnerTransferVO.from(clubGovernanceService.transferOwner(
                    principal, request.getClubId(), request.getTargetMemberId(),
                    request.getReason(), request.getRequestId())));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/cases/report")
    public AjaxResult report(@RequestBody(required = false) ClubGovernanceReportRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少举报参数");
        try {
            return success(ClubGovernanceCaseVO.from(clubGovernanceService.submitReport(
                    principal, request.getClubId(), request.getTargetType(), request.getTargetId(),
                    request.getEvidence(), request.getReason(), request.getRequestId())));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/cases/appeal")
    public AjaxResult appeal(@RequestBody(required = false) ClubGovernanceAppealRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少申诉参数");
        try {
            return success(ClubGovernanceCaseVO.from(clubGovernanceService.submitAppeal(
                    principal, request.getClubId(), request.getReason(), request.getRequestId())));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/cases/mine")
    public AjaxResult myCases(@RequestBody(required = false) ClubGovernanceListRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少俱乐部参数");
        try {
            List<ClubGovernanceCaseVO> result = new ArrayList<>();
            for (ClubGovernanceCase row : clubGovernanceService.listMyCases(
                    principal, request.getClubId())) {
                result.add(ClubGovernanceCaseVO.from(row));
            }
            // CU-C-84:申诉页必须在**提交之前**就知道本人有没有可申诉的有效封禁。
            // 原来只有 /cases/appeal 在提交那一刻拦人,前端拿不到判据,只能让用户白填一遍。
            // data 仍是工单数组(已发布的小程序按数组解析,改成对象会让旧版申诉页整页报错);
            // 封禁判据放在与 data 并列的顶层 activeBan 字段,旧版忽略、新版读取。
            ClubGovernanceBan activeBan = clubGovernanceService.activeBan(principal, request.getClubId());
            AjaxResult ok = success(result);
            ok.put("activeBan", activeBan == null ? null : ClubGovernanceBanVO.from(activeBan));
            return ok;
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    /**
     * CU-C-55:优先认 epoch 毫秒(绝对时刻,与时区无关),没有才回落老端的本地墙上时间串。
     * 「7 天」的 7×24 小时由前端算成绝对时刻,服务端只负责校验区间(1~90 天)。
     */
    private Date banExpiry(ClubGovernanceBanRequest request) {
        Long epochMs = request.getExpiresAtEpochMs();
        return epochMs != null && epochMs > 0 ? new Date(epochMs) : request.getExpiresAt();
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
