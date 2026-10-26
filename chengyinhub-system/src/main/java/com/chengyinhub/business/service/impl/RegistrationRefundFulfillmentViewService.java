package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.ExploreBundleRefundHold;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.ExploreBundleRefundMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.util.OrderRefundCash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 原退款后台详情的只读履约投影；不把持有人/单位异常当作资金未发生。 */
@Service
public class RegistrationRefundFulfillmentViewService {
    @Autowired private RefundApplicationMapper refundApplicationMapper;
    @Autowired private CmsRegistrationMapper cmsRegistrationMapper;
    @Autowired private ExploreBundleRefundMapper exploreBundleRefundMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> view(Long refundId) {
        if (refundId == null || refundId <= 0) throw new IllegalArgumentException("原退款ID无效");
        RefundApplication refund = refundApplicationMapper.selectRefundApplicationById(refundId);
        if (refund == null || !Integer.valueOf(0).equals(refund.getDelFlag())) {
            throw new IllegalArgumentException("退款单不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundId", refundId.toString());
        result.put("refundNo", refund.getRefundNo());
        List<ExploreBundleRefundHold> holds = new ArrayList<>();
        result.put("holds", holds);
        result.put("scopeUnknown", false);
        if (!"registration".equals(refund.getSourceType())) return result;
        CmsRegistration source = cmsRegistrationMapper.selectCmsRegistrationById(refund.getSourceId());
        if (source == null) {
            result.put("scopeUnknown", true);
            return result;
        }
        List<CmsRegistration> rows;
        if (source.getParentOrderNo() == null || source.getParentOrderNo().isBlank()) rows = List.of(source);
        else {
            rows = cmsRegistrationMapper.selectByParentOrderNo(source.getParentOrderNo());
            OrderRefundCash.assertOrderScope(rows, source);
        }
        for (CmsRegistration row : rows) {
            ExploreBundleRefundHold hold = exploreBundleRefundMapper.selectByRegistration(row.getId());
            if (hold != null) {
                if (!row.getId().equals(hold.getRegistrationId())) throw new IllegalStateException("履约冻结投影范围不一致");
                holds.add(hold); // 保留实际归属的原ID，包括已转接的新ID；不伪装成当前查询意图。
            }
        }
        return result;
    }
}
