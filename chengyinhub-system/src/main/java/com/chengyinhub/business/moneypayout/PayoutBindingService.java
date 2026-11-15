package com.chengyinhub.business.moneypayout;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.moneypayout.mapper.PayoutBindingMapper;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.payout.EcommercePersonalReceiverService;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Payout receiver binding from the app (R6b, FLOW-13). The app cannot run {@code wx.login}, so:
 * <ol>
 *   <li>the signed-in app account asks for a short-lived binding code (shown as a QR code);</li>
 *   <li>the member scans it with WeChat, the mini-program binding page opens and sends the code with a fresh
 *       {@code wx.login} code; the server proves the openid and marks the code CLAIMED (nothing is bound yet);</li>
 *   <li>the app account confirms; only then is the openid tied to the app account.</li>
 * </ol>
 * A code is single use, stored only as its sha256, and expires after a few minutes. Every step is refused unless
 * the money switch area for its purpose is open (wallet: {@code withdrawal}; club receiver: {@code payout}).
 */
@Service
public class PayoutBindingService {
    public static final String WALLET = "WALLET";
    public static final String CLUB_RECEIVER = "CLUB_RECEIVER";
    static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{24}");
    static final int CODE_BYTES = 18;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PayoutBindingMapper bindings;
    private final UmsMemberMapper members;
    private final ClubMapper clubs;
    private final IWeChatMiniSessionService sessions;
    private final EcommercePersonalReceiverService receivers;
    private final PayoutMoneyGate gate;
    private final TransactionTemplate tx;
    private final int ttlSeconds;
    private final String qrLinkPrefix;

    public PayoutBindingService(PayoutBindingMapper bindings, UmsMemberMapper members, ClubMapper clubs,
                                IWeChatMiniSessionService sessions, EcommercePersonalReceiverService receivers,
                                PayoutMoneyGate gate, PlatformTransactionManager transactions,
                                @Value("${chengyin.payout-binding.ttl-seconds:300}") int ttlSeconds,
                                @Value("${chengyin.payout-binding.qr-link-prefix:}") String qrLinkPrefix) {
        this.bindings = bindings;
        this.members = members;
        this.clubs = clubs;
        this.sessions = sessions;
        this.receivers = receivers;
        this.gate = gate;
        this.tx = new TransactionTemplate(transactions);
        this.ttlSeconds = Math.max(60, Math.min(600, ttlSeconds));
        this.qrLinkPrefix = safePrefix(qrLinkPrefix);
    }

