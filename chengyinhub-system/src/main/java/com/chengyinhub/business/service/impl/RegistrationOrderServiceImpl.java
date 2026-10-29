package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationCreationTransaction;
import com.chengyinhub.business.service.IRegistrationOrderService;
import com.chengyinhub.business.service.IRegistrationOrderCreateGate;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.chengyinhub.common.utils.sign.Md5Utils;

import static com.chengyinhub.common.constant.CmsConstants.QUOTE_SIGN_SALT;

/**
 * 故意不加事务：具体建单由 ICmsRegistrationService 的事务完成；唯一键冲突抛出并回滚后，
 * 本层才能在一个干净事务外回读 winner，避免在 rollback-only 事务内查询。
 */
@Service
public class RegistrationOrderServiceImpl implements IRegistrationOrderService {

    @Autowired
    private ICmsRegistrationService registrationService;
    @Autowired
    private IRegistrationCreationTransaction registrationCreationTransaction;
    @Autowired
    private IRegistrationOrderCreateGate registrationOrderCreateGate;

    private RegistrationOrderResult findReplay(Long memberId, RegistrationRequest request,
                                               String requestId, String requestFingerprint) {
        if (memberId == null || StringUtils.isBlank(requestId)) {
            return null;
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setRequestId(requestId.trim());
        List<CmsRegistration> matches = registrationService.selectCmsRegistrationList(probe);
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("同一 requestId 命中多条报名，已拒绝不确定重放");
        }
        CmsRegistration primary = matches.get(0);
        List<CmsRegistration> order = loadOrder(primary);
        // B-R2:终态单(已取消3/已过期4)不是「已成功创建」,不得当重放回给调用方 ——
        // 否则撤销/过期后再拿同一 requestId 下单,会被当成下单成功返回一张死单。
        // 幂等键一次性:要重新报名必须换新 requestId(前端撤单成功后已置空旧键)。
        if (Integer.valueOf(3).equals(primary.getRegistrationStatus())
                || Integer.valueOf(4).equals(primary.getRegistrationStatus())) {
            throw new RegistrationRequestConflictException(
                    Integer.valueOf(3).equals(primary.getRegistrationStatus())
                            ? "该幂等键对应的报名已取消，请重新发起报名"
                            : "该幂等键对应的报名已过期，请重新发起报名");
        }
        if (primary.getRequestFingerprint() == null) {
            // Recovery cannot backfill an unretained original identity. Exact stored replays remain readable.
            RestoreDispatchGuard.requireDispatchAllowed();
            if (!isPersistedRequestEquivalent(memberId, request, order)
                    || !registrationService.bindLegacyRequestFingerprint(
                    primary.getId(), requestId, requestFingerprint)) {
                throw new RegistrationRequestConflictException("该幂等键的历史报名内容无法证明等价，请重新提交");
            }
            primary.setRequestFingerprint(requestFingerprint);
        } else if (!requestFingerprint.equals(primary.getRequestFingerprint())) {
            throw new RegistrationRequestConflictException("该幂等键已用于不同的报名内容，请重新提交");
        }
        return new RegistrationOrderResult(order, true);
    }

