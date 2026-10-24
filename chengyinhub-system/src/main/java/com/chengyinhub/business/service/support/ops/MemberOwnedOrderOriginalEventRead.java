package com.chengyinhub.business.service.support.ops;

import java.util.Objects;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.OwnershipUtils;

/**
 * 当前会员本人订单的原事件。
 *
 * <p>订单号只有在与 {@code POST /api/registration/info} 相同的归属事实成立后才接受：
 * 当前会员、{@link ICmsRegistrationService#selectCmsRegistrationById(Long)}、
 * {@link OwnershipUtils#notOwner(Long, Long)}。通过之后才经
 * {@link SupportEventLocator.MapperOriginalEventSource} 走既有
 * {@code selectByTarget("ORDER", id)}。第一笔 ORDER 的 id 必须等于所请求的 id。
 * 另一个 id、空白 id、缺行都不变成这笔订单。不读 RUN，不编造事件，不查管理端审计列表。
 */
public final class MemberOwnedOrderOriginalEventRead {
    private final ICmsRegistrationService registrations;
    private final SupportEventLocator locator;

    public MemberOwnedOrderOriginalEventRead(ICmsRegistrationService registrations,
                                             BusinessAuditEventMapper mapper) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.locator = new SupportEventLocator(new SupportEventLocator.MapperOriginalEventSource(
                Objects.requireNonNull(mapper, "businessAuditEventMapper")));
    }

    /**
     * @param memberId 当前会员，与 {@code getAppUserId()} 同一事实
     * @param orderId 报名 id 原文，与 {@code /info} 的 {@code id} 一样先走 {@link Convert#toLong(Object, Long)}
     * @return 对上的原 ORDER 行；对不上时返回 null，不编造事件
     */
    public BusinessAuditEvent readOwnedOrder(Long memberId, String orderId) {
        Long registrationId = ownedRegistrationId(memberId, orderId);
        if (registrationId == null) {
            return null;
        }
        try {
            SupportEventLocator.LocatedEvent located = locator.locateByOrderId(registrationId, null);
            return firstOrderRow(registrationId, located);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return null;
        }
    }

    private Long ownedRegistrationId(Long memberId, String orderId) {
        if (memberId == null || memberId.longValue() <= 0L) {
            return null;
        }
        Long registrationId = Convert.toLong(orderId, 0L);
        if (registrationId == null || registrationId.longValue() <= 0L) {
            return null;
        }
        CmsRegistration registration = registrations.selectCmsRegistrationById(registrationId);
        if (registration == null || !registrationId.equals(registration.getId())) {
            return null;
        }
        if (OwnershipUtils.notOwner(registration.getMemberId(), memberId)) {
            return null;
        }
        return registrationId;
    }

    /** 只交还第一笔 ORDER。RUN 或后一笔不同 id 都不升成这笔订单。 */
    private static BusinessAuditEvent firstOrderRow(Long registrationId,
                                                    SupportEventLocator.LocatedEvent located) {
        if (located == null || !registrationId.equals(located.getOrderId())) {
            return null;
        }
        BusinessAuditEvent firstOrder = null;
        if (located.getOriginalEvents() != null) {
            for (BusinessAuditEvent event : located.getOriginalEvents()) {
                if (event != null && "ORDER".equals(event.getTargetType())) {
                    firstOrder = event;
                    break;
                }
            }
        }
        if (firstOrder == null || !registrationId.equals(firstOrder.getTargetId())) {
            return null;
        }
        if (firstOrder.getRequestId() == null || firstOrder.getRequestId().trim().isEmpty()) {
            return null;
        }
        return firstOrder;
    }
}
