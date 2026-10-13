package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.dto.MerchantCrmMarketingConsentRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmConsentMerchantVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.service.IMerchantCrmMarketingConsentService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class MerchantCrmMarketingConsentServiceImpl implements IMerchantCrmMarketingConsentService {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{6,64}");

    @Autowired private MerchantCrmMarketingConsentMapper consentMapper;
    @Autowired private BusinessAuditEventMapper auditMapper;

    @Override
    public List<MerchantCrmConsentMerchantVO> list(Long memberId) {
        requireMember(memberId);
        return consentMapper.selectEligibleMerchants(memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantCrmMarketingConsent update(Long memberId, MerchantCrmMarketingConsentRequest request) {
        requireMember(memberId);
        Long merchantRowId = request == null ? null : request.getMerchantRowId();
        if (merchantRowId == null || merchantRowId <= 0) throw new ServiceException("商家无效", 400);
        String channel = channel(request.getChannel());
        if (request.getOptedIn() == null) throw new ServiceException("请选择同意或退订", 400);
        String requestId = requestId(request.getRequestId());
        String event = Boolean.TRUE.equals(request.getOptedIn())
                ? MerchantCrmMarketingConsent.OPT_IN : MerchantCrmMarketingConsent.OPT_OUT;

        MerchantCrmMarketingConsent replay = consentMapper.selectByRequestId(memberId, merchantRowId, requestId);
        if (replay != null) return exactReplay(replay, channel, event);
        MerchantCrmConsentMerchantVO merchant = consentMapper.selectEligibleMerchant(memberId, merchantRowId);
        if (merchant == null) throw new ServiceException("仅可管理有真实报名关系的商家触达授权", 403);
        Long requestedOwner = request.getMerchantOwnerMemberId();
        if (requestedOwner != null && !requestedOwner.equals(merchant.getMerchantOwnerMemberId())) {
            throw new ServiceException("商家作用域不一致", 403);
        }

        MerchantCrmMarketingConsent row = new MerchantCrmMarketingConsent();
        row.setMemberId(memberId);
        row.setMerchantOwnerMemberId(merchant.getMerchantOwnerMemberId());
        row.setMerchantRowId(merchantRowId);
        row.setChannel(channel);
        row.setEventType(event);
        row.setRequestId(requestId);
        try {
            if (consentMapper.insertConsent(row) != 1) throw new ServiceException("授权状态保存失败", 409);
        } catch (DuplicateKeyException e) {
            MerchantCrmMarketingConsent existing = consentMapper.selectByRequestId(
                    memberId, merchantRowId, requestId);
            return exactReplay(existing, channel, event);
        }
        appendAudit(row, merchant.getMerchantRowId());
        return row;
    }

    private MerchantCrmMarketingConsent exactReplay(MerchantCrmMarketingConsent row, String channel, String event) {
        if (row == null || !channel.equals(row.getChannel()) || !event.equals(row.getEventType())) {
            throw new ServiceException("请求标识已用于其他授权操作", 409);
        }
        return row;
    }

    private void appendAudit(MerchantCrmMarketingConsent row, Long merchantRowId) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("MERCHANT");
        audit.setDomainId(merchantRowId);
        audit.setOwnerMemberId(row.getMerchantOwnerMemberId());
        audit.setActorMemberId(row.getMemberId());
        audit.setActorRoleCode("MEMBER");
        audit.setAction("CRM_MARKETING_" + row.getEventType());
        audit.setTargetType("MARKETING_CONSENT");
        audit.setTargetId(row.getId());
        audit.setAfterJson("{\"channel\":\"" + row.getChannel() + "\",\"optedIn\":"
                + MerchantCrmMarketingConsent.OPT_IN.equals(row.getEventType()) + "}");
        audit.setRequestId(row.getRequestId());
        audit.setCreateTime(new Date());
        if (auditMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new ServiceException("授权审计保存失败", 409);
        }
    }

    private String channel(String value) {
        String out = value == null ? "" : value.trim().toUpperCase();
        if (!MerchantCrmMarketingConsent.IN_APP.equals(out)
                && !MerchantCrmMarketingConsent.COUPON.equals(out)) {
            throw new ServiceException("触达渠道无效", 400);
        }
        return out;
    }

    private String requestId(String value) {
        String out = value == null ? "" : value.trim();
        if (!REQUEST_ID.matcher(out).matches()) throw new ServiceException("请求标识无效", 400);
        return out;
    }

    private void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
    }
}
