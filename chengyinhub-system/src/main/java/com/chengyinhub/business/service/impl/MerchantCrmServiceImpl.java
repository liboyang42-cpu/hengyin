package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.domain.MerchantCustomerProfile;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantCrmProfileReconcileVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSummaryVO;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCustomerProfileMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IMerchantCrmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MerchantCrmServiceImpl implements IMerchantCrmService {
    @Autowired
    private MerchantCustomerProfileMapper merchantCustomerProfileMapper;

    @Autowired
    private MerchantCrmAppMapper merchantCrmAppMapper;

    @Autowired
    private MmsMerchantMapper mmsMerchantMapper;

    @Override
    public List<MerchantCustomerProfile> selectCustomerList(MerchantCustomerProfile query) {
        if (query == null || query.getMerchantId() == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        // 注:这里不需要清 query.memberPhone —— selectCustomerList 的 <where> 里只有
        // merchantId/memberId/memberNickname/customerStage 四个条件,没有 memberPhone 过滤,
        // 故不存在「按手机枚举反推」那条路(报名/订单侧有,是因为那边 mapper 真的按 phone 精确匹配)。
        List<MerchantCustomerProfile> rows = merchantCustomerProfileMapper.selectCustomerList(query);
        if (rows != null) {
            // 读时脱敏作兜底。新 CRM 增量画像在 MerchantCrmAppMapper 中已落库脱敏，
            // 存量由 migration_crm_phone_snapshot_mask.sql 清过；这层保留：对 11 位掩码值幂等
            // (138****1234→138****1234),且能兜住回填漏网或将来新写路径忘了脱敏的情况。
            rows.forEach(r -> r.setMemberPhone(PiiCryptoService.maskPhone(r.getMemberPhone())));
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantCrmProfileReconcileVO aggregateMerchantCustomers(Long ownerMemberId) {
        requireOwnerMemberId(ownerMemberId);
        Long merchantId = uniqueMerchantRowId(ownerMemberId);
        int upserted = merchantCrmAppMapper.upsertOwnedCustomerProfiles(merchantId, ownerMemberId);
        int stale = merchantCrmAppMapper.markStaleCustomerProfiles(merchantId, ownerMemberId);
        long sourceCount = merchantCrmAppMapper.countOwnedRegistrationCustomers(ownerMemberId);
        long activeCount = merchantCrmAppMapper.countActiveCustomerProfiles(merchantId);
        MerchantCrmProfileReconcileVO out = new MerchantCrmProfileReconcileVO();
        out.setUpsertedRows(upserted);
        out.setStaleRows(stale);
        out.setSourceCustomerCount(sourceCount);
        out.setActiveProfileCount(activeCount);
        out.setConsistent(sourceCount == activeCount);
        return out;
    }

    @Override
    public MerchantCrmSummaryVO getSummary(Long merchantId) {
        requireMerchantId(merchantId);
        MerchantCrmSummaryVO summary = merchantCustomerProfileMapper.selectSummaryByMerchantId(merchantId);
        if (summary == null) {
            summary = new MerchantCrmSummaryVO();
            summary.setMerchantId(merchantId);
        }
        return summary;
    }

    private void requireMerchantId(Long merchantId) {
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalArgumentException("merchantId is required");
        }
    }

    private void requireOwnerMemberId(Long ownerMemberId) {
        if (ownerMemberId == null || ownerMemberId <= 0) {
            throw new IllegalArgumentException(
                    "ownerMemberId is required; use mms_merchant.member_id, not mms_merchant.id");
        }
    }

    private Long uniqueMerchantRowId(Long ownerMemberId) {
        MmsMerchant query = new MmsMerchant();
        query.setMemberId(ownerMemberId);
        query.setStatus(1L);
        query.setAccountStatus(1L);
        query.setDelFlag(0);
        List<MmsMerchant> rows = mmsMerchantMapper.selectMmsMerchantList(query);
        if (rows == null || rows.size() != 1 || rows.get(0).getId() == null) {
            throw new IllegalArgumentException("ownerMemberId must resolve to exactly one active merchant row");
        }
        return rows.get(0).getId();
    }
}
