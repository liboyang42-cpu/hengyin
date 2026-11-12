package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.EcomPersonalReceiver;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Personal profit-share receiver consent is not checkout seller submerchant onboarding. */
@Service
public class EcommercePersonalReceiverService {
    private final EcomPayoutMapper receivers;
    private final ClubMapper clubs;
    private final UmsMemberMapper members;
    private final IWeChatMiniSessionService sessions;
    private final EcommercePayGateway gateway;
    private final PayoutSwitches switches;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    public EcommercePersonalReceiverService(EcomPayoutMapper receivers, ClubMapper clubs,
            UmsMemberMapper members, IWeChatMiniSessionService sessions, EcommercePayGateway gateway,
            PayoutSwitches switches) {
        this.receivers = receivers;
        this.clubs = clubs;
        this.members = members;
        this.sessions = sessions;
        this.gateway = gateway;
        this.switches = switches;
    }

    /** Provider calls run without database locks; commit rechecks the current owner and canonical identity. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public Map<String, Object> bind(Long memberId, Long clubId, String jsCode, Boolean consent) {
        requireMember(memberId);
        if (!Boolean.TRUE.equals(consent)) throw new ServiceException("PERSONAL_RECEIVER_CONSENT_REQUIRED");
        if (jsCode == null || jsCode.isBlank()) throw new ServiceException("WECHAT_FRESH_CODE_REQUIRED");
        String appid = switches.spAppid();
        if (appid == null || appid.isBlank() || !appid.equals(sessions.miniAppId()))
            throw new ServiceException("PERSONAL_RECEIVER_APPID_MISMATCH");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("PERSONAL_RECEIVER_EXTERNAL_CALL_REQUIRES_NO_TRANSACTION");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        BindingScope before = transaction.execute(status -> bindingScope(memberId, clubId, appid));
        IWeChatMiniSessionService.WeChatMiniSession session;
        try {
            session = sessions.exchange(jsCode);
        } catch (RuntimeException e) {
            throw new ServiceException("WECHAT_FRESH_CODE_EXCHANGE_FAILED");
        }
        if (session == null || !before.openid().equals(session.getOpenId()))
            throw new ServiceException("PERSONAL_RECEIVER_IDENTITY_MISMATCH");
        boolean live = switches.personalClubEnabled();
        EcomPersonalReceiver earlier = before.receiver();
        if (earlier != null && !(live && "CONSENTED".equals(earlier.getState())))
            return verifiedView(earlier, session.getOpenId());
        Date consentedAt = new Date();
        if (live) {
            try {
                gateway.addPersonalReceiver(session.getOpenId(), appid);
            } catch (RuntimeException e) {
                throw new ServiceException("PERSONAL_RECEIVER_PROVIDER_NOT_CONFIRMED");
            }
        }
        return transaction.execute(status -> {
            BindingScope current = bindingScope(memberId, clubId, appid);
            if (!session.getOpenId().equals(current.openid()))
                throw new ServiceException("PERSONAL_RECEIVER_IDENTITY_MISMATCH");
            EcomPersonalReceiver existing = current.receiver();
            if (existing != null && !(live && "CONSENTED".equals(existing.getState())))
                return verifiedView(existing, session.getOpenId());
            EcomPersonalReceiver receiver = new EcomPersonalReceiver();
            receiver.setMemberId(memberId); receiver.setAppid(appid); receiver.setOpenid(session.getOpenId());
            receiver.setConsentedAt(consentedAt); receiver.setVerifiedAt(new Date());
            if (existing != null) {
                if (!session.getOpenId().equals(existing.getOpenid()))
                    throw new ServiceException("PERSONAL_RECEIVER_IDENTITY_IMMUTABLE");
                receiver.setConsentedAt(existing.getConsentedAt());
                receiver.setState("VERIFIED");
                if (receivers.promoteConsentedToVerified(receiver) != 1) throw new ServiceException("PERSONAL_RECEIVER_NOT_SAVED");
            } else if (live) {
                receiver.setState("VERIFIED");
                if (receivers.insertVerified(receiver) != 1) throw new ServiceException("PERSONAL_RECEIVER_NOT_SAVED");
            } else {
                receiver.setState("CONSENTED");
                receiver.setVerifiedAt(receiver.getConsentedAt());
                if (receivers.insertConsented(receiver) != 1) throw new ServiceException("PERSONAL_RECEIVER_NOT_SAVED");
            }
            return view(receiver, live, 0);
        });
    }

    /** 解除个人收款绑定：只认登录人且须为俱乐部主；有处理中的个人分账时拒绝。 */
    @Transactional
    public Map<String, Object> unbind(Long memberId, Long clubId) {
        requireMember(memberId);
        String appid = switches.spAppid();
        if (appid == null || appid.isBlank()) throw new ServiceException("PERSONAL_RECEIVER_APPID_MISMATCH");
        if (!memberId.equals(members.lockActiveMemberById(memberId))) throw new ServiceException("MEMBER_UNAVAILABLE");
        requireOwner(memberId, clubId, true);
        EcomPersonalReceiver current = receivers.selectByMemberAndAppidForUpdate(memberId, appid);
        if (current == null) return view(null, switches.personalClubEnabled(), 0);
        if (receivers.deleteReceiverIfIdle(memberId, appid) != 1)
            throw new ServiceException(IN_FLIGHT_MESSAGE);
        return view(null, switches.personalClubEnabled(), 0);
    }

