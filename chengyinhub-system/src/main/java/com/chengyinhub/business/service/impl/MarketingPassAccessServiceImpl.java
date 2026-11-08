package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.IMarketingPassAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通行证覆盖只读投影：直接读 {@code cms_registration} 权威事实。
 *
 * <p>不写任何表、不发权益、不做准入放行；仅用于报价侧说明"已有通行证覆盖"。
 */
@Service
public class MarketingPassAccessServiceImpl implements IMarketingPassAccessService {

    private final CmsRegistrationMapper registrationMapper;

    public MarketingPassAccessServiceImpl(CmsRegistrationMapper registrationMapper) {
        this.registrationMapper = registrationMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveAccess(long memberId, long topicId) {
        if (memberId <= 0 || topicId <= 0) return false;
        List<CmsRegistration> rows = registrationMapper.selectCmsRegistrationList(probe(memberId, topicId));
        return hasLivePass(rows, new Date());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> activeTopicIds(long memberId) {
        if (memberId <= 0) return new ArrayList<>();
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L);
        probe.setPaymentStatus(2);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> rows = registrationMapper.selectCmsRegistrationList(probe);
        Set<Long> topics = new LinkedHashSet<>();
        Date now = new Date();
        if (rows == null) return new ArrayList<>();
        for (CmsRegistration row : rows) {
            if (row == null || row.getOwnerId() == null) continue;
            if (row.getExpiresAt() != null && !row.getExpiresAt().after(now)) continue;
            topics.add(row.getOwnerId());
        }
        return new ArrayList<>(topics);
    }

    private boolean hasLivePass(List<CmsRegistration> rows, Date now) {
        if (rows == null) return false;
        for (CmsRegistration row : rows) {
            if (row == null) continue;
            if (!Integer.valueOf(2).equals(row.getPaymentStatus())) continue;
            if (!Integer.valueOf(2).equals(row.getRegistrationStatus())) continue;
            if (row.getExpiresAt() != null && !row.getExpiresAt().after(now)) continue;
            return true;
        }
        return false;
    }

    private CmsRegistration probe(long memberId, long topicId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        probe.setPaymentStatus(2);
        probe.setRegistrationStatus(2);
        return probe;
    }
}
