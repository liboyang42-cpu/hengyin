package com.chengyinhub.business.marketplace.paid;
import com.chengyinhub.business.service.impl.VerifiedWorkshopPayment;
/** Optional, unregistered by default. Called only after the existing official SDK verifies a notification. */
public interface WorkshopPaidPaymentHandler {
    boolean accept(VerifiedWorkshopPayment verified);
}