    @Override
    public RegistrationOrderResult createOrReplay(Long memberId, RegistrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("报名参数不能为空");
        }
        String requestId = normalizeRequestId(request.getRequestId());
        if (requestId == null) {
            throw new IllegalArgumentException("报名幂等键不能为空");
        }
        String requestFingerprint = fingerprint(memberId, request);
        RegistrationOrderResult replay = findReplay(memberId, request, requestId, requestFingerprint);
        if (replay != null) {
            return replay;
        }
        RestoreDispatchGuard.requireDispatchAllowed();
        registrationOrderCreateGate.validate(memberId, request);
        replay = findReplay(memberId, request, requestId, requestFingerprint);
        if (replay != null) {
            return replay;
        }
        try {
            List<CmsRegistration> created = registrationCreationTransaction.create(
                    memberId, request, requestId, requestFingerprint);
            return new RegistrationOrderResult(created, false);
        } catch (DuplicateKeyException duplicate) {
            RegistrationOrderResult winner = findReplay(memberId, request, requestId, requestFingerprint);
            if (winner != null) {
                return winner;
            }
            throw duplicate;
        }
    }

    private String fingerprint(Long memberId, RegistrationRequest request) {
        List<String> tickets = new ArrayList<>();
        if (request.getTickets() == null || request.getTickets().isEmpty()) {
            tickets.add(ticketPart(request.getTicketId(), Integer.valueOf(1), null));
        } else {
            request.getTickets().forEach(ticket -> tickets.add(ticketPart(
                    ticket == null ? null : ticket.getTicketId(),
                    ticket == null || ticket.getCount() == null || ticket.getCount() <= 0
                            ? Integer.valueOf(1) : ticket.getCount(),
                    ticket == null ? null : normalize(ticket.getParticipateDate()))));
            Collections.sort(tickets);
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, memberId);
        append(canonical, request.getOwnerType());
        append(canonical, request.getOwnerId());
        append(canonical, Integer.valueOf(tickets.size()));
        for (String ticket : tickets) {
            append(canonical, ticket);
        }
        append(canonical, Integer.valueOf(Integer.valueOf(1).equals(request.getIsUsePoint()) ? 1 : 0));
        append(canonical, normalize(request.getQuoteSign()));
        append(canonical, normalize(request.getRealName()));
        append(canonical, normalize(request.getPhone()));
        append(canonical, normalize(request.getEmail()));
        append(canonical, normalize(request.getAddress()));
        append(canonical, request.getAddressId());
        append(canonical, request.getSourceClubId());
        append(canonical, normalize(request.getSourceChannelCode()));
        append(canonical, request.getWaitlistOfferId());
        append(canonical, normalize(request.getWaitlistOfferToken()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String ticketPart(Long ticketId, Integer count, String participateDate) {
        StringBuilder ticket = new StringBuilder();
        append(ticket, ticketId);
        append(ticket, count);
        append(ticket, participateDate);
        return ticket.toString();
    }

    private void append(StringBuilder canonical, Object value) {
        String text = part(value);
        canonical.append(text.length()).append(':').append(text).append('|');
    }

    private String part(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeRequestId(String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return null;
        }
        String normalized = requestId.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("幂等键长度不能超过64个字符");
        }
        return normalized;
    }

    private boolean isPersistedRequestEquivalent(Long memberId, RegistrationRequest request,
                                                 List<CmsRegistration> order) {
        // 旧单没有可独立证明的 offer/token 快照；禁止给候补请求补绑成“等价”重放。
        if (request.getWaitlistOfferId() != null || request.getWaitlistOfferToken() != null) {
            return false;
        }
        if (order == null || order.isEmpty()) {
            return false;
        }
        List<String> persistedTickets = new ArrayList<>();
        long persistedPoints = 0L;
        for (CmsRegistration row : order) {
            if (row == null || !same(memberId, row.getMemberId())
                    || !same(request.getOwnerType(), row.getOwnerType())
                    || !same(request.getOwnerId(), row.getOwnerId())
                    || !sameText(request.getRealName(), row.getRealName())
                    || !sameText(request.getPhone(), row.getPhone())
                    || !sameText(request.getEmail(), row.getEmail())
                    || !sameText(request.getAddress(), row.getAddress())
                    || !same(request.getAddressId(), row.getAddressId())
                    // 不比 sourceClubId:服务端【永不】把自报值写进 source_club_id
                    // (CmsRegistrationServiceImpl.auditDiscardedTicketSourceClaim),该列恒 null。
                    // 拿「请求里有 7」跟「库里恒 null」比 = 恒不等 ⇒ 每个带分享标记的历史单
                    // (这里走的是 requestFingerprint 为空的旧单补绑路径)重放都被判成冲突。
                    // ⚠️ 本分支【没有】fingerprint 可依赖,残余判据是同样落库的 source_channel_code;
                    // 新单的内容变更才由 fingerprint(fingerprint() 照旧把自报值算进哈希)接住。
                    || !sameText(request.getSourceChannelCode(), row.getSourceChannelCode())) {
                return false;
            }
            int count = row.getOrderNum() == null || row.getOrderNum() <= 0
                    ? 1 : row.getOrderNum().intValue();
            persistedTickets.add(ticketPart(row.getTicketId(), count, normalize(row.getParticipateDate())));
            persistedPoints += row.getPointUsed() == null ? 0L : row.getPointUsed();
        }
        List<String> requestedTickets = requestTicketParts(request);
        Collections.sort(persistedTickets);
        Collections.sort(requestedTickets);
        if (!persistedTickets.equals(requestedTickets)) {
            return false;
        }
        if (!Integer.valueOf(1).equals(request.getIsUsePoint()) && persistedPoints != 0L) {
            return false;
        }
        return StringUtils.isBlank(request.getQuoteSign()) || matchesPersistedQuote(request, order, persistedPoints);
    }

    private List<String> requestTicketParts(RegistrationRequest request) {
        List<String> tickets = new ArrayList<>();
        if (request.getTickets() == null || request.getTickets().isEmpty()) {
            tickets.add(ticketPart(request.getTicketId(), Integer.valueOf(1), null));
            return tickets;
        }
        for (RegistrationTicketItem ticket : request.getTickets()) {
            tickets.add(ticketPart(ticket == null ? null : ticket.getTicketId(),
                    ticket == null || ticket.getCount() == null || ticket.getCount() <= 0
                            ? Integer.valueOf(1) : ticket.getCount(),
                    ticket == null ? null : normalize(ticket.getParticipateDate())));
        }
        return tickets;
    }

    private boolean matchesPersistedQuote(RegistrationRequest request, List<CmsRegistration> order,
                                          long persistedPoints) {
        CmsRegistration primary = order.get(0);
        BigDecimal payable = primary.getPayableAmount();
        if (payable == null) {
            return false;
        }
        StringBuilder ticketsKey = new StringBuilder();
        List<RegistrationTicketItem> requested = request.getTickets();
        if (requested == null || requested.isEmpty()) {
            ticketsKey.append(request.getTicketId()).append(":1");
        } else {
            for (int i = 0; i < requested.size(); i++) {
                RegistrationTicketItem ticket = requested.get(i);
                if (ticket == null || ticket.getTicketId() == null) {
                    return false;
                }
                int count = ticket.getCount() == null || ticket.getCount() <= 0 ? 1 : ticket.getCount();
                if (i > 0) {
                    ticketsKey.append('|');
                }
                ticketsKey.append(ticket.getTicketId()).append(':').append(count);
            }
        }
        int usePoint = Integer.valueOf(1).equals(request.getIsUsePoint()) ? 1 : 0;
        String canonical = memberPart(primary.getMemberId()) + "|" + request.getOwnerType() + "|"
                + request.getOwnerId() + "|" + ticketsKey + "|" + usePoint + "|"
                + payable.setScale(2, RoundingMode.HALF_UP).toPlainString() + "|" + persistedPoints
                + "|0.00|" + QUOTE_SIGN_SALT;
        return Md5Utils.hash(canonical).equals(request.getQuoteSign().trim());
    }

    private String memberPart(Long value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private boolean same(Number left, Number right) {
        return left == null ? right == null : right != null && left.longValue() == right.longValue();
    }

    private boolean sameText(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        return normalizedLeft == null ? normalizedRight == null : normalizedLeft.equals(normalizedRight);
    }

    private List<CmsRegistration> loadOrder(CmsRegistration primary) {
        if (StringUtils.isBlank(primary.getParentOrderNo())) {
            return Collections.singletonList(primary);
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setParentOrderNo(primary.getParentOrderNo());
        List<CmsRegistration> rows = registrationService.selectCmsRegistrationList(probe);
        if (rows == null || rows.isEmpty()) {
            return Collections.singletonList(primary);
        }
        List<CmsRegistration> ordered = new ArrayList<>();
        ordered.add(primary);
        for (CmsRegistration row : rows) {
            if (!primary.getId().equals(row.getId())) {
                ordered.add(row);
            }
        }
        return ordered;
    }
}