    static final String IN_FLIGHT_MESSAGE = "还有收款正在处理，处理完成后才能解除绑定";

    private record BindingScope(String openid, EcomPersonalReceiver receiver) { }

    private BindingScope bindingScope(Long memberId, Long clubId, String appid) {
        if (!memberId.equals(members.lockActiveMemberById(memberId))) throw new ServiceException("MEMBER_UNAVAILABLE");
        requireOwner(memberId, clubId, true);
        UmsMember member = members.selectUmsMemberById(memberId);
        if (member == null || member.getWxOpenId() == null || member.getWxOpenId().isBlank())
            throw new ServiceException("WECHAT_MINI_IDENTITY_REQUIRED");
        return new BindingScope(member.getWxOpenId(), receivers.selectByMemberAndAppidForUpdate(memberId, appid));
    }

    private Map<String, Object> verifiedView(EcomPersonalReceiver receiver, String openid) {
        if (!openid.equals(receiver.getOpenid())) throw new ServiceException("PERSONAL_RECEIVER_IDENTITY_IMMUTABLE");
        if (!"VERIFIED".equals(receiver.getState()) && !"CONSENTED".equals(receiver.getState()))
            throw new ServiceException("PERSONAL_RECEIVER_NOT_VERIFIED");
        return view(receiver, switches.personalClubEnabled(), 0);
    }

    public Map<String, Object> ownerStatus(Long memberId, Long clubId) {
        requireMember(memberId);
        requireOwner(memberId, clubId, false);
        String appid = switches.spAppid();
        if (appid == null || appid.isBlank()) return view(null, switches.personalClubEnabled(), 0);
        return view(receivers.selectByMemberAndAppid(memberId, appid), switches.personalClubEnabled(),
                receivers.countPersonalPayoutsInFlight(memberId));
    }

    private void requireOwner(Long memberId, Long clubId, boolean lock) {
        if (clubId == null || clubId <= 0) throw new ServiceException("CLUB_REQUIRED");
        Club club = lock ? clubs.selectClubByIdForUpdate(clubId) : clubs.selectClubById(clubId);
        if (club == null || !memberId.equals(club.getMemberId())) throw new ServiceException("CLUB_OWNER_REQUIRED");
    }

    private static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录");
    }

    /** payoutEnabled=平台开关；paused=开关已开但绑定尚未经微信确认，该笔分账会暂停。 */
    private static Map<String, Object> view(EcomPersonalReceiver receiver, boolean payoutEnabled, int inFlight) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean verified = receiver != null && "VERIFIED".equals(receiver.getState());
        result.put("available", true);
        result.put("receiverType", "PERSONAL_OPENID");
        result.put("receiverState", receiver == null ? "UNBOUND" : receiver.getState());
        result.put("payoutEnabled", payoutEnabled);
        result.put("paused", payoutEnabled && !verified);
        result.put("pauseReason", payoutEnabled && !verified ? "PERSONAL_BINDING_REQUIRED" : null);
        result.put("canUnbind", receiver != null && inFlight == 0);
        if (receiver != null) {
            result.put("consentedAt", receiver.getConsentedAt());
            result.put("verifiedAt", receiver.getVerifiedAt());
        }
        return result;
    }
}
