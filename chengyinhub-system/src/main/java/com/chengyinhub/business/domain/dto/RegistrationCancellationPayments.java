package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.CmsRegistration;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal cancellation handoff only; never an HTTP input or permission to refund/reopen.
 * Provider facts are B's actual response, copied without synthesizing a SUCCESS or a new alias. */
public record RegistrationCancellationPayments(List<Scope> scopes) {
    public RegistrationCancellationPayments { scopes = List.copyOf(scopes); }

    public record Scope(Long ownerType, Long ownerId, Long topicId, Long activityMemberId,
                        Long clubId, List<Order> orders) {
        public Scope { orders = List.copyOf(orders); }
    }

    public record Identity(Long id, String registrationNo, Long ownerType, Long ownerId, Long memberId,
                           String parentOrderNo, Long topicId, Long ticketId, Long orderNum,
                           String requestFingerprint, BigDecimal wechatAmount) {
        public static Identity of(CmsRegistration row) {
            return new Identity(row.getId(), row.getRegistrationNo(), row.getOwnerType(), row.getOwnerId(),
                    row.getMemberId(), row.getParentOrderNo(), row.getTopicId(), row.getTicketId(),
                    row.getOrderNum(), row.getRequestFingerprint(), row.getWechatPaymentAmount());
        }
        public boolean matches(CmsRegistration row) {
            return row != null && Objects.equals(id, row.getId())
                    && Objects.equals(registrationNo, row.getRegistrationNo())
                    && Objects.equals(ownerType, row.getOwnerType()) && Objects.equals(ownerId, row.getOwnerId())
                    && Objects.equals(memberId, row.getMemberId())
                    && Objects.equals(parentOrderNo, row.getParentOrderNo())
                    && Objects.equals(topicId, row.getTopicId()) && Objects.equals(ticketId, row.getTicketId())
                    && Objects.equals(orderNum, row.getOrderNum())
                    && Objects.equals(requestFingerprint, row.getRequestFingerprint())
                    && wechatAmount != null && row.getWechatPaymentAmount() != null
                    && wechatAmount.compareTo(row.getWechatPaymentAmount()) == 0;
        }
    }

    public record Order(Identity original, Map<String, String> providerFacts, boolean confirmedClosed) {
        public Order {
            Objects.requireNonNull(original);
            if (providerFacts != null)
                providerFacts = Collections.unmodifiableMap(new HashMap<>(providerFacts));
        }
    }

    public Scope requireScope(Long ownerType, Long ownerId) {
        List<Scope> matches = scopes.stream().filter(scope -> Objects.equals(ownerType, scope.ownerType())
                && Objects.equals(ownerId, scope.ownerId())).toList();
        if (matches.size() != 1)
            throw new IllegalStateException("取消支付观察的原范围缺失或重复");
        return matches.getFirst();
    }
}
