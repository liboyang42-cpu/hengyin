package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 活动壳只读结算证据 seam。它与旧现金结算基数刻意分离，调用方不能把本服务结果传回旧分润链。
 */
@Service
public class TopicSettlementEvidenceService {
    private final CmsActivityMapper activityMapper;
    private final CmsRegistrationMapper registrationMapper;
    private final CoopGroupVerifyMapper groupVerifyMapper;
    private final CoopSettlementEvidenceMapper evidenceMapper;

    public TopicSettlementEvidenceService(CmsActivityMapper activityMapper,
            CmsRegistrationMapper registrationMapper, CoopGroupVerifyMapper groupVerifyMapper,
            CoopSettlementEvidenceMapper evidenceMapper) {
        this.activityMapper = activityMapper;
        this.registrationMapper = registrationMapper;
        this.groupVerifyMapper = groupVerifyMapper;
        this.evidenceMapper = evidenceMapper;
    }

    /** 主题工作台履约展示；只读取三项主题级证据，不触发逐商家或候选锁查询。 */
    @Transactional(readOnly = true)
    public Summary readTopic(Long topicId) {
        if (topicId == null) {
            throw new IllegalArgumentException("topicId 不能为空");
        }
        Summary result = new Summary();
        result.totalSales = registrationMapper
                .selectTotalSalesEvidenceByTopicIdIncludingActivityShell(topicId);
        result.verifiedSales = registrationMapper
                .selectVerifiedSalesEvidenceByTopicIdIncludingActivityShell(topicId);
        result.verifiedHeads = registrationMapper
                .selectVerifiedHeadsEvidenceByTopicIdIncludingActivityShell(topicId);
        return result;
    }

    @Transactional(readOnly = true)
    public Summary read(Long topicId, Long merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId 不能为空");
        }
        Summary result = readTopic(topicId);
        result.merchantVerifiedHeads = registrationMapper
                .selectVerifiedHeadsEvidenceByTopicAndMerchantIncludingActivityShell(topicId, merchantId);
        result.merchantVerifiedSales = registrationMapper
                .selectVerifiedSalesEvidenceByTopicAndMerchantIncludingActivityShell(topicId, merchantId);
        result.verifiedRegistrationIds = registrationMapper
                .selectVerifiedRegistrationIdsEvidenceByTopicIdIncludingActivityShell(topicId);
        result.groupBasis = groupVerifyMapper
                .selectSettlementEvidenceBasisByTopicAndMerchantIncludingActivityShell(topicId, merchantId);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<CoopSettlementCandidate> lockCandidates(CoopSettlementSnapshot snapshot, Date settlementCutoff) {
        if (snapshot == null || snapshot.getCommercialContextId() == null) {
            throw new IllegalArgumentException("snapshot.commercialContextId 不能为空");
        }
        List<Long> activityIds = activityMapper
                .selectActiveActivityIdsByTopicId(snapshot.getCommercialContextId());
        return evidenceMapper.selectTopicEvidenceCandidatesForUpdate(snapshot,
                activityIds == null ? Collections.<Long>emptyList() : activityIds, settlementCutoff);
    }

    public static class Summary {
        private BigDecimal totalSales;
        private BigDecimal verifiedSales;
        private int verifiedHeads;
        private int merchantVerifiedHeads;
        private BigDecimal merchantVerifiedSales;
        private List<Long> verifiedRegistrationIds;
        private CoopGroupSettlementBasis groupBasis;

        public BigDecimal getTotalSales() { return totalSales; }
        public BigDecimal getVerifiedSales() { return verifiedSales; }
        public int getVerifiedHeads() { return verifiedHeads; }
        public int getMerchantVerifiedHeads() { return merchantVerifiedHeads; }
        public BigDecimal getMerchantVerifiedSales() { return merchantVerifiedSales; }
        public List<Long> getVerifiedRegistrationIds() { return verifiedRegistrationIds; }
        public CoopGroupSettlementBasis getGroupBasis() { return groupBasis; }
    }
}
