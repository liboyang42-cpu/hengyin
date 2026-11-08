package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.dto.CreatorProfileApplyDTO;
import com.chengyinhub.business.domain.dto.CreatorReviewDTO;
import com.chengyinhub.business.domain.vo.CreatorCenterVO;
import com.chengyinhub.business.domain.vo.CreatorRevenueLedgerVO;
import com.chengyinhub.business.mapper.CreatorIncomeDetailMapper;
import com.chengyinhub.business.mapper.CreatorMetricDailyMapper;
import com.chengyinhub.business.mapper.CreatorProfileMapper;
import com.chengyinhub.business.service.ICreatorCenterService;
import com.chengyinhub.business.service.ICreatorRevenueReadService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * P3 创作者中心服务实现。
 */
@Service
public class CreatorCenterServiceImpl implements ICreatorCenterService {

    @Autowired
    private CreatorProfileMapper creatorProfileMapper;

    @Autowired
    private CreatorMetricDailyMapper creatorMetricDailyMapper;

    @Autowired
    private CreatorIncomeDetailMapper creatorIncomeDetailMapper;

    /** 原账本收益只读件；缺失或读取失败都不阻断中心页，而是显式标 UNKNOWN。 */
    @Autowired(required = false)
    private ICreatorRevenueReadService creatorRevenueReadService;

    @Override
    public CreatorCenterVO getCenter(Long memberId) {
        CreatorCenterVO vo = new CreatorCenterVO();
        if (memberId == null) {
            vo.setApplyStatus("not_applied");
            return vo;
        }
        CreatorProfile profile = creatorProfileMapper.selectByMemberId(memberId);
        if (profile == null) {
            vo.setApplyStatus("not_applied");
            return vo;
        }
        vo.setProfile(profile);
        vo.setApplyStatus(statusText(profile.getApplyStatus()));
        vo.setMetric(creatorMetricDailyMapper.selectLatestMetric(profile.getId()));
        List income = creatorIncomeDetailMapper.selectRecentIncome(profile.getId());
        vo.setRecentIncome(income == null ? Collections.emptyList() : income);
        applyRevenue(vo, memberId);
        return vo;
    }

    /**
     * 原账本收益：成功则 AVAILABLE 并给出应计金额；失败则 UNKNOWN 且 revenue 为 null。
     *
     * <p>未知不能展示成 0 收入，也不能让整个创作者中心报错——其它字段（档案/指标/最近明细）仍然可用。
     * 返回值语义：{@code ACCRUAL_ORIGINAL_LEDGER} 是应计口径，不是实收、不是可提现。
     */
    private void applyRevenue(CreatorCenterVO vo, Long memberId) {
        if (creatorRevenueReadService == null) {
            vo.setRevenueState(CreatorRevenueLedgerVO.State.UNKNOWN);
            vo.setRevenueUnavailableReason("原账本收益读取件未装配");
            return;
        }
        try {
            CreatorRevenueLedgerVO ledger = creatorRevenueReadService.readForMember(memberId, null, null);
            if (ledger == null || !ledger.isAvailable()) {
                vo.setRevenueState(CreatorRevenueLedgerVO.State.UNKNOWN);
                vo.setRevenueUnavailableReason(ledger == null
                        ? "原账本收益读取返回空结果" : ledger.getUnavailableReason());
                return;
            }
            vo.setRevenue(ledger);
            vo.setRevenueState(CreatorRevenueLedgerVO.State.AVAILABLE);
        } catch (RuntimeException unavailable) {
            // 读失败/数据源异常一律降级为显式 UNKNOWN，不冒 500，也不写 0。
            vo.setRevenueState(CreatorRevenueLedgerVO.State.UNKNOWN);
            vo.setRevenueUnavailableReason("原账本收益读取失败：" + unavailable.getClass().getSimpleName());
        }
    }

    @Override
    public int apply(Long memberId, CreatorProfileApplyDTO request) {
        if (memberId == null || request == null || StringUtils.isBlank(request.getCreatorName())) {
            return 0;
        }
        CreatorProfile existing = creatorProfileMapper.selectByMemberId(memberId);
        if (existing != null) {
            // 待审/已通过不重复受理;被驳回(2)的可以改资料重新提交,回到待审
            if (!Integer.valueOf(2).equals(existing.getApplyStatus())) {
                return 0;
            }
            CreatorProfile resubmit = new CreatorProfile();
            resubmit.setId(existing.getId());
            resubmit.setMemberId(memberId);
            resubmit.setCreatorName(request.getCreatorName());
            resubmit.setBio(request.getBio());
            resubmit.setAvatarUrl(request.getAvatarUrl());
            resubmit.setUpdateTime(DateUtils.getNowDate());
            return creatorProfileMapper.resubmitRejectedCreatorProfile(resubmit);
        }
        CreatorProfile profile = new CreatorProfile();
        profile.setMemberId(memberId);
        profile.setCreatorName(request.getCreatorName());
        profile.setBio(request.getBio());
        profile.setAvatarUrl(request.getAvatarUrl());
        profile.setApplyStatus(0);
        profile.setCreateTime(DateUtils.getNowDate());
        profile.setUpdateTime(DateUtils.getNowDate());
        return creatorProfileMapper.insertCreatorProfile(profile);
    }

    @Override
    public int aggregateDaily(String statDate) {
        return 0;
    }

    @Override
    public List<CreatorProfile> selectAdminCreatorList(CreatorProfile query) {
        return creatorProfileMapper.selectAdminCreatorList(query);
    }

    @Override
    public int review(CreatorReviewDTO request, Long reviewerId) {
        if (request == null || request.getId() == null || request.getApplyStatus() == null) {
            throw new ServiceException("审核参数不完整", HttpStatus.BAD_REQUEST);
        }
        if (request.getApplyStatus() != 1 && request.getApplyStatus() != 2) {
            throw new ServiceException("审核状态仅支持通过或驳回", HttpStatus.BAD_REQUEST);
        }
        if (reviewerId == null) {
            throw new ServiceException("未识别到当前审核人", HttpStatus.UNAUTHORIZED);
        }
        String reviewReason = StringUtils.isBlank(request.getReviewReason())
                ? null : request.getReviewReason().trim();
        if (request.getApplyStatus() == 2 && reviewReason == null) {
            throw new ServiceException("请填写驳回原因", HttpStatus.BAD_REQUEST);
        }
        if (reviewReason != null && reviewReason.length() > 500) {
            throw new ServiceException("审核原因不能超过500个字符", HttpStatus.BAD_REQUEST);
        }

        CreatorProfile profile = new CreatorProfile();
        profile.setId(request.getId());
        profile.setApplyStatus(request.getApplyStatus());
        profile.setReviewerId(reviewerId);
        profile.setReviewReason(reviewReason);
        profile.setReviewedAt(DateUtils.getNowDate());
        profile.setUpdateTime(DateUtils.getNowDate());
        int rows = creatorProfileMapper.updatePendingCreatorProfile(profile);
        if (rows != 1) {
            throw new ServiceException("申请已处理或状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        return rows;
    }

    private String statusText(Integer status) {
        if (status == null || status == 0) return "pending";
        if (status == 1) return "approved";
        if (status == 2) return "rejected";
        return "pending";
    }
}
