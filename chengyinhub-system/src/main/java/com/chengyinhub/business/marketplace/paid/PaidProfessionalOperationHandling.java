package com.chengyinhub.business.marketplace.paid;
import java.util.Map;
/** Separate staged operation protocol: cancellation affects an owner nonce, never the purchase. */
public interface PaidProfessionalOperationHandling {
 Map<String,Object> submit(long actor,PaidProfessionalMaterializationCommand command);
 Map<String,Object> cancel(long actor,PaidProfessionalMaterializationCommand command);
 Map<String,Object> history(long actor,String licenseId,String beforeOperationKey);
 Map<String,Object> status(long actor,String requestId);
}
