package com.chengyinhub.business.service;

/** Invoice-owned fact scanner/manual recovery consumer. Never invoke in a refund transaction. */
public interface IUserInvoiceRefundFactConsumer {
    /** Original registration ID, including a child. Reads authoritative facts; retry on failure. */
    void acceptRegistrationRefundFact(Long registrationId);
}
