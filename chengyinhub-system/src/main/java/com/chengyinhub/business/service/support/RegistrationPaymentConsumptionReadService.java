package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.RegistrationPaymentFactReceipt;
import com.chengyinhub.business.mapper.CmsRegistrationPaymentFactMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The registration owner exposes its local consumption; the provider fact owner still verifies the reference. */
@Service
public class RegistrationPaymentConsumptionReadService {
    private final CmsRegistrationPaymentFactMapper mapper;

    public RegistrationPaymentConsumptionReadService(CmsRegistrationPaymentFactMapper mapper) {
        this.mapper = mapper;
    }

    /** Caller already owns the original topic/registration locks. Keep its uncommitted consumption visible. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RegistrationPaymentFactReceipt> findConsumedPayment(Long registrationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("原支付消费读回必须加入现有业务事务");
        if (registrationId == null || registrationId <= 0)
            throw new IllegalArgumentException("缺少原付款报名身份");
        RegistrationPaymentFactReceipt receipt = mapper.selectByRegistrationForUpdate(registrationId);
        if (receipt == null) return Optional.empty();
        if (!registrationId.equals(receipt.registrationId())
                || receipt.registrationNo() == null || receipt.registrationNo().isBlank()
                || receipt.transactionId() == null || receipt.transactionId().isBlank())
            throw new IllegalStateException("原支付消费关联身份不完整或冲突");
        receipt.reference();
        return Optional.of(receipt);
    }
}
