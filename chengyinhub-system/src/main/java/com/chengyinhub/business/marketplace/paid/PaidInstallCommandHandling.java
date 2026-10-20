package com.chengyinhub.business.marketplace.paid;
import java.util.Map;
/** Independently installed server port. No default bean or production registration exists. */
public interface PaidInstallCommandHandling {
    Map<String,Object> targets(long actor,String licenseId,Long before);
    Map<String,Object> submit(long actor,PaidInstallCommand command);
    Map<String,Object> history(long actor,String licenseId,String before);
    Map<String,Object> status(long actor,String requestId);
}