    /** Issue a new code; any earlier open code for the same account and purpose stops working. */
    public Map<String, Object> issue(Long memberId, String rawPurpose, Long rawClubId) {
        requireMember(memberId);
        String purpose = purpose(rawPurpose);
        requireOpen(purpose);
        long clubId = clubFor(purpose, rawClubId);
        if (CLUB_RECEIVER.equals(purpose)) requireOwner(memberId, clubId);
        String code = newCode();
        Date expiresAt = new Date(System.currentTimeMillis() + ttlSeconds * 1000L);
        tx.executeWithoutResult(status -> {
            bindings.cancelOpen(memberId, purpose);
            if (bindings.insert(hash(code), purpose, memberId, clubId, expiresAt) != 1)
                throw new IllegalStateException("绑定码未保存");
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        out.put("code", code);
        out.put("expiresAt", expiresAt);
        out.put("ttlSeconds", ttlSeconds);
        out.put("qrContent", qrLinkPrefix == null ? null : qrLinkPrefix + "?bc=" + code);
        out.put("status", "ISSUED");
        return out;
    }

    /** The latest code's state for the app to poll, and whether a wallet identity is already bound. No openid leaves. */
    public Map<String, Object> status(Long memberId, String rawPurpose, Long rawClubId) {
        requireMember(memberId);
        String purpose = purpose(rawPurpose);
        long clubId = clubFor(purpose, rawClubId);
        Map<String, Object> latest = bindings.selectLatest(memberId, purpose, clubId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        out.put("open", gate.isOpen(areaFor(purpose)));
        out.put("status", latest == null ? "NONE" : effectiveStatus(latest));
        out.put("expiresAt", latest == null ? null : latest.get("expiresAt"));
        if (WALLET.equals(purpose)) out.put("walletBound", walletBound(memberId));
        return out;
    }

    /** Mini-program side: prove the openid with a fresh wx.login code. Nothing is bound until the app confirms. */
    public Map<String, Object> claim(String code, String jsCode) {
        if (code == null || !CODE.matcher(code).matches()) throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "绑定码无效");
        if (jsCode == null || jsCode.isBlank() || jsCode.length() > 128)
            throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "微信登录失败，请重试");
        String hash = hash(code);
        Map<String, Object> peek = bindings.selectByHash(hash);
        if (peek == null || !"ISSUED".equals(effectiveStatus(peek)))
            throw new BusinessRefusal(ErrorCode.GONE, "绑定码已失效，请在 App 里重新生成");
        String purpose = String.valueOf(peek.get("purpose"));
        requireOpen(purpose);
        IWeChatMiniSessionService.WeChatMiniSession session;
        try {
            session = sessions.exchange(jsCode);
        } catch (RuntimeException e) {
            throw new BusinessRefusal(ErrorCode.REFUSED, "微信登录失败，请重试");
        }
        if (session == null || session.getOpenId() == null || session.getOpenId().isBlank())
            throw new BusinessRefusal(ErrorCode.REFUSED, "微信登录失败，请重试");
        String openid = session.getOpenId();
        tx.executeWithoutResult(status -> {
            Map<String, Object> row = bindings.selectByHashForUpdate(hash);
            if (row == null || bindings.claim(toLong(row.get("id")), openid) != 1)
                throw new BusinessRefusal(ErrorCode.GONE, "绑定码已失效，请在 App 里重新生成");
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        out.put("status", "CLAIMED");
        return out;
    }

    /** App side: tie the claimed openid to this app account. */
    public Map<String, Object> confirm(Long memberId, String rawPurpose, Long rawClubId) {
        requireMember(memberId);
        String purpose = purpose(rawPurpose);
        requireOpen(purpose);
        long clubId = clubFor(purpose, rawClubId);
        Map<String, Object> latest = bindings.selectLatest(memberId, purpose, clubId);
        if (latest == null || !"CLAIMED".equals(effectiveStatus(latest)))
            throw new BusinessRefusal(ErrorCode.CONFLICT, "还没有在微信里扫码，或绑定码已失效");
        Long codeId = toLong(latest.get("id"));
        String openid = String.valueOf(latest.get("claimedOpenid"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        if (CLUB_RECEIVER.equals(purpose)) {
            try {
                out.put("receiver", receivers.bindFromAppCode(memberId, clubId, openid));
            } catch (ServiceException e) {
                throw new BusinessRefusal(ErrorCode.REFUSED, receiverMessage(e.getMessage()));
            }
            bindings.confirm(codeId, memberId);
        } else {
            tx.executeWithoutResult(status -> {
                if (!memberId.equals(members.lockActiveMemberById(memberId)))
                    throw new BusinessRefusal(ErrorCode.ACCOUNT_DISABLED);
                UmsMember member = members.selectUmsMemberById(memberId);
                String own = member == null ? null : member.getWxOpenId();
                if (own != null && !own.isBlank() && !own.equals(openid))
                    throw new BusinessRefusal(ErrorCode.REFUSED, "扫码的微信与本账号已关联的微信不一致");
                if (bindings.confirm(codeId, memberId) != 1)
                    throw new BusinessRefusal(ErrorCode.CONFLICT, "绑定码已失效，请重新生成");
                bindings.upsertWallet(memberId, sessions.miniAppId(), openid);
            });
            out.put("walletBound", true);
        }
        out.put("status", "CONFIRMED");
        return out;
    }

    boolean walletBound(Long memberId) {
        Map<String, Object> row = bindings.selectWallet(memberId);
        if (row != null) return true;
        UmsMember member = members.selectUmsMemberById(memberId);
        return member != null && member.getWxOpenId() != null && !member.getWxOpenId().isBlank();
    }

    static String effectiveStatus(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        Object expires = row.get("expiresAt");
        boolean expired = expires instanceof Date d && !d.after(new Date());
        if (expired && ("ISSUED".equals(status) || "CLAIMED".equals(status))) return "EXPIRED";
        return status;
    }

    private void requireOpen(String purpose) {
        if (!gate.isOpen(areaFor(purpose)))
            throw new BusinessRefusal(ErrorCode.FEATURE_OFF, WALLET.equals(purpose) ? "提现暂未开放" : "个人收款暂未开放");
    }

    static MoneyArea areaFor(String purpose) {
        return WALLET.equals(purpose) ? MoneyArea.WITHDRAWAL : MoneyArea.PAYOUT;
    }

    private void requireOwner(Long memberId, long clubId) {
        Club club = clubs.selectClubById(clubId);
        if (club == null) throw new BusinessRefusal(ErrorCode.CLUB_NOT_FOUND);
        if (!memberId.equals(club.getMemberId())) throw new BusinessRefusal(ErrorCode.FORBIDDEN, "只有俱乐部主可以绑定个人收款");
    }

    static String purpose(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        if (WALLET.equals(value) || CLUB_RECEIVER.equals(value)) return value;
        throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "未知的绑定用途");
    }

    static long clubFor(String purpose, Long clubId) {
        if (WALLET.equals(purpose)) return 0L;
        if (clubId == null || clubId <= 0) throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "缺少俱乐部");
        return clubId;
    }

    static String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Only an https prefix without query or fragment is used; anything else means "no QR link configured". */
    static String safePrefix(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (!value.startsWith("https://") || value.contains("?") || value.contains("#") || value.contains(" ")) return null;
        return value;
    }

    private static String receiverMessage(String code) {
        if ("PERSONAL_RECEIVER_IDENTITY_MISMATCH".equals(code) || "PERSONAL_RECEIVER_IDENTITY_IMMUTABLE".equals(code))
            return "扫码的微信与已绑定的收款微信不一致";
        if ("CLUB_OWNER_REQUIRED".equals(code)) return "只有俱乐部主可以绑定个人收款";
        if ("PERSONAL_RECEIVER_PROVIDER_NOT_CONFIRMED".equals(code)) return "微信暂未确认绑定，请稍后再试";
        return "绑定没有完成，请重新生成绑定码";
    }

    private static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new BusinessRefusal(ErrorCode.SIGN_IN_REQUIRED);
    }

    private static Long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value));
    }
}
