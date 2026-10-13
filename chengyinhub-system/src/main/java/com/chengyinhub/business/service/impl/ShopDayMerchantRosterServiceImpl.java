package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.dto.ShopDayMerchantRosterFact;
import com.chengyinhub.business.mapper.ShopDayMerchantRosterMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IShopDayMerchantRosterService;
import com.chengyinhub.common.config.ComplianceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** 探店日商家现场读面：本店事实先在 SQL 收窄，再逐行实时读取单店同意。 */
@Service
public class ShopDayMerchantRosterServiceImpl implements IShopDayMerchantRosterService {
    private static final Logger log = LoggerFactory.getLogger(ShopDayMerchantRosterServiceImpl.class);
    private static final String DOC = "merchant_onsite_data_sharing";
    private static final String SCENE = "merchant_redeem";

    @Autowired
    private ShopDayMerchantRosterMapper rosterMapper;
    @Autowired
    private IConsentRecordService consentRecordService;
    @Autowired
    private ComplianceProperties complianceProperties;

    @Override
    public Map<String, Object> list(Long merchantMemberId, Long topicId) {
        return list(merchantMemberId, topicId, false);
    }

    @Override
    public Map<String, Object> list(Long merchantMemberId, Long topicId,
                                    boolean sensitiveContactVisible) {
        if (merchantMemberId == null || merchantMemberId <= 0L || topicId == null || topicId <= 0L
                || rosterMapper.countLiveMerchantScope(topicId, merchantMemberId) <= 0) {
            throw new RuntimeException("无权查看该探店日门店名单");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ShopDayMerchantRosterFact> facts = rosterMapper.selectRoster(topicId, merchantMemberId);
        if (facts != null) {
            for (ShopDayMerchantRosterFact fact : facts) {
                // 同意决定“这家店能否看到该玩家整行”，权限只决定能否看到手机号。
                // 不能因角色没有敏感字段权限就跳过同意真源，否则 REVOKE 会退化成仍展示名单。
                ConsentState state = consentState(fact.getMemberId(), merchantMemberId);
                if (state == ConsentState.REVOKED || state == ConsentState.UNAVAILABLE) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("registrationId", fact.getRegistrationId());
                row.put("ticketNo", fact.getTicketNo());
                row.put("sessionLabel", sessionLabel(fact));
                row.put("name", displayName(fact));
                row.put("avatar", fact.getAvatar());
                row.put("entitlementStatus", fact.getEntitlementStatus());
                row.put("checkinAt", fact.getCheckinAt());
                row.put("serviceStartedAt", fact.getServiceStartedAt());
                row.put("phone", sensitiveContactVisible && state == ConsentState.AGREED
                        ? fact.getPhone() : null);
                row.put("consentState", sensitiveContactVisible ? state.name() : "HIDDEN_BY_PERMISSION");
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewType", "SHOP_DAY_MERCHANT_ROSTER");
        out.put("rows", rows);
        return out;
    }

    private ConsentState consentState(Long playerMemberId, Long merchantMemberId) {
        String currentVersion = complianceProperties == null ? null : complianceProperties.versionOf(DOC);
        if (currentVersion == null || currentVersion.trim().isEmpty() || consentRecordService == null) {
            log.error("探店日单店同意校验未就绪，拒绝下发手机号 player={} merchant={}", playerMemberId, merchantMemberId);
            return ConsentState.UNAVAILABLE;
        }
        try {
            ConsentRecord latest = consentRecordService.latest(playerMemberId, DOC, SCENE, "MERCHANT", merchantMemberId);
            if (latest == null) return ConsentState.NONE;
            if ("REVOKE".equals(latest.getEventType())) return ConsentState.REVOKED;
            if (currentVersion.equals(latest.getDocVersion()) && "AGREE".equals(latest.getEventType())) {
                return ConsentState.AGREED;
            }
            return ConsentState.STALE;
        } catch (RuntimeException e) {
            log.error("探店日单店同意读取失败，拒绝下发手机号 player={} merchant={}", playerMemberId, merchantMemberId, e);
            return ConsentState.UNAVAILABLE;
        }
    }

    private static String displayName(ShopDayMerchantRosterFact fact) {
        String realName = fact.getRealName();
        if (realName != null && !realName.trim().isEmpty()) return realName.trim();
        String nickname = fact.getNickname();
        return nickname == null ? "" : nickname.trim();
    }

    private static String sessionLabel(ShopDayMerchantRosterFact fact) {
        if (fact.getTicketStart() == null && fact.getTicketEnd() == null) return "";
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String start = fact.getTicketStart() == null ? "" : formatter.format(fact.getTicketStart());
        String end = fact.getTicketEnd() == null ? "" : formatter.format(fact.getTicketEnd());
        return start + (end.isEmpty() ? "" : "–" + end);
    }

    private enum ConsentState { NONE, AGREED, REVOKED, STALE, UNAVAILABLE }
}
